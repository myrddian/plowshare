import AppKit
import ApplicationServices
import Carbon
import Foundation
import ScreenCaptureKit
import Security

private let nativeProofMaximumAge: UInt64 = 250_000_000
private let nativeProofInterval: UInt64 = 100_000_000
private let nativeProofBudgetMs = 200

struct NativeWitnessTicket: Equatable, Sendable { let id: String, deadline: UInt64 }
/// Expiry revokes evidence but never clears physical occupancy. A replacement
/// can begin only after the original native capture actually settles.
struct NativeWitnessLifecycle {
  private(set) var ticket: NativeWitnessTicket?
  mutating func begin(now: UInt64, deadline: UInt64) throws -> NativeWitnessTicket {
    guard ticket == nil, deadline > now else { throw BrokerFailure.busy }
    let created = NativeWitnessTicket(id: UUID().uuidString.lowercased(), deadline: deadline)
    ticket = created
    return created
  }
  func expired(now: UInt64) -> Bool { ticket.map { now >= $0.deadline } ?? false }
  mutating func settle(_ original: NativeWitnessTicket) { if ticket == original { ticket = nil } }
}
private final class NativeSettlementLatch: @unchecked Sendable {
  // Only this lock-protected bit crosses task/thread boundaries.
  private let lock = NSLock()
  private var finished = false
  func finish() { lock.withLock { finished = true } }
  var isFinished: Bool { lock.withLock { finished } }
}
private final class NativeReadinessRevocationSink: @unchecked Sendable {
  // Narrow cross-thread capability: it invokes ONLY the manager's locked
  // revocation entrypoints; no UI/AX/native state is read through this wrapper.
  private weak var owner: NativeReadiness?
  init(_ owner: NativeReadiness) { self.owner = owner }
  func revoke(_ reason: String) { owner?.revoke(reason) }
  func timeout(_ ticket: NativeWitnessTicket) { owner?.expireWitness(ticket) }
}

final class NativeInspectorLocalReservation {
  private let lock = NSLock()
  private var released = false
  private let release: () -> Void
  init(release: @escaping () -> Void) { self.release = release }
  func finish() {
    let act = lock.withLock { () -> Bool in
      if released { return false }
      released = true
      return true
    }
    if act { release() }
  }
}
protocol NativeInspectorLocalAccess {
  func reserveInspector(budget: NativeBudget) async throws -> NativeInspectorLocalReservation
}
private struct NativeUnavailableInspectorAccess: NativeInspectorLocalAccess {
  func reserveInspector(budget: NativeBudget) async throws -> NativeInspectorLocalReservation {
    throw BrokerFailure.busy
  }
}

struct NativeReadinessSnapshot {
  let ready: Bool
  let interference: String
  let interactionGeneration: String
}
struct NativeArmingApplication {
  let id: String, pid: pid_t, launchDate: Date, bundleId: String
}
struct NativeArmingContext {
  let display: BrokerDisplay
  let targets: [NativeConsentTarget]
  let applications: [NativeArmingApplication]
  var applicationGeneration: String {
    brokerHash(
      applications.sorted { $0.id < $1.id }.map {
        "\($0.id):\($0.pid):\($0.launchDate.timeIntervalSince1970):\($0.bundleId)"
      }.joined(separator: ";"))
  }
}
private struct NativeArmBindingValue: Encodable {
  let lease: BrokerLease, helperGeneration: String
}
private struct NativeArmProofValue: Encodable {
  let kind = "local-ui-live-route/1"
  let proofEpoch: String, state: String, verifiedAt: String, displayGeneration: String
  let applicationGeneration: String, policyFingerprint: String
}
/// The private wire projects no nonce bits, event tags, UI answers or raw witness.
struct NativeArmStatusValue: Encodable {
  let state: String
  fileprivate let binding: NativeArmBindingValue
  fileprivate var requestId: String?, challengeId: String?, requestedAt: String?, expiresAt: String?
  fileprivate var armGeneration: String?, armedAt: String?, proof: NativeArmProofValue?
  fileprivate var revokedAt: String?, reason: String?
  private enum Keys: String, CodingKey {
    case state, binding, requestId, challengeId, requestedAt, expiresAt, armGeneration, armedAt,
      proof, revokedAt, reason
  }
  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: Keys.self)
    try c.encode(state, forKey: .state)
    try c.encode(binding, forKey: .binding)
    switch state {
    case "UNARMED": break
    case "PENDING":
      try c.encode(requestId, forKey: .requestId)
      try c.encode(challengeId, forKey: .challengeId)
      try c.encode(requestedAt, forKey: .requestedAt)
      try c.encode(expiresAt, forKey: .expiresAt)
    case "ARMED":
      try c.encode(requestId, forKey: .requestId)
      try c.encode(challengeId, forKey: .challengeId)
      try c.encode(armGeneration, forKey: .armGeneration)
      try c.encode(armedAt, forKey: .armedAt)
      try c.encode(expiresAt, forKey: .expiresAt)
      try c.encode(proof, forKey: .proof)
    case "REVOKED":
      try c.encode(requestId, forKey: .requestId)
      try c.encode(armGeneration, forKey: .armGeneration)
      try c.encode(revokedAt, forKey: .revokedAt)
      try c.encode(reason, forKey: .reason)
    default: throw BrokerFailure.invalid
    }
  }
}
private struct NativePendingArm {
  let request: BrokerArmRequest, challengeId: String, requestedNs: UInt64, requestedWall: Date
  var approving = false
  var gestureNs: UInt64?
  var context: NativeArmingContext?
}
private struct NativeLiveProof {
  let epoch: String, verifiedNs: UInt64, verifiedWall: Date
}
private struct NativeArmedGrant {
  let request: BrokerArmRequest, challengeId: String, generation: String
  let startedNs: UInt64, startedWall: Date, expiresNs: UInt64
  let context: NativeArmingContext, displayGeneration: String
  var proof: NativeLiveProof?
}
private enum NativeArmPhase {
  case unarmed
  case pending(NativePendingArm)
  case armed(NativeArmedGrant)
  case revoked(requestId: String?, generation: String?, time: Date, reason: String)
}
private struct NativeRoutePermit {
  let token: Int64, expectedPid: pid_t, type: CGEventType, key: Int64, button: Int64
  let expiresNs: UInt64, cleanup: Bool, probe: Bool
}
private struct NativeSessionBinding: Equatable {
  let uid: UInt32
  /// Some current WindowServer sessions omit this documented auxiliary key.
  /// Absence is an explicit identity state, not console zero or unlocked proof.
  let console: UInt32?
  static func current() throws -> NativeSessionBinding {
    guard let dictionary = CGSessionCopyCurrentDictionary() as? [String: Any] else {
      throw BrokerFailure.inactive
    }
    return try decode(dictionary, expectedUid: getuid())
  }
  private static func unsigned(_ value: Any?) throws -> UInt32 {
    guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(),
      value.doubleValue.isFinite, value.doubleValue >= 0, value.doubleValue <= Double(UInt32.max),
      value.doubleValue.rounded() == value.doubleValue
    else { throw BrokerFailure.inactive }
    return value.uint32Value
  }
  static func decode(_ dictionary: [String: Any], expectedUid: UInt32) throws
    -> NativeSessionBinding
  {
    // Interpret ONLY documented keys. The required public flags bind the caller
    // GUI context; local consent + live compositor/routing still govern input.
    guard dictionary.count <= 64,
      let onConsole = dictionary[kCGSessionOnConsoleKey as String] as? NSNumber,
      let loginDone = dictionary[kCGSessionLoginDoneKey as String] as? NSNumber,
      CFGetTypeID(onConsole) == CFBooleanGetTypeID(),
      CFGetTypeID(loginDone) == CFBooleanGetTypeID(), onConsole.boolValue, loginDone.boolValue
    else { throw BrokerFailure.inactive }
    let uid = try unsigned(dictionary[kCGSessionUserIDKey as String])
    guard uid == expectedUid else { throw BrokerFailure.inactive }
    let console: UInt32?
    if let value = dictionary[kCGSessionConsoleSetKey as String] {
      console = try unsigned(value)
    } else {
      console = nil
    }
    return NativeSessionBinding(uid: uid, console: console)
  }
  static func memoryCheck() throws -> Int {
    var checks = 0
    let base: [String: Any] = [
      kCGSessionUserIDKey as String: NSNumber(value: UInt32(42)),
      kCGSessionOnConsoleKey as String: NSNumber(value: true),
      kCGSessionLoginDoneKey as String: NSNumber(value: true),
    ]
    let absent = try decode(base, expectedUid: 42)
    guard absent.uid == 42, absent.console == nil else { throw BrokerFailure.invalid }
    checks += 1
    var present = base
    present[kCGSessionConsoleSetKey as String] = NSNumber(value: UInt32.max)
    let bound = try decode(present, expectedUid: 42)
    guard bound.console == UInt32.max, bound != absent else { throw BrokerFailure.invalid }
    checks += 1
    present[kCGSessionConsoleSetKey as String] = NSNumber(value: UInt32(0))
    guard try decode(present, expectedUid: 42) != bound else { throw BrokerFailure.invalid }
    checks += 1
    for value: Any in [
      NSNumber(value: true), NSNull(), NSNumber(value: 0.5), "0", NSNumber(value: -1),
      NSNumber(value: UInt64(UInt32.max) + 1),
    ] {
      var malformed = base
      malformed[kCGSessionConsoleSetKey as String] = value
      do {
        _ = try decode(malformed, expectedUid: 42)
        throw BrokerFailure.invalid
      } catch BrokerFailure.inactive { checks += 1 }
    }
    for key in [
      kCGSessionUserIDKey as String, kCGSessionOnConsoleKey as String,
      kCGSessionLoginDoneKey as String,
    ] {
      var missing = base
      missing.removeValue(forKey: key)
      do {
        _ = try decode(missing, expectedUid: 42)
        throw BrokerFailure.invalid
      } catch BrokerFailure.inactive { checks += 1 }
    }
    for key in [kCGSessionOnConsoleKey as String, kCGSessionLoginDoneKey as String] {
      var inactive = base
      inactive[key] = NSNumber(value: false)
      do {
        _ = try decode(inactive, expectedUid: 42)
        throw BrokerFailure.invalid
      } catch BrokerFailure.inactive { checks += 1 }
    }
    do {
      _ = try decode(base, expectedUid: 43)
      throw BrokerFailure.invalid
    } catch BrokerFailure.inactive { checks += 1 }
    var boolUid = base
    boolUid[kCGSessionUserIDKey as String] = NSNumber(value: true)
    do {
      _ = try decode(boolUid, expectedUid: 42)
      throw BrokerFailure.invalid
    } catch BrokerFailure.inactive { checks += 1 }
    return checks
  }
}

