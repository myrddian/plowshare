import AppKit
import Foundation
import QuartzCore

private func nativeNonceCells(_ bytes: [UInt8]) throws -> [Bool] {
  guard bytes.count == 32 else { throw BrokerFailure.invalid }
  return (0..<256).map { bytes[$0 / 8] & (1 << (7 - $0 % 8)) != 0 }
}

/// AppKit rounds a fractional initial frame outward. Canonicalize that requested
/// frame before construction, then reproject it into the selected Quartz display.
/// This never adopts a later window-server move or weakens the immutable guard.
private func nativeCanonicalPanelFrame(
  _ desired: NSRect, screenFrame: NSRect, displayBounds: CGRect
) throws -> NSRect {
  for rectangle in [desired, screenFrame, displayBounds] {
    guard
      [rectangle.origin.x, rectangle.origin.y, rectangle.width, rectangle.height].allSatisfy({
        $0.isFinite && abs($0) <= 1e7
      }),
      rectangle.width > 0, rectangle.height > 0
    else { throw BrokerFailure.unknown }
  }
  let canonical = desired.integral
  let projected = CGRect(
    x: displayBounds.minX + canonical.minX - screenFrame.minX,
    y: displayBounds.minY + screenFrame.maxY - canonical.maxY,
    width: canonical.width, height: canonical.height)
  guard displayBounds.contains(projected) else { throw BrokerFailure.unknown }
  return canonical
}
private func nativeRequireConstructedPanelFrame(_ actual: NSRect, expected: NSRect) throws {
  guard actual == expected else { throw BrokerFailure.unknown }
}

/// The setter Bool alone does not establish effective policy: an already-accessory
/// no-op may return false. Nonescaping closures keep production calls on the owning
/// MainActor while the state projection can be checked without NSApplication.
private func nativePrepareAccessoryPolicy(
  read: () -> Int, request: () -> Bool, finish: () -> Void
) throws {
  let accessory = NSApplication.ActivationPolicy.accessory.rawValue
  if read() != accessory { _ = request() }
  guard read() == accessory else { throw BrokerFailure.unknown }
  finish()
  guard read() == accessory else { throw BrokerFailure.unknown }
}

/// Pure one-presentation gesture ownership. Queued old down/up events cannot
/// approve a replacement context, and a failed up consumes the old gesture.
private struct NativeUIGesture<Action: Equatable> {
  private var generation = UUID()
  private var startedAt: TimeInterval
  private var held: (UUID, Action, TimeInterval)?
  init(startedAt: TimeInterval) { self.startedAt = startedAt }
  mutating func reset(startedAt: TimeInterval) {
    generation = UUID()
    self.startedAt = startedAt
    held = nil
  }
  mutating func begin(_ action: Action?, at time: TimeInterval, exactWindow: Bool) {
    guard exactWindow, time.isFinite, time >= startedAt else {
      held = nil
      return
    }
    held = action.map { (generation, $0, time) }
  }
  mutating func end(_ action: Action?, at time: TimeInterval, exactWindow: Bool) -> Action? {
    let previous = held
    held = nil
    guard let (epoch, old, downTime) = previous, exactWindow, time.isFinite, time >= startedAt,
      time >= downTime,
      epoch == generation, action == old
    else { return nil }
    return old
  }
}
private struct NativeUIProbeLatch {
  private var expected: Int64?
  mutating func expect(_ token: Int64) { expected = token == 0 ? nil : token }
  mutating func receive(_ token: Int64, exactWindow: Bool, inside: Bool) -> Int64? {
    guard exactWindow, inside, let expected, token == expected else { return nil }
    self.expected = nil
    return expected
  }
}

