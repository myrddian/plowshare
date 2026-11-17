import ApplicationServices
import Carbon
import Darwin
import Foundation

private struct BrokerReply<Value: Encodable>: Encodable {
  let version = nativeBrokerVersion, requestId: String, operation: String, result: Value
}
private struct BrokerPreflight: Encodable {
  let permissions = BrokerPermissions.current(), availability = "unknown"
  let secureInput = IsSecureEventInputEnabled() ? "active" : "clear"
  let globalFence = NativeGlobalFence.presence()
}
private struct BrokerFailureResult: Encodable { let code: String, physicalSettlement: String }
private struct BrokerAcquisition: Encodable {
  let outcome: String, lease: BrokerLease?, code: String?
  private enum Keys: String, CodingKey { case outcome, lease, code }
  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: Keys.self)
    try c.encode(outcome, forKey: .outcome)
    if outcome == "ACQUIRED" {
      try c.encode(lease, forKey: .lease)
    } else if outcome == "UNCERTAIN" {
      try c.encode(lease, forKey: .lease)
      try c.encode(code, forKey: .code)
    } else {
      try c.encode(code, forKey: .code)
    }
  }
}
private struct BrokerCleanup: Encodable { let physicalSettlement: String, heldInputsReleased: Bool }
private struct BrokerRefusal: Encodable {
  struct Delivery: Encodable {
    let outcome = "NOT_DISPATCHED", physicalSettlement = "settled", code: String
  }
  struct Observation: Encodable { let state = "FAILED", code = "OBSERVATION_FAILED" }
  let delivery: Delivery, observation = Observation()
}

private struct BrokerActionReply: Encodable {
  struct Scroll: Encodable { let dx: Int, dy: Int, units: String }
  struct Delivery: Encodable {
    let outcome: String, physicalSettlement: String, code: String?, mechanism: String?,
      scroll: Scroll?
    let delivered: NativeInputExtent?
    init(
      outcome: String, physicalSettlement: String, code: String?, mechanism: String?,
      scroll: Scroll?, delivered: NativeInputExtent? = nil
    ) {
      self.outcome = outcome
      self.physicalSettlement = physicalSettlement
      self.code = code
      self.mechanism = mechanism
      self.scroll = scroll
      self.delivered = delivered
    }
    private enum Keys: String, CodingKey {
      case outcome, physicalSettlement, code, mechanism, scroll, delivered
    }
    func encode(to encoder: Encoder) throws {
      var c = encoder.container(keyedBy: Keys.self)
      try c.encode(outcome, forKey: .outcome)
      try c.encode(physicalSettlement, forKey: .physicalSettlement)
      if let code { try c.encode(code, forKey: .code) }
      if let mechanism { try c.encode(mechanism, forKey: .mechanism) }
      if let scroll { try c.encode(scroll, forKey: .scroll) }
      if let delivered { try c.encode(delivered, forKey: .delivered) }
    }
  }
  enum Observation: Encodable {
    case captured(BrokerObservation)
    case failed(String)
    private enum Keys: String, CodingKey { case state, value, code }
    func encode(to encoder: Encoder) throws {
      var c = encoder.container(keyedBy: Keys.self)
      switch self {
      case .captured(let value):
        try c.encode("CAPTURED", forKey: .state)
        try c.encode(value, forKey: .value)
      case .failed(let code):
        try c.encode("FAILED", forKey: .state)
        try c.encode(code, forKey: .code)
      }
    }
  }
  let delivery: Delivery, observation: Observation
}