/// One lease-local, revocable GUI capability. A local gesture and fresh owned
/// compositor/HID-route witnesses establish a narrow operating envelope, not a
/// theorem about every macOS lock state. Losing evidence immediately forbids new
/// posts; already posted uncertainty retains the existing durable/kernel fence.
final class NativeReadiness {
  let helperGeneration = UUID().uuidString.lowercased()
  private let lease: BrokerLease, configuration: BrokerConfiguration
  private let inputPlatform: any NativeInputPlatform
  private let localAccess: any NativeInspectorLocalAccess
  private var localTransition = false
  private weak var inspector: NativeInspector?
  private let lock = NSLock()
  private var phase = NativeArmPhase.unarmed
  private var owner: BrokerOwner?
  private var interactionEpoch = UUID().uuidString.lowercased()
  private var actionBudget: NativeBudget?
  private var actionPid: pid_t?
  private var active = false
  private var session: NativeSessionBinding?
  private var pendingRoute: NativeRoutePermit?
  private var routeReceipts: [Int64: Bool] = [:]
  private var unresolvedRoutes = Set<Int64>()
  private var actionHandler: ((CGEventType, CGEvent) -> Void)?
  private var tapHealthy = false
  private var tap: CFMachPort?, tapSource: CFRunLoopSource?
  private var task: Task<Void, Never>?
  private var expiryTask: Task<Void, Never>?
  private var choicesTask: Task<Void, Never>?
  private var uiCloseTask: Task<Void, Never>?
  private var witnessLifecycle = NativeWitnessLifecycle()
  private var shutdownJoin: Task<Void, Never>?
  private var approvalTask: Task<Void, Never>?
  private var approvalBudget: NativeBudget?
  private var uiRegions = NativeOwnedUIRegions(
    generation: UUID().uuidString.lowercased(), rectangles: [])
  private var expectedProbe: Int64?, probeReceived: Int64?
  private var closed = false
  private let wallOrigin = Date(), monoOrigin = DispatchTime.now().uptimeNanoseconds
  private let makeUI: @MainActor () -> any NativeReadinessUI
  @MainActor private var ui: (any NativeReadinessUI)?
  @MainActor private var observers: [NSObjectProtocol] = []