/// Custom drawing avoids nested AppKit button/menu tracking loops in the bounded
/// event pump. No model can resolve these helper-owned controls through AX.
@MainActor private final class NativeConsentView: NSView {
  private enum Hit: Equatable {
    case target(String)
    case approve(String)
    case revoke, permissions
  }
  let context: NativeConsentPresentation
  var callbacks: NativeReadinessUICallbacks
  private var targets: [NativeConsentTarget]
  private var selected: String?
  private var firstRow = 0
  private var gesture = NativeUIGesture<Hit>(startedAt: ProcessInfo.processInfo.systemUptime)
  private var approvalConsumed = false
  override var isFlipped: Bool { true }
  init(frame: NSRect, context: NativeConsentPresentation, callbacks: NativeReadinessUICallbacks) {
    self.context = context
    self.callbacks = callbacks
    targets = context.targets
    super.init(frame: frame)
    setAccessibilityElement(false)
  }
  required init?(coder: NSCoder) { nil }
  private var approveRect: NSRect {
    NSRect(x: bounds.width - 208, y: bounds.height - 48, width: 192, height: 32)
  }
  private var revokeRect: NSRect { NSRect(x: 16, y: bounds.height - 48, width: 112, height: 32) }
  private var permissionRect: NSRect {
    NSRect(x: 144, y: bounds.height - 48, width: 160, height: 32)
  }
  private var ownerText: String {
    let owner = context.owner
    return
      "Account: \(owner.account)\nProject: \(owner.project)\nRun: \(owner.run)\nProvider: \(owner.provider)\nInput tools: \(context.tools.joined(separator: ", "))\nCapture display: \(context.display.displayId), x=\(context.display.bounds.x), y=\(context.display.bounds.y), \(context.display.bounds.width)×\(context.display.bounds.height)"
  }
  private static func ownerStyle() -> NSMutableParagraphStyle {
    let paragraph = NSMutableParagraphStyle()
    paragraph.lineBreakMode = .byCharWrapping
    return paragraph
  }
  static func ownerHeight(_ context: NativeConsentPresentation, width: CGFloat) -> CGFloat {
    let owner = context.owner
    let text =
      "Account: \(owner.account)\nProject: \(owner.project)\nRun: \(owner.run)\nProvider: \(owner.provider)\nInput tools: \(context.tools.joined(separator: ", "))\nCapture display: \(context.display.displayId), x=\(context.display.bounds.x), y=\(context.display.bounds.y), \(context.display.bounds.width)×\(context.display.bounds.height)"
    return ceil(
      (text as NSString).boundingRect(
        with: NSSize(width: width - 32, height: 4096),
        options: [.usesLineFragmentOrigin, .usesFontLeading],
        attributes: [.font: NSFont.systemFont(ofSize: 12), .paragraphStyle: ownerStyle()]
      ).height) + 8
  }
  private var rowTop: CGFloat { 140 + Self.ownerHeight(context, width: bounds.width) }
  private var rowCount: Int { max(1, Int((bounds.height - rowTop - 70) / 44)) }
  private func rowRect(_ offset: Int) -> NSRect {
    NSRect(x: 16, y: rowTop + CGFloat(offset * 44), width: bounds.width - 32, height: 40)
  }
  private func drawText(
    _ text: String, _ rectangle: NSRect, size: CGFloat = 13, color: NSColor = .labelColor
  ) {
    let paragraph = NSMutableParagraphStyle()
    paragraph.lineBreakMode = .byTruncatingTail
    (text as NSString).draw(
      in: rectangle,
      withAttributes: [
        .font: NSFont.systemFont(ofSize: size), .foregroundColor: color, .paragraphStyle: paragraph,
      ])
  }
  override func draw(_ dirtyRect: NSRect) {
    NSColor.windowBackgroundColor.setFill()
    bounds.fill()
    drawText(
      "Allow local desktop input", NSRect(x: 16, y: 14, width: bounds.width - 32, height: 28),
      size: 20)
    drawText(
      "Capture: configured display, independent of this input approval.",
      NSRect(x: 16, y: 48, width: bounds.width - 32, height: 22), size: 12)
    drawText(
      "Input: listed apps/tools; selected window sets starting focus.",
      NSRect(x: 16, y: 70, width: bounds.width - 32, height: 22), size: 12)
    (ownerText as NSString).draw(
      in: NSRect(
        x: 16, y: 100, width: bounds.width - 32,
        height: Self.ownerHeight(context, width: bounds.width)),
      withAttributes: [
        .font: NSFont.systemFont(ofSize: 12), .foregroundColor: NSColor.labelColor,
        .paragraphStyle: Self.ownerStyle(),
      ])
    drawText(
      "Input lasts up to \(context.durationMs / 1000)s. Capture follows provider configuration.",
      NSRect(x: 16, y: rowTop - 30, width: bounds.width - 32, height: 25))
    for offset in 0..<rowCount {
      let index = firstRow + offset
      guard index < targets.count else { break }
      let target = targets[index]
      let rectangle = rowRect(offset)
      (selected == target.surfaceId
        ? NSColor.selectedContentBackgroundColor : NSColor.controlBackgroundColor).setFill()
      rectangle.fill()
      let color: NSColor = selected == target.surfaceId ? .selectedTextColor : .labelColor
      drawText(
        "\(target.applicationLabel) — \(target.windowTitle)", rectangle.insetBy(dx: 8, dy: 2),
        color: color)
      drawText(
        target.bundleId,
        NSRect(
          x: rectangle.minX + 8, y: rectangle.minY + 21, width: rectangle.width - 16, height: 18),
        size: 11, color: color)
    }
    for (rectangle, label) in [
      (approveRect, "Allow input; start here"), (revokeRect, "Cancel"),
      (permissionRect, "Check permissions"),
    ] {
      NSColor.controlColor.setFill()
      rectangle.fill()
      drawText(label, rectangle.insetBy(dx: 6, dy: 7))
    }
  }
  private func hit(_ point: NSPoint) -> Hit? {
    if revokeRect.contains(point) { return .revoke }
    if permissionRect.contains(point) { return .permissions }
    if approveRect.contains(point), let selected, !approvalConsumed { return .approve(selected) }
    for offset in 0..<rowCount {
      let index = firstRow + offset
      if index < targets.count, rowRect(offset).contains(point) {
        return .target(targets[index].surfaceId)
      }
    }
    return nil
  }
  override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
  override func mouseDown(with event: NSEvent) {
    gesture.begin(
      hit(convert(event.locationInWindow, from: nil)), at: event.timestamp,
      exactWindow: window != nil && event.window === window)
  }
  override func mouseUp(with event: NSEvent) {
    guard
      let action = gesture.end(
        hit(convert(event.locationInWindow, from: nil)), at: event.timestamp,
        exactWindow: window != nil && event.window === window)
    else { return }
    switch action {
    case .target(let id):
      selected = id
      needsDisplay = true
    case .approve(let id):
      approvalConsumed = true
      callbacks.approve(id)
    case .revoke: callbacks.revoke()
    case .permissions: callbacks.permissionsChanged()
    }
  }
  override func scrollWheel(with event: NSEvent) {
    guard event.scrollingDeltaY.isFinite else { return }
    let delta = event.scrollingDeltaY > 0 ? -1 : event.scrollingDeltaY < 0 ? 1 : 0
    firstRow = min(max(0, targets.count - rowCount), max(0, firstRow + delta))
    gesture.reset(startedAt: ProcessInfo.processInfo.systemUptime)
    needsDisplay = true
  }
  func updateTargets(_ replacement: [NativeConsentTarget]) {
    targets = replacement
    if let selected, !replacement.contains(where: { $0.surfaceId == selected }) {
      self.selected = nil
    }
    firstRow = min(firstRow, max(0, targets.count - rowCount))
    gesture.reset(startedAt: ProcessInfo.processInfo.systemUptime)
    needsDisplay = true
  }
  func invalidate() {
    gesture.reset(startedAt: ProcessInfo.processInfo.systemUptime)
    approvalConsumed = true
  }
}

