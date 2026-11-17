import Darwin
import Foundation

enum NativeFenceFailure: Error {
  case busy
  case unavailable
}

/// One conservative host-wide namespace for every shipped capture/control path.
/// Presence is the fence: closing a descriptor or killing its process never
/// authorizes unlinking. The name is a protocol resource constant, not config.
final class NativeGlobalFence {
  static let coordinationVersion = "native-host-fence/1"
  private static let kernelName = "/plowshare.desktop"
  private var descriptor: UnsafeMutablePointer<sem_t>?

  init() throws {
    let handle = Self.kernelName.withCString {
      sem_open($0, O_CREAT | O_EXCL, mode_t(0o600), UInt32(0))
    }
    guard handle != SEM_FAILED else {
      throw errno == EEXIST || errno == EACCES
        ? NativeFenceFailure.busy : NativeFenceFailure.unavailable
    }
    descriptor = handle
  }

  static func presence() -> String {
    let handle = kernelName.withCString { sem_open($0, 0) }
    guard handle != SEM_FAILED else { return errno == ENOENT ? "clear" : "unknown" }
    guard sem_close(handle) == 0 else { return "unknown" }
    return "held"
  }

  /// Only the owner with proven quiescence may remove this name, exactly once.
  func releaseAfterQuiescence() throws {
    guard let handle = descriptor else { throw NativeFenceFailure.unavailable }
    guard Self.kernelName.withCString({ sem_unlink($0) }) == 0 else {
      throw NativeFenceFailure.unavailable
    }
    descriptor = nil
    guard sem_close(handle) == 0 else { throw NativeFenceFailure.unavailable }
  }

  deinit {
    // Deliberately do not unlink. A crash/deinit cannot prove global input up.
    if let handle = descriptor { _ = sem_close(handle) }
  }
}