  init(
    lease: BrokerLease, configuration: BrokerConfiguration, inspector: NativeInspector,
    uiFactory: @escaping @MainActor () -> any NativeReadinessUI,
    inputPlatform: any NativeInputPlatform = NativeSystemInputPlatform(),
    localAccess: any NativeInspectorLocalAccess = NativeUnavailableInspectorAccess()
  ) {
    makeUI = uiFactory
    self.inputPlatform = inputPlatform
    self.localAccess = localAccess
    self.lease = lease
    self.configuration = configuration
    self.inspector = inspector
  }
  private func nowWall(_ now: UInt64 = DispatchTime.now().uptimeNanoseconds) -> Date {
    wallOrigin.addingTimeInterval(Double(now >= monoOrigin ? now - monoOrigin : 0) / 1e9)
  }
  private func iso(_ value: Date) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: value)
  }
  private var fingerprint: String {
    brokerHash(
      "local-ui-live-route/1:\(configuration.targetId):\(configuration.surfaceId):\(configuration.controlDescription)"
    )
  }
  private func expireLocked(_ now: UInt64) -> (() -> Void)? {
    switch phase {
    case .pending(let pending) where now - pending.requestedNs >= 30_000_000_000:
      return revokeLocked("EXPIRED", now: now)
    case .armed(let grant) where now >= grant.expiresNs:
      return revokeLocked("EXPIRED", now: now)
    default: return nil
    }
  }
  private func revokeLocked(_ reason: String, now: UInt64) -> () -> Void {
    let requestId: String?
    let generation: String?
    switch phase {
    case .pending(let pending):
      requestId = pending.request.requestId
      generation = nil
    case .armed(let grant):
      requestId = grant.request.requestId
      generation = grant.generation
    case .revoked: return {}
    case .unarmed:
      requestId = nil
      generation = nil
    }
    expiryTask?.cancel()
    phase = .revoked(
      requestId: requestId, generation: generation, time: nowWall(now), reason: reason)
    interactionEpoch = UUID().uuidString.lowercased()
    let action = actionBudget
    let approval = approvalBudget
    return {
      action?.cancel()
      approval?.cancel()
      self.closeRevokedUI()
    }
  }
  private func closeRevokedUI() {
    lock.withLock {
      guard uiCloseTask == nil else { return }
      uiCloseTask = Task { @MainActor [weak self] in
        guard let self else { return }
        await self.ui?.close()
        self.ui = nil
        self.lock.withLock { self.uiCloseTask = nil }
      }
    }
  }
  func revoke(_ reason: String) {
    let cancel = lock.withLock { revokeLocked(reason, now: DispatchTime.now().uptimeNanoseconds) }
    cancel()
  }
  private func revokePresentation(_ challenge: String, reason: String) {
    let current = lock.withLock { () -> Bool in
      switch phase {
      case .pending(let p): return p.challengeId == challenge
      case .armed(let g): return g.challengeId == challenge
      default: return false
      }
    }
    if current { revoke(reason) }
  }
  func status() -> NativeArmStatusValue {
    let now = DispatchTime.now().uptimeNanoseconds
    let (value, cancel): (NativeArmStatusValue, (() -> Void)?) = lock.withLock {
      let cancel = expireLocked(now)
      let binding = NativeArmBindingValue(lease: lease, helperGeneration: helperGeneration)
      switch phase {
      case .unarmed: return (NativeArmStatusValue(state: "UNARMED", binding: binding), cancel)
      case .pending(let pending):
        return (
          NativeArmStatusValue(
            state: "PENDING", binding: binding, requestId: pending.request.requestId,
            challengeId: pending.challengeId, requestedAt: iso(pending.requestedWall),
            expiresAt: iso(pending.requestedWall.addingTimeInterval(30))), cancel
        )
      case .armed(let grant):
        let proof = grant.proof
        let fresh =
          !localTransition
          && proof.map { now >= $0.verifiedNs && now - $0.verifiedNs <= nativeProofMaximumAge }
            ?? false
        let projected = NativeArmProofValue(
          proofEpoch: proof?.epoch ?? grant.generation, state: fresh ? "fresh" : "refresh-required",
          verifiedAt: iso(proof?.verifiedWall ?? grant.startedWall),
          displayGeneration: grant.displayGeneration,
          applicationGeneration: grant.context.applicationGeneration, policyFingerprint: fingerprint
        )
        return (
          NativeArmStatusValue(
            state: "ARMED", binding: binding, requestId: grant.request.requestId,
            challengeId: grant.challengeId,
            expiresAt: iso(
              grant.startedWall.addingTimeInterval(Double(grant.request.durationMs) / 1000)),
            armGeneration: grant.generation, armedAt: iso(grant.startedWall), proof: projected),
          cancel
        )
      case .revoked(let requestId, let generation, let time, let reason):
        return (
          NativeArmStatusValue(
            state: "REVOKED", binding: binding, requestId: requestId, armGeneration: generation,
            revokedAt: iso(time), reason: reason), cancel
        )
      }
    }
    cancel?()
    return value
  }
  func snapshot() -> NativeReadinessSnapshot {
    let now = DispatchTime.now().uptimeNanoseconds
    let (value, cancel) = lock.withLock { () -> (NativeReadinessSnapshot, (() -> Void)?) in
      var cancel = expireLocked(now)
      if witnessLifecycle.expired(now: now) { cancel = revokeLocked("WITNESS_FAILED", now: now) }
      var ready = false
      if case .armed(let grant) = phase, let proof = grant.proof {
        ready =
          !localTransition && tapHealthy && now < grant.expiresNs && now >= proof.verifiedNs
          && now - proof.verifiedNs <= nativeProofMaximumAge
      }
      return (
        NativeReadinessSnapshot(
          ready: ready, interference: ready ? "clear" : "unknown",
          interactionGeneration: interactionEpoch), cancel
      )
    }
    cancel?()
    return value
  }
  func regions() -> NativeOwnedUIRegions { lock.withLock { uiRegions } }
  private func checkEnvironment(_ budget: NativeBudget, context: NativeArmingContext? = nil) throws
  {
    try budget.check()
    let permission = BrokerPermissions.current()
    guard
      permission.screenCapture && permission.accessibility && permission.eventPosting
        && permission.eventListening
    else { throw BrokerFailure.denied }
    guard !IsSecureEventInputEnabled() else { throw BrokerFailure.secure }
    let current = try NativeSessionBinding.current()
    if let previous = lock.withLock({ session }), current != previous {
      throw BrokerFailure.inactive
    }
    if let context {
      guard let inspector, try inspector.display(budget) == context.display else {
        throw BrokerFailure.stale
      }
      for expected in context.applications {
        try budget.check()
        guard let app = NSRunningApplication(processIdentifier: expected.pid), !app.isTerminated,
          app.bundleIdentifier == expected.bundleId, app.launchDate == expected.launchDate
        else { throw BrokerFailure.stale }
      }
    }
    try budget.check()
  }
  func request(owner: BrokerOwner, request: BrokerArmRequest, budget: NativeBudget) async throws
    -> NativeArmStatusValue
  {
    try budget.check()
    let now = DispatchTime.now().uptimeNanoseconds
    try lock.withLock {
      guard !closed, task == nil, approvalTask == nil, choicesTask == nil, uiCloseTask == nil,
        expiryTask == nil, witnessLifecycle.ticket == nil
      else { throw BrokerFailure.busy }
      if case .pending = phase { throw BrokerFailure.busy }
      if case .armed = phase { throw BrokerFailure.busy }
      guard self.owner == nil || self.owner == owner else { throw BrokerFailure.noLease }
      self.owner = owner
      let pending = NativePendingArm(
        request: request, challengeId: UUID().uuidString.lowercased(), requestedNs: now,
        requestedWall: nowWall(now))
      phase = .pending(pending)
      expiryTask = Task { [weak self] in
        guard let self else { return }
        defer { self.lock.withLock { self.expiryTask = nil } }
        do { try await Task.sleep(nanoseconds: 30_000_000_000) } catch { return }
        self.revokePresentation(pending.challengeId, reason: "EXPIRED")
      }
    }
    do {
      // Establish caller GUI/session identity before AppKit construction. These
      // documented fields bind context only; they do not assert unlocked state.
      let callerSession = try NativeSessionBinding.current()
      lock.withLock { session = callerSession }
      // A command-line helper is not registered with AppKit until this point.
      // AX system-wide messaging can fail before that registration even when
      // trust is true. Registration is not activation or readiness authority.
      try await prepareUI(budget: budget, pendingRequest: request.requestId)
      try budget.check()
      guard try NativeSessionBinding.current() == callerSession else {
        throw BrokerFailure.inactive
      }
      guard
        lock.withLock({
          if case .pending(let pending) = phase {
            return !closed && pending.request.requestId == request.requestId
          }
          return false
        })
      else { throw BrokerFailure.fenced }
      let context: NativeArmingContext
      if AXIsProcessTrusted(), let inspector {
        context = try inspector.armingContext(budget)
      } else {
        guard let inspector else { throw BrokerFailure.gone }
        context = NativeArmingContext(
          display: try inspector.display(budget), targets: [], applications: [])
      }
      let pending = try lock.withLock { () -> NativePendingArm in
        guard case .pending(var pending) = phase else { throw BrokerFailure.fenced }
        pending.context = context
        phase = .pending(pending)
        return pending
      }
      let value = status()
      try await present(owner: owner, pending: pending, context: context, budget: budget)
      try budget.check()
      return value
    } catch {
      revoke(reason(error))
      throw error
    }
  }
  /// AX discovery/capture are independent read capabilities: registering our
  /// own AppKit application must not require or manufacture an input grant.
  func prepareReadPlane(budget: NativeBudget) async throws {
    try budget.check()
    do {
      let current = try NativeSessionBinding.current()
      try lock.withLock {
        guard !closed else { throw BrokerFailure.fenced }
        if let previous = session, previous != current { throw BrokerFailure.inactive }
        session = current
      }
      try await prepareUI(budget: budget)
      try budget.check()
      guard try NativeSessionBinding.current() == current else { throw BrokerFailure.inactive }
    } catch {
      if error as? BrokerFailure == .inactive { revoke("SESSION_CHANGED") }
      throw error
    }
  }
  @MainActor private func prepareUI(budget: NativeBudget, pendingRequest: String? = nil) throws {
    try budget.check()
    guard
      lock.withLock({
        guard !closed else { return false }
        if let pendingRequest {
          if case .pending(let pending) = phase {
            return pending.request.requestId == pendingRequest
          }
          return false
        }
        return true
      })
    else { throw BrokerFailure.fenced }
    let preparedUI = ui ?? makeUI()
    ui = preparedUI
    try preparedUI.prepare()
    try budget.check()
  }
  @MainActor private func present(
    owner: BrokerOwner, pending: NativePendingArm, context: NativeArmingContext,
    budget: NativeBudget
  ) throws {
    try budget.check()
    guard
      lock.withLock({
        if case .pending(let current) = phase {
          return !closed && current.challengeId == pending.challengeId
        }
        return false
      }), let ui
    else { throw BrokerFailure.fenced }
    let callbacks = NativeReadinessUICallbacks(
      approve: { [weak self] surface in
        self?.beginApproval(surface: surface, challenge: pending.challengeId)
      },
      revoke: { [weak self] in self?.revokePresentation(pending.challengeId, reason: "HOST_REVOKED")
      },
      permissionsChanged: { [weak self] in self?.refreshChoices() },
      probeReceived: { [weak self] token in
        self?.lock.withLock { if self?.expectedProbe == token { self?.probeReceived = token } }
      },
      presentationLost: { [weak self] in
        self?.revokePresentation(pending.challengeId, reason: "WITNESS_FAILED")
      })
    try ui.present(
      NativeConsentPresentation(
        owner: owner, challengeId: pending.challengeId, durationMs: pending.request.durationMs,
        display: context.display, targets: context.targets, tools: configuration.tools.sorted()),
      callbacks: callbacks)
    let regions = ui.regions()
    lock.withLock { uiRegions = regions }
    if observers.isEmpty {
      let workspace = NSWorkspace.shared.notificationCenter
      let sink = NativeReadinessRevocationSink(self)
      for name in [
        NSWorkspace.sessionDidResignActiveNotification, NSWorkspace.screensDidSleepNotification,
        NSWorkspace.willSleepNotification, NSWorkspace.activeSpaceDidChangeNotification,
      ] {
        observers.append(
          workspace.addObserver(forName: name, object: nil, queue: .main) { _ in
            sink.revoke("SESSION_CHANGED")
          })
      }
    }
  }
  private func refreshChoices() {
    lock.withLock {
      guard choicesTask == nil, case .pending = phase else { return }
      choicesTask = Task { [weak self] in
        guard let self else { return }
        defer { self.lock.withLock { self.choicesTask = nil } }
        let budget = NativeBudget(milliseconds: 2000)
        do {
          let reservation = try await self.localAccess.reserveInspector(budget: budget)
          defer { reservation.finish() }
          guard let inspector = self.inspector else { throw BrokerFailure.gone }
          let context = try inspector.armingContext(budget)
          await MainActor.run {
            self.lock.withLock {
              guard case .pending(var pending) = self.phase, !pending.approving else { return }
              if let displayed = pending.context, displayed.display != context.display { return }
              self.ui?.updateTargets(context.targets)
              pending.context = context
              self.phase = .pending(pending)
            }
          }
        } catch { self.revoke(self.reason(error)) }
      }
    }
  }
  private func beginApproval(surface: String, challenge: String) {
    lock.withLock {
      guard case .pending(var pending) = phase, pending.challengeId == challenge,
        !pending.approving,
        DispatchTime.now().uptimeNanoseconds - pending.requestedNs < 30_000_000_000
      else { return }
      pending.approving = true
      pending.gestureNs = DispatchTime.now().uptimeNanoseconds
      phase = .pending(pending)
      expiryTask?.cancel()
      approvalTask = Task { [weak self] in
        guard let self else { return }
        await self.approve(surface: surface)
      }
    }
  }
  private func approve(surface: String) async {
    let budget = NativeBudget(milliseconds: 2000)
    lock.withLock { approvalBudget = budget }
    defer {
      lock.withLock {
        approvalBudget = nil
        approvalTask = nil
      }
    }
    do {
      let reservation = try await localAccess.reserveInspector(budget: budget)
      lock.withLock { localTransition = true }
      defer {
        reservation.finish()
        lock.withLock { localTransition = false }
      }
      let pending = try lock.withLock { () -> NativePendingArm in
        guard case .pending(let pending) = phase else { throw BrokerFailure.fenced }
        return pending
      }
      guard let started = pending.gestureNs else { throw BrokerFailure.fenced }
      try checkEnvironment(budget)
      guard let inspector else { throw BrokerFailure.gone }
      guard let context = pending.context else { throw BrokerFailure.stale }
      let current = try inspector.armingContext(budget)
      guard current.applicationGeneration == context.applicationGeneration,
        current.display == context.display,
        current.targets.contains(where: { $0.surfaceId == surface }),
        context.targets.contains(where: { $0.surfaceId == surface })
      else {
        throw BrokerFailure.gone
      }
      try await installTap(budget)
      try await MainActor.run {
        guard let ui = self.ui else { throw BrokerFailure.gone }
        try ui.showSentinel(display: context.display)
      }
      try await inspector.focusForLocalConsent(surface: surface, budget: budget)
      let binding = try NativeSessionBinding.current()
      try lock.withLock {
        guard case .pending = phase, !closed else { throw BrokerFailure.fenced }
        session = binding
        phase = .armed(
          NativeArmedGrant(
            request: pending.request, challengeId: pending.challengeId,
            generation: UUID().uuidString.lowercased(), startedNs: started,
            startedWall: nowWall(started),
            expiresNs: started + UInt64(pending.request.durationMs) * 1_000_000, context: context,
            displayGeneration: brokerHash("\(context.display)"), proof: nil))
        interactionEpoch = UUID().uuidString.lowercased()
      }
      try await witness(budget: budget, probe: true)
      lock.withLock {
        task = Task { [weak self] in
          guard let self else { return }
          await self.refreshLoop()
        }
      }
    } catch { revoke(reason(error)) }
  }
  private func reason(_ error: Error) -> String {
    switch error as? BrokerFailure {
    case .denied: return "PERMISSION_CHANGED"
    case .secure: return "SECURE_INPUT"
    case .inactive: return "SESSION_CHANGED"
    case .stale, .gone: return "APPLICATION_CHANGED"
    case .interference: return "INTERFERENCE"
    case .expired: return "WITNESS_FAILED"
    default: return "WITNESS_FAILED"
    }
  }
  private func refreshLoop() async {
    defer { lock.withLock { task = nil } }
    while !Task.isCancelled {
      let started = DispatchTime.now().uptimeNanoseconds
      _ = status()
      guard case .armed = lock.withLock({ phase }) else { return }
      do {
        try await witness(budget: NativeBudget(milliseconds: nativeProofBudgetMs), probe: false)
      } catch {
        revoke(reason(error))
        return
      }
      let elapsed = DispatchTime.now().uptimeNanoseconds - started
      if elapsed < nativeProofInterval {
        do { try await Task.sleep(nanoseconds: nativeProofInterval - elapsed) } catch { return }
      }
    }
  }
  func applicationGranted(_ application: NativeArmingApplication) -> Bool {
    lock.withLock {
      guard case .armed(let grant) = phase else { return false }
      return grant.context.applications.contains {
        $0.id == application.id && $0.pid == application.pid
          && $0.launchDate == application.launchDate && $0.bundleId == application.bundleId
      }
    }
  }
  private func probeAdmission(_ budget: NativeBudget) throws {
    for code in UInt16(0)..<128 {
      try budget.check()
      guard !inputPlatform.keyDown(code) else { throw BrokerFailure.interference }
    }
    for raw in UInt32(0)..<3 {
      try budget.check()
      guard let button = CGMouseButton(rawValue: raw), !inputPlatform.buttonDown(button) else {
        throw BrokerFailure.interference
      }
    }
  }
  func enterAction(pid: pid_t, budget: NativeBudget) async throws {
    try checkEnvironment(budget)
    let grant = try lock.withLock { () -> NativeArmedGrant in
      guard case .armed(let grant) = phase, !active else { throw BrokerFailure.unknown }
      guard grant.context.applications.contains(where: { $0.pid == pid }) else {
        throw BrokerFailure.notAllowed
      }
      return grant
    }
    // Probe only on action entry, before any held input exists. Active refreshes
    // are pixel-only and never move the cursor away during typing/dragging.
    try await witness(budget: budget, probe: true)
    try lock.withLock {
      guard case .armed(let current) = phase, current.generation == grant.generation, tapHealthy
      else { throw BrokerFailure.unknown }
      active = true
      actionBudget = budget
      actionPid = pid
    }
  }
  func leaveAction() {
    lock.withLock {
      active = false
      actionBudget = nil
      actionPid = nil
    }
  }
  func setHandler(_ handler: ((CGEventType, CGEvent) -> Void)?) {
    lock.withLock { actionHandler = handler }
  }

  @MainActor private func installTap(_ budget: NativeBudget) throws {
    try budget.check()
    if tap != nil {
      guard lock.withLock({ tapHealthy }) else { throw BrokerFailure.fenced }
      return
    }
    guard CGPreflightListenEventAccess(), CGPreflightPostEventAccess(), AXIsProcessTrusted() else {
      throw BrokerFailure.denied
    }
    let mask = [
      CGEventType.keyDown, .keyUp, .flagsChanged, .mouseMoved, .leftMouseDown, .leftMouseUp,
      .rightMouseDown, .rightMouseUp, .otherMouseDown, .otherMouseUp, .leftMouseDragged,
      .rightMouseDragged, .otherMouseDragged, .scrollWheel,
    ].reduce(CGEventMask(0)) { $0 | (CGEventMask(1) << $1.rawValue) }
    let callback: CGEventTapCallBack = { _, type, event, info in
      guard let info else { return Unmanaged.passUnretained(event) }
      let owner = Unmanaged<NativeReadiness>.fromOpaque(info).takeUnretainedValue()
      return owner.filter(type: type, event: event) ? Unmanaged.passUnretained(event) : nil
    }
    guard
      let port = CGEvent.tapCreate(
        tap: .cgAnnotatedSessionEventTap, place: .tailAppendEventTap, options: .defaultTap,
        eventsOfInterest: mask, callback: callback,
        userInfo: Unmanaged.passUnretained(self).toOpaque()),
      let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, port, 0)
    else { throw BrokerFailure.denied }
    tap = port
    tapSource = source
    CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
    CGEvent.tapEnable(tap: port, enable: true)
    lock.withLock { tapHealthy = true }
    try budget.check()
  }
  /// O(1), cached-only event admission. No AX, capture, AppKit, process queries,
  /// sleeps or native RPCs execute inside the WindowServer callback. Unknown
  /// human events always pass; only exact registered own tokens can be deleted.
  private func filter(type: CGEventType, event: CGEvent) -> Bool {
    let now = DispatchTime.now().uptimeNanoseconds
    let token = event.getIntegerValueField(.eventSourceUserData)
    let source = event.getIntegerValueField(.eventSourceUnixProcessID)
    let target = event.getIntegerValueField(.eventTargetUnixProcessID)
    let (allowed, notify, cancel): (Bool, ((CGEventType, CGEvent) -> Void)?, (() -> Void)?) =
      lock.withLock {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
          tapHealthy = false
          return (true, nil, revokeLocked("WITNESS_FAILED", now: now))
        }
        if source == Int64(getpid()), unresolvedRoutes.contains(token) { return (false, nil, nil) }
        guard source == Int64(getpid()), let permit = pendingRoute, permit.token == token else {
          if case .armed = phase { return (true, nil, revokeLocked("INTERFERENCE", now: now)) }
          return (true, nil, nil)
        }
        pendingRoute = nil
        let bound =
          permit.type == type && permit.key == event.getIntegerValueField(.keyboardEventKeycode)
          && permit.button == event.getIntegerValueField(.mouseEventButtonNumber)
          && target == Int64(permit.expectedPid) && now < permit.expiresNs && tapHealthy
        var live = permit.cleanup
        if case .armed(let grant) = phase, now < grant.expiresNs {
          if permit.probe {
            live = true
          } else if let proof = grant.proof {
            live = now >= proof.verifiedNs && now - proof.verifiedNs <= nativeProofMaximumAge
          }
        }
        let admitted = bound && live && (permit.cleanup || !witnessLifecycle.expired(now: now))
        routeReceipts[token] = admitted
        if !admitted {
          unresolvedRoutes.insert(token)
          return (false, nil, revokeLocked("WITNESS_FAILED", now: now))
        }
        return (true, permit.probe ? nil : actionHandler, nil)
      }
    cancel?()
    notify?(type, event)
    return allowed
  }
  private func post(_ event: CGEvent, pid: pid_t, budget: NativeBudget, cleanup: Bool, probe: Bool)
    throws
  {
    try budget.check()
    let now = DispatchTime.now().uptimeNanoseconds
    let remaining = try budget.remaining()
    let token = event.getIntegerValueField(.eventSourceUserData)
    try lock.withLock {
      guard !closed, tapHealthy, pendingRoute == nil, routeReceipts.count < 16,
        unresolvedRoutes.count < 16,
        token > 0, !unresolvedRoutes.contains(token), routeReceipts[token] == nil
      else { throw BrokerFailure.fenced }
      if !cleanup {
        guard case .armed(let grant) = phase, now < grant.expiresNs else {
          throw BrokerFailure.unknown
        }
        if !probe {
          guard !witnessLifecycle.expired(now: now), active, actionPid == pid,
            let proof = grant.proof,
            now >= proof.verifiedNs, now - proof.verifiedNs <= nativeProofMaximumAge
          else { throw BrokerFailure.unknown }
        }
      } else {
        guard [.keyUp, .leftMouseUp, .rightMouseUp, .otherMouseUp].contains(event.type),
          actionPid == pid
        else { throw BrokerFailure.fenced }
      }
      pendingRoute = NativeRoutePermit(
        token: token, expectedPid: pid, type: event.type,
        key: event.getIntegerValueField(.keyboardEventKeycode),
        button: event.getIntegerValueField(.mouseEventButtonNumber),
        expiresNs: now + UInt64(max(1, remaining * 1e9)), cleanup: cleanup, probe: probe)
    }
    // Ordinary HID only: routing is observed/filtered after WindowServer target
    // annotation, never overridden by setting a target field or postToPid.
    event.post(tap: .cghidEventTap)
  }
  func postOwn(_ event: CGEvent, budget: NativeBudget, cleanup: Bool) throws {
    guard let pid = lock.withLock({ actionPid }) else { throw BrokerFailure.noLease }
    try post(event, pid: pid, budget: budget, cleanup: cleanup, probe: false)
  }
  func waitForReceipt(_ event: CGEvent, budget: NativeBudget) throws {
    let token = event.getIntegerValueField(.eventSourceUserData)
    do {
      while true {
        try budget.check()
        if let admitted = lock.withLock({ routeReceipts.removeValue(forKey: token) }) {
          guard admitted else { throw BrokerFailure.input }
          return
        }
        // The waiter is outside the tap/main actor. At most one submitted event
        // awaits a receipt; cancellation/expiry never enqueue a replacement.
        Thread.sleep(forTimeInterval: min(0.001, try budget.remaining()))
      }
    } catch {
      let cancel = lock.withLock { () -> (() -> Void) in
        if pendingRoute?.token == token {
          pendingRoute = nil
          unresolvedRoutes.insert(token)
        }
        return revokeLocked("WITNESS_FAILED", now: DispatchTime.now().uptimeNanoseconds)
      }
      cancel()
      throw error
    }
  }
  fileprivate func expireWitness(_ original: NativeWitnessTicket) {
    let cancel = lock.withLock { () -> (() -> Void)? in
      guard witnessLifecycle.ticket == original else { return nil }
      return revokeLocked("WITNESS_FAILED", now: DispatchTime.now().uptimeNanoseconds)
    }
    cancel?()
  }
  private func witness(budget parent: NativeBudget, probe: Bool) async throws {
    let ticket: NativeWitnessTicket
    while true {
      try parent.check()
      let admitted = try lock.withLock { () -> NativeWitnessTicket? in
        if witnessLifecycle.ticket != nil { return nil }
        let now = DispatchTime.now().uptimeNanoseconds
        return try witnessLifecycle.begin(
          now: now, deadline: now + UInt64(min(0.2, try parent.remaining()) * 1e9))
      }
      if let admitted {
        ticket = admitted
        break
      }
      try await parent.sleep(milliseconds: 2)
    }
    defer { lock.withLock { witnessLifecycle.settle(ticket) } }
    let budget = try parent.capped(milliseconds: nativeProofBudgetMs)
    let watchdog = DispatchSource.makeTimerSource(queue: .global())
    let sink = NativeReadinessRevocationSink(self)
    watchdog.schedule(deadline: DispatchTime(uptimeNanoseconds: ticket.deadline))
    watchdog.setEventHandler { sink.timeout(ticket) }
    watchdog.resume()
    defer { watchdog.cancel() }
    try budget.check()
    let grant = try lock.withLock { () -> NativeArmedGrant in
      guard case .armed(let grant) = phase, !closed else { throw BrokerFailure.unknown }
      return grant
    }
    try checkEnvironment(budget, context: grant.context)
    var nonceBytes = [UInt8](repeating: 0, count: 32)
    guard SecRandomCopyBytes(kSecRandomDefault, nonceBytes.count, &nonceBytes) == errSecSuccess
    else { throw BrokerFailure.fenced }
    let nonce = nonceBytes
    let geometry = try await MainActor.run { () throws -> NativeSentinelGeometry in
      try budget.check()
      guard let ui = self.ui,
        self.lock.withLock({ () -> Bool in
          if case .armed(let current) = self.phase { return current.generation == grant.generation }
          return false
        })
      else { throw BrokerFailure.gone }
      return try ui.paintNonce(nonce)
    }
    guard geometry.presented, grant.context.display.bounds.rect.contains(geometry.captureRectangle),
      geometry.captureRectangle.width >= 1, geometry.captureRectangle.height >= 1,
      geometry.captureRectangle.width <= 128, geometry.captureRectangle.height <= 128
    else { throw BrokerFailure.observation }
    lock.withLock { uiRegions = geometry.regions }
    guard #available(macOS 15.2, *) else { throw BrokerFailure.unsupported }
    var matched = false
    // Drawing publication is asynchronous. At most two SERIAL read-only captures
    // share this witness's original <=200ms budget; no new task replaces a timed
    // out capture and no raw witness is returned to the SDK/model/journal.
    for attempt in 0..<2 {
      try budget.check()
      let image = try await SCScreenshotManager.captureImage(in: geometry.captureRectangle)
      try budget.check()
      if try nativeNonceMatches(image: image, nonce: nonce) {
        matched = true
        break
      }
      if attempt == 0 { try await budget.sleep(milliseconds: 5) }
    }
    guard matched else { throw BrokerFailure.observation }
    try checkEnvironment(budget, context: grant.context)
    if probe {
      try probeAdmission(budget)
      let token = Int64.random(in: 1...Int64.max)
      lock.withLock {
        expectedProbe = token
        probeReceived = nil
      }
      try await MainActor.run {
        try budget.check()
        self.ui?.expectProbe(token)
      }
      guard
        let event = CGEvent(
          mouseEventSource: nil, mouseType: .mouseMoved,
          mouseCursorPosition: geometry.pointerLocation, mouseButton: .left)
      else { throw BrokerFailure.input }
      event.setIntegerValueField(.eventSourceUserData, value: token)
      guard event.getIntegerValueField(.eventSourceUserData) == token else {
        throw BrokerFailure.input
      }
      try post(event, pid: getpid(), budget: budget, cleanup: false, probe: true)
      // Await the annotated routing receipt without blocking AppKit's main actor.
      try await Task.detached { try self.waitForReceipt(event, budget: budget) }.value
      while !lock.withLock({ probeReceived == token }) { try await budget.sleep(milliseconds: 2) }
      lock.withLock {
        expectedProbe = nil
        probeReceived = nil
      }
    }
    let now = DispatchTime.now().uptimeNanoseconds
    try lock.withLock {
      guard case .armed(var current) = phase, current.generation == grant.generation,
        now < current.expiresNs
      else { throw BrokerFailure.unknown }
      current.proof = NativeLiveProof(
        epoch: UUID().uuidString.lowercased(), verifiedNs: now, verifiedWall: nowWall(now))
      phase = .armed(current)
    }
  }
  /// Stops all owned refresh/approval/UI/tap work before input/helper cleanup can
  /// release a physical fence. Cancelling a ScreenshotManager await is not proof
  /// it stopped; the original task remains joined until settlement or kill.
  func stop(budget: NativeBudget) async throws {
    revoke("LEASE_CLOSED")
    let tasks = lock.withLock { () -> [Task<Void, Never>] in
      closed = true
      let owned = [task, approvalTask, choicesTask, expiryTask, uiCloseTask].compactMap { $0 }
      for task in owned { task.cancel() }
      return owned
    }
    let settled = NativeSettlementLatch()
    let join = Task {
      for task in tasks { await task.value }
      settled.finish()
    }
    lock.withLock { shutdownJoin = join }
    while !settled.isFinished {
      try budget.check()
      try await budget.sleep(milliseconds: 2)
    }
    guard
      lock.withLock({
        witnessLifecycle.ticket == nil && pendingRoute == nil && unresolvedRoutes.isEmpty
      })
    else { throw BrokerFailure.cleanup }
    try budget.check()
    await MainActor.run {
      if let tap = self.tap {
        CGEvent.tapEnable(tap: tap, enable: false)
        CFMachPortInvalidate(tap)
      }
      if let source = self.tapSource {
        CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
      }
      self.tap = nil
      self.tapSource = nil
      for observer in self.observers {
        NSWorkspace.shared.notificationCenter.removeObserver(observer)
      }
      self.observers.removeAll()
    }
    await closeUIAfterStop()
    lock.withLock {
      tapHealthy = false
      actionHandler = nil
    }
    try budget.check()
  }
  @MainActor private func closeUIAfterStop() async {
    await ui?.close()
    ui = nil
  }
  func captureRegions(budget: NativeBudget) async throws -> NativeOwnedUIRegions {
    try budget.check()
    let regions = await MainActor.run { self.ui?.regions() }
    try budget.check()
    if let regions {
      lock.withLock { uiRegions = regions }
      return regions
    }
    return lock.withLock { uiRegions }
  }

}