@MainActor private final class NativeNonceView: NSView {
  private var cells = Array(repeating: false, count: 256)
  private var probe = NativeUIProbeLatch()
  private var area: NSTrackingArea?
  var receipt: ((Int64) -> Void)?
  override var isFlipped: Bool { true }
  override var isOpaque: Bool { true }
  override func draw(_ dirtyRect: NSRect) {
    NSGraphicsContext.current?.shouldAntialias = false
    for index in 0..<256 {
      (cells[index] ? NSColor.white : NSColor.black).setFill()
      NSRect(
        x: CGFloat(index % 16) * bounds.width / 16, y: CGFloat(index / 16) * bounds.height / 16,
        width: bounds.width / 16, height: bounds.height / 16
      ).fill()
    }
  }
  override func updateTrackingAreas() {
    if let area { removeTrackingArea(area) }
    let area = NSTrackingArea(
      rect: bounds, options: [.mouseMoved, .activeAlways, .inVisibleRect], owner: self)
    addTrackingArea(area)
    self.area = area
    super.updateTrackingAreas()
  }
  func paint(_ nonce: [UInt8]) throws {
    cells = try nativeNonceCells(nonce)
    probe.expect(0)
    needsDisplay = true
  }
  func expect(_ token: Int64) { probe.expect(token) }
  override func mouseMoved(with event: NSEvent) {
    guard let window, let token = event.cgEvent?.getIntegerValueField(.eventSourceUserData),
      let accepted = probe.receive(
        token, exactWindow: event.window === window && event.windowNumber == window.windowNumber,
        inside: bounds.contains(convert(event.locationInWindow, from: nil)))
    else { return }
    receipt?(accepted)
  }
  func invalidate() {
    probe.expect(0)
    receipt = nil
  }

}
@MainActor private final class NativeStopView: NSView {
  var revoke: (() -> Void)?
  private var gesture = NativeUIGesture<Bool>(startedAt: ProcessInfo.processInfo.systemUptime)
  override var isFlipped: Bool { true }
  private var stopRect: NSRect { NSRect(x: 92, y: 32, width: 152, height: 36) }
  override func draw(_ dirtyRect: NSRect) {
    NSColor.windowBackgroundColor.setFill()
    bounds.fill()
    let attributes: [NSAttributedString.Key: Any] = [
      .font: NSFont.systemFont(ofSize: 13), .foregroundColor: NSColor.labelColor,
    ]
    ("Desktop input active" as NSString).draw(
      at: NSPoint(x: 92, y: 12), withAttributes: attributes)
    NSColor.controlColor.setFill()
    stopRect.fill()
    ("Stop input" as NSString).draw(at: NSPoint(x: 99, y: 42), withAttributes: attributes)
    ("Capture continues until provider stop" as NSString).draw(
      at: NSPoint(x: 12, y: 82),
      withAttributes: [.font: NSFont.systemFont(ofSize: 11), .foregroundColor: NSColor.labelColor])
  }
  override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
  override func mouseDown(with event: NSEvent) {
    gesture.begin(
      stopRect.contains(convert(event.locationInWindow, from: nil)) ? true : nil,
      at: event.timestamp, exactWindow: window != nil && event.window === window)
  }
  override func mouseUp(with event: NSEvent) {
    if gesture.end(
      stopRect.contains(convert(event.locationInWindow, from: nil)) ? true : nil,
      at: event.timestamp, exactWindow: window != nil && event.window === window) == true
    {
      revoke?()
    }
  }
  func invalidate() {
    gesture.reset(startedAt: ProcessInfo.processInfo.systemUptime)
    revoke = nil
  }

}

