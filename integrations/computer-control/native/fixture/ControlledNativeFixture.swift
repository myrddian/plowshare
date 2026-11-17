import AppKit
import Darwin
import Foundation

private let fixtureVersion = "computer-control-native/fixture/1"
private let requestLimit = 8192
private let replyLimit = 65536
private enum FixtureFailure: Error { case invalid, unconfigured, display, configured, expired }
private struct FixtureKey: CodingKey {
  let stringValue: String
  var intValue: Int? { nil }
  init(stringValue: String) { self.stringValue = stringValue }
  init?(intValue: Int) { return nil }
}
private enum FixtureCommand: Sendable {
  case configure(instanceId: String, displayId: UInt32, slot: String, marker: String)
  case status
  case marker(String)
  case stop
}
private struct FixtureRequest: Decodable, Sendable {
  let requestId: String, timeoutMs: Int, command: FixtureCommand
  private enum Keys: String, CodingKey {
    case version, requestId, timeoutMs, operation, instanceId, displayId, slot, marker
  }
  init(from decoder: Decoder) throws {
    let all = try decoder.container(keyedBy: FixtureKey.self).allKeys.map(\.stringValue)
    let values = try decoder.container(keyedBy: Keys.self)
    guard try values.decode(String.self, forKey: .version) == fixtureVersion else {
      throw FixtureFailure.invalid
    }
    requestId = try values.decode(String.self, forKey: .requestId)
    guard UUID(uuidString: requestId)?.uuidString.lowercased() == requestId else {
      throw FixtureFailure.invalid
    }
    timeoutMs = try values.decode(Int.self, forKey: .timeoutMs)
    guard (1...2000).contains(timeoutMs) else { throw FixtureFailure.invalid }
    let operation = try values.decode(String.self, forKey: .operation)
    let allowed: Set<String>
    switch operation {
    case "configure":
      allowed = [
        "version", "requestId", "timeoutMs", "operation", "instanceId", "displayId", "slot",
        "marker",
      ]
      let instance = try values.decode(String.self, forKey: .instanceId)
      let display = try values.decode(UInt32.self, forKey: .displayId)
      let slot = try values.decode(String.self, forKey: .slot)
      let marker = try values.decode(String.self, forKey: .marker)
      guard UUID(uuidString: instance)?.uuidString.lowercased() == instance, display > 0,
        ["left", "right"].contains(slot), Self.validMarker(marker)
      else { throw FixtureFailure.invalid }
      command = .configure(instanceId: instance, displayId: display, slot: slot, marker: marker)
    case "status", "stop":
      allowed = ["version", "requestId", "timeoutMs", "operation"]
      command = operation == "status" ? .status : .stop
    case "set_marker":
      allowed = ["version", "requestId", "timeoutMs", "operation", "marker"]
      let marker = try values.decode(String.self, forKey: .marker)
      guard Self.validMarker(marker) else { throw FixtureFailure.invalid }
      command = .marker(marker)
    default: throw FixtureFailure.invalid
    }
    guard all.allSatisfy({ allowed.contains($0) }) else { throw FixtureFailure.invalid }
  }
  static func validMarker(_ value: String) -> Bool {
    value.utf8.count == 6 && value.utf8.allSatisfy({ (48...57).contains($0) })
  }
}