extension BrokerConfiguration {
  fileprivate var controlDescription: String {
    tools.sorted().joined(separator: ",") + ":" + allowedBundleIds.sorted().joined(separator: ",")
  }
}

/// Converts only a tiny bounded private witness to standard RGB, then checks the
/// unpredictable 16x16 binary grid. Both raster row orientations prove the same
/// fresh challenge; neither tolerates a stale/different nonce or broad image fit.
func nativeNonceMatches(image: CGImage, nonce: [UInt8]) throws -> Bool {
  guard nonce.count == 32, image.width >= 16, image.height >= 16, image.width <= 1024,
    image.height <= 1024,
    image.bytesPerRow <= 16384, image.bytesPerRow * image.height <= 16_777_216
  else { throw BrokerFailure.observation }
  let width = image.width
  let height = image.height
  var bytes = [UInt8](repeating: 0, count: width * height * 4)
  let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
    guard let space = CGColorSpace(name: CGColorSpace.sRGB),
      let context = CGContext(
        data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: width * 4, space: space,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { return false }
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    return true
  }
  guard drawn else { throw BrokerFailure.observation }
  func matches(flip: Bool) -> Bool {
    for row in 0..<16 {
      for column in 0..<16 {
        let bit = row * 16 + column
        let expected = (nonce[bit / 8] >> (7 - bit % 8)) & 1
        let y = min(height - 1, Int((Double(flip ? 15 - row : row) + 0.5) * Double(height) / 16))
        let x = min(width - 1, Int((Double(column) + 0.5) * Double(width) / 16))
        let offset = (y * width + x) * 4
        let value = (Int(bytes[offset]) + Int(bytes[offset + 1]) + Int(bytes[offset + 2])) / 3
        if expected == 0 ? value >= 64 : value <= 192 { return false }
      }
    }
    return true
  }
  return matches(flip: false) || matches(flip: true)
}

