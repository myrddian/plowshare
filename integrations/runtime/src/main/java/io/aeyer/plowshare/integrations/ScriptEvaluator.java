package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import java.io.IOException;

/**
 * Evaluate a pure handler against immutable captured input; validate the whole plan before return.
 */
public interface ScriptEvaluator {
  ScriptOutput evaluate(String source, String handler, EventEnvelope event, ScriptContext context)
      throws IOException;
}
