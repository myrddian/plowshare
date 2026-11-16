import CoreFoundation
import Foundation

let nativeBrokerVersion = "computer-control-native/2"
let nativeBrokerRequestLimit = 65_536
let nativeBrokerReplyLimit = 2_097_152

enum BrokerFailure: String, Error {
  case invalid = "INVALID_ARGUMENT"
  case noLease = "NO_LEASE"
  case busy = "BUSY"
  case fenced = "FENCED"
  case denied = "PERMISSION_DENIED"
  case gone = "TARGET_GONE"
  case unknown = "SESSION_UNKNOWN"
  case inactive = "SESSION_INACTIVE"
  case secure = "SECURE_INPUT"
  case interference = "INTERFERENCE"
  case stale = "STALE_OBSERVATION"
  case focus = "FOCUS_MISMATCH"
  case notEditable = "NOT_EDITABLE"
  case sensitive = "SENSITIVE_TARGET"
  case ax = "AX_UNAVAILABLE"
  case unsupported = "UNSUPPORTED_ACTION"
  case input = "INPUT_FAILED"
  case observation = "OBSERVATION_FAILED"
  case expired = "DEADLINE_EXPIRED"
  case disabled = "CONTROL_DISABLED"
  case notAllowed = "TARGET_NOT_ALLOWED"
  case cleanup = "CLEANUP_UNCERTAIN"
}

struct BrokerLease: Encodable, Equatable {
  let leaseId: String, targetId: String, sessionId: String, generation: String
}
struct BrokerConfiguration {
  let ownershipDirectory: String, targetId: String, label: String, surfaceId: String
  let displayId: UInt32, tools: Set<String>, allowedBundleIds: Set<String>
  let maxWindows: Int, maxNodes: Int, maxVisitedNodes: Int, cleanupBudgetMs: Int
}
struct BrokerGuard: Encodable, Equatable {
  let surfaceId: String, applicationId: String?, windowId: String?
  let layoutGeneration: String, focusGeneration: String, displayGeneration: String,
    interactionGeneration: String
  // Synthesized optional encoding omits nil, but the strict public DTO requires explicit null.
  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: Keys.self)
    try c.encode(surfaceId, forKey: .surfaceId)
    try c.encode(applicationId, forKey: .applicationId)
    try c.encode(windowId, forKey: .windowId)
    try c.encode(layoutGeneration, forKey: .layoutGeneration)
    try c.encode(focusGeneration, forKey: .focusGeneration)
    try c.encode(displayGeneration, forKey: .displayGeneration)
    try c.encode(interactionGeneration, forKey: .interactionGeneration)
  }
  private enum Keys: String, CodingKey {
    case surfaceId, applicationId, windowId, layoutGeneration, focusGeneration, displayGeneration,
      interactionGeneration
  }
}
struct BrokerElementTarget { let snapshotId: String, elementId: String }
enum BrokerTarget {
  case point(Double, Double)
  case element(BrokerElementTarget)
}
struct BrokerDragPoint {
  let x: Double, y: Double
  let atMs: Int
}
enum BrokerAction {
  case move(BrokerTarget)
  case click(BrokerTarget, String, [String], Int)
  case drag([BrokerDragPoint], String, [String])
  case type(BrokerElementTarget, String, String)
  case key(BrokerElementTarget?, String, [String])
  case scroll(BrokerTarget, Int, Int, String)
  var tool: String {
    switch self {
    case .move, .click, .drag: return "pointer"
    case .type: return "type"
    case .key: return "key"
    case .scroll: return "scroll"
    }
  }
}
enum BrokerCommand {
  case preflight, stop
  case cancel(String)
  case acquire(BrokerLease, BrokerConfiguration)
  case windows(BrokerLease)
  case observe(BrokerLease, String, Bool, BrokerElementTarget?)
  case focus(BrokerLease, String, String, String, Int)
  case perform(BrokerLease, String, BrokerGuard, BrokerAction, Int)
}
struct BrokerRequest {
  let requestId: String, timeoutMs: Int, operation: String, command: BrokerCommand
}

