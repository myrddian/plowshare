package io.aeyer.plowshare.server.llm.dispatch;

/** A completed and accounted provider response that must not enter the tool or answer path. */
public final class ModelResponseRejected extends RuntimeException {
  public enum Reason {
    RAW_PROTOCOL_MARKUP,
    MISSING_STRUCTURED_TOOL_CALLS,
    GUARD_UNAVAILABLE
  }

  private final Reason reason;
  private final String guard;
  private final transient Completion completion;

  ModelResponseRejected(Reason reason, String guard, Completion completion) {
    super("Model response rejected (" + reason + ", guard " + guard + ")");
    this.reason = reason;
    this.guard = guard;
    this.completion = completion;
  }

  public Reason reason() {
    return reason;
  }

  public String guard() {
    return guard;
  }

  /** For restricted, non-projecting diagnostics only. Never expose this as an answer or tool. */
  public Completion completion() {
    return completion;
  }
}
