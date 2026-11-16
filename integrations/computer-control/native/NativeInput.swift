import ApplicationServices
import Darwin
import Foundation

/// One monotonic budget spans AX reads, posting, observation and cleanup. Cancel
/// changes admission, never pretends that an already posted event was recalled.
final class NativeBudget {
  private let end: UInt64
  private let parent: NativeBudget?
  private let lock = NSLock()
  private var cancelled = false
  init(milliseconds: Int) {
    parent = nil
    end = DispatchTime.now().uptimeNanoseconds + UInt64(milliseconds) * 1_000_000
  }
  private init(end: UInt64, parent: NativeBudget) {
    self.end = end
    self.parent = parent
  }
  func work(reserving milliseconds: Int) throws -> NativeBudget {
    try check()
    guard milliseconds > 0, milliseconds <= 300000 else { throw BrokerFailure.invalid }
    let reserve = UInt64(milliseconds) * 1_000_000
    let now = DispatchTime.now().uptimeNanoseconds
    guard milliseconds > 0, end > now, end - now > reserve else { throw BrokerFailure.expired }
    return NativeBudget(end: end - reserve, parent: self)
  }
  func cancel() {
    lock.lock()
    cancelled = true
    lock.unlock()
  }
  func check() throws {
    try parent?.check()
    lock.lock()
    let stopped = cancelled
    lock.unlock()
    guard !stopped, DispatchTime.now().uptimeNanoseconds < end else { throw BrokerFailure.expired }
  }
  func remaining() throws -> Double {
    try check()
    let now = DispatchTime.now().uptimeNanoseconds
    guard now < end else { throw BrokerFailure.expired }
    return Double(end - now) / 1e9
  }
  func sleep(milliseconds: Int) async throws {
    try check()
    if milliseconds > 0 { try await Task.sleep(nanoseconds: UInt64(milliseconds) * 1_000_000) }
    try check()
  }
}

/// Private crash evidence contains only lease/held-input metadata. No typed text,
/// screenshot, app content, environment or bearer is persisted. O_EXCL prevents
/// replacing unexplained evidence; this owner never recursively cleans a path.
protocol NativeHeldEvidence { func recordHeld(keys: Set<CGKeyCode>, buttons: Set<UInt32>) throws }
final class NativeEvidence: NativeHeldEvidence {
  private let directory: String
  private var owned = false
  private var operationId: String?
  private let leaseId: String
  init(directory: String, lease: BrokerLease) throws {
    self.directory = directory + "/capture.lock"
    self.leaseId = lease.leaseId
    var status = stat()
    guard lstat(self.directory, &status) == 0, status.st_mode & S_IFMT == S_IFDIR,
      status.st_uid == getuid(), status.st_mode & 0o077 == 0
    else { throw BrokerFailure.fenced }
    struct Metadata: Encodable {
      let version = "native-broker-active/1"
      let lease: BrokerLease
    }
    let data = try JSONEncoder().encode(Metadata(lease: lease))
    let fd = open(
      self.directory + "/broker-active.json", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW,
      mode_t(0o600))
    guard fd >= 0 else { throw BrokerFailure.fenced }
    owned = true
    defer { _ = Darwin.close(fd) }
    guard data.count <= 4096,
      data.withUnsafeBytes({ Darwin.write(fd, $0.baseAddress, data.count) }) == data.count,
      fsync(fd) == 0
    else { throw BrokerFailure.fenced }
    try forceDirectory()
  }
  func beginOperation(invocationId: String, stage: String, budget: NativeBudget) throws {
    try budget.check()
    guard operationId == nil,
      UUID(uuidString: invocationId)?.uuidString.lowercased() == invocationId,
      ["focus", "input"].contains(stage)
    else { throw BrokerFailure.fenced }
    struct Operation: Encodable {
      let version = "native-broker-operation/1", invocationId: String, leaseId: String,
        stage: String
    }
    let data = try JSONEncoder().encode(
      Operation(invocationId: invocationId, leaseId: leaseId, stage: stage))
    let fd = open(
      directory + "/broker-operation.json", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, mode_t(0o600))
    guard fd >= 0 else { throw BrokerFailure.fenced }
    defer { _ = Darwin.close(fd) }
    operationId = invocationId
    guard data.count <= 4096,
      data.withUnsafeBytes({ Darwin.write(fd, $0.baseAddress, data.count) }) == data.count,
      fsync(fd) == 0
    else { throw BrokerFailure.fenced }
    try forceDirectory()
    try budget.check()
  }
  func endOperationAfterQuiescence() throws {
    guard operationId != nil else { return }
    guard unlink(directory + "/broker-operation.json") == 0 else { throw BrokerFailure.fenced }
    try forceDirectory()
    operationId = nil
  }
  func recordHeld(keys: Set<CGKeyCode>, buttons: Set<UInt32>) throws {
    struct Held: Encodable {
      let version = "native-broker-held/1"
      let keys: [UInt16]
      let buttons: [UInt32]
      let invocationId: String?
    }
    let bytes = try JSONEncoder().encode(
      Held(keys: keys.sorted(), buttons: buttons.sorted(), invocationId: operationId))
    let path = directory + "/broker-held.json"
    let fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, mode_t(0o600))
    guard fd >= 0 else { throw BrokerFailure.fenced }
    defer { _ = Darwin.close(fd) }
    guard bytes.count <= 4096,
      bytes.withUnsafeBytes({ Darwin.write(fd, $0.baseAddress, bytes.count) }) == bytes.count,
      fsync(fd) == 0
    else { throw BrokerFailure.fenced }
    try forceDirectory()
  }
  func removeAfterQuiescence() throws {
    guard owned else { throw BrokerFailure.fenced }
    try endOperationAfterQuiescence()
    if unlink(directory + "/broker-held.json") != 0 && errno != ENOENT {
      throw BrokerFailure.fenced
    }
    guard unlink(directory + "/broker-active.json") == 0 else { throw BrokerFailure.fenced }
    try forceDirectory()
    owned = false
  }
  private func forceDirectory() throws {
    let fd = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
    guard fd >= 0 else { throw BrokerFailure.fenced }
    defer { _ = Darwin.close(fd) }
    guard fsync(fd) == 0 else { throw BrokerFailure.fenced }
  }
}

