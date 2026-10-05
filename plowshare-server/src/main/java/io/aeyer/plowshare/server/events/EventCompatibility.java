package io.aeyer.plowshare.server.events;

import java.util.List;

/**
 * Read-only retained-row inventory. Diagnostics contain identities and fixed codes, never payloads.
 */
public record EventCompatibility(long scanned, long invalid, List<Problem> problems) {
  public enum Code {
    INVALID_EVENT_DTO
  }

  public record Problem(String firing, Code code) {
    public Problem {
      firing = EventPayload.identity(firing, "firing");
      java.util.Objects.requireNonNull(code, "code");
    }
  }

  public EventCompatibility {
    problems = List.copyOf(problems);
    if (scanned < 0
        || invalid < 0
        || invalid > scanned
        || problems.size() > 100
        || problems.size() > invalid)
      throw new IllegalArgumentException("invalid event compatibility inventory");
  }

  public void requireCompatible() {
    if (invalid != 0)
      throw new IllegalStateException(
          "Event DTO cutover blocked: "
              + invalid
              + " retained firing(s) need explicit compatible DTOs; first IDs: "
              + problems.stream().map(Problem::firing).toList());
  }
}
