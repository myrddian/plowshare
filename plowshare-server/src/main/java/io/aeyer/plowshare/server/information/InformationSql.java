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
    var args =
        new java.util.ArrayList<Object>(
            java.util.Arrays.asList(
                context.account(),
                context.selection().scope().name().toLowerCase(java.util.Locale.ROOT),
                context.selection().project(),
                context.selection().includeGlobal(),
                context.typedView()));
    String predicate =
        "information_readable("
            + documentAlias
            + ".id, ?, ?, ?, ?) AND "
            + content(context, documentAlias).sql();
    var boundary = boundary(context, documentAlias + ".id");
    predicate += " AND " + boundary.sql();
    args.addAll(boundary.arguments());
    return new Filter(predicate, args);
  }

  /**
   * Select a corpus through one statement-local authority relation before paging, coverage or
   * ranking. Unlike a targeted read, discovery must not repeat recursive grant and dependency
   * resolution for every document or passage. The retained ceiling is evaluated by the selection
   * routine; typed-content exclusion remains the same as {@link #read}.
   */
  public static Filter discovery(InformationContext context, String documentAlias) {
    if (!java.util.Set.of("d", "r", "information_row")
        .contains(documentAlias == null ? "" : documentAlias))
      throw new IllegalArgumentException("document alias must belong to the repository vocabulary");
    var selected = InformationSelectionSql.catalogue(context, null);
    var content = content(context, documentAlias);
    var args = new java.util.ArrayList<>(selected.arguments());
    args.addAll(content.arguments());
    return new Filter(
        selected.sql().replace("r.id", documentAlias + ".id") + " AND " + content.sql(), args);
  }

  /** Intersect every read path with the operation ceiling, including dependency closure. */
  public static Filter boundary(InformationContext context, String revision) {
    if (!java.util.Set.of(
            "d.id",
            "r.id",
            "information_row.id",
            "coalesce(?,information_navigation_head(?))",
            "information_navigation_head(?)",
            "information_navigation_head(e.source)",
            "coalesce(e.pinned_revision,information_navigation_head(e.target))",
            "coalesce(NULL::uuid,information_navigation_head(e.source))")
        .contains(revision == null ? "" : revision))
      throw new IllegalArgumentException("revision must belong to the repository vocabulary");
    if (context.readBoundary() == null) return new Filter("true", List.of());
    return new Filter(
        "knowledge_document_in_boundary(" + revision + ",?,?,?)",
        java.util.Arrays.asList(
            context.account(),
            context.readBoundary().home().project(),
            context.readBoundary().globalRead()));
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
    var visible =
        new Filter(
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
    var boundary =
        boundary(context, "coalesce(" + pin + ",information_navigation_head(" + neighbour + "))");
    var args = new java.util.ArrayList<Object>(visible.arguments());
    args.addAll(boundary.arguments());
    return new Filter("(" + visible.sql() + ") AND " + boundary.sql(), args);
  }

  public record Filter(String sql, List<Object> arguments) {
    public Filter {
      Objects.requireNonNull(sql, "sql");
      arguments = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(arguments));
    }
  }
}
