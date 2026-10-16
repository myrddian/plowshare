package io.aeyer.plowshare.server.information;

import java.util.*;

/**
 * Pure compatibility policy; repository-certified completeness is required in addition to ready.
 */
public final class InformationProjectionReuse {
  private InformationProjectionReuse() {}

  public record Stage(String name, String state, String fingerprint, boolean complete) {
    public Stage {
      if (!InformationCatalogue.STAGES.contains(name))
        throw new IllegalArgumentException("Invalid stage");
      if (!Set.of("pending", "running", "ready", "failed", "blocked", "cancelled", "skipped")
          .contains(state)) throw new IllegalArgumentException("Invalid stage state");
      if (fingerprint != null) io.aeyer.plowshare.protocol.InformationContent.digest(fingerprint);
    }
  }

  /**
   * Uses the existing content queue's conservative prerequisite order. Enrichment is deliberately
   * left unprepared: it has different tag/attribution inputs and is never inferred from body hash.
   */
  public static List<String> select(List<Stage> stages, Map<String, String> configuration) {
    if (stages.size() > 7 || configuration.size() > 7)
      throw new IllegalArgumentException("Too many stages");
    var named = new HashMap<String, Stage>();
    for (Stage stage : stages)
      if (named.put(stage.name(), stage) != null)
        throw new IllegalArgumentException("Duplicate stage");
    var selected = new ArrayList<String>();
    for (String name : InformationCatalogue.STAGES.subList(0, 5)) {
      var stage = named.get(name);
      String expected = configuration.get(name);
      if (stage == null
          || !stage.state().equals("ready")
          || !stage.complete()
          || expected == null
          || !expected.equals(stage.fingerprint())) break;
      selected.add(name);
    }
    return List.copyOf(selected);
  }
}