/// All application-specific hit/focus/AX validation remains outside this narrow
/// posting port. The only production sink posts to the ordinary HID event stream,
/// never postToPid. A test sink can inspect constructed events without posting.
protocol NativeInputValidation {
  func beforePost(_ event: CGEvent, budget: NativeBudget) throws
}

protocol NativeEventSink { func post(_ event: CGEvent) }
struct NativeHIDEventSink: NativeEventSink {
  func post(_ event: CGEvent) { event.post(tap: .cghidEventTap) }
}

/// Pure bounded acknowledgement accounting. Each event has a distinct token;
/// delayed/reordered/dropped acknowledgements cannot be confused with an older
/// up event for the same key. Test fixtures use this without an OS tap or post.
final class NativePostTracker {
  private struct Expected { let type: CGEventType, key: Int64, button: Int64 }
  private let lock = NSLock()
  private var sequence = Int64.random(in: 1...Int64.max - 1_000_001)
  private var issued = 0
  private var pending: [Int64: Expected] = [:]
  func reserve(_ event: CGEvent) throws -> Int64 {
    try lock.withLock {
      guard issued < 1_000_000 else { throw BrokerFailure.fenced }
      sequence += 1
      issued += 1
      pending[sequence] = Expected(
        type: event.type, key: event.getIntegerValueField(.keyboardEventKeycode),
        button: event.getIntegerValueField(.mouseEventButtonNumber))
      return sequence
    }
  }
  func acknowledge(_ event: CGEvent) -> Bool {
    lock.withLock {
      let token = event.getIntegerValueField(.eventSourceUserData)
      guard let expected = pending[token], expected.type == event.type,
        expected.key == event.getIntegerValueField(.keyboardEventKeycode),
        expected.button == event.getIntegerValueField(.mouseEventButtonNumber)
      else { return false }
      pending.removeValue(forKey: token)
      return true
    }
  }
  var quiet: Bool { lock.withLock { pending.isEmpty } }
  var neverPosted: Bool { lock.withLock { issued == 0 } }
}

protocol NativeInputPlatform {
  func postingPermitted() -> Bool
  func allPermissions() -> Bool
  func keyDown(_ code: CGKeyCode) -> Bool
  func buttonDown(_ button: CGMouseButton) -> Bool
  func cursor() -> CGPoint?
}
struct NativeSystemInputPlatform: NativeInputPlatform {
  func postingPermitted() -> Bool { CGPreflightPostEventAccess() }
  func allPermissions() -> Bool {
    CGPreflightPostEventAccess() && CGPreflightListenEventAccess() && AXIsProcessTrusted()
      && CGPreflightScreenCaptureAccess()
  }
  func keyDown(_ code: CGKeyCode) -> Bool {
    CGEventSource.keyState(.combinedSessionState, key: code)
  }
  func buttonDown(_ button: CGMouseButton) -> Bool {
    CGEventSource.buttonState(.combinedSessionState, button: button)
  }
  func cursor() -> CGPoint? { CGEvent(source: nil)?.location }
}
protocol NativeInputMonitor {
  func start(_ handler: @escaping (CGEventType, CGEvent) -> Void, budget: NativeBudget) throws
  func stop()
}
final class NativeSystemInputMonitor: NativeInputMonitor {
  private let lock = NSLock()
  private var tap: CFMachPort?, source: CFRunLoopSource?, handler: ((CGEventType, CGEvent) -> Void)?
  func start(_ handler: @escaping (CGEventType, CGEvent) -> Void, budget: NativeBudget) throws {
    try budget.check()
    guard tap == nil, CGPreflightListenEventAccess() else { throw BrokerFailure.denied }
    lock.withLock { self.handler = handler }
    let mask = [
      CGEventType.keyDown, .keyUp, .flagsChanged, .leftMouseDown, .leftMouseUp, .rightMouseDown,
      .rightMouseUp, .otherMouseDown, .otherMouseUp, .mouseMoved, .leftMouseDragged,
      .rightMouseDragged, .otherMouseDragged, .scrollWheel,
    ].reduce(CGEventMask(0)) { $0 | (CGEventMask(1) << $1.rawValue) }
    let callback: CGEventTapCallBack = { _, type, event, info in
      guard let info else { return Unmanaged.passUnretained(event) }
      let owner = Unmanaged<NativeSystemInputMonitor>.fromOpaque(info).takeUnretainedValue()
      let handler = owner.lock.withLock { owner.handler }
      handler?(type, event)
      return Unmanaged.passUnretained(event)
    }
    guard
      let port = CGEvent.tapCreate(
        tap: .cgSessionEventTap, place: .headInsertEventTap, options: .listenOnly,
        eventsOfInterest: mask, callback: callback,
        userInfo: Unmanaged.passUnretained(self).toOpaque()),
      let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, port, 0)
    else { throw BrokerFailure.denied }
    tap = port
    self.source = source
    CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
    CGEvent.tapEnable(tap: port, enable: true)
    try budget.check()
  }
  func stop() {
    if let tap {
      CGEvent.tapEnable(tap: tap, enable: false)
      CFMachPortInvalidate(tap)
    }
    if let source { CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes) }
    tap = nil
    source = nil
    lock.withLock { handler = nil }
  }
}

