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
        "information_readable("
            + documentAlias
            + ".id, ?, ?, ?, ?) AND "
            + content(context, documentAlias).sql(),
        java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(
                context.account(),
                context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
                context.selection().project(),
                context.selection().includeShared(),
                context.typedView())));
  }

  /** Legacy readers omit every typed or explicit-only revision before discovery and paging. */
  public static Filter content(InformationContext context, String alias) {
    if (!java.util.Set.of("d", "r", "information_row").contains(alias == null ? "" : alias))
      throw new IllegalArgumentException("unknown revision alias");
    return new Filter(
        "(? OR NOT EXISTS(SELECT 1 FROM information_revision_content typed_content WHERE typed_content.revision_id="
            + alias
            + ".id) AND NOT EXISTS(SELECT 1 FROM information_revisions typed_revision WHERE typed_revision.id="
            + alias
            + ".id AND typed_revision.processing_policy='explicit_only'))",
        List.of(context.typedView()));
  }

  public record Filter(String sql, List<Object> arguments) {
    public Filter {
      Objects.requireNonNull(sql, "sql");
      arguments = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(arguments));
    }
  }
}
