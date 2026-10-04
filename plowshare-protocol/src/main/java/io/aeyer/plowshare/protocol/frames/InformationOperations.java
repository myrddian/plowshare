package io.aeyer.plowshare.protocol.frames;

import java.util.List;
import java.util.Set;

/** Socket-only information capability names. Publishing and migration are human controls. */
public final class InformationOperations {
  private InformationOperations() {}

  public static final List<String> ALL =
      List.of(
          "upload",
          "acquire",
          "list",
          "facets",
          "tags",
          "tags.groups",
          "status",
          "await",
          "read",
          "outline",
          "symbols",
          "search",
          "rank",
          "ask",
          "evidence.record",
          "evidence.read",
          "record.report",
          "finalise",
          "link",
          "unlink",
          "share",
          "unshare",
          "withdraw",
          "exclude",
          "unexclude",
          "restore",
          "delete",
          "retry",
          "revise",
          "replace",
          "refresh",
          "rebuild",
          "allowance",
          "events",
          "migration.list",
          "migration.adopt",
          "migration.inspect",
          "migration.release",
          "inventory",
          "acquisitions");
  public static final Set<String> MODEL =
      Set.of(
          "upload",
          "acquire",
          "list",
          "facets",
          "status",
          "await",
          "read",
          "outline",
          "symbols",
          "search",
          "rank",
          "ask",
          "evidence.record",
          "evidence.read",
          "record.report",
          "events",
          "inventory",
          "acquisitions");

  public static List<String> frames() {
    return ALL.stream().map(operation -> "information." + operation).toList();
  }
}
