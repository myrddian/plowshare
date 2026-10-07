package io.aeyer.plowshare.protocol;

/**
 * Versioned application payload carried as opaque Relay text, independent of broker event kinds.
 */
public final class FilterReview {
  private FilterReview() {}

  public record Request(
      int version, String requestId, String sourceHash, String role, String message) {
    public Request {
      if (version != 1) throw new IllegalArgumentException("Unsupported filter review version");
      RelayPort.uuid(requestId);
      hash(sourceHash);
      FilterReview.role(role);
      RelayPort.text(message);
    }
  }

  /** An acceptance always returns the entire approved message; a rejection must not carry one. */
  public record Response(
      int version,
      String requestId,
      String sourceHash,
      boolean accepted,
      String message,
      String reason) {
    public Response {
      if (version != 1) throw new IllegalArgumentException("Unsupported filter review version");
      RelayPort.uuid(requestId);
      hash(sourceHash);
      RelayPort.name(reason);
      if (accepted) RelayPort.text(message);
      else if (message != null)
        throw new IllegalArgumentException("Rejected review contains a message");
    }
  }

  private static void hash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid filter source hash");
  }

  private static void role(String value) {
    if (!java.util.Set.of("system", "user", "assistant", "tool").contains(value))
      throw new IllegalArgumentException("Invalid filter role");
  }
}
