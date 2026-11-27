package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.support.SqlArrayValue;

/** Repository-only encoding. Ownership, NULL-log semantics and paging remain with each owner. */
public final class InformationLogSql {
  private InformationLogSql() {}

  public static InformationSql.Filter visible(
      String column, String account, InformationLogVisibility.Facts facts) {
    if (!Set.of("conversation", "i.conversation", "id", "c.id", "s.conversation_id")
        .contains(column)) throw new IllegalArgumentException("Unknown log column");
    var denied = unreadable(account, facts);
    return new InformationSql.Filter(column + " NOT IN (" + denied.sql() + ")", denied.arguments());
  }

  /** A reusable statement-local relation for reads that check more than one log reference. */
  public static InformationSql.Filter unreadable(
      String account, InformationLogVisibility.Facts facts) {
    return facts
        .blocked()
        .<InformationSql.Filter>map(
            logs ->
                new InformationSql.Filter(
                    "SELECT log FROM unnest(?::text[]) AS denied(log)",
                    List.of(new SqlArrayValue("text", logs.toArray()))))
        .orElseGet(
            () ->
                new InformationSql.Filter(
                    "SELECT log FROM information_unreadable_logs(?)",
                    java.util.Collections.singletonList(account)));
  }
}