/// Only this object owns helper AppKit event pumping. The broker's async stdin
/// reader stays live; no NSApplication.run or modal event-tracking loop is used.
@MainActor final class NativeAppKitReadinessUI: NativeReadinessUI {
  private enum Phase { case idle, pending, sentinel, closing }
  private var phase = Phase.idle
  private var callbacks: NativeReadinessUICallbacks?
  private var display: BrokerDisplay?
  private var consent: NSPanel?, sentinel: NSPanel?
  private var consentView: NativeConsentView?, nonceView: NativeNonceView?,
    stopView: NativeStopView?
  private var expectedFrames: [Int: NSRect] = [:]
  private var observers: [(NotificationCenter, NSObjectProtocol)] = []
  private var pump: Task<Void, Never>?
  private var epoch = UUID()
  private var lost = false
  private var regionGeneration = UUID().uuidString.lowercased()
  private static var applicationInitialized = false
  private var lastRectangles: [CGRect] = []

  private func validTargets(_ targets: [NativeConsentTarget]) -> Bool {
    targets.count <= 64 && Set(targets.map(\.surfaceId)).count == targets.count
      && targets.allSatisfy {
        [$0.surfaceId, $0.applicationId, $0.applicationLabel, $0.bundleId, $0.windowTitle]
          .allSatisfy { $0.utf16.count <= 256 && !$0.contains("\0") }
      }
  }
  private func screen(_ display: BrokerDisplay) throws -> NSScreen {
    let parts = display.displayId.split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 2, parts[0] == "display", let id = UInt32(parts[1]), id > 0,
      String(id) == parts[1], display.scaleX.isFinite, display.scaleY.isFinite,
      display.scaleX > 0, display.scaleY > 0, display.scaleX <= 8, display.scaleY <= 8,
      [display.bounds.x, display.bounds.y, display.bounds.width, display.bounds.height].allSatisfy({
        $0.isFinite && abs($0) <= 1e7
      }),
      display.bounds.width >= 320, display.bounds.height >= 160, NSScreen.screens.count <= 32,
      let screen = NSScreen.screens.first(where: {
        ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value
          == id
      }),
      CGDisplayBounds(id) == display.bounds.rect, Int(CGDisplayRotation(id)) == display.rotation,
      abs(screen.frame.width - display.bounds.width) <= 0.5,
      abs(screen.frame.height - display.bounds.height) <= 0.5,
      abs(screen.backingScaleFactor - display.scaleX) <= 0.001,
      abs(screen.backingScaleFactor - display.scaleY) <= 0.001
    else { throw BrokerFailure.unknown }
    return screen
  }
  private func quartz(_ rectangle: NSRect, _ display: BrokerDisplay) throws -> CGRect {
    let screen = try screen(display)
    return CGRect(
      x: display.bounds.x + rectangle.minX - screen.frame.minX,
      y: display.bounds.y + screen.frame.maxY - rectangle.maxY, width: rectangle.width,
      height: rectangle.height)
  }
  private func panel(_ rectangle: CGRect, display: BrokerDisplay) throws -> NSPanel {
    guard display.bounds.rect.contains(rectangle) else { throw BrokerFailure.unknown }
    let screenFrame = try screen(display).frame
    let desired = NSRect(
      x: screenFrame.minX + rectangle.minX - display.bounds.x,
      y: screenFrame.maxY - (rectangle.maxY - display.bounds.y),
      width: rectangle.width, height: rectangle.height)
    let frame = try nativeCanonicalPanelFrame(
      desired, screenFrame: screenFrame, displayBounds: display.bounds.rect)
    let panel = NSPanel(
      contentRect: frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered,
      defer: false)
    panel.isReleasedWhenClosed = false
    do { try nativeRequireConstructedPanelFrame(panel.frame, expected: frame) } catch {
      panel.close()
      throw error
    }
    panel.isOpaque = true
    panel.backgroundColor = .windowBackgroundColor
    panel.hasShadow = false
    panel.isMovable = false
    panel.isMovableByWindowBackground = false
    panel.canBecomeVisibleWithoutLogin = false
    panel.hidesOnDeactivate = false
    panel.acceptsMouseMovedEvents = true
    panel.animationBehavior = .none
    panel.level = .floating
    panel.title = "Plowshare desktop control"
    expectedFrames[panel.windowNumber] = frame
    return panel
  }
  private func losePresentation() {
    guard phase != .idle, phase != .closing, !lost else { return }
    lost = true
    nonceView?.expect(0)
    consentView?.invalidate()
    callbacks?.presentationLost()
  }
  private func observe(
    _ center: NotificationCenter, _ name: Notification.Name, object: AnyObject? = nil
  ) {
    let token = center.addObserver(forName: name, object: object, queue: .main) { [weak self] _ in
      // NotificationCenter's explicit .main queue is the actor-isolation boundary.
      MainActor.assumeIsolated { self?.losePresentation() }
    }
    observers.append((center, token))
  }
  private func watch(_ panel: NSPanel) {
    for name in [
      NSWindow.didMoveNotification, NSWindow.didResizeNotification,
      NSWindow.didChangeScreenNotification,
      NSWindow.didMiniaturizeNotification, NSWindow.willCloseNotification,
    ] { observe(.default, name, object: panel) }
    let token = NotificationCenter.default.addObserver(
      forName: NSWindow.didChangeOcclusionStateNotification, object: panel, queue: .main
    ) { [weak self, weak panel] _ in
      MainActor.assumeIsolated {
        if let panel, panel.isVisible, !panel.occlusionState.contains(.visible) {
          self?.losePresentation()
        }
      }
    }
    observers.append((.default, token))
  }
  private func startPump() {
    let application = NSApplication.shared
    let generation = epoch
    pump = Task { @MainActor [weak self] in
      while !Task.isCancelled {
        guard let self, self.epoch == generation, self.phase != .idle, self.phase != .closing else {
          return
        }
        let cutoff = DispatchTime.now().uptimeNanoseconds + 2_000_000
        for _ in 0..<32 {
          guard !Task.isCancelled, self.epoch == generation, self.phase != .closing,
            DispatchTime.now().uptimeNanoseconds < cutoff,
            let event = application.nextEvent(
              matching: .any, until: .distantPast, inMode: .default, dequeue: true)
          else { break }
          application.sendEvent(event)
        }
        guard !Task.isCancelled, self.epoch == generation, self.phase != .closing else { return }
        application.updateWindows()
        do { try await Task.sleep(for: .milliseconds(10)) } catch { return }
      }
    }
  }
  /// Registration is needed before global AX discovery. This is process-wide:
  /// revoke/rearm may replace this UI instance without relaunching NSApplication.
  /// It creates no windows, event pump, activation, permission prompt or input.
  func prepare() throws {
    let application = NSApplication.shared
    try nativePrepareAccessoryPolicy(
      read: { application.activationPolicy().rawValue },
      request: { application.setActivationPolicy(.accessory) },
      finish: {
        if !Self.applicationInitialized {
          application.finishLaunching()
          // Even a failed effective-policy postcondition must not finish twice.
          Self.applicationInitialized = true
        }
      })
  }

