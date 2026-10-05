package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class InformationCollectionsTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  InformationCatalogue catalogue;
  final InformationContext own =
      new InformationContext("reader", InformationContext.Selection.personal());

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void fresh() {
    jdbc = new JdbcTemplate(source);
    jdbc.execute("TRUNCATE admins CASCADE");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('reader','h'),('other','h')");
    catalogue =
        io.aeyer.plowshare.server.information.InformationFixtures.catalogue(
            jdbc,
            new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source)),
            new InformationAccess(new JdbcProjectMembers(jdbc)),
            Clock.systemUTC());
  }

  UUID revision(String kind, String reportStatus) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,kind) VALUES(?,'account:reader',?,'reader',?)",
        resource,
        resource.toString(),
        kind);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,1,'Retained item','text/plain','fixture',1)",
        revision,
        resource);
    if (reportStatus != null)
      jdbc.update(
          "INSERT INTO information_reports(revision_id,status) VALUES(?,?)",
          revision,
          reportStatus);
    return revision;
  }

  @Test
  void report_filter_precedes_pagination_and_includes_readable_drafts_without_publishing() {
    UUID report = revision("report", "final"), draft = revision("report", "draft");
    revision("report", "superseded");
    for (int i = 0; i < 105; i++) revision("source", null);
    var first =
        io.aeyer.plowshare.server.information.InformationFixtures.views(
            catalogue.list(own, 1, 0, "report"));
    var second =
        io.aeyer.plowshare.server.information.InformationFixtures.views(
            catalogue.list(own, 1, 1, "report"));
    assertEquals(1, first.size());
    assertEquals(1, second.size());
    assertEquals(
        java.util.Set.of(report, draft),
        java.util.Set.of(first.getFirst().get("id"), second.getFirst().get("id")));
    var draftRow =
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 100, 0, "report"))
            .stream()
            .filter(row -> draft.equals(row.get("id")))
            .findFirst()
            .orElseThrow();
    assertEquals("report", draftRow.get("kind"));
    assertEquals("draft", draftRow.get("report_status"));
    jdbc.update(
        "UPDATE information_revisions SET extracted_text='Retained draft findings' WHERE id=?",
        draft);
    assertEquals("Retained draft findings", catalogue.text(own, draft));
    assertEquals(
        "draft",
        jdbc.queryForObject(
            "SELECT status FROM information_reports WHERE revision_id=?", String.class, draft));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_document_policies WHERE document_id=?",
            Integer.class,
            draft));
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 1, 2, "report"))
            .isEmpty());
    assertEquals(
        100,
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 100, 0, "source"))
            .size());
    assertEquals(
        5,
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 100, 100, "source"))
            .size());
    var other = new InformationContext("other", InformationContext.Selection.personal());
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(other, 100, 0, "report"))
            .isEmpty());
    assertThrows(
        io.aeyer.plowshare.server.faults.NotFoundFault.class, () -> catalogue.text(other, draft));
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(
                    new InformationContext("reader", InformationContext.Selection.shared()),
                    100,
                    0,
                    "report"))
            .isEmpty());
    assertThrows(
        CallerFault.class,
        () ->
            io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 10, 0, "report' OR true --")));
    jdbc.update("UPDATE information_revisions SET excluded=true WHERE id=?", report);
    assertEquals(
        java.util.List.of(draft),
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 10, 0, "report"))
            .stream()
            .map(row -> row.get("id"))
            .toList());
  }

  @Test
  void unreadable_inputs_and_withdrawal_hide_draft_reports() {
    UUID input = revision("source", null), draft = revision("report", "draft");
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        draft,
        input);
    assertEquals(
        1,
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 10, 0, "report"))
            .size());
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", input);
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 10, 0, "report"))
            .isEmpty());
    jdbc.update("UPDATE information_revisions SET availability='active' WHERE id=?", input);
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", draft);
    assertTrue(
        io.aeyer.plowshare.server.information.InformationFixtures.views(
                catalogue.list(own, 10, 0, "report"))
            .isEmpty());
  }
}
