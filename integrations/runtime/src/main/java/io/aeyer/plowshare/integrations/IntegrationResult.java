package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import java.util.Map;
import java.util.Set;

/**
 * Adapter-owned observations: selected states, an action acknowledgement or a diagnostic. These
 * values grant no execution authority and an acknowledgement alone does not verify a physical
 * result. The field shape also preserves retained private integration journal evidence.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntegrationResult(
    Map<String, Reading> states,
    Boolean acknowledged,
    String verification,
    ActionContext context,
    String diagnostic) {
  public IntegrationResult {
    if (states != null) {
      states = Map.copyOf(states);
      if (states.size() > 256
          || acknowledged != null
          || verification != null
          || context != null
          || diagnostic != null)
        throw new IllegalArgumentException("Invalid integration state result");
      for (var entry : states.entrySet())
        if (!entry.getKey().equals(entry.getValue().alias()))
          throw new IllegalArgumentException("Foreign integration reading alias");
    } else if (acknowledged != null) {
      if (verification != null
          && !Set.of("unconfirmed", "not_observed", "observed").contains(verification))
        throw new IllegalArgumentException("Invalid action verification");
      if (!acknowledged && context != null)
        throw new IllegalArgumentException("Unacknowledged action cannot carry context");
    } else if (diagnostic == null || verification != null || context != null)
      throw new IllegalArgumentException(
          "Integration result requires states, action receipt or diagnostic");
    diagnostic = IntegrationContracts.text(diagnostic, 32768);
  }
}