/// Persistent private stdio broker. The reader remains available for one-way
/// cancellation while a bounded operation awaits a native RPC/capture. Ordinary
/// commands never overlap; EOF/cancel is not an application action or a replay.
/// A killed/hung process leaves BOTH durable evidence and kernel fence intact.
final class NativeBroker: NativeInspectorLocalAccess {
  private let lock = NSLock(), outputLock = NSLock()
  private var activeId: String?, activeBudget: NativeBudget?, stopping = false
  private var lease: BrokerLease?, configuration: BrokerConfiguration?, inspector: NativeInspector?
  private var fence: NativeGlobalFence?, evidence: NativeEvidence?, input: NativeInputExecutor?
  private var readiness: NativeReadiness?
  private var shutdownTask: Task<Void, Never>?
  private var physicallyUncertain = false
  private var cleanupBudgetMs = 2000  // Protected by lock; reader never reads configuration.
  func reserveInspector(budget: NativeBudget) async throws -> NativeInspectorLocalReservation {
    let id = "local:" + UUID().uuidString.lowercased()
    while true {
      try budget.check()
      let admitted = try lock.withLock { () -> Bool in
        guard !stopping else { throw BrokerFailure.fenced }
        if activeId != nil { return false }
        activeId = id
        activeBudget = budget
        return true
      }
      if admitted {
        return NativeInspectorLocalReservation { [weak self] in
          self?.lock.withLock {
            if self?.activeId == id {
              self?.activeId = nil
              self?.activeBudget = nil
            }
          }
        }
      }
      try await budget.sleep(milliseconds: 2)
    }
  }
  private func drainOwnedWork(_ budget: NativeBudget) async throws {
    while lock.withLock({ activeId != nil }) {
      try budget.check()
      try await budget.sleep(milliseconds: 2)
    }
    try budget.check()
  }
  private func readArmingStatus(_ candidate: BrokerLease) throws -> NativeArmStatusValue {
    let snapshot = lock.withLock { (lease == candidate, readiness) }
    guard snapshot.0, let readiness = snapshot.1 else { throw BrokerFailure.noLease }
    return readiness.status()
  }
  private func beginStoppingLocked() {
    stopping = true
    readiness?.revoke("LEASE_CLOSED")
    activeBudget?.cancel()
  }
  private func beginStopping() { lock.withLock { beginStoppingLocked() } }
  private func reply<T: Encodable>(
    _ request: BrokerRequest, _ operation: String, _ value: T, completesOperation: Bool = true
  ) throws {
    let bytes = try JSONEncoder().encode(
      BrokerReply(requestId: request.requestId, operation: operation, result: value))
    guard bytes.count <= nativeBrokerReplyLimit else { throw BrokerFailure.observation }
    // Native work is complete before publishing its final line. Clear only this
    // request's slot, so an immediate next command is never falsely BUSY and an
    // old task's final bookkeeping cannot clear a newer operation.
    lock.withLock {
      if completesOperation && activeId == request.requestId {
        activeId = nil
        activeBudget = nil
      }
    }
    outputLock.lock()
    defer { outputLock.unlock() }
    try FileHandle.standardOutput.write(contentsOf: bytes)
    try FileHandle.standardOutput.write(contentsOf: Data([10]))
  }
  private func owner(_ candidate: BrokerLease) throws {
    guard lease == candidate, !physicallyUncertain else { throw BrokerFailure.noLease }
  }
  private func acquire(
    _ candidate: BrokerLease, _ config: BrokerConfiguration, _ budget: NativeBudget
  ) throws -> BrokerAcquisition {
    guard lease == nil, fence == nil, !physicallyUncertain else {
      return BrokerAcquisition(outcome: "NOT_ACQUIRED", lease: nil, code: "BUSY")
    }
    try budget.check()
    guard #available(macOS 15.2, *) else {
      return BrokerAcquisition(outcome: "NOT_ACQUIRED", lease: nil, code: "UNSUPPORTED_ACTION")
    }
    // Acquisition proves only exclusive ownership. Missing permissions keep all
    // capture/input disarmed, but explicit host-local setup UI remains reachable.
    let view = NativeInspector(configuration: config)
    try view.validateDisplayPresence(budget)
    do { fence = try NativeGlobalFence() } catch NativeFenceFailure.busy {
      return BrokerAcquisition(outcome: "NOT_ACQUIRED", lease: nil, code: "BUSY")
    } catch { return BrokerAcquisition(outcome: "NOT_ACQUIRED", lease: nil, code: "FENCED") }
    // From here any unproved exception retains the kernel fence. Parent owns the
    // exact local capture.lock and may remove it only after our stop proof+exit.
    lock.withLock {
      lease = candidate
      configuration = config
      cleanupBudgetMs = config.cleanupBudgetMs
      inspector = view
    }
    do {
      let ownedEvidence = try NativeEvidence(directory: config.ownershipDirectory, lease: candidate)
      evidence = ownedEvidence
      let readiness = NativeReadiness(
        lease: candidate, configuration: config, inspector: view,
        uiFactory: { NativeAppKitReadinessUI() }, localAccess: self)
      lock.withLock { self.readiness = readiness }
      view.bindReadiness(readiness)
      input = NativeInputExecutor(
        evidence: ownedEvidence, sink: NativeArmedEventSink(readiness: readiness),
        monitor: NativeArmedInputMonitor(readiness: readiness))
      try budget.check()
      return BrokerAcquisition(outcome: "ACQUIRED", lease: candidate, code: nil)
    } catch {
      physicallyUncertain = true
      return BrokerAcquisition(outcome: "UNCERTAIN", lease: candidate, code: "FENCED")
    }
  }
  private func observe(
    surface: String, structured: Bool, root: BrokerElementTarget?, budget: NativeBudget
  ) async throws -> BrokerObservation {
    guard let inspector, inspector.accepts(surface: surface) else { throw BrokerFailure.gone }
    guard #available(macOS 15.2, *) else { throw BrokerFailure.unsupported }
    guard let readiness else { throw BrokerFailure.noLease }
    try await readiness.prepareReadPlane(budget: budget)
    if structured { _ = try inspector.discover(budget) }
    let initial = try inspector.state(surface: surface, budget)
    let projection =
      structured
      ? try inspector.snapshot(surface: surface, root: root, state: initial, budget) : nil
    // Snapshot construction replaces exact bindings. Bind the outgoing guard to
    // those retained objects, then verify the same geometry/focus after pixels.
    let before = try inspector.state(surface: surface, budget)
    let snapshot = projection.map {
      BrokerSnapshot(
        snapshotId: $0.snapshotId, rootElementId: $0.rootElementId, guardValue: before.guardValue,
        capturedAt: $0.capturedAt, nodes: $0.nodes, truncated: $0.truncated)
    }
    let regions = try await readiness.captureRegions(budget: budget)
    let image = try await nativeCapture(
      display: before.display, state: before, budget: budget, redactions: regions.rectangles)
    if regions != (try await readiness.captureRegions(budget: budget)) {
      throw BrokerFailure.stale
    }
    let after = try inspector.state(surface: surface, budget)
    guard before.guardValue == after.guardValue, before.permissions == after.permissions,
      before.display == after.display
    else { throw BrokerFailure.stale }
    try budget.check()
    return BrokerObservation(image: image, state: after, snapshot: snapshot)
  }
  /// A bounded local grant plus fresh helper-owned compositor/GUI-route evidence
  /// enables this scoped operating envelope. Auxiliary console/focus/secure-input
  /// facts alone never become global unlocked authority; loss revokes the grant.
  private func readiness(_ state: BrokerState) -> BrokerFailure? {
    if state.secureInput == "active" { return .secure }
    if !state.permissions.accessibility || !state.permissions.eventPosting
      || !state.permissions.eventListening
    {
      return .denied
    }
    guard state.availability == "ready", state.interference == "clear", state.focusKnown else {
      return .unknown
    }
    return nil
  }
  private func postObservation(surface: String, budget: NativeBudget) async
    -> BrokerActionReply.Observation
  {
    do {
      return .captured(
        try await observe(surface: surface, structured: true, root: nil, budget: budget))
    } catch { return .failed(((error as? BrokerFailure) ?? .observation).rawValue) }
  }
  private func dispatch(
    _ prepared: NativePreparedInput, state: BrokerState, invocationId: String, reserve: Int,
    budget: NativeBudget
  ) async
    -> BrokerActionReply
  {
    guard let input else {
      return BrokerActionReply(
        delivery: .init(
          outcome: "NOT_DISPATCHED", physicalSettlement: "settled", code: "NO_LEASE",
          mechanism: nil, scroll: nil), observation: .failed("OBSERVATION_FAILED"))
    }
    var started = false
    do {
      let work = try budget.work(reserving: reserve)
      guard let evidence else { throw BrokerFailure.noLease }
      try evidence.beginOperation(invocationId: invocationId, stage: "input", budget: work)
      guard let inspector else { throw BrokerFailure.noLease }
      input.admit(NativeAXInputValidation(inspector: inspector, state: state))
      try input.startMonitor(work)
      started = true
      var mechanism = "cg-event"
      var scroll: BrokerActionReply.Scroll?
      switch prepared {
      case .move(let point): try input.move(point, budget: work)
      case .click(let point, let button, let mods, let count):
        try input.click(point, button: button, count: count, modifiers: mods, budget: work)
      case .drag(let points, let button, let mods):
        _ = try await input.drag(points, button: button, modifiers: mods, budget: work)
      case .type(let text, let mode):
        if mode == "replace" {
          try input.press(try nativeKeyCode("a"), modifiers: ["Meta"], budget: work)
          try input.press(try nativeKeyCode("Backspace"), modifiers: [], budget: work)
          input.markClearPosted()
        }
        _ = try input.type(text, budget: work)
        mechanism = "unicode-events"
      case .key(let code, let mods): try input.press(code, modifiers: mods, budget: work)
      case .scroll(let point, let dx, let dy, let units):
        try input.move(point, budget: work)
        try input.scroll(at: point, dx: dx, dy: dy, units: units, budget: work)
        mechanism = "cg-scroll"
        scroll = .init(dx: dx, dy: dy, units: units)
      }
      guard await input.cleanup(budget) else { throw BrokerFailure.cleanup }
      guard let configuration else { throw BrokerFailure.noLease }
      let observation = await postObservation(surface: configuration.surfaceId, budget: budget)
      try evidence.endOperationAfterQuiescence()
      return BrokerActionReply(
        delivery: .init(
          outcome: "DISPATCHED", physicalSettlement: "settled", code: nil, mechanism: mechanism,
          scroll: scroll), observation: observation)
    } catch {
      // Cancellation revokes new work, not owned-input release. A fresh finite
      // cleanup allowance shares the parent's kill/drain envelope, never replays
      // the original action and never upgrades missing acknowledgement proof.
      let cleanup = NativeBudget(milliseconds: lock.withLock { cleanupBudgetMs })
      var quiet = await input.cleanup(cleanup)
      if quiet { do { try evidence?.endOperationAfterQuiescence() } catch { quiet = false } }
      let partial = quiet ? input.partial(prepared, currentOperationStarted: started) : nil
      if !quiet { physicallyUncertain = true }
      return BrokerActionReply(
        delivery: .init(
          outcome: nativeFailureOutcome(started: started, quiet: quiet, hasPartial: partial != nil),
          physicalSettlement: quiet ? "settled" : "uncertain",
          code: ((error as? BrokerFailure) ?? .input).rawValue, mechanism: nil, scroll: nil,
          delivered: partial),
        observation: .failed("OBSERVATION_FAILED"))
    }
  }
  private func stop(_ budget: NativeBudget) async -> BrokerCleanup {
    readiness?.revoke("LEASE_CLOSED")
    if physicallyUncertain {
      return BrokerCleanup(physicalSettlement: "uncertain", heldInputsReleased: false)
    }
    let heldQuiet = await input?.cleanup(budget) ?? true
    guard heldQuiet else {
      physicallyUncertain = true
      return BrokerCleanup(physicalSettlement: "uncertain", heldInputsReleased: false)
    }
    do {
      try budget.check()
      try await readiness?.stop(budget: budget)
      lock.withLock { readiness = nil }
      try evidence?.removeAfterQuiescence()
      evidence = nil
      try fence?.releaseAfterQuiescence()
      fence = nil
      lock.withLock {
        lease = nil
        configuration = nil
        inspector = nil
      }
      return BrokerCleanup(physicalSettlement: "settled", heldInputsReleased: true)
    } catch {
      physicallyUncertain = true
      return BrokerCleanup(physicalSettlement: "uncertain", heldInputsReleased: false)
    }
  }
  private func process(_ request: BrokerRequest, _ budget: NativeBudget) async {
    // The watchdog is a last bound, NOT proof that any native RPC/input stopped.
    // Its exit intentionally retains unexplained kernel/evidence markers.
    let timer = DispatchSource.makeTimerSource(queue: .global())
    let cleanupMs = lock.withLock { cleanupBudgetMs }
    timer.schedule(deadline: .now() + .milliseconds(request.timeoutMs + cleanupMs))
    timer.setEventHandler { exit(124) }
    timer.resume()
    defer { timer.cancel() }
    do {
      try budget.check()
      switch request.command {
      case .preflight: try reply(request, "preflight", BrokerPreflight())
      case .acquire(let candidate, let config):
        try reply(request, "acquire", acquire(candidate, config, budget))
      case .armRequest(let candidate, let owner, let arm):
        try self.owner(candidate)
        guard let readiness else { throw BrokerFailure.noLease }
        try reply(
          request, "arm_request",
          try await readiness.request(owner: owner, request: arm, budget: budget))
      case .armStatus(let candidate):
        try self.owner(candidate)
        guard let readiness else { throw BrokerFailure.noLease }
        try reply(request, "arm_status", readiness.status())
      case .armRevoke: throw BrokerFailure.invalid  // Reader owns the dedicated control lane.
      case .windows(let candidate):
        try owner(candidate)
        guard let inspector, let configuration, configuration.tools.contains("windows") else {
          throw BrokerFailure.disabled
        }
        guard let readiness else { throw BrokerFailure.noLease }
        try await readiness.prepareReadPlane(budget: budget)
        try reply(request, "windows", inspector.discover(budget))
      case .observe(let candidate, let surface, let structured, let root):
        try owner(candidate)
        let result = try await observe(
          surface: surface, structured: structured, root: root, budget: budget)
        try reply(request, "observe", result)
      case .focus(let candidate, let invocationId, let listId, let surface, let reserve):
        try owner(candidate)
        guard let inspector, let configuration, configuration.tools.contains("focus"),
          inspector.accepts(surface: surface)
        else { throw BrokerFailure.disabled }
        let state = try inspector.state(surface: configuration.surfaceId, budget)
        if let code = readiness(state) {
          try reply(request, "focus", BrokerRefusal(delivery: .init(code: code.rawValue)))
          break
        }
        guard let readiness else { throw BrokerFailure.unknown }
        let targetApplication = try inspector.focusApplication(surface: surface, budget: budget)
        guard readiness.applicationGranted(targetApplication) else {
          try reply(request, "focus", BrokerRefusal(delivery: .init(code: "TARGET_NOT_ALLOWED")))
          break
        }
        try await readiness.enterAction(
          pid: try inspector.routePid(state: state, budget: budget), budget: budget)
        defer { readiness.leaveAction() }
        do {
          let work = try budget.work(reserving: reserve)
          guard let evidence else { throw BrokerFailure.noLease }
          try evidence.beginOperation(invocationId: invocationId, stage: "focus", budget: work)
          try await inspector.focusWindow(listId: listId, surface: surface, budget: work)
          let observed = await postObservation(surface: configuration.surfaceId, budget: budget)
          try evidence.endOperationAfterQuiescence()
          try reply(
            request, "focus",
            BrokerActionReply(
              delivery: .init(
                outcome: "DISPATCHED", physicalSettlement: "settled", code: nil, mechanism: "focus",
                scroll: nil),
              observation: observed))
        } catch {
          physicallyUncertain = true
          readiness.revoke("WITNESS_FAILED")
          try reply(
            request, "focus",
            BrokerActionReply(
              delivery: .init(
                outcome: "UNCERTAIN", physicalSettlement: "uncertain",
                code: ((error as? BrokerFailure) ?? .input).rawValue, mechanism: nil, scroll: nil),
              observation: .failed("OBSERVATION_FAILED")))
        }
      case .perform(let candidate, let invocationId, let expected, let action, let reserve):
        try owner(candidate)
        guard let inspector, let configuration, configuration.tools.contains(action.tool),
          inspector.accepts(surface: expected.surfaceId)
        else { throw BrokerFailure.disabled }
        let state = try inspector.state(surface: expected.surfaceId, budget)
        if state.guardValue != expected {
          try reply(request, "perform", BrokerRefusal(delivery: .init(code: "STALE_OBSERVATION")))
          break
        }
        if let code = readiness(state) {
          try reply(request, "perform", BrokerRefusal(delivery: .init(code: code.rawValue)))
          break
        }
        guard let readiness else { throw BrokerFailure.unknown }
        try await readiness.enterAction(
          pid: try inspector.routePid(state: state, budget: budget), budget: budget)
        defer { readiness.leaveAction() }
        let prepared: NativePreparedInput
        do { prepared = try inspector.prepare(action, state: state, budget: budget) } catch {
          try reply(
            request, "perform",
            BrokerRefusal(delivery: .init(code: ((error as? BrokerFailure) ?? .stale).rawValue)))
          break
        }
        try reply(
          request, "perform",
          await dispatch(
            prepared, state: state, invocationId: invocationId, reserve: reserve, budget: budget))
      case .stop:
        let result = await stop(budget)
        try reply(request, "stop", result)
        exit(result.physicalSettlement == "settled" ? 0 : 1)
      case .cancel: break
      }
    } catch {
      let code = (error as? BrokerFailure) ?? .observation
      do {
        try reply(
          request, "failure",
          BrokerFailureResult(
            code: code.rawValue, physicalSettlement: physicallyUncertain ? "uncertain" : "settled"))
      } catch { exit(1) }
    }
    lock.withLock {
      if activeId == request.requestId {
        activeId = nil
        activeBudget = nil
      }
    }
  }
  private func accept(_ request: BrokerRequest) {
    lock.lock()
    if case .cancel(let target) = request.command {
      if activeId == target {
        readiness?.revoke("HOST_REVOKED")
        activeBudget?.cancel()
      }
      lock.unlock()
      return
    }
    if case .armStatus(let candidate) = request.command {
      lock.unlock()
      do {
        try reply(request, "arm_status", try readArmingStatus(candidate), completesOperation: false)
      } catch {
        do {
          try reply(
            request, "failure",
            BrokerFailureResult(
              code: ((error as? BrokerFailure) ?? .fenced).rawValue, physicalSettlement: "settled"),
            completesOperation: false)
        } catch { exit(1) }
      }
      return
    }
    if case .armRevoke(let candidate) = request.command {
      let readiness = self.readiness
      let owned = self.lease == candidate
      lock.unlock()
      do {
        guard owned, let readiness else { throw BrokerFailure.noLease }
        readiness.revoke("HOST_REVOKED")
        try reply(request, "arm_revoke", readiness.status(), completesOperation: false)
      } catch {
        do {
          try reply(
            request, "failure",
            BrokerFailureResult(
              code: ((error as? BrokerFailure) ?? .fenced).rawValue, physicalSettlement: "settled"),
            completesOperation: false)
        } catch { exit(1) }
      }
      return
    }
    if case .stop = request.command, activeId != nil, !stopping {
      beginStoppingLocked()
      let budget = NativeBudget(milliseconds: request.timeoutMs)
      shutdownTask = Task { [self] in
        do {
          try await drainOwnedWork(budget)
          try lock.withLock {
            try budget.check()
            activeId = request.requestId
            activeBudget = budget
          }
          await process(request, budget)
        } catch {
          do {
            try reply(
              request, "stop",
              BrokerCleanup(physicalSettlement: "uncertain", heldInputsReleased: false),
              completesOperation: false)
          } catch { exit(1) }
          exit(1)
        }
      }
      lock.unlock()
      return
    }
    if activeId != nil || stopping {
      lock.unlock()
      do {
        try reply(
          request, "failure", BrokerFailureResult(code: "BUSY", physicalSettlement: "settled"),
          completesOperation: false)
      } catch { exit(1) }
      return
    }
    if case .stop = request.command { beginStoppingLocked() }
    let budget = NativeBudget(milliseconds: request.timeoutMs)
    activeId = request.requestId
    activeBudget = budget
    lock.unlock()
    Task { await process(request, budget) }
  }
  private func finishEOF() async {
    let cleanupMs = lock.withLock {
      stopping = true
      readiness?.revoke("HOST_REVOKED")
      activeBudget?.cancel()
      return cleanupBudgetMs
    }
    let budget = NativeBudget(milliseconds: cleanupMs)
    while true {
      let busy = lock.withLock { activeId != nil }
      if !busy { break }
      do { try await budget.sleep(milliseconds: 5) } catch { exit(1) }
    }
    let result = await stop(budget)
    exit(result.physicalSettlement == "settled" ? 0 : 1)
  }
  func run() async {
    // Blocking stdin is confined to a dedicated reader; cancellation does not
    // wait for a capture/AX operation to finish before reaching its budget.
    await Task.detached { [self] in
      var frame = Data()
      var chunk = [UInt8](repeating: 0, count: 1024)
      while true {
        let length = Darwin.read(STDIN_FILENO, &chunk, chunk.count)
        if length < 0 {
          if errno == EINTR { continue }
          exit(1)
        }
        if length == 0 { break }
        for byte in chunk.prefix(length) {
          if byte == 10 {
            do { accept(try decodeBrokerRequest(frame)) } catch { exit(1) }
            frame.removeAll(keepingCapacity: true)
          } else {
            frame.append(byte)
            if frame.count > nativeBrokerRequestLimit { exit(1) }
          }
        }
      }
      // A partial trailing frame is malformed, never an implicit operation.
      if !frame.isEmpty { exit(1) }
      await finishEOF()
    }.value
  }
}

