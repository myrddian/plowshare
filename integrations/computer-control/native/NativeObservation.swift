import AppKit
import ApplicationServices
import Carbon
import CryptoKit
import Darwin
import Foundation
import ImageIO
import ScreenCaptureKit
import UniformTypeIdentifiers

struct BrokerPermissions: Encodable, Equatable {
  let screenCapture: Bool, accessibility: Bool, eventPosting: Bool, eventListening: Bool
  static func current() -> BrokerPermissions {
    BrokerPermissions(
      screenCapture: CGPreflightScreenCaptureAccess(), accessibility: AXIsProcessTrusted(),
      eventPosting: CGPreflightPostEventAccess(), eventListening: CGPreflightListenEventAccess())
  }
}
struct BrokerBounds: Encodable, Equatable {
  let x: Double, y: Double, width: Double, height: Double
  init(_ rect: CGRect) throws {
    guard
      [rect.origin.x, rect.origin.y, rect.width, rect.height].allSatisfy({
        $0.isFinite && abs($0) <= 1e7
      }), rect.width > 0, rect.height > 0
    else { throw BrokerFailure.observation }
    x = rect.origin.x
    y = rect.origin.y
    width = rect.width
    height = rect.height
  }
  var rect: CGRect { CGRect(x: x, y: y, width: width, height: height) }
}
struct BrokerDisplay: Encodable, Equatable {
  let displayId: String, bounds: BrokerBounds, scaleX: Double, scaleY: Double, rotation: Int
}
/// Mode dimensions distinguish logical display points from backing pixels.
/// CGDisplayPixelsWide/High can report logical dimensions after AppKit registers
/// a Retina process; using them would undercount the capture allocation and
/// misidentify the backing scale. One current mode snapshot supplies both units.
struct NativeDisplayPixels {
  let width: Int, height: Int, scaleX: Double, scaleY: Double
}
func nativeDisplayPixels(
  bounds: BrokerBounds, rotation: Int, logicalWidth: Int, logicalHeight: Int,
  pixelWidth: Int, pixelHeight: Int
) throws -> NativeDisplayPixels {
  guard [0, 90, 180, 270].contains(rotation), logicalWidth > 0, logicalHeight > 0,
    logicalWidth <= 16384, logicalHeight <= 16384,
    pixelWidth > 0, pixelHeight > 0, pixelWidth <= 16384, pixelHeight <= 16384,
    pixelWidth * pixelHeight <= 33_554_432
  else { throw BrokerFailure.unsupported }
  let width: Int
  let height: Int
  if bounds.width == Double(logicalWidth), bounds.height == Double(logicalHeight) {
    width = pixelWidth
    height = pixelHeight
  } else if [90, 270].contains(rotation), bounds.width == Double(logicalHeight),
    bounds.height == Double(logicalWidth)
  {
    width = pixelHeight
    height = pixelWidth
  } else {
    // No DPI or orientation guessing if the current mode and Quartz geometry
    // disagree. A later fresh read may succeed; no capture is attempted here.
    throw BrokerFailure.unsupported
  }
  let scaleX = Double(width) / bounds.width
  let scaleY = Double(height) / bounds.height
  guard scaleX.isFinite, scaleY.isFinite, scaleX > 0, scaleY > 0,
    scaleX <= 16, scaleY <= 16
  else { throw BrokerFailure.unsupported }
  return NativeDisplayPixels(width: width, height: height, scaleX: scaleX, scaleY: scaleY)
}
func nativeDisplayPixelMemoryCheck() throws -> Int {
  let retina = try BrokerBounds(CGRect(x: 0, y: 0, width: 1920, height: 1080))
  let unit = try BrokerBounds(CGRect(x: -4096, y: -44, width: 4096, height: 2160))
  let rotated = try BrokerBounds(CGRect(x: 0, y: 0, width: 1080, height: 1920))
  let cases: [(BrokerBounds, Int, Int, Int, Int, Int, Bool, Double)] = [
    (retina, 0, 1920, 1080, 3840, 2160, true, 2),
    (unit, 0, 4096, 2160, 4096, 2160, true, 1),
    (rotated, 90, 1920, 1080, 3840, 2160, true, 2),
    (rotated, 270, 1080, 1920, 2160, 3840, true, 2),
    (retina, 0, 1600, 900, 3200, 1800, false, 0),
    (rotated, 0, 1920, 1080, 3840, 2160, false, 0),
    (retina, 0, 1920, 1080, 16385, 2160, false, 0),
    (retina, 0, 1920, 1080, 16384, 16384, false, 0),
    (retina, 0, 1920, 1080, 0, 2160, false, 0),
    (retina, 45, 1920, 1080, 3840, 2160, false, 0),
  ]
  for (bounds, rotation, logicalWidth, logicalHeight, pixelWidth, pixelHeight, accepted, scale)
    in cases
  {
    do {
      let value = try nativeDisplayPixels(
        bounds: bounds, rotation: rotation, logicalWidth: logicalWidth,
        logicalHeight: logicalHeight, pixelWidth: pixelWidth, pixelHeight: pixelHeight)
      guard accepted, value.scaleX == scale, value.scaleY == scale,
        Double(value.width) == bounds.width * scale,
        Double(value.height) == bounds.height * scale
      else { throw BrokerFailure.invalid }
    } catch BrokerFailure.unsupported {
      guard !accepted else { throw BrokerFailure.invalid }
    }
  }
  return cases.count
}

