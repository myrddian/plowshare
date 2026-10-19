package io.aeyer.plowshare.server.information;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Statement-local authority and dependency evaluation for full catalogue/navigation selections. */
final class InformationSelectionSql {
  private InformationSelectionSql() {}

  /**
   * Catalogue callers retain content/facet/report filters in their owning statement. The database
   * routine admits roots before paging and reuses one live authority relation across their bounded
   * dependency closures. It does not retain permission facts beyond that statement.
   */
  static InformationSql.Filter catalogue(InformationContext context, String kind) {
    return selection(context, kind, false, null, null, null);
  }

  /** Parent selection and retained ceiling remain separate from dependency-only neighbours. */
  static InformationSql.Filter navigation(
      InformationContext context, java.util.UUID related, String relation, String direction) {
    return selection(context, null, true, related, relation, direction);
  }

  private static InformationSql.Filter selection(
      InformationContext context,
      String kind,
      boolean heads,
      java.util.UUID related,
      String relation,
      String direction) {
    Objects.requireNonNull(context);
    var boundary = context.readBoundary();
    return new InformationSql.Filter(
        "r.id IN (SELECT revision FROM knowledge_readable_selection(?,?,?,?,?,?,?,?,?,?,?,?))",
        Arrays.asList(
            context.account(),
            context.selection().scope().name().toLowerCase(Locale.ROOT),
            context.selection().project(),
            context.selection().includeGlobal(),
            boundary == null ? null : boundary.home().project(),
            boundary == null ? null : boundary.globalRead(),
            heads ? "document" : context.corpus().documentType(),
            kind,
            heads,
            related,
            relation,
            direction));
  }
}