extension NativeBroker {
  /// Pure local Inspector concurrency fixtures: no helper process, semaphore,
  /// AX call, UI, event tap or OS input is started by these reservations.
  static func inspectorMemoryCheck() async throws -> Int {
    var checks = 0
    let broker = NativeBroker()
    let budget = NativeBudget(milliseconds: 100)
    let held = try await broker.reserveInspector(budget: budget)
    let original = broker.lock.withLock { broker.activeId }
    do {
      _ = try await broker.reserveInspector(budget: NativeBudget(milliseconds: 5))
      throw BrokerFailure.invalid
    } catch BrokerFailure.expired { checks += 1 }
    guard broker.lock.withLock({ broker.activeId == original }) else { throw BrokerFailure.invalid }
    checks += 1
    let config = BrokerConfiguration(
      ownershipDirectory: "/memory/not-used", targetId: "memory", label: "Memory",
      surfaceId: "display:1", displayId: 1, tools: ["pointer"],
      allowedBundleIds: ["org.fixture.Allowed"], maxWindows: 1, maxNodes: 1, maxVisitedNodes: 1,
      cleanupBudgetMs: 100)
    let lease = BrokerLease(
      leaseId: "memory", targetId: "memory", sessionId: "memory", generation: "memory")
    let inspector = NativeInspector(configuration: config)
    let readiness = NativeReadiness(
      lease: lease, configuration: config, inspector: inspector,
      uiFactory: { NativeReadinessMemoryUI() }, localAccess: broker)
    broker.lock.withLock {
      broker.lease = lease
      broker.readiness = readiness
    }
    guard try broker.readArmingStatus(lease).state == "UNARMED",
      broker.lock.withLock({ broker.activeId == original }), !budgetIsCancelled(budget)
    else { throw BrokerFailure.invalid }
    checks += 1
    broker.beginStopping()
    let settled = NativeSettlementLatchForBroker()
    let drained = Task {
      try await broker.drainOwnedWork(NativeBudget(milliseconds: 100))
      settled.finish()
    }
    try await Task.sleep(nanoseconds: 2_000_000)
    guard !settled.isFinished, broker.lock.withLock({ broker.activeId == original }) else {
      throw BrokerFailure.invalid
    }
    checks += 1
    held.finish()
    try await drained.value
    guard broker.lock.withLock({ broker.activeId == nil }) else { throw BrokerFailure.invalid }
    checks += 1
    do {
      _ = try await broker.reserveInspector(budget: NativeBudget(milliseconds: 100))
      throw BrokerFailure.invalid
    } catch BrokerFailure.fenced { checks += 1 }
    let pending = NativeBroker()
    let reservation = try await pending.reserveInspector(budget: NativeBudget(milliseconds: 100))
    pending.beginStopping()
    do {
      try await pending.drainOwnedWork(NativeBudget(milliseconds: 5))
      throw BrokerFailure.invalid
    } catch BrokerFailure.expired { checks += 1 }
    guard pending.lock.withLock({ pending.activeId != nil }) else { throw BrokerFailure.invalid }
    checks += 1
    reservation.finish()
    try await pending.drainOwnedWork(NativeBudget(milliseconds: 100))
    pending.lock.withLock { pending.stopping = false }
    let next = try await pending.reserveInspector(budget: NativeBudget(milliseconds: 100))
    let newId = pending.lock.withLock { pending.activeId }
    reservation.finish()
    guard pending.lock.withLock({ pending.activeId == newId }) else { throw BrokerFailure.invalid }
    checks += 1
    next.finish()
    try await readiness.stop(budget: NativeBudget(milliseconds: 100))
    withExtendedLifetime(inspector) {}
    return checks
  }
}
private func budgetIsCancelled(_ budget: NativeBudget) -> Bool {
  do {
    try budget.check()
    return false
  } catch { return true }
}
private final class NativeSettlementLatchForBroker: @unchecked Sendable {
  private let lock = NSLock()
  private var done = false
  func finish() { lock.withLock { done = true } }
  var isFinished: Bool { lock.withLock { done } }
}
