package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Usage.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.*;
import java.util.*;

/** Pure selection validation shared by boundary admission and repository invariant checks. */
final class UsageFilters {
  private static final Set<String> DIMENSIONS =
      Set.of("day", "model", "pool", "agent", "operation", "project", "run");

  private UsageFilters() {}

  static Resolved resolve(String type, Filter f, Clock clock) {
    if (type == null || !UsageQueryService.REPORTS.contains(type) && !type.equals("usage.calls"))
      throw new CallerFault("unsupported usage report");
    Instant to = f.to() == null ? clock.instant() : f.to();
    Instant from = f.from() == null ? to.minus(Duration.ofDays(30)) : f.from();
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0)
      throw new CallerFault("usage range must be positive and at most 366 days");
    String scope = f.scope() == null ? "direct" : f.scope();
    if (!Set.of("direct", "subtree").contains(scope))
      throw new CallerFault("usage scope must be direct or subtree");
    List<String> groups =
        f.groupBy() == null
            ? switch (type) {
              case "usage.models" -> List.of("model");
              case "usage.pools" -> List.of("pool");
              default -> List.of();
            }
            : List.copyOf(f.groupBy());
    if (groups.size() > 2
        || new HashSet<>(groups).size() != groups.size()
        || !DIMENSIONS.containsAll(groups))
      throw new CallerFault("usage group_by takes at most two distinct supported dimensions");
    int limit = f.limit() == null ? type.equals("usage.calls") ? 50 : 200 : f.limit();
    if (limit < 1 || limit > 200) throw new CallerFault("usage limit must be from 1 to 200");
    String required =
        switch (type) {
          case "usage.conversation" -> f.conversation();
          case "usage.project" -> f.project();
          case "usage.agent" -> f.agent();
          case "usage.run" -> f.run();
          case "usage.orchestration" -> f.orchestration();
          default -> "present";
        };
    if (required == null || required.isBlank())
      throw new CallerFault("usage report is missing its target");
    for (String id :
        Arrays.asList(
            f.conversation(),
            f.project(),
            f.agent(),
            f.run(),
            f.orchestration(),
            f.model(),
            f.pool(),
            f.route()))
      if (id != null && (id.isBlank() || id.length() > 512))
        throw new CallerFault("invalid usage selector");
    if (f.cursor() != null && f.cursor().length() > 4096)
      throw new CallerFault("invalid usage cursor");
    return new Resolved(
        type,
        new Filter(
            f.conversation(),
            f.project(),
            f.agent(),
            f.run(),
            f.orchestration(),
            f.model(),
            f.pool(),
            f.route(),
            scope,
            from,
            to,
            groups,
            f.cursor(),
            limit));
  }
}