struct BrokerState: Encodable {
  let guardValue: BrokerGuard
  let availability: String
  let permissions: BrokerPermissions
  let secureInput: String
  let interference: String
  let focusKnown: Bool
  let focusedElementId: String?
  let display: BrokerDisplay
  let applicationLabel: String?, bundleId: String?
  func encode(to encoder: Encoder) throws {
    try guardValue.encode(to: encoder)
    var c = encoder.container(keyedBy: Keys.self)
    try c.encode(availability, forKey: .availability)
    try c.encode(permissions, forKey: .permissions)
    try c.encode(secureInput, forKey: .secureInput)
    try c.encode(interference, forKey: .interference)
    try c.encode(focusKnown, forKey: .focusKnown)
    try c.encode(focusedElementId, forKey: .focusedElementId)
    try c.encode(display, forKey: .display)
    try c.encode(applicationLabel, forKey: .applicationLabel)
    try c.encode(bundleId, forKey: .bundleId)
  }
  private enum Keys: String, CodingKey {
    case availability, permissions, secureInput, interference, focusKnown, focusedElementId,
      display, applicationLabel, bundleId
  }
}
struct BrokerNode: Encodable {
  let elementId: String, parentElementId: String?, role: String, subrole: String?, name: String
  let editable: Bool, focused: Bool, enabled: Bool, secure: Bool
  let bounds: BrokerBounds?
  enum CodingKeys: String, CodingKey {
    case elementId, parentElementId, role, subrole, name, editable, focused, enabled, secure, bounds
  }
  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(elementId, forKey: .elementId)
    try c.encode(parentElementId, forKey: .parentElementId)
    try c.encode(role, forKey: .role)
    try c.encode(subrole, forKey: .subrole)
    try c.encode(name, forKey: .name)
    try c.encode(editable, forKey: .editable)
    try c.encode(focused, forKey: .focused)
    try c.encode(enabled, forKey: .enabled)
    try c.encode(secure, forKey: .secure)
    try c.encode(bounds, forKey: .bounds)
  }
}
struct BrokerSnapshot: Encodable {
  let snapshotId: String, rootElementId: String?, guardValue: BrokerGuard, capturedAt: String
  let kind = "ax", approximate = true, nodes: [BrokerNode], truncated: Bool
  private enum Keys: String, CodingKey {
    case snapshotId, rootElementId, `guard`, capturedAt, kind, approximate, nodes, truncated
  }
  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: Keys.self)
    try c.encode(snapshotId, forKey: .snapshotId)
    try c.encode(rootElementId, forKey: .rootElementId)
    try c.encode(guardValue, forKey: .guard)
    try c.encode(capturedAt, forKey: .capturedAt)
    try c.encode(kind, forKey: .kind)
    try c.encode(approximate, forKey: .approximate)
    try c.encode(nodes, forKey: .nodes)
    try c.encode(truncated, forKey: .truncated)
  }
}
struct BrokerWindow: Encodable {
  let surfaceId: String, applicationId: String, windowId: String, captureSurfaceId: String,
    applicationLabel: String, bundleId: String, title: String
  let bounds: BrokerBounds, focused: Bool, state: String
}
struct BrokerWindows: Encodable {
  let windowsId: String, capturedAt: String, windows: [BrokerWindow], truncated: Bool
}
struct BrokerTransform: Encodable {
  let a: Double, b = 0.0, c = 0.0, d: Double, e: Double, f: Double
}
struct BrokerImage: Encodable {
  let bytesBase64: String, mimeType = "image/png", width: Int, height: Int
  let transform: BrokerTransform, capturedAt: String, layoutGeneration: String
}
struct BrokerObservation: Encodable {
  let image: BrokerImage, state: BrokerState, snapshot: BrokerSnapshot?
  private enum Keys: String, CodingKey { case image, state, snapshot }
  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: Keys.self)
    try c.encode(image, forKey: .image)
    try c.encode(state, forKey: .state)
    try c.encode(snapshot, forKey: .snapshot)
  }
}
func brokerTime() -> String { ISO8601DateFormatter().string(from: Date()) }
func brokerHash(_ text: String) -> String {
  SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
}
func brokerLabel(_ text: String, maximum: Int = 256) -> String {
  var result = ""
  var units = 0
  for scalar in text.unicodeScalars where scalar.value != 0 {
    let addition = scalar.value > 0xffff ? 2 : 1
    if units + addition > maximum { break }
    result.unicodeScalars.append(scalar)
    units += addition
  }
  return result
}

private struct AXApplication {
  let id: String, pid: pid_t, launchDate: Date, bundleId: String, label: String,
    element: AXUIElement
}
private struct AXWindow {
  let id: String, application: AXApplication, element: AXUIElement
}
private struct AXBinding { let element: AXUIElement, application: AXApplication, window: AXWindow }

struct NativeAXInputValidation: NativeInputValidation {
  let inspector: NativeInspector, state: BrokerState
  func beforePost(_ event: CGEvent, budget: NativeBudget) throws {
    try inspector.revalidatePosting(event, state: state, budget: budget)
  }
}
enum NativePreparedInput {
  case move(CGPoint)
  case click(CGPoint, CGMouseButton, [String], Int)
  case drag([BrokerDragPoint], CGMouseButton, [String])
  case type(String, String)
  case key(CGKeyCode, [String])
  case scroll(CGPoint, Int, Int, String)
}
func nativeKeyCode(_ key: String) throws -> CGKeyCode {
  let codes: [String: CGKeyCode] = [
    "Enter": 36, "Tab": 48, "Escape": 53, "Backspace": 51, "Delete": 117, "Home": 115, "End": 119,
    "PageUp": 116, "PageDown": 121, "ArrowUp": 126, "ArrowDown": 125, "ArrowLeft": 123,
    "ArrowRight": 124, "Space": 49, "a": 0, "z": 6,
  ]
  guard let code = codes[key] else { throw BrokerFailure.unsupported }
  return code
}

