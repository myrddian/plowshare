package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.util.Optional;
import java.util.function.BiFunction;

/** Tools whose human questions retain the exact original call and existing no-replay fence. */
interface RetainedApprovalTool extends AgentTool {
  ToolPre gate(
      String arguments,
      Home home,
      HookContext context,
      BiFunction<HookContext, String, ToolPre> chain);

  default void retainedApproval(String id) {}

  ToolPre gateRetained(
      String arguments,
      String side,
      Home home,
      HookContext context,
      BiFunction<HookContext, String, ToolPre> chain);

  Optional<RunApproval> waitingApproval();

  String retainedArguments(String arguments, Home home);

  boolean securityRejected();
}
