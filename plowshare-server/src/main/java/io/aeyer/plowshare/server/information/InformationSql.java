package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Objects;

/** Repository-only SQL encoding of admitted, typed information criteria. */
public final class InformationSql {
  private InformationSql() {}

  /**
   * Apply in WHERE before ordering, LIMIT, totals or coverage. Membership is also checked in SQL so
   * a removal between admission and the query cannot reveal project material.
   */
  public static Filter read(InformationContext context, String documentAlias) {
    if (!java.util.Set.of("d", "r", "information_row")
        .contains(documentAlias == null ? "" : documentAlias))
      throw new IllegalArgumentException("document alias must belong to the repository vocabulary");
    return new Filter(
        "information_readable(" + documentAlias + ".id, ?, ?, ?, ?)",
        java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(
                context.account(),
                context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
                context.selection().project(),
                context.selection().includeShared())));
  }

  public record Filter(String sql, List<Object> arguments) {
    public Filter {
      Objects.requireNonNull(sql, "sql");
      arguments = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(arguments));
    }
  }
}
