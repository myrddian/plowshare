package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.embedding.EmbeddingWorkRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.JdbcJobInformationRepository;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.time.Instant;
import java.util.List;
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

/** Real SQL snapshot, revocation, propagation and rollback semantics; no model/provider calls. */
@Tag("full-db")
@Testcontainers
class MemoryInputSnapshotsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private MemoryStore store;
  private JdbcJobInformationRepository inputs;
  private UnitOfWork work;
  private final Home home = Home.of("one");
  private final MemoryReadRepository.Reader writer =
      new MemoryReadRepository.Reader("writer", null);
  private final MemoryReadRepository.Reader reader =
      new MemoryReadRepository.Reader("reader", null);

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
    AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE admins,projects,information_job_inputs,information_quarantined_payloads CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('writer','fixture'),('reader','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'writer','MANAGER' FROM projects");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='one'");
    conversation("cnv_source", null);
    store = new MemoryStore(jdbc);
    store.protectInformation();
    inputs = new JdbcJobInformationRepository(jdbc);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  private void conversation(String id, String parent) {
    var conversations = new ConversationStore(jdbc, Instant::now, () -> id);
    if (parent == null) conversations.open(home, Budget.of(10), TurnCap.of(10), "writer");
    else conversations.log(Origin.DELEGATION, home, "fixture", parent, null, "writer");
  }

  private UUID document(String project) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags) VALUES(?,?,?,'writer',(SELECT id FROM projects WHERE name=?),'source','[]')",
        resource,
        "project:" + project,
        resource + ".txt",
        project);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text) VALUES(?,?,1,'Source','text/plain','fixture',1,'source')",
        revision,
        resource);
    return revision;
  }

  private void bind(String log, UUID revision, String project) {
    inputs.bind(
        log,
        new InformationContext(
            "writer",
            new InformationContext.Selection(InformationContext.Scope.PROJECT, project, false)),
        List.of(revision));
  }

  private MemoryWrites.Request request(String log) {
    return new MemoryWrites.Request(
        "writer",
        home,
        new MemoryProposal(
            "Operational lesson", "tooling", "Retry the transient read", "writer", "fixture"),
        new MemoryWrites.Origin("tool:" + log + ":call", () -> {}, () -> {}, null)
            .withSourceLog(log));
  }

  private Memory file(String id, String log) {
    var memory =
        Memory.formed(
            id,
            "Operational lesson",
            "tooling",
            "Retry the transient read",
            new Provenance(Instant.EPOCH, "writer", "fixture"),
            home);
    return work.inTransaction(
        () -> {
          var request = request(log);
          var pin = store.pin(request);
          store.lock(request, pin, false);
          store.save(memory);
          store.retainInputs(id, request);
          return memory;
        });
  }

  @Test
  void operationalLessonIsSavedAfterDocumentReadAndRestrictedAtEveryDisclosure() {
    bind("cnv_source", document("two"), "two");
    var memory = file("mem_lesson", "cnv_source");
    assertEquals(memory, store.load(memory.id(), writer).orElseThrow());
    assertTrue(store.load(memory.id(), reader).isEmpty());
    assertTrue(store.load(memory.id()).isEmpty());
    assertEquals(1, store.index(home, writer).size());
    assertTrue(store.index(home, reader).isEmpty());
    assertEquals(1, store.countUnsearchable(home, writer));
    assertEquals(0, store.countUnsearchable(home, reader));
    assertEquals(memory, store.indexingOutput(memory.id()));
    float[] vector = new float[768];
    vector[0] = 1;
    var output =
        new EmbeddingWorkRepository.Source(
            EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, memory.id()),
            jdbc.queryForObject(
                "SELECT embedding_source_revision FROM memories WHERE id=?",
                Long.class,
                memory.id()),
            "Operational lesson\ntooling",
            home.project());
    work.inTransaction(
        () -> {
          var request = request("cnv_source");
          store.publishLegacy(request, store.pin(request), output, vector, () -> {});
          return null;
        });
    assertEquals(List.of(memory), store.searchByVector(vector, home, 1, writer));
    assertTrue(store.searchByVector(vector, home, 1, reader).isEmpty());
  }

  @Test
  void laterDocumentReadsDoNotRetroactivelyRestrictExistingMemories() {
    var before = file("mem_before", "cnv_source");
    bind("cnv_source", document("two"), "two");
    var after = file("mem_after", "cnv_source");
    assertEquals(before, store.load(before.id(), reader).orElseThrow());
    assertEquals(before, store.load(before.id()).orElseThrow());
    assertTrue(store.load(after.id(), reader).isEmpty());
    assertEquals(after, store.load(after.id(), writer).orElseThrow());
  }

  @Test
  void currentSourcePermissionRevocationAndDeletionFenceReadsAndIndexing() {
    var revision = document("one");
    bind("cnv_source", revision, "one");
    var memory = file("mem_revoked", "cnv_source");
    assertTrue(store.load(memory.id(), reader).isPresent());
    jdbc.update("DELETE FROM project_members WHERE handle='reader'");
    assertTrue(store.load(memory.id(), reader).isEmpty());
    assertTrue(store.index(home, reader).isEmpty());
    assertTrue(store.load(memory.id(), writer).isPresent());
    jdbc.update("UPDATE information_revisions SET availability='deleted' WHERE id=?", revision);
    assertTrue(store.load(memory.id(), writer).isEmpty());
    assertThrows(CallerFault.class, () -> store.indexingOutput(memory.id()));
  }

  @Test
  void mergeUnionsDependenciesInsteadOfReplacingEarlierRestrictions() {
    bind("cnv_source", document("one"), "one");
    var memory = file("mem_union", "cnv_source");
    conversation("cnv_second", null);
    bind("cnv_second", document("two"), "two");
    work.inTransaction(
        () -> {
          var request = request("cnv_second");
          store.lock(request, store.pin(request), false);
          store.retainInputs(memory.id(), request);
          return null;
        });
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_job_inputs WHERE job_id=?",
            Integer.class,
            memory.id()));
    assertTrue(store.load(memory.id(), reader).isEmpty());
    assertTrue(store.load(memory.id(), writer).isPresent());
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name='two'");
    assertTrue(store.load(memory.id(), reader).isPresent());
  }

  @Test
  void summariesPropagateInputsToReceivingRunAndItsAncestorsBeforeAnotherMemoryWrite() {
    bind("cnv_source", document("two"), "two");
    var first = file("mem_first", "cnv_source");
    conversation("cnv_parent", null);
    conversation("cnv_receiver", "cnv_parent");
    var receiving = new MemoryReadRepository.Reader("writer", "cnv_receiver");
    work.inTransaction(
        () -> {
          store.lockDisclosure();
          var index = store.index(home, receiving);
          store.retainInputs(index.stream().map(TocEntry::id).toList(), receiving);
          return null;
        });
    assertTrue(inputs.hasInputs("cnv_receiver"));
    assertTrue(inputs.hasInputs("cnv_parent"));
    var next = file("mem_next", "cnv_receiver");
    assertTrue(store.load(first.id(), reader).isEmpty());
    assertTrue(store.load(next.id(), reader).isEmpty());
    assertTrue(store.load(next.id(), writer).isPresent());
  }

  @Test
  void rollbackDoesNotLeaveContentOrUntrackedInputSnapshots() {
    bind("cnv_source", document("one"), "one");
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  file("mem_rollback", "cnv_source");
                  throw new IllegalStateException("abort content transaction");
                }));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM memories WHERE id='mem_rollback'", Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_job_inputs WHERE job_id='mem_rollback'",
            Integer.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM memory_input_snapshots WHERE memory_id='mem_rollback'",
            Integer.class));
  }

  @Test
  void inheritedParentInputsAndPrivateResourcePoliciesRemainEffective() {
    var revision = document("one");
    bind("cnv_source", revision, "one");
    conversation("cnv_child", "cnv_source");
    var memory = file("mem_child", "cnv_child");
    assertTrue(store.load(memory.id(), reader).isPresent());
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,private_only=true,privacy_principal=(SELECT id FROM authority_accounts WHERE legacy_handle='writer'),reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='writer') WHERE resource_id=(SELECT resource_id FROM information_revisions WHERE id=?)",
        revision);
    assertTrue(store.load(memory.id(), reader).isEmpty());
    assertTrue(store.load(memory.id(), writer).isPresent());
  }

  @Test
  void legacyProvenanceCannotBeDeclassifiedByAddingANewSnapshot() {
    var revision = document("two");
    bind("cnv_source", revision, "two");
    var legacy =
        Memory.formed(
            "mem_legacy",
            "Legacy",
            "tooling",
            "Legacy body",
            new Provenance(Instant.EPOCH, "writer", "fixture"),
            home);
    store.save(legacy);
    jdbc.update("INSERT INTO memory_provenance VALUES('mem_legacy','cnv_source',1)");
    conversation("cnv_new", null);
    work.inTransaction(
        () -> {
          var request = request("cnv_new");
          store.lock(request, store.pin(request), false);
          store.retainInputs(legacy.id(), request);
          return null;
        });
    assertTrue(store.load(legacy.id(), reader).isEmpty());
    assertTrue(store.load(legacy.id(), writer).isPresent());
    assertEquals(
        List.of(revision),
        jdbc.queryForList(
            "SELECT revision_id FROM information_job_inputs WHERE job_id='mem_legacy'",
            UUID.class));
    jdbc.update("INSERT INTO information_quarantined_payloads VALUES('cnv_new','fixture')");
    work.inTransaction(
        () -> {
          store.retainInputs(legacy.id(), request("cnv_new"));
          return null;
        });
    assertTrue(store.load(legacy.id(), writer).isEmpty());
    assertThrows(CallerFault.class, () -> store.indexingOutput(legacy.id()));
  }

  @Test
  void directBatchReadAndInvalidationPreserveRestrictionsAndTombstoneAccess() {
    bind("cnv_source", document("two"), "two");
    var memory = file("mem_read", "cnv_source");
    conversation("cnv_reader", null);
    var receiving = new MemoryReadRepository.Reader("writer", "cnv_reader");
    var archive =
        new Archive(store, mock(ReasonLog.class), mock(EmbeddingClient.class), work, 1000, 50, 30);
    assertThrows(
        ArchiveException.class,
        () -> archive.read(List.of(memory.id(), "mem_missing"), receiving, () -> {}));
    assertFalse(inputs.hasInputs("cnv_reader"));
    assertEquals(0, store.load(memory.id(), writer).orElseThrow().uses());
    assertEquals(1, archive.read(List.of(memory.id()), receiving, () -> {}).getFirst().uses());
    assertTrue(inputs.hasInputs("cnv_reader"));
    var dead = archive.invalidate(memory.id(), "Obsolete", "writer", writer, () -> {});
    assertEquals(dead, store.load(memory.id(), writer).orElseThrow());
    assertTrue(store.index(home, writer).isEmpty());
    assertTrue(store.load(memory.id(), reader).isEmpty());
  }

  @Test
  void sourceAuthorityMustStillBeLiveWhenTheMemoryWriteIsAdmitted() {
    bind("cnv_source", document("two"), "two");
    jdbc.update(
        "DELETE FROM project_members WHERE handle='writer' AND project_id=(SELECT id FROM projects WHERE name='two')");
    assertThrows(CallerFault.class, () -> file("mem_denied", "cnv_source"));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM memories WHERE id='mem_denied'", Integer.class));
  }

  @Test
  void aSourceAuditQuarantineWithdrawsAnEarlierSnapshotWithoutDependingOnLaterReads() {
    file("mem_audited", "cnv_source");
    jdbc.update(
        "INSERT INTO information_quarantined_payloads VALUES('cnv_source','Source audit incomplete')");
    assertTrue(store.load("mem_audited", writer).isEmpty());
    assertTrue(store.load("mem_audited").isEmpty());
    assertThrows(CallerFault.class, () -> store.indexingOutput("mem_audited"));
  }

  @Test
  void aRunCannotReceiveSourceRestrictedMemoryWithoutADurableLog() {
    bind("cnv_source", document("two"), "two");
    var memory = file("mem_durable", "cnv_source");
    var archive =
        new Archive(store, mock(ReasonLog.class), mock(EmbeddingClient.class), work, 1000, 50, 30);
    assertThrows(
        CallerFault.class,
        () ->
            archive.read(
                List.of(memory.id()),
                new MemoryReadRepository.Reader("writer", "cnv_missing"),
                () -> {}));
    assertEquals(0, store.load(memory.id(), writer).orElseThrow().uses());
    assertFalse(inputs.hasInputs("cnv_missing"));
  }
}