struct NativeInputExtent: Encodable {
  let kind: String
  var moves: Int?, clicks: Int?, points: Int?, buttonReleased: Bool?, dispatchedUtf16Units: Int?,
    cleared: Bool?, keyDowns: Int?, keyUps: Int?, dx: Int?, dy: Int?, units: String?
}

final class NativeInputExecutor {
  private let sink: any NativeEventSink
  private let evidence: any NativeHeldEvidence
  private let platform: any NativeInputPlatform
  private let monitor: any NativeInputMonitor
  private var cleanupResult: Bool?
  private let posts = NativePostTracker()
  private var validation: (any NativeInputValidation)?
  func admit(_ validation: any NativeInputValidation) { self.validation = validation }
  private var heldKeys = Set<CGKeyCode>()
  private var heldButtons = Set<UInt32>()
  private var downMayHaveBegun = false
  private var postedMoves = 0, postedClicks = 0, postedDragPoints = 0, postedUtf16 = 0
  private var postedKeyDowns = Set<CGKeyCode>(), postedKeyUps = Set<CGKeyCode>()
  private var clearPosted = false, scrollPosted = false
  func markClearPosted() { clearPosted = true }
  func partial(_ prepared: NativePreparedInput, currentOperationStarted: Bool) -> NativeInputExtent?
  {
    guard currentOperationStarted, cleanupResult == true else { return nil }
    switch prepared {
    case .move, .click:
      guard postedMoves + postedClicks > 0 else { return nil }
      return NativeInputExtent(kind: "pointer", moves: postedMoves, clicks: postedClicks)
    case .drag:
      guard postedDragPoints > 0 else { return nil }
      return NativeInputExtent(kind: "drag", points: postedDragPoints, buttonReleased: true)
    case .type:
      guard postedUtf16 > 0 || clearPosted else { return nil }
      return NativeInputExtent(
        kind: "type", dispatchedUtf16Units: postedUtf16, cleared: clearPosted)
    case .key:
      guard !postedKeyDowns.isEmpty, postedKeyDowns == postedKeyUps, postedKeyDowns.count <= 5
      else { return nil }
      return NativeInputExtent(
        kind: "key", keyDowns: postedKeyDowns.count, keyUps: postedKeyUps.count)
    case .scroll(_, let dx, let dy, let units):
      guard scrollPosted else { return nil }
      return NativeInputExtent(kind: "scroll", dx: dx, dy: dy, units: units)
    }
  }
  private let lock = NSLock()
  private var monitorHealthy = false
  private var interference = false
  private var observedUps = Set<String>()

