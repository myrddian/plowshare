package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.protocol.InformationRequest.FacetFilter;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Audit;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Migration, SQL keysets, live policy/dependency closure and audit writes require PostgreSQL. */
@Tag("full-db")
@Testcontainers
class InformationDiscoveryDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  InformationDiscoveryService service;
  Reader reader = new Reader("reader", null, null);

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
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('reader','fixture'),('other','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two'),('secret')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','VIEWER' FROM projects WHERE name IN ('one','two')");
    service =
        new CursorInformationDiscovery(
            new JdbcInformationDiscoveryRepository(jdbc), Clock.systemUTC());
  }

  String scope(String project) {
    return jdbc.queryForObject(
        "SELECT s.id::text FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE p.name=? AND s.kind='PROJECT'",
        String.class,
        project);
  }

  UUID retained(String project, String name, String kind) {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags) VALUES(?,?,?,'reader',(SELECT id FROM projects WHERE name=?),?,'[\"network\"]')",
        resource,
        "project:" + project,
        name,
        project,
        kind);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text,created_at) VALUES(?,?,1,?,'text/plain','fixture',1,'A😀B',?::timestamptz)",
        revision,
        resource,
        name,
        "2026-11-14T00:00:00Z");
    if (kind.equals("report"))
      jdbc.update(
          "INSERT INTO information_reports(revision_id,status) VALUES(?,'draft')", revision);
    return revision;
  }

  InformationDiscovery.Query query(List<String> scopes, int limit, String cursor) {
    return new InformationDiscovery.Query(scopes, null, null, null, limit, cursor);
  }

  @Test
  void scopes_and_multi_scope_keysets_filter_before_paging_and_do_not_duplicate_heads() {
    var scopes = new ArrayList<InformationDiscovery.Scope>();
    String cursor = null;
    do {
      var page = service.scopes(reader, new InformationDiscovery.ScopesRequest(1, cursor));
      scopes.addAll(page.items());
      cursor = page.nextCursor();
    } while (cursor != null);
    assertTrue(scopes.stream().anyMatch(s -> "one".equals(s.project())));
    assertTrue(scopes.stream().anyMatch(s -> "two".equals(s.project())));
    assertFalse(scopes.stream().anyMatch(s -> "secret".equals(s.project())));
    Set<UUID> expected =
        Set.of(
            retained("one", "Scan one", "source"),
            retained("two", "Scan two", "source"),
            retained("one", "Report", "report"));
    retained("secret", "Hidden", "source");
    var found = new HashSet<UUID>();
    var selected = List.of(scope("one"), scope("two"));
    cursor = null;
    do {
      var page = service.list(reader, query(selected, 1, cursor));
      for (var item : page.items())
        assertTrue(found.add(item.revision()), "Duplicate keyset result");
      cursor = page.nextCursor();
    } while (cursor != null);
    assertEquals(expected, found);
    assertThrows(
        NotFoundFault.class, () -> service.list(reader, query(List.of(scope("secret")), 10, null)));
  }

  @Test
  void report_filters_metadata_search_and_facets_work_without_content_indexing() {
    UUID report = retained("one", "Network privacy report", "report");
    retained("one", "Device scan", "source");
    var selected = List.of(scope("one"));
    var filter = new FacetFilter("report", null, null, null, null, null, null, null, null);
    var reports =
        service.list(
            reader, new InformationDiscovery.Query(selected, filter, null, null, 10, null));
    assertEquals(
        List.of(report),
        reports.items().stream().map(InformationDiscovery.Item::revision).toList());
    assertEquals("draft", reports.items().getFirst().reportStatus());
    assertFalse(reports.items().getFirst().contentIndexed());
    var search =
        service.search(
            reader, new InformationDiscovery.Query(selected, null, "privacy", null, 10, null));
    assertEquals(report, search.items().getFirst().revision());
    var facets =
        service.facets(
            reader, new InformationDiscovery.Query(selected, null, null, "tags", 10, null));
    assertEquals(List.of(new InformationDiscovery.Facet("network", 2)), facets.items());
    var window = service.read(reader, new InformationDiscovery.Read(selected, report, 1, 2));
    assertEquals("😀", window.text());
  }

  @Test
  void revocation_privacy_and_unreadable_dependencies_apply_to_discovery_and_exact_reads() {
    UUID visible = retained("one", "Visible", "source");
    UUID secret = retained("secret", "Secret", "source");
    UUID derived = retained("one", "Derived", "report");
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES(?,?)",
        derived,
        secret);
    var selected = List.of(scope("one"));
    assertEquals(
        List.of(visible),
        service.list(reader, query(selected, 10, null)).items().stream()
            .map(InformationDiscovery.Item::revision)
            .toList());
    assertThrows(
        NotFoundFault.class,
        () -> service.read(reader, new InformationDiscovery.Read(selected, derived, 0, 10)));
    jdbc.update(
        "DELETE FROM project_members WHERE handle='reader' AND project_id=(SELECT id FROM projects WHERE name='one')");
    assertThrows(NotFoundFault.class, () -> service.list(reader, query(selected, 10, null)));
    assertThrows(
        NotFoundFault.class,
        () -> service.read(reader, new InformationDiscovery.Read(selected, visible, 0, 10)));
  }

  @Test
  void server_owned_confinement_cannot_navigate_to_another_readable_project_or_general_audience() {
    retained("one", "Own", "source");
    retained("two", "Other", "source");
    var confined = new Reader("reader", "one", null);
    assertEquals(
        List.of("one"),
        service.scopes(confined, new InformationDiscovery.ScopesRequest(50, null)).items().stream()
            .map(InformationDiscovery.Scope::project)
            .toList());
    assertThrows(
        NotFoundFault.class, () -> service.list(confined, query(List.of(scope("two")), 10, null)));
    assertThrows(
        NotFoundFault.class, () -> service.list(confined, query(List.of("global_read"), 10, null)));
  }

  @Test
  void facets_retain_all_contributors_in_the_original_source_selection() {
    UUID one = retained("one", "One", "source"), two = retained("two", "Two", "source");
    var audited = new Reader("reader", null, new Audit("cnv_discovery_fixture", "reader"));
    service.facets(
        audited,
        new InformationDiscovery.Query(
            List.of(scope("one"), scope("two")), null, null, "tags", 1, null));
    assertEquals(
        Set.of(one, two),
        Set.copyOf(
            jdbc.queryForList(
                "SELECT revision_id FROM information_job_inputs WHERE job_id='cnv_discovery_fixture'",
                UUID.class)));
    assertEquals(
        Set.of("one", "two"),
        Set.copyOf(
            jdbc.queryForList(
                "SELECT project_name FROM information_job_inputs WHERE job_id='cnv_discovery_fixture'",
                String.class)));
  }

  @Test
  void public_selection_never_discloses_private_or_quarantined_roots() {
    UUID published = retained("secret", "Published", "source");
    UUID hidden = retained("one", "Private", "source");
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,audience='GLOBAL_READ',reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='reader') WHERE resource_id=(SELECT resource_id FROM information_revisions WHERE id=?)",
        published);
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,private_only=true,privacy_principal=(SELECT id FROM authority_accounts WHERE legacy_handle='other'),reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='reader') WHERE resource_id=(SELECT resource_id FROM information_revisions WHERE id=?)",
        hidden);
    assertEquals(
        List.of(published),
        service.list(reader, query(List.of("global_read"), 10, null)).items().stream()
            .map(InformationDiscovery.Item::revision)
            .toList());
    assertTrue(service.list(reader, query(List.of(scope("one")), 10, null)).items().isEmpty());
    assertThrows(
        NotFoundFault.class,
        () ->
            service.read(
                reader, new InformationDiscovery.Read(List.of(scope("one")), hidden, 0, 10)));
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,audience='QUARANTINED',reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle='reader') WHERE resource_id=(SELECT resource_id FROM information_revisions WHERE id=?)",
        published);
    assertTrue(service.list(reader, query(List.of("global_read"), 10, null)).items().isEmpty());
  }

  @Test
  void service_credential_scope_and_revocation_cap_the_owner_grants() {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password) VALUES('owner','fixture','SERVICE',false),('@service/probe','fixture','SERVICE_TOKEN',false)");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'owner','MANAGER' FROM projects WHERE name IN ('one','two')");
    UUID token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,'owner','probe','@service/probe',?,now()+interval '1 hour')",
        token,
        token.toString());
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'VIEWER' FROM projects WHERE name='one'",
        token);
    UUID source = retained("one", "Scan", "source");
    var tokenReader = new Reader("@service/probe", null, null);
    var projects =
        service
            .scopes(tokenReader, new InformationDiscovery.ScopesRequest(50, null))
            .items()
            .stream()
            .map(InformationDiscovery.Scope::project)
            .toList();
    assertTrue(projects.contains("one"));
    assertFalse(projects.contains("two"));
    assertEquals(
        source,
        service
            .list(tokenReader, query(List.of(scope("one")), 10, null))
            .items()
            .getFirst()
            .revision());
    assertThrows(
        NotFoundFault.class,
        () -> service.list(tokenReader, query(List.of(scope("two")), 10, null)));
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
    assertTrue(
        service
            .scopes(tokenReader, new InformationDiscovery.ScopesRequest(50, null))
            .items()
            .isEmpty());
    assertThrows(
        NotFoundFault.class,
        () ->
            service.read(
                tokenReader, new InformationDiscovery.Read(List.of(scope("one")), source, 0, 10)));
  }

  @Test
  void contained_identity_discovers_only_its_subtree_and_never_the_container_account_union() {
    String project = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES(?)", project);
    UUID childScope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES(?,'CONTAINED',(SELECT id FROM projects WHERE name=?),?::uuid)",
        childScope,
        project,
        scope("one"));
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES(?)", childScope);
    String principal =
        jdbc.queryForObject(
            "SELECT id::text FROM contained_principals WHERE scope_id=?", String.class, childScope);
    UUID source = retained(project, "Child evidence", "source");
    retained("two", "Outside", "source");
    var child = new Reader(principal, project, null);
    assertEquals(
        List.of(childScope.toString()),
        service.scopes(child, new InformationDiscovery.ScopesRequest(50, null)).items().stream()
            .map(InformationDiscovery.Scope::id)
            .toList());
    assertEquals(
        source,
        service
            .list(child, query(List.of(childScope.toString()), 10, null))
            .items()
            .getFirst()
            .revision());
    assertThrows(
        NotFoundFault.class, () -> service.list(child, query(List.of(scope("two")), 10, null)));
    assertThrows(
        NotFoundFault.class, () -> service.list(child, query(List.of("global_read"), 10, null)));
  }

  @Test
  void list_retains_only_disclosed_heads_and_rechecks_access_after_a_cursor_is_issued() {
    retained("one", "Older", "source");
    retained("one", "Other", "source");
    var selected = List.of(scope("one"));
    var audited = new Reader("reader", null, new Audit("cnv_page_fixture", "reader"));
    var first = service.list(audited, query(selected, 1, null));
    assertTrue(first.hasMore());
    assertEquals(
        List.of(first.items().getFirst().revision()),
        jdbc.queryForList(
            "SELECT revision_id FROM information_job_inputs WHERE job_id='cnv_page_fixture'",
            UUID.class));
    jdbc.update(
        "DELETE FROM project_members WHERE handle='reader' AND project_id=(SELECT id FROM projects WHERE name='one')");
    assertThrows(
        NotFoundFault.class, () -> service.list(audited, query(selected, 1, first.nextCursor())));
  }

  @Test
  void inventory_uses_one_current_head_but_exact_reads_can_follow_history() {
    UUID original = retained("one", "Versioned", "source"), head = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,extracted_text) SELECT ?,resource_id,2,'Current title','text/plain','fixture-new',7,'updated' FROM information_revisions WHERE id=?",
        head,
        original);
    var selected = List.of(scope("one"));
    var page = service.list(reader, query(selected, 10, null));
    assertEquals(
        List.of(head), page.items().stream().map(InformationDiscovery.Item::revision).toList());
    assertEquals(
        "A😀B",
        service.read(reader, new InformationDiscovery.Read(selected, original, 0, 10)).text());
  }
}
