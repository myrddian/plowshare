package io.aeyer.plowshare.server.agents;

/**
 * Stable model-visible failures. Diagnostics do not imply that an uncertain effect may be retried.
 */
public record ToolFailure(Code code, String message) {
  public enum Code {
    E_NO_ACCESS,
    E_NO_CONNECTION,
    E_NO_EXEC,
    E_GENERAL_TOOL_FAILURE
  }

  public ToolFailure {
    java.util.Objects.requireNonNull(code);
    java.util.Objects.requireNonNull(message);
  }

  public String render() {
    return code + ": " + message;
  }
}
