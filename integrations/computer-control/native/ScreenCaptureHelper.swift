import ApplicationServices
import Foundation
import ImageIO
import ScreenCaptureKit
import UniformTypeIdentifiers

// One request per process. The Node owner bounds stdin/stdout and kills/reaps this
// process on expiry; the watchdog also prevents an abandoned helper from waiting.
private let protocolVersion = "computer-control-native/1"
private let maximumRequestBytes = 4096

private enum HelperFailure: String, Error {
  case invalidRequest = "INVALID_REQUEST"
  case unsupportedPlatform = "UNSUPPORTED_PLATFORM"
  case permissionDenied = "PERMISSION_DENIED"
  case targetGone = "TARGET_GONE"
  case captureFailed = "CAPTURE_FAILED"
  case captureTooLarge = "CAPTURE_TOO_LARGE"
}

private struct AnyKey: CodingKey {
  let stringValue: String
  var intValue: Int? { nil }
  init(stringValue: String) { self.stringValue = stringValue }
  init?(intValue: Int) { return nil }
}

// JSONDecoder does not report duplicate keys. Scan bounded string tokens first;
// decoding each key also catches escaped aliases such as "oper\u0061tion".
// JSONDecoder remains the syntax/type validator after this duplicate-key check.
private func rejectDuplicateFields(_ input: Data) throws {
  let bytes = Array(input)
  var index = 0
  var names = Set<String>()
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
    guard index < bytes.count else { throw HelperFailure.invalidRequest }
    index += 1
    let end = index
    while index < bytes.count, [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
    if index < bytes.count, bytes[index] == 58 {
      let name = try JSONDecoder().decode(String.self, from: Data(bytes[start..<end]))
      guard names.insert(name).inserted else { throw HelperFailure.invalidRequest }
    }
  }
}

private struct Request: Decodable {
  let operation: String
  let timeoutMs: Int
  let surfaceId: String?
  let maxEdge: Int?
  let maxBytes: Int?
  let coordination: String?

  private enum CodingKeys: String, CodingKey {
    case version, operation, timeoutMs, surfaceId, maxEdge, maxBytes, coordination
  }

  init(from decoder: Decoder) throws {
    let names = try decoder.container(keyedBy: AnyKey.self).allKeys.map(\.stringValue)
    let fields = try decoder.container(keyedBy: CodingKeys.self)
    let version = try fields.decode(String.self, forKey: .version)
    operation = try fields.decode(String.self, forKey: .operation)
    timeoutMs = try fields.decode(Int.self, forKey: .timeoutMs)
    coordination = try fields.decodeIfPresent(String.self, forKey: .coordination)
    if let coordination, coordination != NativeGlobalFence.coordinationVersion {
      throw HelperFailure.invalidRequest
    }
    guard version == protocolVersion, (1...300_000).contains(timeoutMs) else {
      throw HelperFailure.invalidRequest
    }
    let allowed: Set<String>
    switch operation {
    case "status":
      allowed = ["version", "operation", "timeoutMs", "coordination"]
      surfaceId = nil
      maxEdge = nil
      maxBytes = nil
    case "capture":
      allowed = [
        "version", "operation", "timeoutMs", "surfaceId", "maxEdge", "maxBytes", "coordination",
      ]
      let surface = try fields.decode(String.self, forKey: .surfaceId)
      let edge = try fields.decode(Int.self, forKey: .maxEdge)
      let bytes = try fields.decode(Int.self, forKey: .maxBytes)
      let parts = surface.split(separator: ":", omittingEmptySubsequences: false)
      guard parts.count == 2, ["display", "window"].contains(String(parts[0])),
        let identifier = UInt32(parts[1]), identifier > 0,
        String(identifier) == parts[1], (1...1600).contains(edge),
        (1...1_048_576).contains(bytes)
      else { throw HelperFailure.invalidRequest }
      surfaceId = surface
      maxEdge = edge
      maxBytes = bytes
    default:
      throw HelperFailure.invalidRequest
    }
    guard Set(names).isSubset(of: allowed) else { throw HelperFailure.invalidRequest }
  }
}

private struct Transform: Encodable {
  let a: Double
  let b: Double = 0
  let c: Double = 0
  let d: Double
  let e: Double
  let f: Double
}

private struct StatusReply: Encodable {
  let version = protocolVersion
  let ok = true
  let operation = "status"
  let screenCaptureAllowed: Bool
  let accessibilityAllowed: Bool
  let coordination: String?
}

