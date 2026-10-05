package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InformationSqlIdentifiersTest {
  @Test
  void caller_supplied_sql_fragments_and_unregistered_identifiers_are_refused() {
    for (String value : java.util.List.of("r; DROP TABLE documents", "other_alias", "r.id", "r ")) {
      assertThrows(IllegalArgumentException.class, () -> InformationFacetSql.readyTags(value));
      assertThrows(
          IllegalArgumentException.class, () -> InformationFacetSql.visibleGroups("r", value));
      assertThrows(
          IllegalArgumentException.class,
          () -> InformationFacetSql.facets(InformationFacets.NONE, value, "q"));
      assertThrows(IllegalArgumentException.class, () -> InformationSql.read(null, value));
    }
  }
}