// Generic Foundation JSON exists only inside this codec. Every field becomes a
// validated immutable command before the broker sees it; raw dictionaries never
// cross into native AX/input business logic.
private struct BrokerObject {
  private let fields: [String: Any]
  init(_ value: Any?, _ allowed: Set<String>) throws {
    guard let fields = value as? [String: Any], Set(fields.keys).isSubset(of: allowed) else {
      throw BrokerFailure.invalid
    }
    self.fields = fields
  }
  func only(_ allowed: Set<String>) throws {
    guard Set(fields.keys).isSubset(of: allowed) else { throw BrokerFailure.invalid }
  }
  func object(_ key: String, _ allowed: Set<String>) throws -> BrokerObject {
    try BrokerObject(fields[key], allowed)
  }
  func has(_ key: String) -> Bool { fields[key] != nil }
  func isNull(_ key: String) -> Bool { fields[key] is NSNull }
  func string(_ key: String, max: Int = 256, empty: Bool = false) throws -> String {
    guard let value = fields[key] as? String, value.utf16.count <= max, empty || !value.isEmpty,
      !value.unicodeScalars.contains(where: { $0.value == 0 })
    else { throw BrokerFailure.invalid }
    return value
  }
  func id(_ key: String) throws -> String {
    let value = try string(key)
    guard value.trimmingCharacters(in: .whitespacesAndNewlines) == value,
      !value.unicodeScalars.contains(where: {
        $0.value < 32 || (127...159).contains($0.value) || $0.value == 0x2028 || $0.value == 0x2029
      })
    else { throw BrokerFailure.invalid }
    return value
  }
  func number(_ key: String, min: Double, max: Double, integer: Bool = false) throws -> Double {
    guard let value = fields[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() else {
      throw BrokerFailure.invalid
    }
    let result = value.doubleValue
    guard result.isFinite, result >= min, result <= max, !integer || result.rounded() == result
    else { throw BrokerFailure.invalid }
    return result
  }
  func int(_ key: String, min: Int = 1, max: Int) throws -> Int {
    Int(try number(key, min: Double(min), max: Double(max), integer: true))
  }
  func bool(_ key: String) throws -> Bool {
    guard let value = fields[key] as? NSNumber, CFGetTypeID(value) == CFBooleanGetTypeID() else {
      throw BrokerFailure.invalid
    }
    return value.boolValue
  }
  func strings(_ key: String, maxCount: Int, choices: Set<String>? = nil) throws -> [String] {
    guard let values = fields[key] as? [Any], values.count <= maxCount else {
      throw BrokerFailure.invalid
    }
    let strings = try values.map { value -> String in
      guard let string = value as? String, !string.isEmpty, string.utf16.count <= 256,
        !string.unicodeScalars.contains(where: { $0.value < 32 }),
        choices == nil || choices!.contains(string)
      else { throw BrokerFailure.invalid }
      return string
    }
    guard Set(strings).count == strings.count else { throw BrokerFailure.invalid }
    return strings
  }
  func objects(_ key: String, fields allowed: Set<String>, max: Int) throws -> [BrokerObject] {
    guard let values = fields[key] as? [Any], values.count <= max else {
      throw BrokerFailure.invalid
    }
    return try values.map { try BrokerObject($0, allowed) }
  }
}

// Scoped duplicate detection precedes JSONSerialization, including escaped key
// aliases. JSONDecoder validates each string token and rejects malformed UTF16.
// The structural scanner has a finite nesting bound and runs only on <=64KiB.
private struct BrokerJSONScanner {
  let bytes: [UInt8]
  var index = 0
  mutating func whitespace() {
    while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
  }
  mutating func string() throws -> String {
    guard index < bytes.count, bytes[index] == 34 else { throw BrokerFailure.invalid }
    let start = index
    index += 1
    while index < bytes.count && bytes[index] != 34 {
      if bytes[index] == 92 { index += 1 }
      index += 1
    }
    guard index < bytes.count else { throw BrokerFailure.invalid }
    index += 1
    return try JSONDecoder().decode(String.self, from: Data(bytes[start..<index]))
  }
  mutating func value(depth: Int) throws {
    guard depth <= 16 else { throw BrokerFailure.invalid }
    whitespace()
    guard index < bytes.count else { throw BrokerFailure.invalid }
    if bytes[index] == 34 {
      _ = try string()
      return
    }
    if bytes[index] == 123 {
      index += 1
      whitespace()
      var keys = Set<String>()
      if index < bytes.count && bytes[index] == 125 {
        index += 1
        return
      }
      while true {
        whitespace()
        let key = try string()
        guard keys.insert(key).inserted else { throw BrokerFailure.invalid }
        whitespace()
        guard index < bytes.count, bytes[index] == 58 else { throw BrokerFailure.invalid }
        index += 1
        try value(depth: depth + 1)
        whitespace()
        guard index < bytes.count else { throw BrokerFailure.invalid }
        if bytes[index] == 125 {
          index += 1
          return
        }
        guard bytes[index] == 44 else { throw BrokerFailure.invalid }
        index += 1
      }
    }
    if bytes[index] == 91 {
      index += 1
      whitespace()
      if index < bytes.count && bytes[index] == 93 {
        index += 1
        return
      }
      while true {
        try value(depth: depth + 1)
        whitespace()
        guard index < bytes.count else { throw BrokerFailure.invalid }
        if bytes[index] == 93 {
          index += 1
          return
        }
        guard bytes[index] == 44 else { throw BrokerFailure.invalid }
        index += 1
      }
    }
    let start = index
    while index < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) { index += 1 }
    guard index > start else { throw BrokerFailure.invalid }
  }
}
private func lease(_ object: BrokerObject) throws -> BrokerLease {
  BrokerLease(
    leaseId: try object.id("leaseId"), targetId: try object.id("targetId"),
    sessionId: try object.id("sessionId"), generation: try object.id("generation"))
}
private let leaseFields: Set<String> = ["leaseId", "targetId", "sessionId", "generation"]
private let targetFields: Set<String> = ["kind", "x", "y", "snapshotId", "elementId"]
private func element(_ object: BrokerObject) throws -> BrokerElementTarget {
  try object.only(["kind", "snapshotId", "elementId"])
  guard try object.string("kind") == "element" else { throw BrokerFailure.invalid }
  return BrokerElementTarget(
    snapshotId: try object.id("snapshotId"), elementId: try object.id("elementId"))
}
private func target(_ object: BrokerObject) throws -> BrokerTarget {
  let kind = try object.string("kind")
  if kind == "element" { return .element(try element(object)) }
  guard kind == "point", !object.has("snapshotId"), !object.has("elementId") else {
    throw BrokerFailure.invalid
  }
  return .point(
    try object.number("x", min: -1e7, max: 1e7), try object.number("y", min: -1e7, max: 1e7))
}
private func guardValue(_ object: BrokerObject) throws -> BrokerGuard {
  BrokerGuard(
    surfaceId: try object.id("surfaceId"),
    applicationId: try object.isNull("applicationId") ? nil : object.id("applicationId"),
    windowId: try object.isNull("windowId") ? nil : object.id("windowId"),
    layoutGeneration: try object.id("layoutGeneration"),
    focusGeneration: try object.id("focusGeneration"),
    displayGeneration: try object.id("displayGeneration"),
    interactionGeneration: try object.id("interactionGeneration"))
}
private let guardFields: Set<String> = [
  "surfaceId", "applicationId", "windowId", "layoutGeneration", "focusGeneration",
  "displayGeneration", "interactionGeneration",
]
private func action(_ value: BrokerObject) throws -> BrokerAction {
  let kind = try value.string("kind")
  func mods() throws -> [String] {
    try value.strings("modifiers", maxCount: 4, choices: ["Alt", "Control", "Meta", "Shift"])
  }
  func button() throws -> String {
    let v = try value.string("button")
    guard ["left", "right", "middle"].contains(v) else { throw BrokerFailure.invalid }
    return v
  }
  switch kind {
  case "move":
    try value.only(["kind", "target"])
    return .move(try target(value.object("target", targetFields)))
  case "click", "double_click":
    try value.only(["kind", "target", "button", "modifiers"])
    return .click(
      try target(value.object("target", targetFields)), try button(), try mods(),
      kind == "click" ? 1 : 2)
  case "drag":
    try value.only(["kind", "points", "button", "modifiers"])
    let points = try value.objects("points", fields: ["x", "y", "atMs"], max: 128).map {
      try BrokerDragPoint(
        x: $0.number("x", min: -1e7, max: 1e7), y: $0.number("y", min: -1e7, max: 1e7),
        atMs: $0.int("atMs", min: 0, max: 1000))
    }
    guard points.count >= 2, points[0].atMs == 0,
      zip(points, points.dropFirst()).allSatisfy({ $0.atMs < $1.atMs })
    else { throw BrokerFailure.invalid }
    return .drag(points, try button(), try mods())
  case "type":
    try value.only(["kind", "target", "mode", "text"])
    let mode = try value.string("mode")
    guard ["insert", "replace"].contains(mode) else { throw BrokerFailure.invalid }
    return .type(
      try element(value.object("target", ["kind", "snapshotId", "elementId"])), mode,
      try value.string("text", max: 4096, empty: mode == "replace"))
  case "key":
    try value.only(["kind", "expectedFocus", "key", "modifiers"])
    let key = try value.string("key")
    let modifiers = try mods()
    let plain: Set<String> = [
      "Enter", "Tab", "Escape", "Backspace", "Delete", "Home", "End", "PageUp", "PageDown",
      "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Space",
    ]
    let shiftKeys: Set<String> = [
      "Enter", "Tab", "Home", "End", "PageUp", "PageDown", "ArrowUp", "ArrowDown", "ArrowLeft",
      "ArrowRight", "Space",
    ]
    let selected = Set(modifiers)
    let safe =
      modifiers.isEmpty
      ? plain.contains(key)
      : ((key == "a" || key == "z") && selected == ["Meta"])
        || (key == "z" && selected == ["Meta", "Shift"])
        || (selected == ["Shift"] && shiftKeys.contains(key))
    guard safe else { throw BrokerFailure.invalid }
    return .key(
      try value.isNull("expectedFocus")
        ? nil : element(value.object("expectedFocus", ["kind", "snapshotId", "elementId"])), key,
      modifiers)
  case "scroll":
    try value.only(["kind", "target", "dx", "dy", "units"])
    let units = try value.string("units")
    guard ["pixels", "lines"].contains(units) else { throw BrokerFailure.invalid }
    let maximum = units == "pixels" ? 32768 : 128
    let dx = try value.int("dx", min: -maximum, max: maximum)
    let dy = try value.int("dy", min: -maximum, max: maximum)
    guard dx != 0 || dy != 0 else { throw BrokerFailure.invalid }
    return .scroll(try target(value.object("target", targetFields)), dx, dy, units)
  default: throw BrokerFailure.invalid
  }
}

