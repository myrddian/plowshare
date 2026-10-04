package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import java.util.UUID;

/** An operation the harness can bind without letting a model replace its arguments. */
public interface BoundCommandTool extends AgentTool {
  String runBound(UUID invocation, String definitionHash, String arguments, Home home);
}
