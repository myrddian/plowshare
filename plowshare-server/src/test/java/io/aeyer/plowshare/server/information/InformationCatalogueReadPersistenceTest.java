package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentMetadata;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual PostgreSQL row codecs and equivalence of the live authorization predicates. */
@Tag("full-db")
@Testcontainers
class InformationCatalogueReadPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  JdbcInformationCatalogueRepository repository;
  UnitOfWork work;
  final InformationContext own =
      new InformationContext("reader", InformationContext.Selection.personal()).withView(true);

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('reader','fixture'),('other','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('one')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='one'");
    repository = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  UUID revision(String owner, String project, String policy, String text) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags) VALUES(?,?,?,?,(SELECT id FROM projects WHERE name=?),'source','[\"tag\"]'::jsonb)",
        resource,
        project == null ? "account:" + owner : "project:" + project,
        resource + ".txt",
        owner,
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,text_hash,byte_size,created_at,extracted_text,processing_policy) VALUES(?,?,1,'Retained title','text/plain','source-hash',?,20,now(),?,?)",
        revision,
        resource,
        text == null ? null : "extraction-hash",
        text,
        policy);
    return revision;
  }

  void equivalent(InformationContext context, UUID revision, boolean expected) {
    assertEquals(expected, repository.readable(context, revision));
    var metadata = repository.authorizedMetadata(context, revision);
    var text = repository.authorizedText(context, revision);
    assertEquals(expected, metadata.isPresent());
    assertEquals(expected, text.isPresent());
    if (expected) {
      assertEquals(context, metadata.orElseThrow().context());
      assertEquals(repository.metadata(revision), metadata.orElseThrow().revision());
      assertEquals(context, text.orElseThrow().context());
      assertEquals(revision, text.orElseThrow().revision());
    }
  }

  @Test
  void
      snapshotsMapActualHashesClassificationAndNullableExtractionWithoutTreatingGenerationAsMetadataVersion() {
    UUID retained = revision("reader", null, "automatic", "a🔎retained text");
    var text = repository.authorizedText(own, retained).orElseThrow();
    assertEquals("source-hash", text.contentHash());
    assertEquals("extraction-hash", text.textHash());
    assertEquals("a🔎retained text", text.text());
    assertEquals("document", text.documentType());
    assertEquals("text", text.documentSubtype());
    assertEquals(1, text.processingGeneration());
    var before = repository.authorizedMetadata(own, retained).orElseThrow().revision();
    jdbc.update(
        "UPDATE information_resources SET tags='[\"changed\"]'::jsonb WHERE id=?", text.resource());
    var after = repository.authorizedMetadata(own, retained).orElseThrow().revision();
    assertEquals(before.generation(), after.generation());
    assertEquals(List.of("tag"), before.tags());
    assertEquals(List.of("changed"), after.tags());
    assertThrows(UnsupportedOperationException.class, () -> after.tags().add("mutate"));
    UUID pending = revision("reader", null, "automatic", null);
    var unextracted = repository.authorizedText(own, pending).orElseThrow();
    assertNull(unextracted.text());
    assertNull(unextracted.textHash());
    equivalent(own, retained, true);
  }

  @Test
  void authorizedProjectionsMatchCurrentReadabilityForPrivateProjectTypedAndUnavailableRows() {
    UUID personal = revision("reader", null, "automatic", "retained");
    UUID privateOther = revision("other", null, "automatic", "private");
    UUID project = revision("other", "one", "automatic", "project");
    UUID explicit = revision("reader", null, "explicit_only", "explicit");
    equivalent(own, personal, true);
    equivalent(own, privateOther, false);
    equivalent(own, explicit, true);
    equivalent(own.withView(false), explicit, false);
    equivalent(own, UUID.randomUUID(), false);
    var selected =
        new InformationContext("reader", InformationContext.Selection.project("one"))
            .withView(true);
    equivalent(selected, project, true);
    equivalent(selected.within(KnowledgeReadScope.homeOnly(Home.of("different"))), project, false);
    jdbc.update("DELETE FROM project_members WHERE handle='reader'");
    equivalent(selected, project, false);
    jdbc.update("UPDATE information_revisions SET availability='withdrawn' WHERE id=?", personal);
    equivalent(own, personal, false);
  }

  @Test
  void typedBindingUsesTheExistingLegacyReadFenceAndMapsImmutableTypeIdentity() {
    UUID retained = revision("reader", null, "automatic", "typed");
    var types = new JdbcInformationContentTypeRepository(jdbc);
    InformationContent.Contract contract =
        work.inTransaction(
            () -> {
              var draft =
                  types.create(
                      "account:reader",
                      "reader",
                      null,
                      null,
                      "note",
                      "Note",
                      "",
                      SchemaDefinition.fromWire(
                          Map.of("type", "object", "additionalProperties", false)),
                      DocumentMetadata.empty());
              return types
                  .transition(
                      "account:reader", draft.contract().version(), draft.controlRevision(), true)
                  .contract();
            });
    var metadata = DocumentMetadata.empty();
    new JdbcInformationContentRepository(jdbc, types)
        .attach(
            retained,
            new InformationContent.Binding(contract, SchemaCodec.fingerprint(metadata), metadata));
    equivalent(own.withView(false), retained, false);
    equivalent(own, retained, true);
    assertEquals(
        contract.version(),
        repository
            .authorizedMetadata(own, retained)
            .orElseThrow()
            .revision()
            .contentType()
            .version());
  }
}
