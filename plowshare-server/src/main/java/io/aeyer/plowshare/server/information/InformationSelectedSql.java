package io.aeyer.plowshare.server.information;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Repository-only encoding of explicit selections, shared by passage and citation projections. */
public final class InformationSelectedSql {
  private InformationSelectedSql() {}

  /** NULL targets selects heads; a nonempty bounded set selects exact historical revisions. */
  public static InformationSql.Filter candidates(
      InformationReadSelection selection, List<UUID> targets) {
    if (targets != null
        && (targets.isEmpty() || targets.size() > 20 || targets.stream().anyMatch(Objects::isNull)))
      throw new IllegalArgumentException("One to twenty exact targets required");
    var reader = selection.reader();
    return new InformationSql.Filter(
        "SELECT * FROM information_selected_candidates(?,CAST(? AS uuid[]),?,?,CAST(? AS uuid[]))",
        Arrays.asList(
            reader.actor(),
            "{"
                + String.join(
                    ",", selection.scopes().stream().filter(s -> !s.equals("global_read")).toList())
                + "}",
            selection.scopes().contains("global_read"),
            reader.boundaryHome(),
            targets == null
                ? null
                : "{"
                    + targets.stream().map(UUID::toString).collect(Collectors.joining(","))
                    + "}"));
  }
}
