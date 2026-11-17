import AppKit
import Foundation

/// Exact bounded AX choices, issued by the broker. The local gesture selects an
/// opaque existing window, never a model PID, selector, launch command or URL.
struct NativeConsentTarget {
  let surfaceId: String
  let applicationId: String
  let applicationLabel: String
  let bundleId: String
  let windowTitle: String
}
struct NativeConsentPresentation {
  let owner: BrokerOwner
  let challengeId: String
  let durationMs: Int
  let display: BrokerDisplay
  /// These targets cover every granted application incarnation; selected window
  /// is only the initial focus, not the whole cross-application grant scope.
  let targets: [NativeConsentTarget]
  let tools: [String]
}
struct NativeOwnedUIRegions: Equatable {
  /// Rotates only if the exact fixed helper-owned window regions change.
  let generation: String
  let rectangles: [CGRect]
}
struct NativeSentinelGeometry {
  /// Exact Quartz rectangle containing ONLY the 16x16 black/white nonce grid.
  let captureRectangle: CGRect
  let pointerLocation: CGPoint
  let windowNumber: Int
  let regions: NativeOwnedUIRegions
  /// Public AppKit presentation evidence, auxiliary to the fresh pixels/route.
  let presented: Bool
}
struct NativeReadinessUICallbacks {
  /// Runs only from a helper-local confirmation action; there is no RPC confirm.
  let approve: (String) -> Void
  let revoke: () -> Void
  let permissionsChanged: () -> Void
  /// Receipt from the exact helper-owned NSView's mouseMoved handler.
  let probeReceived: (Int64) -> Void
  let presentationLost: () -> Void
}

/// Main-actor-owned AppKit boundary. Implementation never chooses permission,
/// lease, grant TTL, target policy or readiness; the broker owns those decisions.
/// No nonce, tag or consent callback is exposed as a model/Relay capability.
@MainActor
protocol NativeReadinessUI {
  /// Registers this own accessory application before AX messaging. This creates
  /// no window, pump, focus, activation or permission request, and is idempotent.
  /// Only the explicit local-arming path may call it after GUI-session binding.
  func prepare() throws
  func present(_ context: NativeConsentPresentation, callbacks: NativeReadinessUICallbacks) throws
  func updateTargets(_ targets: [NativeConsentTarget])
  func showSentinel(display: BrokerDisplay) throws
  /// Exactly 32 bytes => 256 black/white cells; the caller generates each nonce.
  /// Render it synchronously into an opaque, fixed, nonactivating own panel.
  func paintNonce(_ nonce: [UInt8]) throws -> NativeSentinelGeometry
  /// Register a one-shot current probe tag BEFORE the broker posts mouseMoved.
  /// View receipt must match this tag + own window identity; old receipts fail.
  func expectProbe(_ token: Int64)
  func regions() -> NativeOwnedUIRegions
  /// Cancels and joins the owned AppKit pump before physical UI cleanup settles.
  func close() async
}
