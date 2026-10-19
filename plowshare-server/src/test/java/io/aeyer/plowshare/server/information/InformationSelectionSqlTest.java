package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Complete bound selection tuples are shared by each owning projection, never permission leases.
 */
class InformationSelectionSqlTest {
  @Test
  void catalogueBindsAccountScopeCorpusAndCeilingWithoutInterpolatingValues() {
    var context =
        new InformationContext(
                "reader",
                new InformationContext.Selection(
                    InformationContext.Scope.PROJECT, "research", false),
                InformationContext.Corpus.CODE)
            .within(new KnowledgeReadScope(Home.of("research"), true));
    var selection = InformationSelectionSql.catalogue(context, "report");
    assertEquals(
        Arrays.asList(
            "reader",
            "project",
            "research",
            false,
            "research",
            true,
            "code",
            "report",
            false,
            null,
            null,
            null),
        selection.arguments());
    assertFalse(selection.sql().contains("reader"));
    assertFalse(selection.sql().contains("research"));
    assertTrue(selection.sql().contains("knowledge_readable_selection("));
  }

  @Test
  void navigationKeepsTheParentAndDirectionAsBoundValuesAndUsesDocumentHeads() {
    UUID parent = UUID.randomUUID();
    var context = new InformationContext("reader", InformationContext.Selection.personal());
    var selection = InformationSelectionSql.navigation(context, parent, "cites", "backlinks");
    assertEquals(
        Arrays.asList(
            "reader",
            "personal",
            null,
            true,
            null,
            null,
            "document",
            null,
            true,
            parent,
            "cites",
            "backlinks"),
        selection.arguments());
    assertFalse(selection.sql().contains(parent.toString()));
    assertFalse(selection.sql().contains("cites"));
  }

  @Test
  void aRetainedGlobalHomeIsNotConfusedWithAnAbsentCeiling() {
    var context = new InformationContext("reader", InformationContext.Selection.personal());
    assertNull(InformationSelectionSql.catalogue(context, null).arguments().get(5));
    var global = context.within(KnowledgeReadScope.homeOnly(Home.global()));
    assertNull(InformationSelectionSql.catalogue(global, null).arguments().get(4));
    assertEquals(false, InformationSelectionSql.catalogue(global, null).arguments().get(5));
  }
}