  func present(_ context: NativeConsentPresentation, callbacks: NativeReadinessUICallbacks) throws {
    guard phase == .idle, (1...30_000).contains(context.durationMs), validTargets(context.targets),
      UUID(uuidString: context.challengeId)?.uuidString.lowercased() == context.challengeId,
      !context.tools.isEmpty, context.tools.count <= 6,
      Set(context.tools).count == context.tools.count,
      context.tools.allSatisfy({
        ["windows", "focus", "pointer", "type", "key", "scroll"].contains($0)
      }),
      [context.owner.account, context.owner.project, context.owner.run, context.owner.provider]
        .allSatisfy({ !$0.isEmpty && $0.utf16.count <= 256 && !$0.contains("\0") })
    else { throw BrokerFailure.invalid }
    try prepare()
    _ = try screen(context.display)
    let available = try quartz(screen(context.display).visibleFrame, context.display).insetBy(
      dx: 16, dy: 16)
    let width = min(720, available.width)
    let minimumHeight = 140 + NativeConsentView.ownerHeight(context, width: width) + 114
    let height = min(max(560, minimumHeight), available.height)
    guard width >= 520, height >= minimumHeight else { throw BrokerFailure.unknown }
    let rectangle = CGRect(
      x: available.midX - width / 2,
      y: available.midY - height / 2, width: width,
      height: height)
    let panel = try panel(rectangle, display: context.display)
    let view = NativeConsentView(
      frame: NSRect(origin: .zero, size: panel.frame.size), context: context, callbacks: callbacks)
    panel.contentView = view
    self.callbacks = callbacks
    display = context.display
    consent = panel
    consentView = view
    phase = .pending
    epoch = UUID()
    lost = false
    watch(panel)
    for name in [
      NSWorkspace.sessionDidResignActiveNotification, NSWorkspace.screensDidSleepNotification,
      NSWorkspace.activeSpaceDidChangeNotification,
    ] { observe(NSWorkspace.shared.notificationCenter, name) }
    observe(.default, NSApplication.didChangeScreenParametersNotification)
    panel.orderFrontRegardless()
    startPump()
    _ = regions()
  }
  func updateTargets(_ targets: [NativeConsentTarget]) {
    guard phase == .pending, validTargets(targets), !lost else {
      losePresentation()
      return
    }
    consentView?.updateTargets(targets)
  }
  func showSentinel(display: BrokerDisplay) throws {
    guard phase == .pending, !lost, self.display == display else { throw BrokerFailure.unknown }
    let available = try quartz(screen(display).visibleFrame, display).insetBy(dx: 16, dy: 16)
    let rectangle = CGRect(x: available.minX, y: available.minY, width: 260, height: 100)
    guard available.contains(rectangle) else { throw BrokerFailure.unknown }
    let panel = try panel(rectangle, display: display)
    let stop = NativeStopView(frame: NSRect(origin: .zero, size: panel.frame.size))
    stop.revoke = { [weak self] in self?.callbacks?.revoke() }
    let grid = NativeNonceView(
      frame: NSRect(
        x: 12, y: 100 - 12 - 64 / display.scaleY, width: 64 / display.scaleX,
        height: 64 / display.scaleY))
    grid.setAccessibilityElement(false)
    grid.receipt = { [weak self] tag in
      guard let self, self.phase == .sentinel, !self.lost else { return }
      self.callbacks?.probeReceived(tag)
    }
    stop.addSubview(grid)
    grid.updateTrackingAreas()
    stop.setAccessibilityElement(false)
    panel.contentView = stop
    consentView?.invalidate()
    consent?.orderOut(nil)
    sentinel = panel
    nonceView = grid
    stopView = stop
    phase = .sentinel
    watch(panel)
    panel.orderFrontRegardless()
    _ = regions()
  }
  func paintNonce(_ nonce: [UInt8]) throws -> NativeSentinelGeometry {
    guard phase == .sentinel, !lost, let display, let sentinel, let grid = nonceView,
      sentinel.isVisible, expectedFrames[sentinel.windowNumber] == sentinel.frame
    else { throw BrokerFailure.unknown }
    _ = try screen(display)
    try grid.paint(nonce)
    sentinel.displayIfNeeded()
    grid.displayIfNeeded()
    NSApplication.shared.updateWindows()
    CATransaction.flush()
    let gridOnScreen = sentinel.convertToScreen(grid.convert(grid.bounds, to: nil))
    let rectangle = try quartz(gridOnScreen, display)
    let owned = regions()
    guard !lost, display.bounds.rect.contains(rectangle),
      abs(rectangle.width * display.scaleX - 64) <= 0.001,
      abs(rectangle.height * display.scaleY - 64) <= 0.001
    else { throw BrokerFailure.unknown }
    return NativeSentinelGeometry(
      captureRectangle: rectangle, pointerLocation: CGPoint(x: rectangle.midX, y: rectangle.midY),
      windowNumber: sentinel.windowNumber, regions: owned,
      presented: sentinel.isVisible && !sentinel.isMiniaturized
        && sentinel.occlusionState.contains(.visible))
  }
  func expectProbe(_ token: Int64) {
    guard phase == .sentinel, !lost else {
      nonceView?.expect(0)
      return
    }
    nonceView?.expect(token)
  }
  func regions() -> NativeOwnedUIRegions {
    var rectangles: [CGRect] = []
    if let display {
      for panel in [consent, sentinel].compactMap({ $0 }) where panel.isVisible {
        if expectedFrames[panel.windowNumber] != panel.frame { losePresentation() }
        if let rectangle = try? quartz(panel.frame, display) {
          rectangles.append(rectangle.insetBy(dx: -2, dy: -2))
        } else {
          losePresentation()
        }
      }
    }
    if rectangles != lastRectangles || lost {
      regionGeneration = UUID().uuidString.lowercased()
      lastRectangles = rectangles
    }
    return NativeOwnedUIRegions(generation: regionGeneration, rectangles: rectangles)
  }
  func close() async {
    let pending = pump
    if phase != .closing {
      phase = .closing
      epoch = UUID()
      callbacks = nil
      consentView?.invalidate()
      nonceView?.invalidate()
      stopView?.invalidate()
      pending?.cancel()
      for (center, token) in observers { center.removeObserver(token) }
      observers.removeAll()
      consent?.orderOut(nil)
      sentinel?.orderOut(nil)
      consent?.close()
      sentinel?.close()
    }
    let closingEpoch = epoch
    await pending?.value
    // Multiple close waiters may resume after a new presentation was admitted.
    // Only the original still-closing epoch may erase its owned resources.
    guard phase == .closing, epoch == closingEpoch else { return }
    pump = nil
    consent = nil
    sentinel = nil
    consentView = nil
    nonceView = nil
    stopView = nil
    display = nil
    expectedFrames.removeAll()
    lastRectangles = []
    regionGeneration = UUID().uuidString.lowercased()
    phase = .idle
  }

}