  init(
    evidence: any NativeHeldEvidence, sink: any NativeEventSink = NativeHIDEventSink(),
    monitor: any NativeInputMonitor = NativeSystemInputMonitor(),
    platform: any NativeInputPlatform = NativeSystemInputPlatform()
  ) {
    self.evidence = evidence
    self.sink = sink
    self.monitor = monitor
    self.platform = platform
  }
  /// Production installs a public listen-only tap only after affirmative readiness.
  /// Fakes exercise cancellation/owned-release/partial posting without any OS tap.
  func startMonitor(_ budget: NativeBudget) throws {
    try budget.check()
    guard validation != nil, cleanupResult != false else { throw BrokerFailure.fenced }
    guard platform.allPermissions() else { throw BrokerFailure.denied }
    cleanupResult = nil
    postedMoves = 0
    postedClicks = 0
    postedDragPoints = 0
    postedUtf16 = 0
    postedKeyDowns.removeAll()
    postedKeyUps.removeAll()
    clearPosted = false
    scrollPosted = false
    try monitor.start({ [weak self] type, event in self?.observe(type, event) }, budget: budget)
    lock.withLock { monitorHealthy = true }
    // A hold predating the tap has no down event for the monitor to see. Refuse
    // admission while any standard physical key/button is already held; never
    // adopt it as owned input or let an existing modifier alter an injected key.
    for code in UInt16(0)..<128 {
      try budget.check()
      guard !platform.keyDown(code) else { throw BrokerFailure.interference }
    }
    for raw in UInt32(0)..<3 {
      try budget.check()
      guard let button = CGMouseButton(rawValue: raw), !platform.buttonDown(button) else {
        throw BrokerFailure.interference
      }
    }
    try budget.check()
  }
  private func observe(_ type: CGEventType, _ event: CGEvent) {
    lock.lock()
    defer { lock.unlock() }
    if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
      monitorHealthy = false
      return
    }
    guard posts.acknowledge(event) else {
      interference = true
      return
    }
    if type == .keyUp {
      observedUps.insert("k:\(event.getIntegerValueField(.keyboardEventKeycode))")
    }
    if [.leftMouseUp, .rightMouseUp, .otherMouseUp].contains(type) {
      observedUps.insert("b:\(event.getIntegerValueField(.mouseEventButtonNumber))")
    }
  }
  private func safeToPost(_ budget: NativeBudget) throws {
    try budget.check()
    lock.lock()
    let healthy = monitorHealthy && !interference
    lock.unlock()
    guard healthy else { throw BrokerFailure.interference }
    guard platform.allPermissions() else {
      throw BrokerFailure.denied
    }
    // Close the monitor-delivery race for already-applied foreign modifiers.
    // Owned modifiers are released only by their own recorded key-up path.
    let modifierCodes: [CGKeyCode] = [54, 55, 56, 60, 58, 61, 59, 62, 63]
    for code in modifierCodes {
      guard heldKeys.contains(code) || !platform.keyDown(code) else {
        throw BrokerFailure.interference
      }
    }
  }
  private func post(_ event: CGEvent, budget: NativeBudget, cleanup: Bool = false) throws {
    if cleanup {
      try budget.check()
      guard platform.postingPermitted() else { throw BrokerFailure.denied }
    } else {
      try safeToPost(budget)
      guard let validation else { throw BrokerFailure.unknown }
      try validation.beforePost(event, budget: budget)
    }
    let token = try posts.reserve(event)
    event.setIntegerValueField(.eventSourceUserData, value: token)
    guard event.getIntegerValueField(.eventSourceUserData) == token else {
      throw BrokerFailure.input
    }
    sink.post(event)
  }
  static func unicodeChunks(_ text: String) -> [[UniChar]] {
    text.unicodeScalars.map { Array(String($0).utf16) }
  }
  static func unicodeEvent(_ units: [UniChar], down: Bool) throws -> CGEvent {
    guard (1...2).contains(units.count),
      Array(String(decoding: units, as: UTF16.self).utf16) == units,
      String(decoding: units, as: UTF16.self).unicodeScalars.count == 1,
      let event = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
    else { throw BrokerFailure.input }
    event.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
    var length = 0
    var roundtrip = [UniChar](repeating: 0, count: 2)
    event.keyboardGetUnicodeString(
      maxStringLength: 2, actualStringLength: &length, unicodeString: &roundtrip)
    guard length == units.count, Array(roundtrip.prefix(length)) == units else {
      throw BrokerFailure.input
    }
    return event
  }
  private func key(
    _ code: CGKeyCode, down: Bool, flags: CGEventFlags, budget: NativeBudget, cleanup: Bool = false
  ) throws {
    guard let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down) else {
      throw BrokerFailure.input
    }
    event.flags = flags
    if down {
      guard heldKeys.contains(code) || !platform.keyDown(code)
      else { throw BrokerFailure.interference }
      _ = lock.withLock { observedUps.remove("k:\(code)") }
      heldKeys.insert(code)
      try evidence.recordHeld(keys: heldKeys, buttons: heldButtons)
      downMayHaveBegun = true
    }
    try post(event, budget: budget, cleanup: cleanup)
    if down { postedKeyDowns.insert(code) } else { postedKeyUps.insert(code) }
    // Held entries remain until cleanup observes tagged-up + combined-up. A void
    // post(up) never clears crash evidence on its own.
  }
  func type(_ text: String, budget: NativeBudget) throws -> Int {
    guard text.utf16.count <= 4096 else { throw BrokerFailure.invalid }
    var delivered = 0
    for chunk in Self.unicodeChunks(text) {
      try safeToPost(budget)
      let down = try Self.unicodeEvent(chunk, down: true)
      let up = try Self.unicodeEvent(chunk, down: false)
      guard heldKeys.contains(0) || !platform.keyDown(0) else {
        throw BrokerFailure.interference
      }
      _ = lock.withLock { observedUps.remove("k:0") }
      heldKeys.insert(0)
      try evidence.recordHeld(keys: heldKeys, buttons: heldButtons)
      downMayHaveBegun = true
      try post(down, budget: budget)
      postedUtf16 += chunk.count
      try post(up, budget: budget)
      delivered += chunk.count
    }
    return delivered
  }
  private static func modifierKey(_ name: String) throws -> (CGKeyCode, CGEventFlags) {
    switch name {
    case "Meta": return (55, .maskCommand)
    case "Control": return (59, .maskControl)
    case "Alt": return (58, .maskAlternate)
    case "Shift": return (56, .maskShift)
    default: throw BrokerFailure.invalid
    }
  }
  private func beginModifiers(_ names: [String], budget: NativeBudget) throws -> CGEventFlags {
    guard Set(names).count == names.count, names.count <= 4 else { throw BrokerFailure.invalid }
    var flags = CGEventFlags()
    for name in names {
      let (code, flag) = try Self.modifierKey(name)
      flags.insert(flag)
      try key(code, down: true, flags: flags, budget: budget)
    }
    return flags
  }
  private func endModifiers(_ names: [String], flags: CGEventFlags, budget: NativeBudget) throws {
    var flags = flags
    for name in names.reversed() {
      let (code, flag) = try Self.modifierKey(name)
      flags.remove(flag)
      try key(code, down: false, flags: flags, budget: budget)
    }
  }
  func press(_ code: CGKeyCode, modifiers: [String], budget: NativeBudget) throws {
    let selected = try modifiers.map(Self.modifierKey)
    var flags = CGEventFlags()
    for (keyCode, flag) in selected {
      flags.insert(flag)
      try key(keyCode, down: true, flags: flags, budget: budget)
    }
    try key(code, down: true, flags: flags, budget: budget)
    try key(code, down: false, flags: flags, budget: budget)
    for (keyCode, flag) in selected.reversed() {
      flags.remove(flag)
      try key(keyCode, down: false, flags: flags, budget: budget)
    }
  }
  func move(_ point: CGPoint, budget: NativeBudget) throws {
    guard point.x.isFinite, point.y.isFinite, abs(point.x) <= 1e7, abs(point.y) <= 1e7 else {
      throw BrokerFailure.invalid
    }
    guard
      let event = CGEvent(
        mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: point,
        mouseButton: .left)
    else { throw BrokerFailure.input }
    try post(event, budget: budget)
    postedMoves += 1
  }
  private func mouse(
    _ point: CGPoint, button: CGMouseButton, down: Bool, clicks: Int64 = 1,
    flags: CGEventFlags = [], budget: NativeBudget, cleanup: Bool = false
  ) throws {
    let type: CGEventType =
      button == .left
      ? (down ? .leftMouseDown : .leftMouseUp)
      : button == .right
        ? (down ? .rightMouseDown : .rightMouseUp) : (down ? .otherMouseDown : .otherMouseUp)
    guard
      let event = CGEvent(
        mouseEventSource: nil, mouseType: type, mouseCursorPosition: point, mouseButton: button)
    else { throw BrokerFailure.input }
    event.setIntegerValueField(.mouseEventClickState, value: clicks)
    event.flags = flags
    if down {
      guard
        heldButtons.contains(button.rawValue)
          || !platform.buttonDown(button)
      else { throw BrokerFailure.interference }
      _ = lock.withLock { observedUps.remove("b:\(button.rawValue)") }
      heldButtons.insert(button.rawValue)
      try evidence.recordHeld(keys: heldKeys, buttons: heldButtons)
      downMayHaveBegun = true
    }
    try post(event, budget: budget, cleanup: cleanup)
    if down { postedClicks += 1 }
  }
  func click(
    _ point: CGPoint, button: CGMouseButton, count: Int, modifiers: [String] = [],
    budget: NativeBudget
  ) throws {
    guard (1...2).contains(count) else { throw BrokerFailure.invalid }
    let flags = try beginModifiers(modifiers, budget: budget)
    for number in 1...count {
      try mouse(
        point, button: button, down: true, clicks: Int64(number), flags: flags, budget: budget)
      try mouse(
        point, button: button, down: false, clicks: Int64(number), flags: flags, budget: budget)
    }
    try endModifiers(modifiers, flags: flags, budget: budget)
  }
  func drag(
    _ points: [BrokerDragPoint], button: CGMouseButton, modifiers: [String] = [],
    budget: NativeBudget
  ) async throws
    -> Int
  {
    guard points.count >= 2, points.count <= 128, points.first?.atMs == 0,
      points.allSatisfy({
        $0.x.isFinite && $0.y.isFinite && abs($0.x) <= 1e7 && abs($0.y) <= 1e7
          && (0...1000).contains($0.atMs)
      }),
      zip(points, points.dropFirst()).allSatisfy({ $0.atMs < $1.atMs })
    else { throw BrokerFailure.invalid }
    let flags = try beginModifiers(modifiers, budget: budget)
    let started = DispatchTime.now().uptimeNanoseconds
    try mouse(
      CGPoint(x: points[0].x, y: points[0].y), button: button, down: true, flags: flags,
      budget: budget)
    var delivered = 1
    postedDragPoints = 1
    for point in points.dropFirst() {
      let elapsed = Int((DispatchTime.now().uptimeNanoseconds - started) / 1_000_000)
      try await budget.sleep(milliseconds: max(0, point.atMs - elapsed))
      let kind: CGEventType =
        button == .left
        ? .leftMouseDragged : button == .right ? .rightMouseDragged : .otherMouseDragged
      guard
        let event = CGEvent(
          mouseEventSource: nil, mouseType: kind,
          mouseCursorPosition: CGPoint(x: point.x, y: point.y), mouseButton: button)
      else { throw BrokerFailure.input }
      event.flags = flags
      try post(event, budget: budget)
      delivered += 1
      postedDragPoints += 1
    }
    if let last = points.last {
      try mouse(
        CGPoint(x: last.x, y: last.y), button: button, down: false, flags: flags, budget: budget)
    }
    try endModifiers(modifiers, flags: flags, budget: budget)
    return delivered
  }
  func scroll(at point: CGPoint, dx: Int, dy: Int, units: String, budget: NativeBudget) throws {
    guard ["pixels", "lines"].contains(units) else { throw BrokerFailure.invalid }
    let maximum = units == "pixels" ? 32768 : 128
    guard dx >= -maximum, dx <= maximum, dy >= -maximum, dy <= maximum, dx != 0 || dy != 0 else {
      throw BrokerFailure.invalid
    }
    guard
      let event = CGEvent(
        scrollWheelEvent2Source: nil, units: units == "pixels" ? .pixel : .line, wheelCount: 2,
        wheel1: Int32(dy), wheel2: Int32(dx), wheel3: 0)
    else { throw BrokerFailure.input }
    event.location = point
    try post(event, budget: budget)
    scrollPosted = true
  }
  /// Never releases someone else's key/button. All owned downs stay recorded
  /// until tagged ups and combined-state reads corroborate local release. Loss of
  /// monitor/permission/interference evidence is permanent uncertainty.
  func cleanup(_ budget: NativeBudget) async -> Bool {
    if let cleanupResult { return cleanupResult }
    let result = await finishCleanup(budget)
    cleanupResult = result
    return result
  }
  private func finishCleanup(_ budget: NativeBudget) async -> Bool {
    if !downMayHaveBegun {
      if posts.neverPosted {
        stopMonitor()
        return true
      }
      do {
        while !posts.quiet {
          try safeToPost(budget)
          try await budget.sleep(milliseconds: 5)
        }
        try safeToPost(budget)
        stopMonitor()
        return true
      } catch {
        stopMonitor()
        return false
      }
    }
    do {
      let keys = heldKeys
      let buttons = heldButtons
      lock.withLock { observedUps.removeAll() }
      var attemptsSucceeded = true
      for keyCode in keys {
        do { try key(keyCode, down: false, flags: [], budget: budget, cleanup: true) } catch {
          attemptsSucceeded = false
        }
      }
      for raw in buttons {
        guard let button = CGMouseButton(rawValue: raw),
          let position = platform.cursor()
        else { throw BrokerFailure.cleanup }
        do { try mouse(position, button: button, down: false, budget: budget, cleanup: true) } catch
        { attemptsSucceeded = false }
      }
      guard attemptsSucceeded else { throw BrokerFailure.cleanup }
      while true {
        try safeToPost(budget)
        let acknowledged = lock.withLock {
          keys.allSatisfy { observedUps.contains("k:\($0)") }
            && buttons.allSatisfy { observedUps.contains("b:\($0)") }
        }
        if acknowledged
          && keys.allSatisfy({ !platform.keyDown($0) })
          && buttons.allSatisfy({ raw in
            guard let button = CGMouseButton(rawValue: raw) else { return false }
            return !platform.buttonDown(button)
          }) && posts.quiet
        {
          heldKeys.removeAll()
          heldButtons.removeAll()
          try evidence.recordHeld(keys: heldKeys, buttons: heldButtons)
          stopMonitor()
          return true
        }
        try await budget.sleep(milliseconds: 5)
      }
    } catch {
      stopMonitor()
      return false
    }
  }
  private func stopMonitor() {
    monitor.stop()
    lock.withLock { monitorHealthy = false }
  }

}

