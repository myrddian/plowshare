package io.aeyer.plowshare.server.llm;

import java.util.Map;

/**
 * Context windows the provider actually loaded, keyed by exact model identity. Missing/zero windows
 * remain unknown; advertised maximum capability is never substituted for live capacity.
 */
public record LoadedModels(Map<String, Integer> contextLengths) {
  public LoadedModels {
    contextLengths = Map.copyOf(contextLengths);
    if (contextLengths.size() > 10000) throw new IllegalArgumentException("too many loaded models");
    for (var entry : contextLengths.entrySet()) {
      String id = entry.getKey();
      if (id.isBlank()
          || id.length() > 1024
          || !id.equals(id.strip())
          || id.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)
          || entry.getValue() < 1)
        throw new IllegalArgumentException("invalid loaded model identity or context window");
    }
  }
}
