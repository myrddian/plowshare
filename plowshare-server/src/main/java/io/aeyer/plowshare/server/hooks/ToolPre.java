package io.aeyer.plowshare.server.hooks;

import java.util.List;
import java.util.Objects;

/**
 * The arguments a tool will run with, or the sentence refusing it.
 *
 * @param arguments the final arguments JSON; the permission check inside the tool runs against
 *     these
 * @param denied the refusal the model is shown, or {@code null} when allowed
 * @param asked the first {@code { ask: reason }} a hook returned, or {@code null}. Only {@code
 *     run}'s gate reads it: a person is asked before the command starts
 * @param allowedFor the arguments the last counted {@code { allow: true }} was given, or {@code
 *     null} when none counted. An allow is bound to exactly what it judged: a rewrite after it
 *     voids it unless a hook at or after the rewrite allows again (spec
 *     2026-09-30-local-hooks-are-served, Task 7 fix round 2), so {@link #explicitlyAllowed} holds
 *     only when these are the final {@code arguments}
 */
public record ToolPre(
    String arguments, String denied, List<HookRecord> records, String asked, String allowedFor) {

  public ToolPre {
    Objects.requireNonNull(arguments, "arguments");
    records = List.copyOf(records);
  }

  public ToolPre(String arguments, String denied, List<HookRecord> records) {
    this(arguments, denied, records, null, null);
  }

  /**
   * @param explicitlyAllowed whether an allow counted for exactly {@code arguments}
   */
  public ToolPre(
      String arguments, String denied, List<HookRecord> records, boolean explicitlyAllowed) {
    this(arguments, denied, records, explicitlyAllowed, null);
  }

  /**
   * @param explicitlyAllowed whether an allow counted for exactly {@code arguments}
   */
  public ToolPre(
      String arguments,
      String denied,
      List<HookRecord> records,
      boolean explicitlyAllowed,
      String asked) {
    this(arguments, denied, records, asked, explicitlyAllowed ? arguments : null);
  }

  public static ToolPre allowed(String arguments) {
    return new ToolPre(arguments, null, List.of());
  }

  public boolean isDenied() {
    return denied != null;
  }

  /**
   * Whether some hook returned {@code { allow: true }} that counted, for exactly the arguments the
   * tool will run with — as against every hook saying nothing. The two are the same for every tool
   * but {@code run} in a {@code gated} environment, where only the first lets the command start.
   */
  public boolean explicitlyAllowed() {
    return arguments.equals(allowedFor);
  }
}