func decodeBrokerRequest(_ input: Data) throws -> BrokerRequest {
  guard !input.isEmpty, input.count <= nativeBrokerRequestLimit else { throw BrokerFailure.invalid }
  var scanner = BrokerJSONScanner(bytes: Array(input))
  try scanner.value(depth: 0)
  scanner.whitespace()
  guard scanner.index == input.count else { throw BrokerFailure.invalid }
  let root = try BrokerObject(
    JSONSerialization.jsonObject(with: input),
    [
      "version", "requestId", "operation", "timeoutMs", "lease", "configuration", "request",
      "targetRequestId",
    ])
  guard try root.string("version") == nativeBrokerVersion else { throw BrokerFailure.invalid }
  let id = try root.id("requestId")
  let operation = try root.string("operation")
  guard UUID(uuidString: id)?.uuidString.lowercased() == id else { throw BrokerFailure.invalid }
  let timeout = operation == "cancel" ? 1 : try root.int("timeoutMs", max: 300000)
  let command: BrokerCommand
  switch operation {
  case "preflight", "stop":
    guard !["lease", "configuration", "request", "targetRequestId"].contains(where: root.has) else {
      throw BrokerFailure.invalid
    }
    command = operation == "preflight" ? .preflight : .stop
  case "cancel":
    guard !["timeoutMs", "lease", "configuration", "request"].contains(where: root.has) else {
      throw BrokerFailure.invalid
    }
    let target = try root.id("targetRequestId")
    guard UUID(uuidString: target)?.uuidString.lowercased() == target else {
      throw BrokerFailure.invalid
    }
    command = .cancel(target)
  case "acquire":
    guard !root.has("request"), !root.has("targetRequestId") else { throw BrokerFailure.invalid }
    let l = try lease(root.object("lease", leaseFields))
    let c = try root.object(
      "configuration",
      [
        "ownershipDirectory", "target", "control", "maxWindows", "maxNodes", "maxVisitedNodes",
        "cleanupBudgetMs",
      ])
    let t = try c.object("target", ["targetId", "label", "surfaceId"])
    let p = try c.object("control", ["enabled", "tools", "allowedBundleIds"])
    let surface = try t.id("surfaceId")
    let parts = surface.split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 2, parts[0] == "display", let display = UInt32(parts[1]), display > 0,
      String(display) == parts[1], try p.bool("enabled")
    else { throw BrokerFailure.invalid }
    let tools = try p.strings(
      "tools", maxCount: 6, choices: ["windows", "focus", "pointer", "type", "key", "scroll"])
    let bundles = try p.strings("allowedBundleIds", maxCount: 32)
    guard !tools.isEmpty, !bundles.isEmpty,
      bundles.allSatisfy({
        $0.utf16.count <= 255
          && $0.range(
            of: "^[A-Za-z0-9][A-Za-z0-9-]*(?:\\.[A-Za-z0-9][A-Za-z0-9-]*)+$",
            options: .regularExpression) != nil
      })
    else { throw BrokerFailure.invalid }
    let directory = try c.string("ownershipDirectory", max: 4096)
    guard directory.hasPrefix("/"), !directory.unicodeScalars.contains(where: { $0.value < 32 })
    else { throw BrokerFailure.invalid }
    let nodes = try c.int("maxNodes", max: 200)
    let visited = try c.int("maxVisitedNodes", max: 10000)
    let targetId = try t.id("targetId")
    guard visited >= nodes, targetId == l.targetId else { throw BrokerFailure.invalid }
    command = .acquire(
      l,
      BrokerConfiguration(
        ownershipDirectory: directory, targetId: targetId, label: try t.id("label"),
        surfaceId: surface, displayId: display, tools: Set(tools), allowedBundleIds: Set(bundles),
        maxWindows: try c.int("maxWindows", max: 32), maxNodes: nodes, maxVisitedNodes: visited,
        cleanupBudgetMs: try c.int("cleanupBudgetMs", max: 300000)))
  case "windows", "observe", "focus", "perform":
    guard !root.has("configuration"), !root.has("targetRequestId") else {
      throw BrokerFailure.invalid
    }
    let l = try lease(root.object("lease", leaseFields))
    if operation == "windows" {
      guard !root.has("request") else { throw BrokerFailure.invalid }
      command = .windows(l)
      break
    }
    if operation == "observe" {
      let r = try root.object("request", ["surfaceId", "structured", "root"])
      let structured = try r.bool("structured")
      let subtree =
        try r.has("root") ? element(r.object("root", ["kind", "snapshotId", "elementId"])) : nil
      guard structured || subtree == nil else { throw BrokerFailure.invalid }
      command = .observe(l, try r.id("surfaceId"), structured, subtree)
      break
    }
    if operation == "focus" {
      let r = try root.object(
        "request", ["invocationId", "windowsId", "surfaceId", "observationReserveMs"])
      let invocation = try r.id("invocationId")
      guard UUID(uuidString: invocation)?.uuidString.lowercased() == invocation else {
        throw BrokerFailure.invalid
      }
      command = .focus(
        l, invocation, try r.id("windowsId"), try r.id("surfaceId"),
        try r.int("observationReserveMs", max: 300000))
      break
    }
    let r = try root.object(
      "request", ["invocationId", "expected", "action", "observationReserveMs"])
    let invocation = try r.id("invocationId")
    guard UUID(uuidString: invocation)?.uuidString.lowercased() == invocation else {
      throw BrokerFailure.invalid
    }
    let a = try r.object(
      "action",
      [
        "kind", "target", "button", "modifiers", "points", "mode", "text", "key", "expectedFocus",
        "dx", "dy", "units",
      ])
    command = .perform(
      l, invocation, try guardValue(r.object("expected", guardFields)), try action(a),
      try r.int("observationReserveMs", max: 300000))
  default: throw BrokerFailure.invalid
  }
  return BrokerRequest(requestId: id, timeoutMs: timeout, operation: operation, command: command)
}