/// Explicit no-post test entrypoint. It constructs CoreGraphics objects only:
/// never creates a broker, semaphore, evidence file, event tap or TCC request.
func runNativeInputMemoryCheck() async throws {
  let text = "Aé\n\t🙂e\u{301}👩‍💻"
  let chunks = NativeInputExecutor.unicodeChunks(text)
  guard brokerLabel(String(repeating: "🙂", count: 200)).utf16.count == 256,
    brokerLabel("🙂", maximum: 1).isEmpty
  else { throw BrokerFailure.input }
  guard chunks.flatMap({ $0 }) == Array(text.utf16),
    chunks.allSatisfy({ (1...2).contains($0.count) })
  else { throw BrokerFailure.input }
  for chunk in chunks {
    _ = try NativeInputExecutor.unicodeEvent(chunk, down: true)
    _ = try NativeInputExecutor.unicodeEvent(chunk, down: false)
  }
  guard
    let mouse = CGEvent(
      mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: CGPoint(x: -50, y: 25),
      mouseButton: .left),
    let key = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: true),
    let scroll = CGEvent(
      scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: -128, wheel2: 64,
      wheel3: 0),
    mouse.location == CGPoint(x: -50, y: 25), key.getIntegerValueField(.keyboardEventKeycode) == 36,
    scroll.getDoubleValueField(.scrollWheelEventPointDeltaAxis1) == -128,
    scroll.getDoubleValueField(.scrollWheelEventPointDeltaAxis2) == 64
  else { throw BrokerFailure.input }
  let budget = NativeBudget(milliseconds: 1000)
  budget.cancel()
  do {
    try budget.check()
    throw BrokerFailure.input
  } catch BrokerFailure.expired {}
  struct Result: Encodable {
    let version = "computer-control-native/self-test/1", ok = true
    let unicodeScalars: Int, utf16Units: Int, eventRoundTrips: Int
    let protocolFramesChecked: Int, acknowledgementCasesChecked: Int, executorCasesChecked: Int
    let pointerRoundTrip = true, keyRoundTrip = true, scrollRoundTrip = true,
      cancelledBudgetRejected = true
  }
  let result = Result(
    unicodeScalars: chunks.count, utf16Units: text.utf16.count, eventRoundTrips: chunks.count * 2,
    protocolFramesChecked: try runNativeProtocolMemoryCheck(),
    acknowledgementCasesChecked: try runNativePostMemoryCheck(),
    executorCasesChecked: try await runNativeExecutorMemoryCheck())
  try FileHandle.standardOutput.write(contentsOf: JSONEncoder().encode(result))
  try FileHandle.standardOutput.write(contentsOf: Data([10]))
}