/// The private fixture protocol is flat. Reject scoped duplicate/escaped keys
/// before JSONDecoder: decoder key precedence must not become test setup policy.
private func decodeFixtureRequest(_ input: Data) throws -> FixtureRequest {
  guard !input.isEmpty, input.count <= requestLimit,
    String(data: input, encoding: .utf8) != nil
  else { throw FixtureFailure.invalid }
  let bytes = Array(input)
  var index = 0
  var keys = Set<String>()
  while index < bytes.count {
    guard bytes[index] == 34 else {
      index += 1
      continue
    }
    let start = index
    index += 1
    while index < bytes.count, bytes[index] != 34 {
      if bytes[index] == 92 { index += 1 }
      index += 1
    }
    guard index < bytes.count else { throw FixtureFailure.invalid }
    index += 1
    let end = index
    while index < bytes.count, [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
    if index < bytes.count, bytes[index] == 58 {
      let key = try JSONDecoder().decode(String.self, from: Data(bytes[start..<end]))
      guard keys.insert(key).inserted else { throw FixtureFailure.invalid }
    }
  }
  return try JSONDecoder().decode(FixtureRequest.self, from: input)
}
private struct FixtureCounts: Encodable {
  var mouseDown = 0, mouseUp = 0, keyDown = 0, scroll = 0, textChanged = 0
  var buttonActivated = 0, dragCompleted = 0, menuSelected = 0, dialogOpened = 0, dialogClosed = 0
}
private struct FixtureStatus: Encodable {
  let instanceId: String, bundleId: String
  let processId: Int32, windowNumber: Int
  let configuredDisplayId: UInt32
  let setupGeneration: Int, markerGeneration: Int, renderedMarkerGeneration: Int, eventSequence: Int
  let counts: FixtureCounts
  let text: String, toggled: Bool, dialogOpen: Bool, truncated: Bool
  let windowVisible: Bool, applicationActive: Bool
  let scrollX: Double, scrollY: Double
}
private struct FixtureReply: Encodable {
  let version = fixtureVersion
  let requestId: String
  let ok: Bool
  let status: FixtureStatus?
  let code: String?
}
private func writeFixtureReply(
  _ requestId: String, status: FixtureStatus? = nil, code: String? = nil
) throws {
  let reply = FixtureReply(requestId: requestId, ok: code == nil, status: status, code: code)
  let data = try JSONEncoder().encode(reply)
  guard data.count <= replyLimit else { throw FixtureFailure.invalid }
  try FileHandle.standardOutput.write(contentsOf: data)
  try FileHandle.standardOutput.write(contentsOf: Data([10]))
}

/// Marker digits are painted, not exposed as AX text/labels. The fixture never
/// claims model vision just because its own expected marker is known by the host.
@MainActor private final class MarkerView: NSView {
  var marker = "000000"
  var toggled = false
  var generation = 0
  var rendered: ((Int) -> Void)?
  override var isFlipped: Bool { true }
  override func draw(_ dirtyRect: NSRect) {
    (toggled ? NSColor.systemBlue : NSColor.systemOrange).setFill()
    bounds.fill()
    let patterns = [
      0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
      0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011,
    ]
    let segments = [
      NSRect(x: 8, y: 4, width: 24, height: 5), NSRect(x: 33, y: 9, width: 5, height: 22),
      NSRect(x: 33, y: 36, width: 5, height: 22), NSRect(x: 8, y: 59, width: 24, height: 5),
      NSRect(x: 2, y: 36, width: 5, height: 22), NSRect(x: 2, y: 9, width: 5, height: 22),
      NSRect(x: 8, y: 31, width: 24, height: 5),
    ]
    NSColor.black.setFill()
    for (index, digit) in marker.utf8.enumerated() {
      let pattern = patterns[Int(digit - 48)]
      for (segment, rectangle) in segments.enumerated() where pattern & (1 << (6 - segment)) != 0 {
        rectangle.offsetBy(dx: CGFloat(index * 44 + 12), dy: 8).fill()
      }
    }
    rendered?(generation)
  }
}
@MainActor private final class FixtureWindow: NSWindow {
  var observedEvent: ((NSEvent) -> Void)?
  override func sendEvent(_ event: NSEvent) {
    observedEvent?(event)
    super.sendEvent(event)
  }
}
@MainActor private final class DragView: NSView {
  var completed: (() -> Void)?
  private var initial: NSPoint?
  private var latest = NSPoint.zero
  override func draw(_ dirtyRect: NSRect) {
    NSColor.systemGreen.setFill()
    bounds.fill()
    NSColor.black.setFill()
    NSRect(x: latest.x - 4, y: latest.y - 4, width: 8, height: 8).fill()
  }
  override func mouseDown(with event: NSEvent) {
    initial = convert(event.locationInWindow, from: nil)
    latest = initial ?? .zero
    needsDisplay = true
  }
  override func mouseDragged(with event: NSEvent) {
    latest = convert(event.locationInWindow, from: nil)
    needsDisplay = true
  }
  override func mouseUp(with event: NSEvent) {
    latest = convert(event.locationInWindow, from: nil)
    if let initial, hypot(latest.x - initial.x, latest.y - initial.y) >= 5 { completed?() }
    initial = nil
    needsDisplay = true
  }
}
@MainActor private final class FixtureTextView: NSTextView {
  override func menu(for event: NSEvent) -> NSMenu? { nil }
}

@MainActor
private final class FixtureApplication: NSObject, NSApplicationDelegate, NSTextViewDelegate {
  private var window: FixtureWindow?
  private var marker = MarkerView(frame: NSRect(x: 16, y: 366, width: 300, height: 80))
  private var textField = FixtureTextView(frame: .zero)
  private var counts = FixtureCounts()
  private var eventSequence = 0, setupGeneration = 0, markerGeneration = 0,
    renderedMarkerGeneration = 0
  private var instanceId: String?, displayId: UInt32 = 0
  private var toggled = false, dialogOpen = false, truncated = false
  private var scrollX = 0.0, scrollY = 0.0

  private func increment(_ key: WritableKeyPath<FixtureCounts, Int>) {
    if counts[keyPath: key] < 1_000_000 { counts[keyPath: key] += 1 } else { truncated = true }
  }
  private func event(_ event: NSEvent) {
    if eventSequence < 1_000_000 { eventSequence += 1 } else { truncated = true }
    switch event.type {
    case .leftMouseDown, .rightMouseDown, .otherMouseDown: increment(\.mouseDown)
    case .leftMouseUp, .rightMouseUp, .otherMouseUp: increment(\.mouseUp)
    case .keyDown: increment(\.keyDown)
    case .scrollWheel:
      increment(\.scroll)
      scrollX = max(-1e7, min(1e7, scrollX + event.scrollingDeltaX))
      scrollY = max(-1e7, min(1e7, scrollY + event.scrollingDeltaY))
    default: break
    }
  }
  private func label(_ text: String, frame: NSRect, parent: NSView) {
    let label = NSTextField(labelWithString: text)
    label.frame = frame
    parent.addSubview(label)
  }
  private func button(_ title: String, action: Selector, frame: NSRect, parent: NSView) {
    let button = NSButton(title: title, target: self, action: action)
    button.frame = frame
    parent.addSubview(button)
  }
  private func configure(instance: String, display: UInt32, slot: String, digits: String) throws {
    guard window == nil else { throw FixtureFailure.configured }
    let screens = NSScreen.screens
    guard screens.count <= 32,
      let screen = screens.first(where: {
        ($0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value
          == display
      })
    else { throw FixtureFailure.display }
    let available = screen.visibleFrame.insetBy(dx: 12, dy: 12)
    let width = min(560, available.width / 2 - 6)
    let height: CGFloat = 480
    guard width >= 420, available.height >= height else { throw FixtureFailure.display }
    let x = slot == "left" ? available.minX : available.maxX - width
    let rectangle = NSRect(x: x, y: available.midY - height / 2, width: width, height: height)
    let owned = FixtureWindow(
      contentRect: rectangle, styleMask: [.titled, .closable], backing: .buffered, defer: false)
    guard screen.visibleFrame.contains(owned.frame) else { throw FixtureFailure.display }
    owned.title = "Controlled native fixture \(slot) \(instance)"
    owned.isReleasedWhenClosed = false
    owned.observedEvent = { [weak self] in self?.event($0) }
    guard let content = owned.contentView else { throw FixtureFailure.display }
    label(
      "Pixel-only fixture marker", frame: NSRect(x: 16, y: 450, width: 300, height: 20),
      parent: content)
    marker.marker = digits
    marker.generation = 1
    marker.rendered = { [weak self] generation in self?.renderedMarkerGeneration = generation }
    marker.setAccessibilityElement(false)
    content.addSubview(marker)
    label("Unicode input", frame: NSRect(x: 16, y: 336, width: 150, height: 20), parent: content)
    let textScroll = NSScrollView(frame: NSRect(x: 16, y: 288, width: width - 32, height: 44))
    textScroll.hasVerticalScroller = true
    textField.frame = NSRect(x: 0, y: 0, width: width - 48, height: 44)
    textField.isRichText = false
    textField.isAutomaticQuoteSubstitutionEnabled = false
    textField.isAutomaticTextReplacementEnabled = false
    textField.isAutomaticSpellingCorrectionEnabled = false
    textField.setAccessibilityLabel("Unicode input")
    textField.delegate = self
    textField.isVerticallyResizable = true
    textField.isHorizontallyResizable = false
    textField.textContainer?.widthTracksTextView = true
    textScroll.documentView = textField
    content.addSubview(textScroll)
    let secure = NSSecureTextField(frame: NSRect(x: 16, y: 256, width: 180, height: 26))
    secure.placeholderString = "Secure test input"
    secure.setAccessibilityLabel("Secure test input")
    content.addSubview(secure)
    let disabled = NSTextField(frame: NSRect(x: 210, y: 256, width: 180, height: 26))
    disabled.placeholderString = "Disabled test input"
    disabled.setAccessibilityLabel("Disabled test input")
    disabled.isEnabled = false
    content.addSubview(disabled)
    button(
      "Toggle pixels", action: #selector(toggle),
      frame: NSRect(x: 12, y: 212, width: 132, height: 32), parent: content)
    button(
      "Open menu", action: #selector(menu), frame: NSRect(x: 150, y: 212, width: 120, height: 32),
      parent: content)
    button(
      "Open dialog", action: #selector(dialog),
      frame: NSRect(x: 276, y: 212, width: 128, height: 32), parent: content)
    let canvas = DragView(frame: NSRect(x: 16, y: 20, width: 180, height: 176))
    canvas.setAccessibilityElement(true)
    canvas.setAccessibilityRole(.group)
    canvas.setAccessibilityLabel("Drag canvas")
    canvas.completed = { [weak self] in self?.increment(\.dragCompleted) }
    content.addSubview(canvas)
    let scroll = NSScrollView(frame: NSRect(x: 212, y: 20, width: width - 228, height: 176))
    scroll.hasVerticalScroller = true
    scroll.setAccessibilityLabel("Scroll sample")
    let document = NSView(frame: NSRect(x: 0, y: 0, width: width - 245, height: 1200))
    for index in 0..<24 {
      label(
        "Fixture row \(index + 1)",
        frame: NSRect(x: 8, y: CGFloat(index * 48), width: 150, height: 24), parent: document)
    }
    scroll.documentView = document
    content.addSubview(scroll)
    instanceId = instance
    displayId = display
    window = owned
    setupGeneration = 1
    markerGeneration = 1
    owned.makeKeyAndOrderFront(nil)
    NSApp.activate(ignoringOtherApps: true)
  }
  func textDidChange(_ notification: Notification) { increment(\.textChanged) }
  func textView(
    _ textView: NSTextView, shouldChangeTextIn affectedCharRange: NSRange,
    replacementString: String?
  ) -> Bool {
    guard let replacementString else { return true }
    let length = textView.string.utf16.count
    guard affectedCharRange.location <= length,
      affectedCharRange.length <= length - affectedCharRange.location
    else { return false }
    return length - affectedCharRange.length + replacementString.utf16.count <= 4096
  }
  @objc private func toggle() {
    increment(\.buttonActivated)
    toggled.toggle()
    marker.toggled = toggled
    markerGeneration += 1
    marker.generation = markerGeneration
    marker.needsDisplay = true
  }
  @objc private func menu() {
    let menu = NSMenu(title: "Fixture menu")
    let item = NSMenuItem(
      title: "Record fixture selection", action: #selector(selected), keyEquivalent: "")
    item.target = self
    menu.addItem(item)
    if let content = window?.contentView {
      menu.popUp(positioning: nil, at: NSPoint(x: 150, y: 212), in: content)
    }
  }
  @objc private func selected() { increment(\.menuSelected) }
  @objc private func dialog() {
    guard let window, !dialogOpen else { return }
    increment(\.dialogOpened)
    dialogOpen = true
    let alert = NSAlert()
    alert.messageText = "Controlled fixture dialog"
    alert.informativeText = "Only this fixture is modified."
    alert.addButton(withTitle: "Record fixture confirmation")
    alert.beginSheetModal(for: window) { [weak self] _ in
      guard let self else { return }
      self.increment(\.dialogClosed)
      self.dialogOpen = false
    }
  }
  private func status() throws -> FixtureStatus {
    guard let instanceId, let window, let bundleId = Bundle.main.bundleIdentifier else {
      throw FixtureFailure.unconfigured
    }
    return FixtureStatus(
      instanceId: instanceId, bundleId: bundleId, processId: getpid(),
      windowNumber: window.windowNumber,
      configuredDisplayId: displayId, setupGeneration: setupGeneration,
      markerGeneration: markerGeneration, renderedMarkerGeneration: renderedMarkerGeneration,
      eventSequence: eventSequence, counts: counts, text: textField.string, toggled: toggled,
      dialogOpen: dialogOpen,
      truncated: truncated, windowVisible: window.isVisible, applicationActive: NSApp.isActive,
      scrollX: scrollX, scrollY: scrollY)
  }
  func handle(_ request: FixtureRequest, cutoff: UInt64) {
    let watchdog = DispatchWorkItem { Darwin._exit(2) }
    DispatchQueue.global().asyncAfter(
      deadline: .now() + .milliseconds(request.timeoutMs), execute: watchdog)
    defer { watchdog.cancel() }
    do {
      guard DispatchTime.now().uptimeNanoseconds < cutoff else { throw FixtureFailure.expired }
      switch request.command {
      case .configure(let id, let display, let slot, let digits):
        try configure(instance: id, display: display, slot: slot, digits: digits)
      case .status: break
      case .marker(let value):
        guard window != nil else { throw FixtureFailure.unconfigured }
        marker.marker = value
        markerGeneration += 1
        marker.generation = markerGeneration
        setupGeneration += 1
        marker.needsDisplay = true
      case .stop:
        try writeFixtureReply(request.requestId, status: try status())
        NSApp.terminate(nil)
        return
      }
      guard DispatchTime.now().uptimeNanoseconds < cutoff else { throw FixtureFailure.expired }
      try writeFixtureReply(request.requestId, status: try status())
    } catch {
      do { try writeFixtureReply(request.requestId, code: "FIXTURE_REFUSED") } catch {
        Darwin._exit(2)
      }
    }
  }
}

private func runCodecCheck() throws {
  let base =
    "\"version\":\"\(fixtureVersion)\",\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"timeoutMs\":1000"
  let valid = [
    "{\(base),\"operation\":\"status\"}", "{\(base),\"operation\":\"stop\"}",
    "{\(base),\"operation\":\"set_marker\",\"marker\":\"123456\"}",
    "{\(base),\"operation\":\"configure\",\"instanceId\":\"22222222-2222-2222-2222-222222222222\",\"displayId\":1,\"slot\":\"left\",\"marker\":\"123456\"}",
  ]
  let invalid = [
    "{\(base),\"operation\":\"status\",\"extra\":true}",
    "{\(base),\"operation\":\"set_marker\",\"marker\":\"１２３４５６\"}",
    "{\(base),\"operation\":\"status\",\"oper\\u0061tion\":\"stop\"}",
    valid[0].replacingOccurrences(of: "1000", with: "true"),
    valid[3].replacingOccurrences(of: "\"displayId\":1", with: "\"displayId\":0"),
    valid[3].replacingOccurrences(of: "\"left\"", with: "\"unknown\""),
  ]
  for value in valid { _ = try decodeFixtureRequest(Data(value.utf8)) }
  for value in invalid {
    do { _ = try decodeFixtureRequest(Data(value.utf8)) } catch { continue }
    throw FixtureFailure.invalid
  }
  struct Result: Encodable {
    let version = "computer-control-native/fixture-self-test/1", ok = true
    let cases: Int
  }
  try FileHandle.standardOutput.write(
    contentsOf: JSONEncoder().encode(Result(cases: valid.count + invalid.count)))
  try FileHandle.standardOutput.write(contentsOf: Data([10]))
}

@main private struct ControlledNativeFixture {
  @MainActor static func main() {
    if CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--check-codec" {
      do {
        try runCodecCheck()
        return
      } catch { Darwin._exit(1) }
    }
    guard CommandLine.arguments.count == 1 else { Darwin._exit(1) }
    let application = NSApplication.shared
    application.setActivationPolicy(.regular)
    let delegate = FixtureApplication()
    application.delegate = delegate
    DispatchQueue.global().async {
      var packet = Data()
      while true {
        do {
          guard let byte = try FileHandle.standardInput.read(upToCount: 1), !byte.isEmpty else {
            Darwin._exit(0)
          }
          if byte[0] != 10 {
            guard packet.count < requestLimit else { Darwin._exit(1) }
            packet.append(byte)
            continue
          }
          let request = try decodeFixtureRequest(packet)
          packet.removeAll(keepingCapacity: true)
          let cutoff = DispatchTime.now().uptimeNanoseconds + UInt64(request.timeoutMs) * 1_000_000
          let completed = DispatchSemaphore(value: 0)
          DispatchQueue.main.async {
            delegate.handle(request, cutoff: cutoff)
            completed.signal()
          }
          guard completed.wait(timeout: .now() + .milliseconds(request.timeoutMs)) == .success
          else { Darwin._exit(2) }
        } catch { Darwin._exit(1) }
      }
    }
    application.run()
    withExtendedLifetime(delegate) {}
  }
}