private struct CaptureReply: Encodable {
  let version = protocolVersion
  let ok = true
  let operation = "capture"
  let byteLength: Int
  let width: Int
  let height: Int
  let transform: Transform
  let capturedAt: String
  let layoutGeneration: String
  let coordination: String?
}

private struct FailureReply: Encodable {
  let version = protocolVersion
  let ok = false
  let code: String
}

private func writeReply<T: Encodable>(_ reply: T, bytes: Data? = nil) throws {
  let metadata = try JSONEncoder().encode(reply)
  guard metadata.count < 8192 else { throw HelperFailure.captureFailed }
  try FileHandle.standardOutput.write(contentsOf: metadata)
  try FileHandle.standardOutput.write(contentsOf: Data([10]))
  if let bytes { try FileHandle.standardOutput.write(contentsOf: bytes) }
}

// Capturing a window uses desktop-independent capture; only the explicitly named
// surface is selected. No titles, accessibility values or other pixels are output.
@available(macOS 14.2, *)
private func capture(_ request: Request) async throws {
  guard CGPreflightScreenCaptureAccess() else { throw HelperFailure.permissionDenied }
  guard let surfaceId = request.surfaceId, let maxEdge = request.maxEdge,
    let maxBytes = request.maxBytes
  else { throw HelperFailure.invalidRequest }
  let content: SCShareableContent
  do {
    content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
  } catch {
    if !CGPreflightScreenCaptureAccess() { throw HelperFailure.permissionDenied }
    throw HelperFailure.captureFailed
  }
  let filter: SCContentFilter
  let frame: CGRect
  if surfaceId.hasPrefix("display:"), let identifier = UInt32(surfaceId.dropFirst(8)),
    let display = content.displays.first(where: { $0.displayID == identifier })
  {
    filter = SCContentFilter(display: display, excludingWindows: [])
    frame = display.frame
  } else if surfaceId.hasPrefix("window:"), let identifier = UInt32(surfaceId.dropFirst(7)),
    let window = content.windows.first(where: { $0.windowID == identifier })
  {
    filter = SCContentFilter(desktopIndependentWindow: window)
    frame = window.frame
  } else {
    throw HelperFailure.targetGone
  }
  guard frame.width.isFinite, frame.height.isFinite, frame.origin.x.isFinite,
    frame.origin.y.isFinite, frame.width > 0, frame.height > 0
  else { throw HelperFailure.captureFailed }
  let ratio = min(1, Double(maxEdge) / max(frame.width, frame.height))
  let width = max(1, Int((frame.width * ratio).rounded(.down)))
  let height = max(1, Int((frame.height * ratio).rounded(.down)))
  let configuration = SCStreamConfiguration()
  configuration.width = width
  configuration.height = height
  configuration.showsCursor = false
  configuration.scalesToFit = true
  configuration.preservesAspectRatio = false
  configuration.ignoreShadowsSingleWindow = true
  configuration.ignoreGlobalClipSingleWindow = true
  configuration.includeChildWindows = false
  let image: CGImage
  do {
    image = try await SCScreenshotManager.captureImage(
      contentFilter: filter, configuration: configuration)
  } catch {
    if !CGPreflightScreenCaptureAccess() { throw HelperFailure.permissionDenied }
    throw HelperFailure.captureFailed
  }
  let capturedAt = Date()
  guard image.width > 0, image.height > 0, image.width <= maxEdge, image.height <= maxEdge else {
    throw HelperFailure.captureTooLarge
  }
  let data = NSMutableData()
  guard
    let destination = CGImageDestinationCreateWithData(
      data, UTType.png.identifier as CFString, 1, nil)
  else { throw HelperFailure.captureFailed }
  CGImageDestinationAddImage(destination, image, nil)
  guard CGImageDestinationFinalize(destination) else { throw HelperFailure.captureFailed }
  guard data.length > 0, data.length <= maxBytes else { throw HelperFailure.captureTooLarge }
  let formatter = ISO8601DateFormatter()
  formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
  let geometry = "\(surfaceId):\(frame.origin.x):\(frame.origin.y):\(frame.width):\(frame.height)"
  let reply = CaptureReply(
    byteLength: data.length, width: image.width, height: image.height,
    transform: Transform(
      a: frame.width / Double(image.width), d: frame.height / Double(image.height),
      e: frame.origin.x, f: frame.origin.y),
    capturedAt: formatter.string(from: capturedAt), layoutGeneration: geometry,
    coordination: request.coordination)
  try writeReply(reply, bytes: data as Data)
}