struct NativeArmedEventSink: NativeEventSink {
  let readiness: NativeReadiness
  func post(_ event: CGEvent, budget: NativeBudget, cleanup: Bool) throws {
    try readiness.postOwn(event, budget: budget, cleanup: cleanup)
  }
  func settle(_ event: CGEvent, budget: NativeBudget) throws {
    try readiness.waitForReceipt(event, budget: budget)
  }
}
struct NativeArmedInputMonitor: NativeInputMonitor {
  let readiness: NativeReadiness
  func start(_ handler: @escaping (CGEventType, CGEvent) -> Void, budget: NativeBudget) throws {
    try budget.check()
    guard readiness.snapshot().ready else { throw BrokerFailure.unknown }
    readiness.setHandler(handler)
  }
  func stop() { readiness.setHandler(nil) }
}

private struct NativeReadinessMemoryPlatform: NativeInputPlatform {
  var heldKey: CGKeyCode?, heldButton: UInt32?
  func postingPermitted() -> Bool { true }
  func allPermissions() -> Bool { true }
  func keyDown(_ code: CGKeyCode) -> Bool { heldKey == code }
  func buttonDown(_ button: CGMouseButton) -> Bool { heldButton == button.rawValue }
  func cursor() -> CGPoint? { .zero }
}
/// No AppKit objects, windows, event pumps or permission APIs are constructed.
@MainActor
final class NativeReadinessMemoryUI: NativeReadinessUI {
  var prepares = 0, presentations = 0, closes = 0
  var beforePrepare: (() throws -> Void)?
  func prepare() throws {
    prepares += 1
    try beforePrepare?()
  }
  func present(_ context: NativeConsentPresentation, callbacks: NativeReadinessUICallbacks) throws {
    presentations += 1
  }
  func updateTargets(_ targets: [NativeConsentTarget]) {}
  func showSentinel(display: BrokerDisplay) throws { throw BrokerFailure.invalid }
  func paintNonce(_ nonce: [UInt8]) throws -> NativeSentinelGeometry { throw BrokerFailure.invalid }
  func expectProbe(_ token: Int64) {}
  func regions() -> NativeOwnedUIRegions {
    NativeOwnedUIRegions(generation: "memory", rectangles: [])
  }
  func close() async { closes += 1 }
}