private func runNativePostMemoryCheck() throws -> Int {
  let tracker = NativePostTracker()
  guard let down = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: true),
    let up = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: false),
    let move = CGEvent(
      mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: .zero, mouseButton: .left)
  else { throw BrokerFailure.input }
  for event in [down, up, move] {
    event.setIntegerValueField(.eventSourceUserData, value: try tracker.reserve(event))
  }
  guard !tracker.quiet, tracker.acknowledge(up), !tracker.quiet else { throw BrokerFailure.input }
  let wrong = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: false)!
  wrong.setIntegerValueField(
    .eventSourceUserData, value: down.getIntegerValueField(.eventSourceUserData))
  guard !tracker.acknowledge(wrong), !tracker.quiet, tracker.acknowledge(down), !tracker.quiet,
    tracker.acknowledge(move), tracker.quiet
  else { throw BrokerFailure.input }
  guard let nextDown = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: true),
    let droppedMove = CGEvent(
      mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: .zero, mouseButton: .left)
  else { throw BrokerFailure.input }
  nextDown.setIntegerValueField(.eventSourceUserData, value: try tracker.reserve(nextDown))
  guard !tracker.acknowledge(up), !tracker.quiet, tracker.acknowledge(nextDown), tracker.quiet
  else {
    throw BrokerFailure.input
  }
  droppedMove.setIntegerValueField(.eventSourceUserData, value: try tracker.reserve(droppedMove))
  guard !tracker.quiet else { throw BrokerFailure.input }
  return 8
}