/// Pure no-GUI fixture: binary row-major MSB-first cells, exact size and input
/// rejection. This neither constructs AppKit windows nor posts an event.
func nativeReadinessUIMemoryCheck() throws -> Int {
  let zero = try nativeNonceCells(Array(repeating: 0, count: 32))
  let one = try nativeNonceCells(Array(repeating: 255, count: 32))
  let leading = try nativeNonceCells([128] + Array(repeating: 0, count: 31))
  guard zero.count == 256, !zero.contains(true), one.allSatisfy({ $0 }), leading[0],
    leading.dropFirst().allSatisfy({ !$0 })
  else { throw BrokerFailure.invalid }
  for count in [0, 31, 33, 64] {
    do { _ = try nativeNonceCells(Array(repeating: 0, count: count)) } catch { continue }
    throw BrokerFailure.invalid
  }
  var gesture = NativeUIGesture<String>(startedAt: 10)
  gesture.begin("allow", at: 11, exactWindow: true)
  guard gesture.end("allow", at: 12, exactWindow: true) == "allow" else {
    throw BrokerFailure.invalid
  }
  gesture.begin("allow", at: 11, exactWindow: true)
  guard gesture.end("cancel", at: 12, exactWindow: true) == nil,
    gesture.end("allow", at: 12, exactWindow: true) == nil
  else { throw BrokerFailure.invalid }
  gesture.begin("allow", at: 9, exactWindow: true)
  guard gesture.end("allow", at: 12, exactWindow: true) == nil else { throw BrokerFailure.invalid }
  gesture.begin("allow", at: 11, exactWindow: true)
  gesture.reset(startedAt: 13)
  guard gesture.end("allow", at: 14, exactWindow: true) == nil else { throw BrokerFailure.invalid }
  gesture.begin("allow", at: 14, exactWindow: false)
  guard gesture.end("allow", at: 15, exactWindow: true) == nil else { throw BrokerFailure.invalid }
  var probe = NativeUIProbeLatch()
  probe.expect(42)
  guard probe.receive(42, exactWindow: false, inside: true) == nil else {
    throw BrokerFailure.invalid
  }
  guard probe.receive(42, exactWindow: true, inside: false) == nil else {
    throw BrokerFailure.invalid
  }
  guard probe.receive(41, exactWindow: true, inside: true) == nil else {
    throw BrokerFailure.invalid
  }
  guard probe.receive(42, exactWindow: true, inside: true) == 42 else {
    throw BrokerFailure.invalid
  }
  guard probe.receive(42, exactWindow: true, inside: true) == nil else {
    throw BrokerFailure.invalid
  }
  probe.expect(42)
  probe.expect(0)
  guard probe.receive(42, exactWindow: true, inside: true) == nil else {
    throw BrokerFailure.invalid
  }
  let accessory = NSApplication.ActivationPolicy.accessory.rawValue
  let regular = NSApplication.ActivationPolicy.regular.rawValue
  var policy = accessory
  var requests = 0
  var finishes = 0
  try nativePrepareAccessoryPolicy(
    read: { policy },
    request: {
      requests += 1
      return false
    }, finish: { finishes += 1 })
  guard requests == 0, finishes == 1 else { throw BrokerFailure.invalid }
  policy = regular
  requests = 0
  finishes = 0
  try nativePrepareAccessoryPolicy(
    read: { policy },
    request: {
      requests += 1
      policy = accessory
      return false
    }, finish: { finishes += 1 })
  guard requests == 1, finishes == 1, policy == accessory else { throw BrokerFailure.invalid }
  for claimedTransition in [false, true] {
    policy = regular
    finishes = 0
    var refused = false
    do {
      try nativePrepareAccessoryPolicy(
        read: { policy }, request: { claimedTransition }, finish: { finishes += 1 })
    } catch BrokerFailure.unknown { refused = true }
    guard refused, finishes == 0 else { throw BrokerFailure.invalid }
  }
  policy = accessory
  finishes = 0
  var refused = false
  do {
    try nativePrepareAccessoryPolicy(
      read: { policy }, request: { false },
      finish: {
        finishes += 1
        policy = regular
      })
  } catch BrokerFailure.unknown { refused = true }
  guard refused, finishes == 1 else { throw BrokerFailure.invalid }
  let screen = CGRect(x: 0, y: 0, width: 1920, height: 1080)
  let fractional = try nativeCanonicalPanelFrame(
    CGRect(x: 600, y: 244.5, width: 720, height: 560), screenFrame: screen, displayBounds: screen)
  guard fractional == CGRect(x: 600, y: 244, width: 720, height: 561) else {
    throw BrokerFailure.invalid
  }
  try nativeRequireConstructedPanelFrame(fractional, expected: fractional)
  guard
    try nativeCanonicalPanelFrame(fractional, screenFrame: screen, displayBounds: screen)
      == fractional
  else { throw BrokerFailure.invalid }
  let negative = try nativeCanonicalPanelFrame(
    CGRect(x: -1903.5, y: 244.5, width: 720, height: 560),
    screenFrame: CGRect(x: -1920, y: 0, width: 1920, height: 1080),
    displayBounds: CGRect(x: -1920, y: -44, width: 1920, height: 1080))
  guard negative == CGRect(x: -1904, y: 244, width: 721, height: 561) else {
    throw BrokerFailure.invalid
  }
  refused = false
  do {
    _ = try nativeCanonicalPanelFrame(
      CGRect(x: 0.5, y: 10, width: 720, height: 560),
      screenFrame: CGRect(x: 0.5, y: 0.5, width: 1920, height: 1080), displayBounds: screen)
  } catch BrokerFailure.unknown { refused = true }
  guard refused else { throw BrokerFailure.invalid }
  refused = false
  do {
    _ = try nativeCanonicalPanelFrame(
      CGRect(x: CGFloat.nan, y: 10, width: 720, height: 560), screenFrame: screen,
      displayBounds: screen)
  } catch BrokerFailure.unknown { refused = true }
  guard refused else { throw BrokerFailure.invalid }
  refused = false
  do {
    try nativeRequireConstructedPanelFrame(
      fractional.offsetBy(dx: 0.5, dy: 0), expected: fractional)
  } catch BrokerFailure.unknown { refused = true }
  guard refused else { throw BrokerFailure.invalid }
  return 29
}