private actor NativeMemoryCapture {
  private var continuation: CheckedContinuation<Void, Never>?
  private(set) var started = false
  func wait() async {
    started = true
    await withCheckedContinuation { continuation = $0 }
  }
  func finish() {
    continuation?.resume()
    continuation = nil
  }
}

extension NativeReadiness {
  /// Fixed no-UI/no-post fixtures. Affirmative cached scope is constructed only
  /// here; it cannot be requested through private protocol/model/SDK arguments.
  static func memoryCheck() async throws -> Int {
    var checks = 0
    let display = BrokerDisplay(
      displayId: "display:1", bounds: try BrokerBounds(CGRect(x: 0, y: 0, width: 100, height: 100)),
      scaleX: 1, scaleY: 1, rotation: 0)
    let config = BrokerConfiguration(
      ownershipDirectory: "/memory/not-used", targetId: "memory", label: "Memory",
      surfaceId: "display:1", displayId: 1, tools: ["pointer"],
      allowedBundleIds: ["org.fixture.Allowed"], maxWindows: 1, maxNodes: 1, maxVisitedNodes: 1,
      cleanupBudgetMs: 100)
    let lease = BrokerLease(
      leaseId: "memory-lease", targetId: "memory", sessionId: "memory-session",
      generation: "memory-generation")
    let owner = BrokerOwner(
      account: "account", project: "project", run: "run", provider: "provider")
    let request = BrokerArmRequest(
      requestId: "11111111-1111-1111-1111-111111111111", durationMs: 1000)
    let context = NativeArmingContext(
      display: display,
      targets: [
        NativeConsentTarget(
          surfaceId: "window", applicationId: "application", applicationLabel: "Memory",
          bundleId: "org.fixture.Allowed", windowTitle: "Memory")
      ],
      applications: [
        NativeArmingApplication(
          id: "application", pid: 1234, launchDate: Date(timeIntervalSince1970: 1),
          bundleId: "org.fixture.Allowed")
      ])
    let memoryInspector = NativeInspector(configuration: config)
    defer { withExtendedLifetime(memoryInspector) {} }
    func fixture(platform: any NativeInputPlatform = NativeReadinessMemoryPlatform())
      -> NativeReadiness
    {
      let manager = NativeReadiness(
        lease: lease, configuration: config, inspector: memoryInspector,
        uiFactory: { NativeReadinessMemoryUI() }, inputPlatform: platform)
      let now = DispatchTime.now().uptimeNanoseconds
      manager.owner = owner
      manager.phase = .armed(
        NativeArmedGrant(
          request: request, challengeId: UUID().uuidString.lowercased(),
          generation: UUID().uuidString.lowercased(), startedNs: now,
          startedWall: manager.nowWall(now), expiresNs: now + 1_000_000_000, context: context,
          displayGeneration: brokerHash("\(display)"),
          proof: NativeLiveProof(
            epoch: UUID().uuidString.lowercased(), verifiedNs: now,
            verifiedWall: manager.nowWall(now))))
      manager.tapHealthy = true
      manager.active = true
      manager.actionPid = 1234
      return manager
    }
    // A request reserves/validates the auxiliary GUI context before this private
    // boundary. Injected preparation failures and a late return cannot advance
    // into discovery/presentation; physical UI ownership remains until close.
    for kind in 0..<3 {
      let ui = await MainActor.run { NativeReadinessMemoryUI() }
      let setup = NativeBudget(milliseconds: 100)
      await MainActor.run {
        if kind == 1 { ui.beforePrepare = { throw BrokerFailure.ax } }
        if kind == 2 { ui.beforePrepare = { setup.cancel() } }
      }
      let prepared = NativeReadiness(
        lease: lease, configuration: config, inspector: memoryInspector, uiFactory: { ui })
      prepared.phase = .pending(
        NativePendingArm(
          request: request, challengeId: "memory",
          requestedNs: DispatchTime.now().uptimeNanoseconds,
          requestedWall: Date()))
      var advanced = false
      do {
        try await prepared.prepareUI(budget: setup, pendingRequest: request.requestId)
        advanced = true
      } catch {
        guard kind != 0 else { throw BrokerFailure.invalid }
      }
      guard advanced == (kind == 0),
        await MainActor.run(body: {
          ui.prepares == 1 && ui.presentations == 0 && ui.closes == 0
        })
      else { throw BrokerFailure.invalid }
      try await prepared.stop(budget: NativeBudget(milliseconds: 100))
      guard await MainActor.run(body: { ui.closes == 1 }) else { throw BrokerFailure.invalid }
      checks += 1
    }
    // Read preparation works independently of input arming. Expired or throwing
    // preparation cannot return to the caller's next AX step or create consent.
    for kind in 0..<3 {
      let ui = await MainActor.run { NativeReadinessMemoryUI() }
      let setup = NativeBudget(milliseconds: 100)
      await MainActor.run {
        if kind == 1 { ui.beforePrepare = { throw BrokerFailure.ax } }
      }
      if kind == 2 { setup.cancel() }
      let prepared = NativeReadiness(
        lease: lease, configuration: config, inspector: memoryInspector, uiFactory: { ui })
      var advanced = false
      do {
        try await prepared.prepareUI(budget: setup)
        advanced = true
      } catch {
        guard kind != 0 else { throw BrokerFailure.invalid }
      }
      guard advanced == (kind == 0),
        await MainActor.run(body: {
          ui.prepares == (kind == 2 ? 0 : 1) && ui.presentations == 0
        }), prepared.status().state == "UNARMED"
      else { throw BrokerFailure.invalid }
      try await prepared.stop(budget: NativeBudget(milliseconds: 100))
      guard await MainActor.run(body: { ui.closes == (kind == 2 ? 0 : 1) }) else {
        throw BrokerFailure.invalid
      }
      checks += 1
    }
    // A hung physical capture keeps the sole claim after its evidence watchdog
    // revokes. Stop expires instead of publishing quiet or replacing that work.
    let manager = fixture()
    let capture = NativeMemoryCapture()
    let now = DispatchTime.now().uptimeNanoseconds
    let ticket = try manager.lock.withLock {
      try manager.witnessLifecycle.begin(now: now, deadline: now + 5_000_000)
    }
    let original = Task {
      await capture.wait()
      manager.lock.withLock {
        manager.witnessLifecycle.settle(ticket)
        manager.task = nil
      }
    }
    manager.lock.withLock { manager.task = original }
    while !(await capture.started) { try await Task.sleep(nanoseconds: 1_000_000) }
    let timer = DispatchSource.makeTimerSource(queue: .global())
    let sink = NativeReadinessRevocationSink(manager)
    timer.schedule(deadline: DispatchTime(uptimeNanoseconds: ticket.deadline))
    timer.setEventHandler { sink.timeout(ticket) }
    timer.resume()
    let guardBudget = NativeBudget(milliseconds: 100)
    while manager.status().state != "REVOKED" { try await guardBudget.sleep(milliseconds: 1) }
    guard !manager.snapshot().ready,
      manager.lock.withLock({ manager.witnessLifecycle.ticket == ticket })
    else { throw BrokerFailure.invalid }
    checks += 1
    do {
      _ = try manager.lock.withLock {
        try manager.witnessLifecycle.begin(now: now + 10_000_000, deadline: now + 20_000_000)
      }
    } catch BrokerFailure.busy { checks += 1 }
    do {
      try await manager.stop(budget: NativeBudget(milliseconds: 5))
      throw BrokerFailure.invalid
    } catch BrokerFailure.expired { checks += 1 }
    guard manager.lock.withLock({ manager.witnessLifecycle.ticket == ticket }) else {
      throw BrokerFailure.invalid
    }
    checks += 1
    await capture.finish()
    await original.value
    timer.cancel()
    try await manager.stop(budget: NativeBudget(milliseconds: 100))
    guard manager.lock.withLock({ manager.witnessLifecycle.ticket == nil }) else {
      throw BrokerFailure.invalid
    }
    checks += 1
    func event(token: Int64, pid: pid_t) throws -> CGEvent {
      guard let value = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: true) else {
        throw BrokerFailure.input
      }
      value.setIntegerValueField(.eventSourceUserData, value: token)
      value.setIntegerValueField(.eventSourceUnixProcessID, value: Int64(getpid()))
      value.setIntegerValueField(.eventTargetUnixProcessID, value: Int64(pid))
      return value
    }
    func permit(_ event: CGEvent) -> NativeRoutePermit {
      NativeRoutePermit(
        token: event.getIntegerValueField(.eventSourceUserData), expectedPid: 1234, type: .keyDown,
        key: 36, button: event.getIntegerValueField(.mouseEventButtonNumber),
        expiresNs: DispatchTime.now().uptimeNanoseconds + 1_000_000_000, cleanup: false,
        probe: false)
    }
    let keyHeld = fixture(platform: NativeReadinessMemoryPlatform(heldKey: 55))
    do {
      try keyHeld.probeAdmission(NativeBudget(milliseconds: 100))
      throw BrokerFailure.invalid
    } catch BrokerFailure.interference { checks += 1 }
    guard keyHeld.pendingRoute == nil, keyHeld.routeReceipts.isEmpty else {
      throw BrokerFailure.invalid
    }
    let buttonHeld = fixture(platform: NativeReadinessMemoryPlatform(heldButton: 0))
    do {
      try buttonHeld.probeAdmission(NativeBudget(milliseconds: 100))
      throw BrokerFailure.invalid
    } catch BrokerFailure.interference { checks += 1 }
    guard buttonHeld.pendingRoute == nil, buttonHeld.routeReceipts.isEmpty else {
      throw BrokerFailure.invalid
    }
    let accepted = fixture()
    guard accepted.applicationGranted(context.applications[0]),
      !accepted.applicationGranted(
        NativeArmingApplication(
          id: "new-application", pid: 1234, launchDate: Date(timeIntervalSince1970: 2),
          bundleId: "org.fixture.Allowed"))
    else { throw BrokerFailure.invalid }
    checks += 1
    let good = try event(token: 101, pid: 1234)
    accepted.pendingRoute = permit(good)
    guard accepted.filter(type: .keyDown, event: good), accepted.routeReceipts[101] == true else {
      throw BrokerFailure.invalid
    }
    checks += 1
    let rejected = fixture()
    let wrong = try event(token: 102, pid: 5678)
    rejected.pendingRoute = permit(wrong)
    guard !rejected.filter(type: .keyDown, event: wrong), rejected.status().state == "REVOKED",
      rejected.unresolvedRoutes.contains(102)
    else { throw BrokerFailure.invalid }
    checks += 1
    guard !rejected.filter(type: .keyDown, event: wrong) else { throw BrokerFailure.invalid }
    checks += 1
    let human = try event(token: 0, pid: 5678)
    human.setIntegerValueField(.eventSourceUnixProcessID, value: 0)
    guard rejected.filter(type: .keyDown, event: human) else { throw BrokerFailure.invalid }
    checks += 1
    let disabled = fixture()
    let pending = try event(token: 103, pid: 1234)
    disabled.pendingRoute = permit(pending)
    guard disabled.filter(type: .tapDisabledByTimeout, event: pending),
      !disabled.filter(type: .keyDown, event: pending), disabled.status().state == "REVOKED"
    else { throw BrokerFailure.invalid }
    checks += 1
    let stale = fixture()
    let late = try event(token: 104, pid: 1234)
    let time = DispatchTime.now().uptimeNanoseconds
    stale.pendingRoute = permit(late)
    _ = try stale.witnessLifecycle.begin(now: time - 2, deadline: time - 1)
    guard !stale.filter(type: .keyDown, event: late), stale.status().state == "REVOKED" else {
      throw BrokerFailure.invalid
    }
    checks += 1
    // A readiness probe is outside the executor's held-input tracker. A lost
    // annotated receipt therefore remains explicit owned uncertainty at stop.
    let lostProbe = fixture()
    guard
      let probeEvent = CGEvent(
        mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: .zero,
        mouseButton: .left)
    else { throw BrokerFailure.input }
    probeEvent.setIntegerValueField(.eventSourceUserData, value: 105)
    lostProbe.pendingRoute = NativeRoutePermit(
      token: 105, expectedPid: getpid(), type: .mouseMoved,
      key: probeEvent.getIntegerValueField(.keyboardEventKeycode),
      button: probeEvent.getIntegerValueField(.mouseEventButtonNumber),
      expiresNs: DispatchTime.now().uptimeNanoseconds + 1_000_000_000, cleanup: false, probe: true)
    do {
      try lostProbe.waitForReceipt(probeEvent, budget: NativeBudget(milliseconds: 5))
      throw BrokerFailure.invalid
    } catch BrokerFailure.expired { checks += 1 }
    do {
      try await lostProbe.stop(budget: NativeBudget(milliseconds: 100))
      throw BrokerFailure.invalid
    } catch BrokerFailure.cleanup { checks += 1 }
    guard lostProbe.unresolvedRoutes.contains(105) else { throw BrokerFailure.invalid }
    let projection = fixture()
    projection.localTransition = true
    guard !projection.snapshot().ready, projection.status().proof?.state == "refresh-required"
    else { throw BrokerFailure.invalid }
    checks += 1
    projection.localTransition = false
    guard projection.snapshot().ready, projection.status().proof?.state == "fresh" else {
      throw BrokerFailure.invalid
    }
    checks += 1
    let stable = fixture()
    let before = try JSONEncoder().encode(stable.status())
    let interaction = stable.snapshot().interactionGeneration
    let later = DispatchTime.now().uptimeNanoseconds
    if case .armed(var grant) = stable.phase {
      grant.proof = NativeLiveProof(
        epoch: UUID().uuidString.lowercased(), verifiedNs: later,
        verifiedWall: stable.nowWall(later))
      stable.phase = .armed(grant)
    }
    guard stable.snapshot().interactionGeneration == interaction,
      before != (try JSONEncoder().encode(stable.status()))
    else { throw BrokerFailure.invalid }
    checks += 1
    stable.revoke("HOST_REVOKED")
    let revoked = stable.status()
    guard revoked.state == "REVOKED", stable.snapshot().interactionGeneration != interaction else {
      throw BrokerFailure.invalid
    }
    checks += 1
    let uiDrain = NativeBudget(milliseconds: 100)
    while stable.lock.withLock({ stable.uiCloseTask != nil }) {
      try await uiDrain.sleep(milliseconds: 1)
    }
    do {
      _ = try await stable.request(
        owner: BrokerOwner(account: "other", project: "project", run: "run", provider: "provider"),
        request: request, budget: NativeBudget(milliseconds: 100))
      throw BrokerFailure.invalid
    } catch BrokerFailure.noLease { checks += 1 }
    let tokenA = NativeWitnessTicket(id: "old", deadline: 1)
    var lifecycle = NativeWitnessLifecycle()
    let tokenB = try lifecycle.begin(now: 0, deadline: 10)
    lifecycle.settle(tokenA)
    guard lifecycle.ticket == tokenB else { throw BrokerFailure.invalid }
    checks += 1
    // The tiny pixel matcher accepts only the current 256-bit pattern.
    let nonce = (0..<32).map { UInt8(($0 * 73 + 17) % 256) }
    var bytes = [UInt8](repeating: 0, count: 16 * 16 * 4)
    for bit in 0..<256 {
      let white = ((nonce[bit / 8] >> (7 - bit % 8)) & 1) == 1
      bytes[bit * 4] = white ? 255 : 0
      bytes[bit * 4 + 1] = bytes[bit * 4]
      bytes[bit * 4 + 2] = bytes[bit * 4]
      bytes[bit * 4 + 3] = 255
    }
    guard let provider = CGDataProvider(data: Data(bytes) as CFData),
      let space = CGColorSpace(name: CGColorSpace.sRGB),
      let image = CGImage(
        width: 16, height: 16, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 64, space: space,
        bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
        provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    else { throw BrokerFailure.observation }
    guard try nativeNonceMatches(image: image, nonce: nonce),
      !(try nativeNonceMatches(image: image, nonce: [UInt8](repeating: 0, count: 32)))
    else { throw BrokerFailure.invalid }
    checks += 1
    if let ticket = stale.witnessLifecycle.ticket { stale.witnessLifecycle.settle(ticket) }
    for value in [accepted, stable, keyHeld, buttonHeld, projection] {
      try await value.stop(budget: NativeBudget(milliseconds: 100))
    }
    for value in [rejected, disabled, stale] {
      do {
        try await value.stop(budget: NativeBudget(milliseconds: 100))
        throw BrokerFailure.invalid
      } catch BrokerFailure.cleanup { checks += 1 }
      guard !value.unresolvedRoutes.isEmpty else { throw BrokerFailure.invalid }
    }
    // stale deliberately keeps an unresolved physical ticket, mirroring the
    // fenced lifecycle; no native task/OS resource exists in this fixed fixture.
    return checks + (try await NativeBroker.inspectorMemoryCheck())
      + (try NativeSessionBinding.memoryCheck()) + (try nativeDisplayPixelMemoryCheck())
  }
}
