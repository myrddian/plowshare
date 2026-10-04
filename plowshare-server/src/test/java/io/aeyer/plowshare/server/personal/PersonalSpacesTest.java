package io.aeyer.plowshare.server.personal;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.DefinitionResolver.Caller;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.union.Hub;
import io.aeyer.plowshare.server.union.UnionStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class PersonalSpacesTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  @TempDir Path temporary;
  DataLayout data;
  PersonalSpaces personal;
  ProjectStore projects;
  String alice, bob;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void setup() {
    data = new DataLayout(temporary.resolve("data")).initialise();
    personal = new PersonalSpaces(jdbc, data);
    projects =
        new ProjectStore(
            jdbc,
            temporary.resolve("config"),
            temporary.resolve("sampling"),
            null,
            null,
            data.root());
    alice = "alice-" + UUID.randomUUID();
    bob = "bob-" + UUID.randomUUID();
    var admins = new AdminStore(jdbc);
    admins.useEvents(
        event -> {
          if (event instanceof PersonalSpaces.AccountCreated created) personal.created(created);
        });
    admins.create(alice, "hashed");
    admins.create(bob, "hashed");
  }

  @Test
  void account_creation_provisions_a_private_union_and_retries_preserve_changes() throws Exception {
    long id = personal.id(alice).orElseThrow();
    Hub hub = new Hub(data.unionFor(id));
    assertTrue(new UnionStore(jdbc).find(PersonalSpaces.name(alice)).orElseThrow().enabled());
    assertTrue(hub.main().isPresent());
    for (String section : PersonalSpaces.SECTIONS)
      assertTrue(Files.isDirectory(hub.tree().resolve(section)));
    assertTrue(Files.exists(data.botsFor(id).resolve("default")));
    assertTrue(Files.exists(hub.tree().resolve("Resources/AGENTS.md")));
    assertTrue(Files.exists(hub.tree().resolve("Resources/skills/personal-capture/SKILL.md")));
    Files.writeString(hub.tree().resolve("Planning/plan.md"), "Keep this plan");
    Files.delete(hub.tree().resolve("In/.keep"));
    Files.delete(hub.tree().resolve("Resources/skills/personal-capture/SKILL.md"));
    personal.ensure(alice);
    assertEquals("Keep this plan", Files.readString(hub.tree().resolve("Planning/plan.md")));
    assertFalse(Files.exists(hub.tree().resolve("In/.keep")));
    assertFalse(Files.exists(hub.tree().resolve("Resources/skills/personal-capture/SKILL.md")));
    assertEquals(id, personal.id(alice).orElseThrow());
    var members = new ProjectMembers(jdbc);
    assertTrue(members.mayUse(PersonalSpaces.name(alice), alice));
    assertFalse(members.mayUse(PersonalSpaces.name(alice), bob));
    assertThrows(RuntimeException.class, () -> members.add(PersonalSpaces.name(alice), bob));
    assertTrue(
        projects.allFor(alice).stream()
            .noneMatch(row -> row.name().equals(PersonalSpaces.name(bob))));
    assertThrows(RuntimeException.class, () -> projects.forget(PersonalSpaces.name(alice)));
    assertThrows(
        RuntimeException.class,
        () -> projects.rootOn(PersonalSpaces.name(alice), "laptop", "/home/bob", bob));
    projects.rootOn(PersonalSpaces.name(alice), "laptop", "/home/alice/.plowshare/personal", alice);
    assertEquals(
        "/personal",
        new UnionStore(jdbc).find(PersonalSpaces.name(alice)).orElseThrow().workspace());
  }

  @Test
  void personal_skills_follow_the_account_in_shared_projects_and_project_overrides_win()
      throws Exception {
    long first = personal.id(alice).orElseThrow(), second = personal.id(bob).orElseThrow();
    String project = "shared-" + UUID.randomUUID();
    Path workspace = Files.createDirectory(temporary.resolve("work"));
    projects.define(project, workspace, List.of());
    long projectId = projects.id(project);
    skill(data.skillsFor(first), "review", "Alice's review");
    skill(data.skillsFor(second), "review", "Bob's review");
    var skills =
        new SkillResolver(
            data,
            (session, request) -> {
              throw new AssertionError("No file channel is needed");
            },
            projects::exists,
            session -> false,
            (id, session) -> false);
    skills.usePersonalResources(caller -> personal.id(caller.handle()).orElse(null));
    var a =
        skills.forCaller(new Caller(projectId, null, alice)).skills().get("review").definition();
    var b = skills.forCaller(new Caller(projectId, null, bob)).skills().get("review").definition();
    assertEquals(Tier.PERSONAL, a.tier());
    assertNotEquals(a.hash(), b.hash());
    skill(data.skillsFor(projectId), "review", "Project review");
    assertEquals(
        Tier.PROJECT,
        skills
            .forCaller(new Caller(projectId, null, alice))
            .skills()
            .get("review")
            .definition()
            .tier());
  }

  @Test
  void inherited_provenance_is_persisted_without_starting_paid_work() throws Exception {
    long id = personal.id(alice).orElseThrow();
    skill(data.skillsFor(id), "review", "Durable review");
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    var parent =
        conversations.open(
            Home.of(PersonalSpaces.name(alice)),
            io.aeyer.plowshare.server.agents.Budget.of(2),
            null,
            alice);
    var definition =
        io.aeyer.plowshare.server.agents.SkillDefinition.parse(
            new io.aeyer.plowshare.server.agents.DefinitionSource.Definition(
                "review",
                "Resources/skills/review/SKILL.md",
                Files.readString(data.skillsFor(id).resolve("review/SKILL.md"))),
            Tier.PERSONAL);
    var receipts = new io.aeyer.plowshare.server.agents.SkillExecutions(jdbc);
    UUID invocation = UUID.randomUUID();
    assertTrue(
        receipts.claim(
            alice,
            invocation,
            "review it",
            parent.id(),
            "interlocutor",
            definition,
            definition.mode()));
    assertEquals(Tier.PERSONAL, receipts.find(alice, invocation).orElseThrow().skill().tier());
    assertFalse(
        receipts.claim(
            alice,
            invocation,
            "review it",
            parent.id(),
            "interlocutor",
            definition,
            definition.mode()));
    var store =
        new io.aeyer.plowshare.server.orchestrations.OrchestrationStore(
            jdbc, java.time.Instant::now);
    var run =
        store.insert(
            new io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration(
                "review",
                Tier.PERSONAL,
                "sha256:fixture",
                "fixture source",
                "Resources/orchestrations/review.md",
                List.of(),
                1,
                PersonalSpaces.name(alice),
                parent.id(),
                null,
                "interlocutor",
                alice,
                null,
                null,
                0));
    assertEquals(Tier.PERSONAL, store.find(run.id()).orElseThrow().tier());
  }

  @Test
  void missing_initialized_storage_is_refused_instead_of_recreated() throws Exception {
    long id = personal.id(alice).orElseThrow();
    Hub hub = new Hub(data.unionFor(id));
    try (var files = Files.walk(hub.bare())) {
      for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList())
        Files.delete(file);
    }
    assertThrows(IllegalStateException.class, () -> personal.ensure(alice));
    assertFalse(hub.exists());
  }

  @Test
  void conversation_handles_do_not_bypass_personal_ownership() {
    var conversations = new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    var row =
        conversations.open(
            Home.of(PersonalSpaces.name(alice)),
            io.aeyer.plowshare.server.agents.Budget.of(2),
            null,
            alice);
    var access = new PersonalAccess(conversations);
    access.payload(java.util.Map.of("conversation", row.id()), alice);
    assertThrows(
        CallerFault.class, () -> access.payload(java.util.Map.of("conversation", row.id()), bob));
    assertThrows(
        CallerFault.class,
        () -> access.payload(java.util.Map.of("conversations", List.of(row.id())), bob));
  }

  @Test
  void runnable_defaults_are_personal_and_global_remains_an_abstract_resource_scope() {
    assertEquals(Home.of(PersonalSpaces.name(alice)), personal.home(null, alice));
    assertThrows(CallerFault.class, () -> personal.home(null, null));
    assertThrows(CallerFault.class, () -> personal.home(PersonalSpaces.name(bob), alice));
    assertEquals(Home.global(), io.aeyer.plowshare.server.requests.RequestedHome.in(null));
  }

  @Test
  void readable_personal_addresses_identify_one_account_and_listings_keep_the_storage_identity()
      throws Exception {
    assertEquals("Personal:" + alice, PersonalSpaces.address(alice));
    assertNotEquals(PersonalSpaces.address(alice), PersonalSpaces.address(bob));
    assertEquals(
        PersonalSpaces.name(alice),
        PersonalSpaces.resolveAddress(PersonalSpaces.address(alice), alice));
    assertThrows(
        CallerFault.class, () -> PersonalSpaces.resolveAddress(PersonalSpaces.address(bob), alice));
    var listing =
        new io.aeyer.plowshare.server.ws.ProjectListHandler(projects, new ProjectMembers(jdbc));
    var outcome =
        listing.handle(
            java.util.Map.of(), new io.aeyer.plowshare.server.ws.Asking("client", alice));
    var rows = (List<?>) outcome.payload();
    var own =
        rows.stream()
            .map(io.aeyer.plowshare.server.api.ProjectView.class::cast)
            .filter(row -> row.kind().equals("personal"))
            .toList();
    assertEquals(1, own.size());
    assertEquals(PersonalSpaces.name(alice), own.getFirst().name());
    assertEquals("Personal", own.getFirst().displayName());
    assertEquals(PersonalSpaces.address(alice), own.getFirst().routingIdentity());
    Path root = Files.createDirectory(temporary.resolve("ordinary"));
    assertThrows(
        RuntimeException.class,
        () -> projects.define(PersonalSpaces.address(alice), root, List.of(), alice));
  }

  static void skill(Path root, String name, String text) throws Exception {
    Path dir = Files.createDirectories(root.resolve(name));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: Review\nmode: DIRECT\n---\n" + text);
  }
}