/// Bounded AX reads use the exact object's remaining messaging timeout before
/// every RPC. No unbounded AX children/windows arrays or runningApplications/CG
/// window list are materialized. Discovery examines a fixed <=256 PID prefix;
/// truncation explicitly means omitted applications/windows may still exist.
final class NativeInspector {
  let configuration: BrokerConfiguration
  private var readiness: NativeReadiness?
  func bindReadiness(_ readiness: NativeReadiness) { self.readiness = readiness }
  private var applications: [AXApplication] = []
  private var windows: [AXWindow] = []
  private var windowsId = UUID().uuidString.lowercased()
  private var snapshotId = UUID().uuidString.lowercased()
  private var bindings: [String: AXBinding] = [:]
  private var focusIdentity: AXUIElement?
  private var focusId: String?
  private var focusGeneration = UUID().uuidString.lowercased()
  private let interactionGeneration = UUID().uuidString.lowercased()
  init(configuration: BrokerConfiguration) { self.configuration = configuration }
  private func timeout(_ element: AXUIElement, _ budget: NativeBudget) throws {
    let remaining = try budget.remaining()
    guard AXUIElementSetMessagingTimeout(element, Float(max(0.001, min(remaining, 2)))) == .success
    else { throw BrokerFailure.ax }
    try budget.check()
  }
  private func attribute(_ element: AXUIElement, _ name: CFString, _ budget: NativeBudget) throws
    -> CFTypeRef?
  {
    try timeout(element, budget)
    var value: CFTypeRef?
    let result = AXUIElementCopyAttributeValue(element, name, &value)
    try budget.check()
    if [.noValue, .attributeUnsupported].contains(result) { return nil }
    guard result == .success else { throw BrokerFailure.ax }
    return value
  }
  private func axElement(_ value: CFTypeRef?) -> AXUIElement? {
    guard let value, CFGetTypeID(value) == AXUIElementGetTypeID() else { return nil }
    // CF type identity above proves this narrow native cast.
    return unsafeBitCast(value, to: AXUIElement.self)
  }
  private func text(
    _ element: AXUIElement, _ name: CFString, _ budget: NativeBudget, maximum: Int = 256
  ) throws -> String? {
    guard let value = try attribute(element, name, budget),
      CFGetTypeID(value) == CFStringGetTypeID()
    else { return nil }
    return brokerLabel(value as! String, maximum: maximum)  // CFString identity validated above.
  }
  private func flag(_ element: AXUIElement, _ name: CFString, _ budget: NativeBudget) throws
    -> Bool?
  {
    guard let value = try attribute(element, name, budget),
      CFGetTypeID(value) == CFBooleanGetTypeID()
    else { return nil }
    return CFBooleanGetValue((value as! CFBoolean))  // CFBoolean identity validated above.
  }
  private func children(
    _ element: AXUIElement, _ name: CFString, maximum: Int, _ budget: NativeBudget
  ) throws -> ([AXUIElement], Bool) {
    try timeout(element, budget)
    var total: CFIndex = 0
    let countResult = AXUIElementGetAttributeValueCount(element, name, &total)
    try budget.check()
    if [.attributeUnsupported, .noValue].contains(countResult) { return ([], false) }
    guard countResult == .success, total >= 0 else { throw BrokerFailure.ax }
    let count = min(total, maximum)
    guard count > 0 else { return ([], total > 0) }
    try timeout(element, budget)
    var array: CFArray?
    guard AXUIElementCopyAttributeValues(element, name, 0, count, &array) == .success, let array,
      CFArrayGetCount(array) <= count
    else { throw BrokerFailure.ax }
    try budget.check()
    var values: [AXUIElement] = []
    for index in 0..<CFArrayGetCount(array) {
      let raw = CFArrayGetValueAtIndex(array, index)
      let value = Unmanaged<CFTypeRef>.fromOpaque(raw!).takeUnretainedValue()
      guard let child = axElement(value) else { throw BrokerFailure.ax }
      values.append(child)
    }
    return (values, total > count)
  }
  private func bounds(_ element: AXUIElement, _ budget: NativeBudget) throws -> BrokerBounds? {
    guard let positionValue = try attribute(element, kAXPositionAttribute as CFString, budget),
      let sizeValue = try attribute(element, kAXSizeAttribute as CFString, budget),
      CFGetTypeID(positionValue) == AXValueGetTypeID(), CFGetTypeID(sizeValue) == AXValueGetTypeID()
    else { return nil }
    let position = unsafeBitCast(positionValue, to: AXValue.self)
    let size = unsafeBitCast(sizeValue, to: AXValue.self)
    guard AXValueGetType(position) == .cgPoint, AXValueGetType(size) == .cgSize else { return nil }
    var point = CGPoint.zero
    var dimensions = CGSize.zero
    guard AXValueGetValue(position, .cgPoint, &point), AXValueGetValue(size, .cgSize, &dimensions),
      dimensions.width > 0, dimensions.height > 0
    else { return nil }
    return try BrokerBounds(CGRect(origin: point, size: dimensions))
  }
  private func live(_ app: AXApplication, _ budget: NativeBudget) throws -> Bool {
    try budget.check()
    guard let current = NSRunningApplication(processIdentifier: app.pid), !current.isTerminated,
      current.bundleIdentifier == app.bundleId, current.launchDate == app.launchDate
    else { return false }
    return true
  }
  func discover(_ budget: NativeBudget) throws -> BrokerWindows {
    try budget.check()
    guard AXIsProcessTrusted() else { throw BrokerFailure.denied }
    var pids = [pid_t](repeating: 0, count: 257)
    let length = pids.withUnsafeMutableBytes {
      proc_listpids(UInt32(PROC_ALL_PIDS), 0, $0.baseAddress, Int32($0.count))
    }
    guard length >= 0 else { throw BrokerFailure.ax }
    try budget.check()
    let count = min(Int(length) / MemoryLayout<pid_t>.size, pids.count)
    let selectedDisplay = try display(budget)
    let system = AXUIElementCreateSystemWide()
    let globalApp = axElement(
      try attribute(system, kAXFocusedApplicationAttribute as CFString, budget))
    var foregroundPid: pid_t = 0
    if let globalApp {
      try timeout(globalApp, budget)
      guard AXUIElementGetPid(globalApp, &foregroundPid) == .success else { throw BrokerFailure.ax }
      try budget.check()
    }
    var truncated = count > 256
    var discoveredApps: [AXApplication] = []
    var discoveredWindows: [AXWindow] = []
    var projected: [BrokerWindow] = []
    for pid in pids.prefix(min(count, 256)) where pid > 0 && pid != getpid() {
      try budget.check()
      guard let process = NSRunningApplication(processIdentifier: pid), !process.isTerminated,
        let bundle = process.bundleIdentifier, configuration.allowedBundleIds.contains(bundle)
      else { continue }
      guard let date = process.launchDate else {
        truncated = true
        continue
      }
      let old = applications.first {
        $0.pid == pid && $0.launchDate == date && $0.bundleId == bundle
      }
      let app =
        old
        ?? AXApplication(
          id: UUID().uuidString.lowercased(), pid: pid, launchDate: date, bundleId: bundle,
          label: brokerLabel(process.localizedName ?? bundle),
          element: AXUIElementCreateApplication(pid))
      discoveredApps.append(app)
      let remaining = configuration.maxWindows - projected.count
      let (elements, omitted) = try children(
        app.element, kAXWindowsAttribute as CFString, maximum: remaining, budget)
      truncated = truncated || omitted
      let focusedWindow = axElement(
        try attribute(app.element, kAXFocusedWindowAttribute as CFString, budget))
      for element in elements {
        try budget.check()
        guard let rectangle = try bounds(element, budget) else {
          truncated = true
          continue
        }
        let existing = windows.first { $0.application.id == app.id && CFEqual($0.element, element) }
        let window =
          existing
          ?? AXWindow(id: UUID().uuidString.lowercased(), application: app, element: element)
        discoveredWindows.append(window)
        projected.append(
          BrokerWindow(
            surfaceId: window.id, applicationId: app.id, windowId: window.id,
            captureSurfaceId: configuration.surfaceId, applicationLabel: app.label,
            bundleId: bundle, title: try text(element, kAXTitleAttribute as CFString, budget) ?? "",
            bounds: rectangle,
            focused: pid == foregroundPid && (focusedWindow.map { CFEqual($0, element) } ?? false),
            state: selectedDisplay.bounds.rect.contains(rectangle.rect)
              ? "available" : "unavailable"))
      }
    }
    applications = discoveredApps
    windows = discoveredWindows
    windowsId = UUID().uuidString.lowercased()
    return BrokerWindows(
      windowsId: windowsId, capturedAt: brokerTime(), windows: projected, truncated: truncated)
  }
  /// Acquisition establishes exclusion only and runs before AppKit registration.
  /// It does not promise capture geometry, permissions, or input readiness.
  func validateDisplayPresence(_ budget: NativeBudget) throws {
    try budget.check()
    guard CGDisplayIsActive(configuration.displayId) != 0 else { throw BrokerFailure.gone }
    _ = try BrokerBounds(CGDisplayBounds(configuration.displayId))
    try budget.check()
  }
  func display(_ budget: NativeBudget) throws -> BrokerDisplay {
    try validateDisplayPresence(budget)
    let id = configuration.displayId
    let rectangle = try BrokerBounds(CGDisplayBounds(id))
    let rotation = CGDisplayRotation(id)
    guard [0.0, 90.0, 180.0, 270.0].contains(rotation),
      let mode = CGDisplayCopyDisplayMode(id)
    else { throw BrokerFailure.unsupported }
    try budget.check()
    // Bound actual backing pixels BEFORE ScreenCaptureKit allocation. This is
    // a single current-mode snapshot, not a physical-monitor/native-resolution
    // estimate or an unbounded display-mode enumeration.
    let pixels = try nativeDisplayPixels(
      bounds: rectangle, rotation: Int(rotation), logicalWidth: mode.width,
      logicalHeight: mode.height, pixelWidth: mode.pixelWidth, pixelHeight: mode.pixelHeight)
    try budget.check()
    return BrokerDisplay(
      displayId: configuration.surfaceId, bounds: rectangle, scaleX: pixels.scaleX,
      scaleY: pixels.scaleY, rotation: Int(rotation))
  }
  private func focused(_ budget: NativeBudget) throws -> (
    AXApplication?, AXWindow?, AXUIElement?, Bool
  ) {
    guard AXIsProcessTrusted() else { return (nil, nil, nil, false) }
    let system = AXUIElementCreateSystemWide()
    guard
      let appElement = axElement(
        try attribute(system, kAXFocusedApplicationAttribute as CFString, budget))
    else { return (nil, nil, nil, false) }
    var pid: pid_t = 0
    try budget.check()
    guard AXUIElementGetPid(appElement, &pid) == .success else { throw BrokerFailure.ax }
    try budget.check()
    guard let app = applications.first(where: { $0.pid == pid }), try live(app, budget) else {
      return (nil, nil, nil, false)
    }
    let focusedWindow = axElement(
      try attribute(app.element, kAXFocusedWindowAttribute as CFString, budget))
    try timeout(app.element, budget)
    var focusValue: CFTypeRef?
    let focusStatus = AXUIElementCopyAttributeValue(
      app.element, kAXFocusedUIElementAttribute as CFString, &focusValue)
    try budget.check()
    guard [.success, .noValue, .attributeUnsupported].contains(focusStatus) else {
      throw BrokerFailure.ax
    }
    let focusedElement = axElement(focusValue)
    let focusKnown = focusStatus == .noValue || (focusStatus == .success && focusedElement != nil)
    let window = windows.first { candidate in
      candidate.application.id == app.id
        && focusedWindow.map { CFEqual($0, candidate.element) } == true
    }
    return (app, window, focusedElement, focusKnown)
  }
  private func focus(_ element: AXUIElement?) -> String? {
    let same =
      focusIdentity == nil && element == nil
      || (focusIdentity != nil && element != nil && CFEqual(focusIdentity!, element!))
    if !same {
      focusGeneration = UUID().uuidString.lowercased()
      focusId = element == nil ? nil : UUID().uuidString.lowercased()
      focusIdentity = element
    }
    return focusId
  }
  func state(surface: String, _ budget: NativeBudget) throws -> BrokerState {
    let geometry = try display(budget)
    let permission = BrokerPermissions.current()
    let (app, window, element, known) = try focused(budget)
    let focusedId = focus(element)
    let windowLayout: String
    if let window, let rectangle = try bounds(window.element, budget) {
      windowLayout = "\(window.id):\(rectangle)"
    } else {
      windowLayout = "unknown-window"
    }
    // Re-read exact retained AX objects, not names/selectors, to detect changed
    // observed geometry/semantics before another action or image is admitted.
    var elementLayout = ""
    for (id, binding) in bindings.sorted(by: { $0.key < $1.key }) {
      guard try live(binding.application, budget) else { throw BrokerFailure.gone }
      let subrole = try text(binding.element, kAXSubroleAttribute as CFString, budget)
      let name =
        subrole == "AXSecureTextField"
        ? ""
        : (try text(binding.element, kAXTitleAttribute as CFString, budget) ?? text(
          binding.element, kAXDescriptionAttribute as CFString, budget) ?? "")
      elementLayout +=
        "\(name):\(id):\(String(describing: try bounds(binding.element, budget))):\(try text(binding.element, kAXRoleAttribute as CFString, budget) ?? ""):\(try text(binding.element, kAXSubroleAttribute as CFString, budget) ?? ""):\(try flag(binding.element, kAXEnabledAttribute as CFString, budget) ?? false);"
    }
    let capability = readiness?.snapshot()
    let grantedApplication =
      app.map {
        readiness?.applicationGranted(
          NativeArmingApplication(
            id: $0.id, pid: $0.pid, launchDate: $0.launchDate, bundleId: $0.bundleId)) == true
      } ?? false
    let secureInput = IsSecureEventInputEnabled() ? "active" : "clear"
    let guardValue = BrokerGuard(
      surfaceId: surface, applicationId: app?.id, windowId: window?.id,
      layoutGeneration: brokerHash("\(geometry):\(windowLayout):\(elementLayout)"),
      focusGeneration: focusGeneration,
      displayGeneration: brokerHash("\(geometry)"),
      interactionGeneration: capability?.interactionGeneration ?? interactionGeneration)
    return BrokerState(
      guardValue: guardValue,
      availability: capability?.ready == true && known && grantedApplication && window != nil
        && permission.screenCapture && permission.accessibility && permission.eventPosting
        && permission.eventListening && secureInput == "clear" ? "ready" : "unknown",
      permissions: permission,
      secureInput: secureInput,
      interference: capability?.interference ?? "unknown", focusKnown: known,
      focusedElementId: focusedId, display: geometry, applicationLabel: app?.label,
      bundleId: app?.bundleId)
  }
  func snapshot(
    surface: String, root: BrokerElementTarget?, state: BrokerState, _ budget: NativeBudget
  ) throws -> BrokerSnapshot {
    guard AXIsProcessTrusted() else { throw BrokerFailure.denied }
    let binding: AXBinding?
    if let root {
      guard root.snapshotId == snapshotId, let old = bindings[root.elementId],
        try live(old.application, budget), old.window.id == state.guardValue.windowId
      else { throw BrokerFailure.stale }
      binding = old
    } else if let window = windows.first(where: { $0.id == state.guardValue.windowId }) {
      binding = AXBinding(element: window.element, application: window.application, window: window)
    } else {
      throw BrokerFailure.ax
    }
    guard let binding else { throw BrokerFailure.ax }
    var queue: [(AXBinding, String?)] = [(binding, nil)]
    var visited: [AXUIElement] = []
    var nodes: [BrokerNode] = []
    var newBindings: [String: AXBinding] = [:]
    var truncated = false
    while !queue.isEmpty && visited.count < configuration.maxVisitedNodes
      && nodes.count < configuration.maxNodes
    {
      try budget.check()
      let (current, parent) = queue.removeFirst()
      if visited.contains(where: { CFEqual($0, current.element) }) { continue }
      visited.append(current.element)
      guard try live(current.application, budget) else { throw BrokerFailure.gone }
      let role =
        try text(current.element, kAXRoleAttribute as CFString, budget, maximum: 64) ?? "AXUnknown"
      let subrole = try text(current.element, kAXSubroleAttribute as CFString, budget, maximum: 64)
      let secure = subrole == "AXSecureTextField"
      let isFocused = focusIdentity.map { CFEqual($0, current.element) } ?? false
      let id =
        isFocused ? (focusId ?? UUID().uuidString.lowercased()) : UUID().uuidString.lowercased()
      let enabled = try flag(current.element, kAXEnabledAttribute as CFString, budget) ?? false
      let editable = !secure && ["AXTextField", "AXTextArea", "AXComboBox"].contains(role)
      // Titles/descriptions only. kAXValue and selected text are never read.
      let name =
        secure
        ? ""
        : (try text(current.element, kAXTitleAttribute as CFString, budget) ?? text(
          current.element, kAXDescriptionAttribute as CFString, budget) ?? "")
      nodes.append(
        BrokerNode(
          elementId: id, parentElementId: parent, role: role, subrole: subrole, name: name,
          editable: editable, focused: isFocused, enabled: enabled, secure: secure,
          bounds: try bounds(current.element, budget)))
      newBindings[id] = current
      let remaining = configuration.maxVisitedNodes - visited.count - queue.count
      let (children, omitted) = try children(
        current.element, kAXChildrenAttribute as CFString, maximum: max(0, remaining), budget)
      truncated = truncated || omitted
      queue.append(
        contentsOf: children.map {
          (AXBinding(element: $0, application: current.application, window: current.window), id)
        })
    }
    truncated =
      truncated || !queue.isEmpty
      || (focusId != nil && !nodes.contains(where: { $0.elementId == focusId }))
    bindings = newBindings
    snapshotId = UUID().uuidString.lowercased()
    return BrokerSnapshot(
      snapshotId: snapshotId, rootElementId: root == nil ? nil : nodes.first?.elementId,
      guardValue: state.guardValue, capturedAt: brokerTime(), nodes: nodes, truncated: truncated)
  }
  func revalidatePosting(_ event: CGEvent, state expected: BrokerState, budget: NativeBudget) throws
  {
    // Owned ups must remain releasable even when the original view/focus moved.
    if [.keyUp, .leftMouseUp, .rightMouseUp, .otherMouseUp].contains(event.type) { return }
    let current = try state(surface: expected.guardValue.surfaceId, budget)
    guard current.guardValue == expected.guardValue, current.focusKnown,
      current.permissions == expected.permissions
    else { throw BrokerFailure.stale }
    if [
      .mouseMoved, .leftMouseDown, .rightMouseDown, .otherMouseDown, .leftMouseDragged,
      .rightMouseDragged, .otherMouseDragged, .scrollWheel,
    ].contains(event.type) {
      _ = try hit(event.location, state: current, budget: budget)
    } else if let focusIdentity,
      let window = windows.first(where: { $0.id == current.guardValue.windowId })
    {
      _ = try safeAncestors(focusIdentity, window: window, budget: budget)
    }
    try budget.check()
  }
  private func exact(_ target: BrokerElementTarget, state: BrokerState, budget: NativeBudget) throws
    -> AXBinding
  {
    guard target.snapshotId == snapshotId, let binding = bindings[target.elementId],
      binding.application.id == state.guardValue.applicationId,
      binding.window.id == state.guardValue.windowId,
      try live(binding.application, budget)
    else { throw BrokerFailure.stale }
    _ = try attribute(binding.element, kAXRoleAttribute as CFString, budget)
    return binding
  }
  private func safeAncestors(_ element: AXUIElement, window: AXWindow, budget: NativeBudget) throws
    -> [AXUIElement]
  {
    var path: [AXUIElement] = []
    var current: AXUIElement? = element
    for _ in 0..<32 {
      try budget.check()
      guard let element = current else { throw BrokerFailure.stale }
      guard !path.contains(where: { CFEqual($0, element) }) else { throw BrokerFailure.stale }
      try timeout(element, budget)
      var pid: pid_t = 0
      guard AXUIElementGetPid(element, &pid) == .success, pid == window.application.pid else {
        throw BrokerFailure.stale
      }
      let subrole = try text(element, kAXSubroleAttribute as CFString, budget)
      guard subrole != "AXSecureTextField" else { throw BrokerFailure.sensitive }
      path.append(element)
      if CFEqual(element, window.element) { return path }
      current = axElement(try attribute(element, kAXParentAttribute as CFString, budget))
    }
    throw BrokerFailure.stale
  }
  private func hit(_ point: CGPoint, state: BrokerState, budget: NativeBudget) throws
    -> [AXUIElement]
  {
    guard state.display.bounds.rect.contains(point),
      let window = windows.first(where: { $0.id == state.guardValue.windowId }),
      window.application.id == state.guardValue.applicationId, try live(window.application, budget)
    else { throw BrokerFailure.stale }
    let system = AXUIElementCreateSystemWide()
    try timeout(system, budget)
    var hit: AXUIElement?
    guard
      AXUIElementCopyElementAtPosition(system, Float(point.x), Float(point.y), &hit) == .success,
      let hit
    else { throw BrokerFailure.ax }
    try budget.check()
    return try safeAncestors(hit, window: window, budget: budget)
  }
  private func point(_ target: BrokerTarget, state: BrokerState, budget: NativeBudget) throws
    -> CGPoint
  {
    switch target {
    case .point(let x, let y):
      let point = CGPoint(x: x, y: y)
      _ = try hit(point, state: state, budget: budget)
      return point
    case .element(let ref):
      let binding = try exact(ref, state: state, budget: budget)
      guard let rectangle = try bounds(binding.element, budget),
        try flag(binding.element, kAXEnabledAttribute as CFString, budget) == true
      else { throw BrokerFailure.stale }
      let point = CGPoint(
        x: rectangle.x + rectangle.width / 2, y: rectangle.y + rectangle.height / 2)
      let path = try hit(point, state: state, budget: budget)
      guard path.contains(where: { CFEqual($0, binding.element) }) else {
        throw BrokerFailure.stale
      }
      return point
    }
  }
  private func editable(_ ref: BrokerElementTarget, state: BrokerState, budget: NativeBudget) throws
  {
    let binding = try exact(ref, state: state, budget: budget)
    guard state.focusKnown, let focusIdentity, CFEqual(focusIdentity, binding.element) else {
      throw BrokerFailure.focus
    }
    _ = try point(.element(ref), state: state, budget: budget)
    let role = try text(binding.element, kAXRoleAttribute as CFString, budget)
    guard ["AXTextField", "AXTextArea", "AXComboBox"].contains(role ?? "") else {
      throw BrokerFailure.notEditable
    }
    try timeout(binding.element, budget)
    var writable = DarwinBoolean(false)
    guard
      AXUIElementIsAttributeSettable(binding.element, kAXValueAttribute as CFString, &writable)
        == .success, writable.boolValue
    else { throw BrokerFailure.notEditable }
    try budget.check()
  }
  func prepare(_ action: BrokerAction, state: BrokerState, budget: NativeBudget) throws
    -> NativePreparedInput
  {
    guard let selected = windows.first(where: { $0.id == state.guardValue.windowId }),
      let rectangle = try bounds(selected.element, budget),
      state.display.bounds.rect.contains(rectangle.rect), selected.application.pid != getpid()
    else { throw BrokerFailure.notAllowed }
    guard state.focusKnown, state.guardValue.applicationId != nil, state.guardValue.windowId != nil,
      state.bundleId.map(configuration.allowedBundleIds.contains) == true
    else { throw BrokerFailure.focus }
    func button(_ value: String) -> CGMouseButton {
      value == "left" ? .left : value == "right" ? .right : .center
    }
    switch action {
    case .move(let target): return .move(try point(target, state: state, budget: budget))
    case .click(let target, let b, let mods, let count):
      return .click(try point(target, state: state, budget: budget), button(b), mods, count)
    case .drag(let points, let b, let mods):
      for item in points {
        _ = try hit(CGPoint(x: item.x, y: item.y), state: state, budget: budget)
      }
      return .drag(points, button(b), mods)
    case .type(let ref, let mode, let text):
      try editable(ref, state: state, budget: budget)
      return .type(text, mode)
    case .key(let ref, let key, let mods):
      if let ref {
        let binding = try exact(ref, state: state, budget: budget)
        guard let focusIdentity, CFEqual(focusIdentity, binding.element) else {
          throw BrokerFailure.focus
        }
        _ = try point(.element(ref), state: state, budget: budget)
      } else {
        guard focusIdentity == nil else { throw BrokerFailure.focus }
      }
      return .key(try nativeKeyCode(key), mods)
    case .scroll(let target, let dx, let dy, let units):
      return .scroll(try point(target, state: state, budget: budget), dx, dy, units)
    }
  }
  func focusWindow(listId: String, surface: String, budget: NativeBudget) async throws {
    guard listId == windowsId, let window = windows.first(where: { $0.id == surface }),
      try live(window.application, budget),
      let app = NSRunningApplication(processIdentifier: window.application.pid)
    else { throw BrokerFailure.stale }
    guard let rectangle = try bounds(window.element, budget),
      try display(budget).bounds.rect.contains(rectangle.rect)
    else { throw BrokerFailure.notAllowed }
    try budget.check()
    guard app.activate(options: []) else { throw BrokerFailure.input }
    try budget.check()
    try timeout(window.element, budget)
    guard AXUIElementPerformAction(window.element, kAXRaiseAction as CFString) == .success else {
      throw BrokerFailure.input
    }
    // AppKit YES means the activation request was sent, not completed. Never
    // replay activate/raise. Confirm the exact global AX app/window incarnation
    // with fresh native reads before treating this requested transition as quiet.
    while true {
      try budget.check()
      let (app, focusedWindow, _, _) = try focused(budget)
      let sameProcess = try live(window.application, budget)
      if app?.id == window.application.id && focusedWindow?.id == window.id && sameProcess {
        return
      }
      try await budget.sleep(milliseconds: 5)
    }
  }
  func armingContext(_ budget: NativeBudget) throws -> NativeArmingContext {
    _ = try discover(budget)
    let geometry = try display(budget)
    var targets: [NativeConsentTarget] = []
    for window in windows where window.application.pid != getpid() {
      guard let rectangle = try bounds(window.element, budget),
        geometry.bounds.rect.contains(rectangle.rect)
      else { continue }
      targets.append(
        NativeConsentTarget(
          surfaceId: window.id, applicationId: window.application.id,
          applicationLabel: window.application.label, bundleId: window.application.bundleId,
          windowTitle: try text(window.element, kAXTitleAttribute as CFString, budget) ?? ""))
    }
    let selectedIds = Set(targets.map { $0.applicationId })
    let apps = applications.filter { selectedIds.contains($0.id) }.map {
      NativeArmingApplication(
        id: $0.id, pid: $0.pid, launchDate: $0.launchDate, bundleId: $0.bundleId)
    }
    return NativeArmingContext(display: geometry, targets: targets, applications: apps)
  }
  func applicationGeneration(_ budget: NativeBudget) throws -> String {
    try budget.check()
    let apps = applications.filter { $0.pid != getpid() }.map {
      NativeArmingApplication(
        id: $0.id, pid: $0.pid, launchDate: $0.launchDate, bundleId: $0.bundleId)
    }
    return NativeArmingContext(display: try display(budget), targets: [], applications: apps)
      .applicationGeneration
  }
  func focusForLocalConsent(surface: String, budget: NativeBudget) async throws {
    try await focusWindow(listId: windowsId, surface: surface, budget: budget)
  }
  func focusApplication(surface: String, budget: NativeBudget) throws -> NativeArmingApplication {
    guard let window = windows.first(where: { $0.id == surface }),
      window.application.pid != getpid(), try live(window.application, budget),
      let rectangle = try bounds(window.element, budget),
      try display(budget).bounds.rect.contains(rectangle.rect)
    else { throw BrokerFailure.notAllowed }
    let app = window.application
    return NativeArmingApplication(
      id: app.id, pid: app.pid, launchDate: app.launchDate, bundleId: app.bundleId)
  }
  func routePid(state: BrokerState, budget: NativeBudget) throws -> pid_t {
    guard let app = applications.first(where: { $0.id == state.guardValue.applicationId }),
      app.pid != getpid(), try live(app, budget),
      let window = windows.first(where: { $0.id == state.guardValue.windowId }),
      window.application.id == app.id,
      let rectangle = try bounds(window.element, budget),
      state.display.bounds.rect.contains(rectangle.rect)
    else { throw BrokerFailure.notAllowed }
    return app.pid
  }
  func accepts(surface: String) -> Bool {
    surface == configuration.surfaceId || windows.contains { $0.id == surface }
  }
}