/// Shared boundary matrix, evaluated entirely in memory. No decoded request is
/// ever passed to NativeBroker, so even acquire/perform fixtures have no effect.
func runNativeProtocolMemoryCheck() throws -> Int {
  let id = "11111111-1111-1111-1111-111111111111"
  let lease =
    "\"lease\":{\"leaseId\":\"lease\",\"targetId\":\"desktop\",\"sessionId\":\"session\",\"generation\":\"generation\"}"
  let guardJSON =
    "{\"surfaceId\":\"display:1\",\"applicationId\":null,\"windowId\":null,\"layoutGeneration\":\"layout\",\"focusGeneration\":\"focus\",\"displayGeneration\":\"display\",\"interactionGeneration\":\"interaction\"}"
  let element = "{\"kind\":\"element\",\"snapshotId\":\"snapshot\",\"elementId\":\"element\"}"
  func perform(_ action: String) -> String {
    "{\"version\":\"\(nativeBrokerVersion)\",\"requestId\":\"\(id)\",\"timeoutMs\":1000,\"operation\":\"perform\",\(lease),\"request\":{\"invocationId\":\"\(id)\",\"expected\":\(guardJSON),\"action\":\(action),\"observationReserveMs\":100}}"
  }
  func key(_ name: String, _ mods: String) -> String {
    perform("{\"kind\":\"key\",\"expectedFocus\":null,\"key\":\"\(name)\",\"modifiers\":\(mods)}")
  }
  func acquire(_ bundle: String) -> String {
    "{\"version\":\"\(nativeBrokerVersion)\",\"requestId\":\"\(id)\",\"timeoutMs\":1000,\"operation\":\"acquire\",\(lease),\"configuration\":{\"ownershipDirectory\":\"/fixture/ownership\",\"target\":{\"targetId\":\"desktop\",\"label\":\"Fixture\",\"surfaceId\":\"display:1\"},\"control\":{\"enabled\":true,\"tools\":[\"pointer\"],\"allowedBundleIds\":[\"\(bundle)\"]},\"maxWindows\":16,\"maxNodes\":200,\"maxVisitedNodes\":2000,\"cleanupBudgetMs\":1000}}"
  }
  let valid = [
    key("Enter", "[]"), key("ArrowLeft", "[\"Shift\"]"), key("a", "[\"Meta\"]"),
    key("z", "[\"Shift\",\"Meta\"]"),
    perform("{\"kind\":\"type\",\"target\":\(element),\"mode\":\"replace\",\"text\":\"\"}"),
    perform(
      "{\"kind\":\"drag\",\"points\":[{\"x\":-1,\"y\":2,\"atMs\":0},{\"x\":3,\"y\":4,\"atMs\":10}],\"button\":\"left\",\"modifiers\":[]}"
    ), acquire("org.fixture.Allowed"),
  ]
  let invalid = [
    key("a", "[\"Control\"]"), key("z", "[\"Control\"]"), key("y", "[\"Control\"]"),
    key("Escape", "[\"Shift\"]"),
    perform("{\"kind\":\"type\",\"target\":\(element),\"mode\":\"insert\",\"text\":\"\"}"),
    valid[0].replacingOccurrences(
      of: "\"kind\":\"key\"", with: "\"kind\":\"key\",\"k\\u0069nd\":\"key\""),
    valid[0].replacingOccurrences(of: "\"leaseId\":\"lease\"", with: "\"leaseId\":\" lease\""),
    valid[0].replacingOccurrences(of: "\"leaseId\":\"lease\"", with: "\"leaseId\":\"lease\\n\""),
    valid[0].replacingOccurrences(of: id, with: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"),
    acquire("org..fixture"), acquire("fixture"), acquire("org.fixture."),
  ]
  for frame in valid { _ = try decodeBrokerRequest(Data(frame.utf8)) }
  for frame in invalid {
    do { _ = try decodeBrokerRequest(Data(frame.utf8)) } catch { continue }
    throw BrokerFailure.invalid
  }
  return valid.count + invalid.count
}

/// Settlement dominates the diagnostic variant: a no-send claim is publishable
/// only with known quiet cleanup. This pure rule is shared by no-post fixtures.
func nativeFailureOutcome(started: Bool, quiet: Bool, hasPartial: Bool) -> String {
  if !quiet { return "UNCERTAIN" }
  if hasPartial { return "PARTIAL" }
  return started ? "UNCERTAIN" : "NOT_DISPATCHED"
}