/// The async-main runtime can exit successfully when its single CF main-loop
/// drain returns, even while the async worker is unfinished. AppKit may stop a
/// run-loop turn. This explicit process owner only completes when its one mode
/// worker returns; a CF return is never a success or physical-settlement proof.
private final class NativeMainCompletion: @unchecked Sendable {
  // This one flag is accessed only through this lock, including worker wakeup.
  private let lock = NSLock()
  private var completed = false
  var isCompleted: Bool { lock.withLock { completed } }
  func finish() { lock.withLock { completed = true } }
}

/// Fixed no-GUI regression: the original async-main entrypoint would exit zero
/// before either continuation/strict self-test JSON. One original work budget
/// covers both turns; no AppKit, capture, input, tap or ownership is initialized.
@MainActor
private func nativeMainLoopMemoryCheck() async throws -> Int {
  let budget = NativeBudget(milliseconds: 100)
  for _ in 0..<2 {
    try budget.check()
    CFRunLoopStop(CFRunLoopGetMain())
    try await budget.sleep(milliseconds: 10)
  }
  return 2
}

@main
private enum Main {
  @MainActor static func main() {
    let completion = NativeMainCompletion()
    Task { @MainActor in
      await runMode()
      completion.finish()
      CFRunLoopStop(CFRunLoopGetMain())
    }
    // A persistent stdin subscription has no two-second lifetime; its active
    // commands retain their existing original deadlines/watchdogs and cleanup
    // budgets. This loop only services the owning main executor, never polls UI
    // state or renews a grant. A stopped/empty turn cannot complete the worker.
    while !completion.isCompleted {
      let started = DispatchTime.now().uptimeNanoseconds
      autoreleasepool { _ = CFRunLoopRunInMode(.defaultMode, 0.1, false) }
      if !completion.isCompleted,
        DispatchTime.now().uptimeNanoseconds - started < 1_000_000
      {
        Thread.sleep(forTimeInterval: 0.001)
      }
    }
  }
  @MainActor private static func runMode() async {
    if CommandLine.arguments == [CommandLine.arguments[0], "--check-input-memory"] {
      do {
        let mainLoopCases = try await nativeMainLoopMemoryCheck()
        try await runNativeInputMemoryCheck(mainLoopCasesChecked: mainLoopCases)
      } catch { exit(1) }
      return
    }
    if CommandLine.arguments == [CommandLine.arguments[0], "--control"] {
      await NativeBroker().run()
      return
    }
    guard CommandLine.arguments.count == 1 else { exit(1) }
    // This upper bound starts before stdin. The caller closes the bounded stdin
    // request immediately, then the request's earlier deadline replaces it.
    let watchdog = DispatchSource.makeTimerSource(queue: .global())
    watchdog.schedule(deadline: .now() + .milliseconds(2_000))
    watchdog.setEventHandler { exit(124) }
    watchdog.resume()
    defer { watchdog.cancel() }
    do {
      var input = Data()
      while let chunk = try FileHandle.standardInput.read(upToCount: maximumRequestBytes + 1),
        !chunk.isEmpty
      {
        input.append(chunk)
        guard input.count <= maximumRequestBytes else { throw HelperFailure.invalidRequest }
      }
      guard input.count > 0, input.count <= maximumRequestBytes else {
        throw HelperFailure.invalidRequest
      }
      try rejectDuplicateFields(input)
      let request = try JSONDecoder().decode(Request.self, from: input)
      watchdog.schedule(deadline: .now() + .milliseconds(request.timeoutMs))
      switch request.operation {
      case "status":
        try writeReply(
          StatusReply(
            screenCaptureAllowed: CGPreflightScreenCaptureAccess(),
            accessibilityAllowed: AXIsProcessTrusted(), coordination: request.coordination))
      case "capture":
        guard #available(macOS 14.2, *) else { throw HelperFailure.unsupportedPlatform }
        // Legacy-shaped accepted requests also join the host-wide fence. Only
        // explicit coordinated requests add feature metadata to old wire replies.
        let fence = try NativeGlobalFence()
        var captureError: Error?
        do { try await capture(request) } catch { captureError = error }
        try fence.releaseAfterQuiescence()
        if let captureError { throw captureError }
      default:
        throw HelperFailure.invalidRequest
      }
    } catch {
      let code = (error as? HelperFailure)?.rawValue ?? HelperFailure.invalidRequest.rawValue
      do { try writeReply(FailureReply(code: code)) } catch { exit(1) }
    }
  }
}