@available(macOS 15.2, *)
func nativeCapture(
  display: BrokerDisplay, state: BrokerState, budget: NativeBudget, redactions: [CGRect] = []
) async throws
  -> BrokerImage
{
  try budget.check()
  guard CGPreflightScreenCaptureAccess() else { throw BrokerFailure.denied }
  let captured = try await SCScreenshotManager.captureImage(in: display.bounds.rect)
  try budget.check()
  guard captured.width > 0, captured.height > 0, captured.width <= 16384, captured.height <= 16384,
    captured.width * captured.height <= 33_554_432, captured.bytesPerRow <= 131072,
    captured.bytesPerRow * captured.height <= 268_435_456
  else { throw BrokerFailure.observation }
  let ratio = min(1.0, 1600.0 / Double(max(captured.width, captured.height)))
  let width = max(1, Int(Double(captured.width) * ratio))
  let height = max(1, Int(Double(captured.height) * ratio))
  guard
    let context = CGContext(
      data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
  else { throw BrokerFailure.observation }
  context.interpolationQuality = .medium
  context.draw(captured, in: CGRect(x: 0, y: 0, width: width, height: height))
  try budget.check()
  guard redactions.count <= 8 else { throw BrokerFailure.observation }
  context.setFillColor(CGColor(gray: 0.25, alpha: 1))
  for raw in redactions {
    let visible = raw.intersection(display.bounds.rect)
    if visible.isNull || visible.isEmpty { continue }
    let sx = Double(width) / display.bounds.width
    let sy = Double(height) / display.bounds.height
    let left = floor((visible.minX - display.bounds.x) * sx) - 2
    let bottom = floor(Double(height) - (visible.maxY - display.bounds.y) * sy) - 2
    let right = ceil((visible.maxX - display.bounds.x) * sx) + 2
    let top = ceil(Double(height) - (visible.minY - display.bounds.y) * sy) + 2
    context.fill(CGRect(x: left, y: bottom, width: right - left, height: top - bottom))
  }
  guard let scaled = context.makeImage() else { throw BrokerFailure.observation }
  let data = NSMutableData()
  guard
    let destination = CGImageDestinationCreateWithData(
      data, UTType.png.identifier as CFString, 1, nil)
  else { throw BrokerFailure.observation }
  CGImageDestinationAddImage(destination, scaled, nil)
  guard CGImageDestinationFinalize(destination), data.length <= 1_048_576 else {
    throw BrokerFailure.observation
  }
  try budget.check()
  return BrokerImage(
    bytesBase64: (data as Data).base64EncodedString(), width: width, height: height,
    transform: BrokerTransform(
      a: display.bounds.width / Double(width), d: display.bounds.height / Double(height),
      e: display.bounds.x, f: display.bounds.y), capturedAt: brokerTime(),
    layoutGeneration: state.guardValue.layoutGeneration)
}