// These fixed fixtures are the only affirmative fake platform/validation. They
// never create a real event tap, post, semaphore, evidence file or TCC request.
private final class MemoryInputEvidence: NativeHeldEvidence {
  var records = 0
  func recordHeld(keys: Set<CGKeyCode>, buttons: Set<UInt32>) throws { records += 1 }
}
private final class MemoryInputPlatform: NativeInputPlatform {
  var keys = Set<CGKeyCode>(), buttons = Set<UInt32>()
  func postingPermitted() -> Bool { true }
  func allPermissions() -> Bool { true }
  func keyDown(_ code: CGKeyCode) -> Bool { keys.contains(code) }
  func buttonDown(_ button: CGMouseButton) -> Bool { buttons.contains(button.rawValue) }
  func cursor() -> CGPoint? { .zero }
}
private final class MemoryInputMonitor: NativeInputMonitor {
  var handler: ((CGEventType, CGEvent) -> Void)?
  func start(_ handler: @escaping (CGEventType, CGEvent) -> Void, budget: NativeBudget) throws {
    try budget.check()
    self.handler = handler
  }
  func stop() { handler = nil }
}
private struct MemoryInputValidation: NativeInputValidation {
  func beforePost(_ event: CGEvent, budget: NativeBudget) throws { try budget.check() }
}
private final class MemoryInputSink: NativeEventSink {
  let platform: MemoryInputPlatform, monitor: MemoryInputMonitor
  var events: [CGEvent] = [], acknowledge = true, onPost: ((CGEvent) -> Void)?
  init(platform: MemoryInputPlatform, monitor: MemoryInputMonitor) {
    self.platform = platform
    self.monitor = monitor
  }
  func post(_ event: CGEvent) {
    events.append(event)
    if event.type == .keyDown {
      platform.keys.insert(CGKeyCode(event.getIntegerValueField(.keyboardEventKeycode)))
    }
    if event.type == .keyUp {
      platform.keys.remove(CGKeyCode(event.getIntegerValueField(.keyboardEventKeycode)))
    }
    if [.leftMouseDown, .rightMouseDown, .otherMouseDown].contains(event.type) {
      platform.buttons.insert(UInt32(event.getIntegerValueField(.mouseEventButtonNumber)))
    }
    if [.leftMouseUp, .rightMouseUp, .otherMouseUp].contains(event.type) {
      platform.buttons.remove(UInt32(event.getIntegerValueField(.mouseEventButtonNumber)))
    }
    if acknowledge { monitor.handler?(event.type, event) }
    onPost?(event)
  }
}
private struct MemoryExecutor {
  let platform = MemoryInputPlatform(), monitor = MemoryInputMonitor(),
    evidence = MemoryInputEvidence()
  let sink: MemoryInputSink, input: NativeInputExecutor
  init() {
    sink = MemoryInputSink(platform: platform, monitor: monitor)
    input = NativeInputExecutor(
      evidence: evidence, sink: sink, monitor: monitor, platform: platform)
    input.admit(MemoryInputValidation())
  }
}
private func runNativeExecutorMemoryCheck() async throws -> Int {
  guard nativeFailureOutcome(started: false, quiet: false, hasPartial: false) == "UNCERTAIN",
    nativeFailureOutcome(started: false, quiet: true, hasPartial: false) == "NOT_DISPATCHED",
    nativeFailureOutcome(started: true, quiet: true, hasPartial: false) == "UNCERTAIN",
    nativeFailureOutcome(started: true, quiet: true, hasPartial: true) == "PARTIAL"
  else { throw BrokerFailure.input }
  // A cancelled original action still releases its own down under a separately
  // bounded cleanup allowance, with matching fresh acknowledgement proof.
  let key = MemoryExecutor()
  let action = NativeBudget(milliseconds: 1000)
  try key.input.startMonitor(action)
  key.sink.onPost = { event in if event.type == .keyDown { action.cancel() } }
  do {
    try key.input.press(36, modifiers: [], budget: action)
    throw BrokerFailure.input
  } catch BrokerFailure.expired {}
  guard await key.input.cleanup(NativeBudget(milliseconds: 100)), key.platform.keys.isEmpty,
    key.sink.events.count == 2,
    key.input.partial(.key(36, []), currentOperationStarted: true)?.keyDowns == 1,
    key.input.partial(.key(36, []), currentOperationStarted: true)?.keyUps == 1
  else { throw BrokerFailure.input }
  // A second operation rejected before fresh counter admission cannot inherit
  // the previous operation's settled posting extent or claim a new effect.
  let secondAdmission = NativeBudget(milliseconds: 1000)
  secondAdmission.cancel()
  do {
    try key.input.startMonitor(secondAdmission)
    throw BrokerFailure.input
  } catch BrokerFailure.expired {}
  guard key.input.partial(.key(36, []), currentOperationStarted: false) == nil,
    nativeFailureOutcome(started: false, quiet: true, hasPartial: false) == "NOT_DISPATCHED",
    key.sink.events.count == 2
  else { throw BrokerFailure.input }
  // A physical foreign hold is refused before any post and is never released.
  let foreign = MemoryExecutor()
  let foreignBudget = NativeBudget(milliseconds: 1000)
  try foreign.input.startMonitor(foreignBudget)
  foreign.platform.keys.insert(36)
  do {
    try foreign.input.press(36, modifiers: [], budget: foreignBudget)
    throw BrokerFailure.input
  } catch BrokerFailure.interference {}
  guard await foreign.input.cleanup(NativeBudget(milliseconds: 100)), foreign.sink.events.isEmpty,
    foreign.platform.keys == [36]
  else { throw BrokerFailure.input }
  // Lost/foreign monitor evidence still permits ONE best-effort owned up, but
  // never manufactures quiet proof or releases another physical user's key.
  let disturbed = MemoryExecutor()
  let disturbedBudget = NativeBudget(milliseconds: 1000)
  try disturbed.input.startMonitor(disturbedBudget)
  disturbed.platform.keys.insert(55)
  disturbed.sink.onPost = { event in if event.type == .keyDown { disturbedBudget.cancel() } }
  do { try disturbed.input.press(36, modifiers: [], budget: disturbedBudget) } catch {}
  guard
    let foreignMove = CGEvent(
      mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: .zero, mouseButton: .left)
  else { throw BrokerFailure.input }
  disturbed.monitor.handler?(.mouseMoved, foreignMove)
  guard !(await disturbed.input.cleanup(NativeBudget(milliseconds: 100))),
    disturbed.platform.keys == [55],
    disturbed.sink.events.contains(where: {
      $0.type == .keyUp && $0.getIntegerValueField(.keyboardEventKeycode) == 36
    })
  else { throw BrokerFailure.input }
  let count = disturbed.sink.events.count
  guard !(await disturbed.input.cleanup(NativeBudget(milliseconds: 100))),
    disturbed.sink.events.count == count
  else { throw BrokerFailure.input }
  // A move-only dropped acknowledgement remains occupied/uncertain too.
  let dropped = MemoryExecutor()
  let moveBudget = NativeBudget(milliseconds: 1000)
  try dropped.input.startMonitor(moveBudget)
  dropped.sink.acknowledge = false
  try dropped.input.move(CGPoint(x: -5, y: 5), budget: moveBudget)
  guard !(await dropped.input.cleanup(NativeBudget(milliseconds: 10))) else {
    throw BrokerFailure.input
  }
  // Scalar posting extent survives failure before the next Unicode up/event;
  // it is a local stream extent, never application insertion success.
  let typed = MemoryExecutor()
  let typeBudget = NativeBudget(milliseconds: 1000)
  try typed.input.startMonitor(typeBudget)
  typed.sink.onPost = { event in if event.type == .keyDown { typeBudget.cancel() } }
  do { _ = try typed.input.type("A🙂", budget: typeBudget) } catch {}
  guard await typed.input.cleanup(NativeBudget(milliseconds: 100)),
    typed.input.partial(.type("A🙂", "insert"), currentOperationStarted: true)?.dispatchedUtf16Units
      == 1
  else { throw BrokerFailure.input }
  let dragged = MemoryExecutor()
  let dragBudget = NativeBudget(milliseconds: 1000)
  try dragged.input.startMonitor(dragBudget)
  dragged.sink.onPost = { event in if event.type == .leftMouseDown { dragBudget.cancel() } }
  let points = [BrokerDragPoint(x: 0, y: 0, atMs: 0), BrokerDragPoint(x: 1, y: 1, atMs: 10)]
  do { _ = try await dragged.input.drag(points, button: .left, budget: dragBudget) } catch {}
  guard await dragged.input.cleanup(NativeBudget(milliseconds: 100)),
    dragged.input.partial(.drag(points, .left, []), currentOperationStarted: true)?.points == 1,
    dragged.platform.buttons.isEmpty
  else { throw BrokerFailure.input }
  return 6
}
