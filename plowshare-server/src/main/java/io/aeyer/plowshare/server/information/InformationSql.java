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

  /**
   * Resolve relationship neighbours exactly as navigation does: live links use the current head,
   * pins use their exact revision, and unavailable content exposes only permitted references.
   * Repositories apply this predicate before paging or export bounds. The e alias is code-owned.
   */
  public static Filter relationshipNeighbour(InformationContext context, boolean outgoing) {
    String neighbour = outgoing ? "e.target" : "e.source";
    String pin = outgoing ? "e.pinned_revision" : "NULL::uuid";
    return new Filter(
        "(information_navigation_own_readable(coalesce("
            + pin
            + ",information_navigation_head("
            + neighbour
            + ")),?,?) AND information_reference_readable(coalesce("
            + pin
            + ",information_navigation_head("
            + neighbour
            + ")),?,?) OR information_navigation_reference_visible("
            + neighbour
            + ",?,?))",
        java.util.Arrays.asList(
            context.account(), context.selection().project(),
            context.account(), context.selection().project(),
            context.account(), context.selection().project()));
  }

  public record Filter(String sql, List<Object> arguments) {
    public Filter {
      Objects.requireNonNull(sql, "sql");
      arguments = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(arguments));
    }
  }
}
