package io.aeyer.plowshare.server.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.EventRuns;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.SkillDefinition;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.agents.WorkCallers;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcJobLog;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.BoardMessage;
import io.aeyer.plowshare.server.board.BoardStore;
import io.aeyer.plowshare.server.board.BoardTopic;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.ApplicationInboxStore.Notice;
import io.aeyer.plowshare.server.events.ApplicationInboxes;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.JdbcApplicationInboxStore;
import io.aeyer.plowshare.server.events.JdbcFiringStore;
import io.aeyer.plowshare.server.events.JdbcInboxStore;
import io.aeyer.plowshare.server.events.ScheduleDefinitions;
import io.aeyer.plowshare.server.events.ScheduleRecord;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.events.TriggerStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.information.InformationEventCodec;
import io.aeyer.plowshare.server.information.InformationEvents;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.information.JdbcInformationCatalogueRepository;
import io.aeyer.plowshare.server.information.JdbcInformationEventRecords;
import io.aeyer.plowshare.server.information.JdbcJobInformationRepository;
import io.aeyer.plowshare.server.information.JdbcLogTreeFactRepository;
import io.aeyer.plowshare.server.information.LogTreeFact;
import io.aeyer.plowshare.server.information.RelayInformationEvents;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayForwardingHistory;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayRetirementRepository;
import io.aeyer.plowshare.server.relay.JdbcRelaySourceRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumption;
import io.aeyer.plowshare.server.relay.RelayNativeRepository;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real database coverage for transactional capture, deduplication and the retained cursor. */
@Tag("full-db")
@Testcontainers
class PersonalEventSubscriptionDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String OWNER = "subscriber";
  private static final String OTHER = "another";
  private static final String OPERATOR = "controller";
  private static final String PROJECT = "research";
  private static final String SCHEDULE = "price-watch";
  private static final String PERSONAL = PersonalSpaces.name(OWNER);
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;

  @TempDir Path workspace;
  private long projectId;
  private long otherId;
  private long sourceId;
  private Path otherWorkspace;
  private JdbcProjectMembers members;
  private Relay relay;
  private JdbcRelayRepository relayRepository;
  private Relay.TopicKey sourceTopic;
  private PersonalSubscriptionRuntime runtime;
  private JdbcRelaySourceRepository publisher;
  private JdbcFiringStore firings;
  private JdbcInboxStore inbox;
  private JdbcApplicationInboxStore applicationRows;
  private ApplicationInboxes applications;
  private JdbcJobLog jobLog;
  private OrchestrationStore orchestrationLog;
  private BoardStore board;
  private InformationEvents informationEvents;
  private TriggerRecord trigger;
  private EventRuns jobs;
  private MessageService messages;
  private PersonalScheduleAlerts scheduleAlerts;
  private final List<String> prompts = new ArrayList<>();
  private final List<String> runOwners = new ArrayList<>();
  private final List<EventRuns.SubscriptionSource> inputSources = new ArrayList<>();

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
  }

  @BeforeEach
  void setup() throws Exception {
    AuthorityFixtures.truncate(jdbc, "TRUNCATE user_inbox, firings, admins, projects CASCADE");
    jdbc.update(
        """
        INSERT INTO admins(handle,password_hash,server_admin,must_change_password)
        VALUES (?,'fixture',false,false),(?,'fixture',false,false),(?,'fixture',true,false)
        """,
        OWNER,
        OTHER,
        OPERATOR);
    projectId =
        jdbc.queryForObject(
            "INSERT INTO projects(name,personal_owner) VALUES (?,?) RETURNING id",
            Long.class,
            PERSONAL,
            OWNER);
    otherId =
        jdbc.queryForObject(
            "INSERT INTO projects(name,personal_owner) VALUES (?,?) RETURNING id",
            Long.class,
            PersonalSpaces.name(OTHER),
            OTHER);
    sourceId =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES (?) RETURNING id", Long.class, PROJECT);
    members = new JdbcProjectMembers(jdbc);
    members.assign(PROJECT, OWNER, ProjectRole.VIEWER, OPERATOR, true);
    members.assign(PROJECT, OTHER, ProjectRole.VIEWER, OPERATOR, true);
    relayRepository =
        new JdbcRelayRepository(
            jdbc,
            work,
            new JdbcRelayPayloadStore(
                jdbc, work, Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(4)));
    relay = new DurableRelay(relayRepository, Instant::now);
    sourceTopic = new Relay.TopicKey(sourceId, "research.completed");
    publisher = new JdbcRelaySourceRepository(jdbc, work, relay, mock(RelayNativeRepository.class));
    firings = new JdbcFiringStore(jdbc);
    inbox = new JdbcInboxStore(jdbc);
    applicationRows = new JdbcApplicationInboxStore(jdbc, work);
    var policy = mock(ApplicationPolicy.class);
    var applicationWorkspace = Files.createDirectory(workspace.resolve("application"));
    Files.writeString(applicationWorkspace.resolve("plowshare.json"), "{}");
    when(policy.source(PROJECT))
        .thenReturn(
            new ApplicationPolicy.Source(
                new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.APPLICATION, Map.of()),
                Optional.of(
                    new ProjectRecord(
                        PROJECT,
                        applicationWorkspace,
                        List.of(),
                        List.of(),
                        "MANAGED",
                        List.of()))));
    applications =
        new ApplicationInboxes(
            policy, members, applicationRows, AccountPushes.NONE, inbox, Instant::now);
    jobLog = new JdbcJobLog(jdbc);
    orchestrationLog = new OrchestrationStore(jdbc, Instant::now, work);
    board = new BoardStore(jdbc, Instant::now);
    var informationProjects = mock(ProjectWorkspaces.class);
    when(informationProjects.id(PROJECT)).thenReturn(sourceId);
    informationEvents =
        new RelayInformationEvents(
            new JdbcInformationEventRecords(jdbc),
            relay,
            mock(RelayConsumption.class),
            informationProjects,
            Instant::now);
    var informationCatalogue =
        new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC(), informationEvents);
    var selections = mock(InformationAccess.class);
    when(selections.resolve(any(), any()))
        .thenAnswer(call -> new InformationContext(call.getArgument(0), call.getArgument(1)));
    var nativeSources =
        new PersonalNativeSources(
            applications,
            inbox,
            jobLog,
            orchestrationLog,
            new InformationJobs(new JdbcJobInformationRepository(jdbc), selections),
            board,
            selections,
            (context, revision) -> {
              if (!informationCatalogue.readable(context, revision))
                throw new NotFoundFault("Revision unavailable");
            });
    var personal = mock(PersonalSpaces.class);
    when(personal.id(OWNER)).thenReturn(Optional.of(projectId));
    when(personal.owner(projectId)).thenReturn(Optional.of(OWNER));
    when(personal.workspace(PERSONAL)).thenReturn(Optional.of(workspace));
    Files.createDirectories(workspace.resolve("Resources"));
    otherWorkspace = workspace.resolve("other");
    Files.createDirectories(otherWorkspace.resolve("Resources"));
    when(personal.id(OTHER)).thenReturn(Optional.of(otherId));
    when(personal.owner(otherId)).thenReturn(Optional.of(OTHER));
    when(personal.workspace(PersonalSpaces.name(OTHER))).thenReturn(Optional.of(otherWorkspace));
    var callers = mock(WorkCallers.class);
    var access = new DefinitionResolver.Caller(projectId, null, OWNER);
    var otherAccess = new DefinitionResolver.Caller(otherId, null, OTHER);
    when(callers.callerFor(PERSONAL, null, OWNER)).thenReturn(access);
    when(callers.callerFor(PersonalSpaces.name(OTHER), null, OTHER)).thenReturn(otherAccess);
    var agent =
        new AgentDefinition(
                "assistant", "assistant", "model", List.of(), List.of(), List.of(), 8, 8, "Help")
            .withSkills(List.of("track"));
    when(callers.requireAgent("assistant", access)).thenReturn(agent);
    when(callers.requireAgent("assistant", otherAccess)).thenReturn(agent);
    var skill = mock(SkillDefinition.class);
    when(skill.hash()).thenReturn("a".repeat(64));
    var skills = mock(SkillResolver.class);
    var catalogue =
        new SkillResolver.Catalog(
            Map.of("track", new SkillResolver.Resolved(skill, null)), Map.of());
    when(skills.forCaller(access)).thenReturn(catalogue);
    when(skills.forCaller(otherAccess)).thenReturn(catalogue);
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id(PROJECT)).thenReturn(sourceId);
    var schedules = mock(ScheduleStore.class);
    when(schedules.find(SCHEDULE))
        .thenReturn(
            Optional.of(
                new ScheduleRecord(
                    SCHEDULE, "0 0 * * * *", "UTC", "prices.due", false, Instant.now(), OWNER)));
    trigger =
        new TriggerRecord(
            "capture-price",
            "prices.due",
            PROJECT,
            null,
            "researcher",
            "Find prices",
            null,
            null,
            1,
            false,
            OWNER);
    var triggers = mock(TriggerStore.class);
    when(triggers.listening("prices.due")).thenReturn(List.of(trigger));
    when(triggers.find(trigger.name())).thenReturn(Optional.of(trigger));
    var history = new JdbcRelayForwardingHistory(jdbc);
    jobs = mock(EventRuns.class);
    messages = mock(MessageService.class);
    var sql =
        new SqlWorkBudget() {
          public <T> T within(Duration budget, Supplier<T> action) {
            return action.get();
          }
        };
    runtime =
        new PersonalSubscriptionRuntime(
            personal,
            new JdbcPersonalSubscriptionStore(jdbc),
            relay,
            new JdbcRelayRetirementRepository(jdbc, work),
            history,
            8,
            mock(ScheduleDefinitions.class),
            schedules,
            triggers,
            firings,
            members,
            projects,
            callers,
            skills,
            inbox,
            nativeSources,
            jobs,
            () -> messages,
            work,
            sql,
            Duration.ofSeconds(2));
    scheduleAlerts =
        new PersonalScheduleAlerts(
            new JdbcPersonalScheduleAlertStore(jdbc),
            inbox,
            () -> messages,
            work,
            sql,
            Duration.ofSeconds(2),
            8);
  }

  @Test
  void board_posts_and_resolutions_use_the_exact_public_messages() throws Exception {
    activateNative(
        OWNER,
        workspace,
        nativeEntry(
            "board-post",
            "{\"kind\":\"board\",\"project\":\"research\",\"event\":\"message.posted\"}"),
        nativeEntry(
            "board-resolution",
            "{\"kind\":\"board\",\"project\":\"research\",\"event\":\"resolved\"}"));
    var thread =
        board.openRoot(
            new BoardStore.NewTopic(
                PROJECT,
                "Investigation",
                "REVIEW",
                OWNER,
                BoardTopic.BY_PERSON,
                OWNER,
                null,
                8,
                1));
    String privateLog = "cnv_private_board";
    finishJob("job_private_board", privateLog);
    var post =
        board.post(
            new BoardStore.NewMessage(
                thread.id(),
                null,
                BoardMessage.BY_PERSON,
                OWNER,
                privateLog,
                null,
                BoardMessage.POST,
                null,
                "Board report text",
                false,
                List.of()));
    assertEquals(1, publish(new Relay.TopicKey(sourceId, "board.message.posted")));
    recordNativeJobs();
    runtime.process();
    var resolution =
        board.post(
            new BoardStore.NewMessage(
                thread.id(),
                post.id(),
                BoardMessage.BY_PERSON,
                OWNER,
                null,
                null,
                BoardMessage.RESOLUTION,
                null,
                "Board resolution text",
                false,
                List.of()));
    assertTrue(board.close(thread.id(), resolution.id()));
    assertEquals(1, publish(new Relay.TopicKey(sourceId, "board.resolved")));
    runtime.process();
    assertEquals(List.of(OWNER, OWNER), runOwners);
    assertTrue(
        prompts.getFirst().contains(post.id()) && prompts.getFirst().contains("Board report text"));
    assertTrue(
        prompts.getLast().contains(resolution.id())
            && prompts.getLast().contains("Board resolution text"));
    assertTrue(prompts.stream().noneMatch(prompt -> prompt.contains(privateLog)));
    assertEquals(
        List.of(new EventRuns.ProjectSource(sourceId), new EventRuns.ProjectSource(sourceId)),
        inputSources);
  }

  @Test
  void report_finalisation_needs_the_exact_revision_grant_and_ignores_other_publishers_and_stages()
      throws Exception {
    String source =
        "{\"kind\":\"information\",\"project\":\"research\",\"event\":\"report.finalised\"}";
    activateNative(OWNER, workspace, nativeEntry("information-report", source));
    activateNative(OTHER, otherWorkspace, nativeEntry("information-report", source));
    var topic = RelayInformationEvents.subscriptionTopic(sourceId);
    assertThrows(
        CallerFault.class,
        () ->
            activateNative(
                OWNER,
                workspace,
                nativeEntry(
                    "raw-topic",
                    "{\"kind\":\"relay\",\"project\":\"research\",\"topic\":\""
                        + topic.name()
                        + "\"}")));
    activateNative(OWNER, workspace, nativeEntry("information-report", source));

    UUID resource = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind)"
            + " VALUES(?,?,?,?,?,'source')",
        resource,
        "project:" + PROJECT,
        "fixture",
        OWNER,
        sourceId);
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size)"
            + " VALUES(?,?,1,'private report','text/plain','fixture',1)",
        revision,
        resource);
    jdbc.update(
        "UPDATE information_resource_policies SET version=version+1,private_only=true,"
            + " reason='AUDIENCE',actor=(SELECT id FROM authority_accounts WHERE legacy_handle=?) WHERE resource_id=?",
        OWNER,
        resource);
    String forged =
        InformationEventCodec.write(
            new InformationEventCodec.Notice(
                revision.toString(), 1, "report", "finalised", "document", null, OWNER));
    relay.publish(
        topic,
        new Relay.Draft(
            "fake-information",
            "sdk.fixture",
            Instant.now(),
            null,
            null,
            new RelayPayload.Text(forged)));
    informationEvents.publish(
        new InformationEvents.Occurrence(revision, 1, OWNER, "extract", "ready", "Only a stage"));
    informationEvents.publish(
        new InformationEvents.Occurrence(
            revision, 1, OWNER, "report", "finalised", "Private detail"));

    recordNativeJobs();
    runtime.process();
    runtime.process();
    assertTrue(prompts.isEmpty());
    runtime.process();
    runtime.process();
    assertEquals(List.of(OWNER), runOwners);
    assertTrue(prompts.getFirst().contains(revision.toString()));
    assertFalse(prompts.getFirst().contains("fake-information"));
    assertFalse(prompts.getFirst().contains("Private detail"));
    assertEquals("source-unavailable", status(OTHER, "information-report").state());
    assertNull(status(OTHER, "information-report").job());
    var retained = (EventRuns.RevisionSource) inputSources.getFirst();
    assertEquals(revision, retained.revision());
    assertEquals(sourceId, retained.projectId());
    assertEquals(
        new InformationContext.Selection(InformationContext.Scope.PROJECT, PROJECT, false),
        retained.context().selection());
    sourceConversation("cnv_report_input");
    var logs = new JdbcJobInformationRepository(jdbc);
    logs.bind("cnv_report_input", retained.context(), List.of(revision));
    assertTrue(logs.logAllowed("cnv_report_input", OWNER));
    assertFalse(logs.logAllowed("cnv_report_input", OTHER));
  }

  @Test
  void application_result_and_attention_copy_keep_separate_identity_and_read_state()
      throws Exception {
    members.assign(PROJECT, OWNER, ProjectRole.MANAGER, OPERATOR, false);
    applicationRows.append(
        sourceId,
        "earlier",
        new Notice(OWNER, "finding", null, null, "Historical", null, true),
        List.of(OWNER),
        Instant.now());
    activateNative(
        OWNER,
        workspace,
        applicationEntry("project-item", "project"),
        applicationEntry("personal-item", "attention"));
    activateNative(OTHER, otherWorkspace, applicationEntry("viewer-item", "project"));
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  applicationRows.append(
                      sourceId,
                      "rollback",
                      new Notice(OWNER, "finding", null, null, "Rolled back", null, true),
                      List.of(OWNER),
                      Instant.now());
                  throw new IllegalStateException("roll back both copies and their events");
                }));
    String source = "cnv_private_application";
    finishJob("job_private_application", source);
    var result = new Notice(OWNER, "run", source, "ANSWERED", "Published result", null, true);
    assertEquals(
        List.of(OWNER),
        applicationRows.append(sourceId, "future", result, List.of(OWNER), Instant.now()));
    assertEquals(
        List.of(),
        applicationRows.append(sourceId, "future", result, List.of(OWNER), Instant.now()));
    applicationRows.append(
        sourceId,
        "question",
        new Notice(OWNER, "approval", null, null, "Human approval", "approval:fixture", true),
        List.of(OWNER),
        Instant.now());
    assertEquals(2, publish(new Relay.TopicKey(sourceId, PersonalNativeSources.APPLICATION_TOPIC)));
    assertEquals(2, publish(new Relay.TopicKey(projectId, PersonalNativeSources.ATTENTION_TOPIC)));
    recordNativeJobs();
    runtime.process();
    assertTrue(prompts.isEmpty());
    runtime.process();
    runtime.process();
    assertEquals(3, prompts.size());
    String projectItem = status(OWNER, "project-item").item();
    String personalItem = status(OWNER, "personal-item").item();
    assertFalse(projectItem.equals(personalItem));
    assertEquals(projectItem, status(OTHER, "viewer-item").item());
    assertTrue(
        prompts.stream()
            .anyMatch(
                prompt -> prompt.contains(projectItem) && prompt.contains("\"copy\":\"project\"")));
    assertTrue(
        prompts.stream()
            .anyMatch(
                prompt ->
                    prompt.contains(personalItem) && prompt.contains("\"copy\":\"attention\"")));
    assertTrue(
        prompts.stream()
            .allMatch(prompt -> prompt.contains("Published result") && !prompt.contains(source)));
    assertTrue(inputSources.contains(new EventRuns.ProjectSource(sourceId)));
    assertTrue(inputSources.contains(new EventRuns.PersonalSource()));
    assertNull(applicationRows.subscriptionItem(sourceId, projectItem).orElseThrow().readAt());
    assertNull(
        inbox.attentionResult(OWNER, sourceId, personalItem, projectItem).orElseThrow().readAt());
    assertTrue(inbox.attentionResult(OTHER, sourceId, personalItem, projectItem).isEmpty());
  }

  @Test
  void issued_attention_copy_remains_available_after_the_application_grant_is_removed()
      throws Exception {
    members.assign(PROJECT, OWNER, ProjectRole.MANAGER, OPERATOR, false);
    activateNative(
        OWNER,
        workspace,
        applicationEntry("project-item", "project"),
        applicationEntry("personal-item", "attention"));
    applications.publish(OWNER, PROJECT, UUID.randomUUID(), "finding", "Issued attention copy");
    assertEquals(1, publish(new Relay.TopicKey(sourceId, PersonalNativeSources.APPLICATION_TOPIC)));
    assertEquals(1, publish(new Relay.TopicKey(projectId, PersonalNativeSources.ATTENTION_TOPIC)));
    members.remove(PROJECT, OWNER, OPERATOR);
    recordNativeJobs();
    runtime.process();
    assertEquals(List.of(new EventRuns.PersonalSource()), inputSources);
    assertTrue(prompts.getFirst().contains("Issued attention copy"));
    assertEquals("blocked", status(OWNER, "project-item").state());
    assertTrue(status(OWNER, "personal-item").item().startsWith("inb_"));
  }

  @Test
  void job_ending_uses_the_owning_record_and_ignores_an_untrusted_publisher() throws Exception {
    var topic = new Relay.TopicKey(sourceId, PersonalNativeSources.JOB_TOPIC);
    activateNative(OWNER, workspace, lifecycleEntry("job-event", "job"));
    String job = "job_ended_project";
    finishJob(job, "cnv_ended_project");
    var valid = jobLog.find(job).orElseThrow();
    relay.publish(
        topic,
        new Relay.Draft(
            "fake-event",
            "sdk.fixture",
            valid.endedAt(),
            null,
            null,
            new RelayPayload.Lifecycle(topic.name(), job, "ANSWERED", "researcher", null)));
    assertEquals(1, publish(topic));
    recordNativeJobs();
    runtime.process();
    assertTrue(prompts.isEmpty());
    runtime.process();
    runtime.process();
    assertEquals(List.of(new EventRuns.LogSource("cnv_ended_project", sourceId)), inputSources);
    assertTrue(prompts.getFirst().contains(job));
    assertTrue(prompts.getFirst().contains("ANSWERED"));
    assertFalse(prompts.getFirst().contains("fake-event"));
  }

  @Test
  void orchestration_ending_reaches_only_its_caller_even_with_two_project_viewers()
      throws Exception {
    var topic = new Relay.TopicKey(sourceId, PersonalNativeSources.ORCHESTRATION_TOPIC);
    activateNative(OWNER, workspace, lifecycleEntry("orch-event", "orchestration"));
    activateNative(OTHER, otherWorkspace, lifecycleEntry("orch-event", "orchestration"));
    sourceConversation("cnv_orchestration");
    var run =
        orchestrationLog.insert(
            new OrchestrationStore.NewOrchestration(
                "investigate",
                OrchestrationDefinition.Tier.GLOBAL,
                "fixture",
                "fixture",
                "fixture",
                List.of(),
                0,
                PROJECT,
                "cnv_orchestration",
                null,
                "researcher",
                OWNER,
                null,
                null,
                0));
    assertTrue(orchestrationLog.finish(run.id(), "Private workflow result"));
    assertEquals(1, publish(topic));
    recordNativeJobs();
    runtime.process();
    assertEquals(List.of(OWNER), runOwners);
    assertEquals(List.of(new EventRuns.LogSource("cnv_orchestration", sourceId)), inputSources);
    assertTrue(prompts.getFirst().contains(run.id()));
    assertFalse(prompts.getFirst().contains("Private workflow result"));
    assertEquals("source-unavailable", status(OTHER, "orch-event").state());
    assertNull(status(OTHER, "orch-event").job());
  }

  private void sourceConversation(String log) {
    jdbc.update(
        """
        INSERT INTO conversations(id,project_id,origin,agent,created_at,budget_total,budget_spent)
        VALUES (?,?,'event','researcher',clock_timestamp(),8,0)
        """,
        log,
        sourceId);
  }

  private void finishJob(String job, String log) {
    sourceConversation(log);
    Instant now = Instant.now();
    jobLog.started(job, "researcher", Home.of(PROJECT), now, log, null);
    assertTrue(jobLog.ended(job, Outcome.Ending.ANSWERED, 1, 1, Instant.now()));
  }

  private PersonalEventSubscriptions.Status status(String owner, String id) {
    return runtime.preview(owner).active().stream()
        .filter(item -> item.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static String applicationEntry(String id, String copy) {
    return nativeEntry(
        id,
        """
        {"kind":"application-inbox","project":"research","copy":"%s"}"""
            .formatted(copy));
  }

  private static String lifecycleEntry(String id, String kind) {
    return nativeEntry(
        id,
        """
        {"kind":"%s","project":"research","event":"ended"}"""
            .formatted(kind));
  }

  private static String nativeEntry(String id, String source) {
    return """
        {"id":"%s","source":%s,"handler":{"kind":"skill","agent":"assistant","name":"track"},"paused":false}
        """
        .formatted(id, source);
  }

  private void activateNative(String owner, Path root, String... entries) throws Exception {
    Files.writeString(
        root.resolve("Resources/subscriptions.json"),
        "{\"version\":1,\"subscriptions\":[" + String.join(",", entries) + "]}");
    var draft = runtime.preview(owner);
    runtime.activate(
        owner, new PersonalEventSubscriptions.Activation(draft.revision(), draft.activeRevision()));
  }

  private void recordNativeJobs() {
    doAnswer(
            call -> {
              String owner = call.getArgument(4);
              assertEquals(Home.of(PersonalSpaces.name(owner)), call.getArgument(2));
              var run = (EventRuns.SubscriptionSkill) call.getArgument(7);
              run.requireSource().run();
              String job = "job_Native" + prompts.size();
              run.started().accept(job);
              runOwners.add(owner);
              inputSources.add(run.source());
              prompts.add(call.getArgument(1));
              return new JobStore.EventRun(job, "cnv_native");
            })
        .when(jobs)
        .submitSubscriptionSkill(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void viewers_follow_an_existing_topic_independently_through_personal_only() throws Exception {
    assertEquals(Optional.of(ProjectRole.VIEWER), members.role(PROJECT, OWNER));
    assertFalse(members.mayWork(PROJECT, OWNER));
    topic(Duration.ofDays(1));
    publishTopic("historical", "Earlier result");
    activateRelay(OWNER, workspace, false);
    activateRelay(OTHER, otherWorkspace, true);
    recordTopicJobs();
    publishTopic("first", "Exact shared publication");
    runtime.process();
    assertEquals(List.of(OWNER), runOwners);
    assertTrue(prompts.getFirst().contains("Exact shared publication"));
    assertFalse(prompts.getFirst().contains("Earlier result"));
    assertEquals("first", runtime.preview(OWNER).active().getFirst().event());
    assertNull(runtime.preview(OTHER).active().getFirst().event());
    assertNull(runtime.preview(OWNER).active().getFirst().item());

    activateRelay(OTHER, otherWorkspace, false);
    runtime.process();
    runtime.process();
    assertEquals(List.of(OWNER, OTHER), runOwners);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscriptions WHERE topic=?",
            Integer.class,
            sourceTopic.name()));
  }

  @Test
  void queued_project_input_and_retained_logs_require_current_read_access() throws Exception {
    topic(Duration.ofDays(1));
    activateRelay(OWNER, workspace, false);
    publishTopic("restricted", "Private research result");
    interruptStart();
    var logs = new JdbcJobInformationRepository(jdbc);
    for (String log : List.of("cnv_relay", "cnv_copy"))
      jdbc.update(
          """
          INSERT INTO conversations(id,project_id,origin,agent,created_at,budget_total,budget_spent)
          VALUES (?,?,'event','assistant',clock_timestamp(),8,0)
          """,
          log,
          projectId);
    logs.bindSourceProject("cnv_relay", sourceId);
    logs.inherit("cnv_relay", "cnv_copy");
    assertTrue(logs.logAllowed("cnv_copy", OWNER));
    assertTrue(logs.hasInputs("cnv_relay"));
    assertTrue(
        new JdbcLogTreeFactRepository(jdbc).tree("cnv_relay") instanceof LogTreeFact.Oversized);

    members.remove(PROJECT, OWNER, OPERATOR);
    recordTopicJobs();
    runtime.process();
    assertTrue(runOwners.isEmpty());
    var status = runtime.preview(OWNER).active().getFirst();
    assertEquals("blocked", status.state());
    assertNull(status.event());
    assertNull(status.job());
    assertFalse(logs.logAllowed("cnv_relay", OWNER));
    assertFalse(logs.logAllowed("cnv_copy", OWNER));
    assertTrue(
        jdbc.queryForList("SELECT log FROM information_unreadable_logs(?)", String.class, OWNER)
            .containsAll(List.of("cnv_relay", "cnv_copy")));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM personal_mailbox_subscriptions WHERE relay_input IS NOT NULL",
            Integer.class));
  }

  @Test
  void admitted_publication_survives_retention_and_unseen_gap_blocks() throws Exception {
    topic(Duration.ofSeconds(1));
    activateRelay(OWNER, workspace, false);
    publishTopic("admitted", "Retain this input");
    interruptStart();
    assertEquals(1, relayRepository.prune(sourceTopic, Instant.now().plusSeconds(60), 10));
    recordTopicJobs();
    runtime.process();
    assertTrue(prompts.getFirst().contains("Retain this input"));

    // A second owner watches a later occurrence that expires before admission.
    activateRelay(OTHER, otherWorkspace, false);
    publishTopic("unseen", "Expired before admission");
    assertEquals(1, relayRepository.prune(sourceTopic, Instant.now().plusSeconds(60), 10));
    runtime.process();
    assertEquals(List.of(OWNER), runOwners);
    assertEquals("blocked", runtime.preview(OTHER).active().getFirst().state());
    assertTrue(runtime.preview(OTHER).active().getFirst().detail().contains("expired"));
  }

  @Test
  void recreated_topic_requires_an_explicit_new_binding() throws Exception {
    topic(Duration.ofDays(1));
    activateRelay(OWNER, workspace, false);
    var original = relay.topic(sourceTopic).generation();
    jdbc.update(
        "DELETE FROM relay_topics WHERE project_id=? AND name=?", sourceId, sourceTopic.name());
    topic(Duration.ofDays(1));
    assertFalse(original.equals(relay.topic(sourceTopic).generation()));
    publishTopic("replacement", "Replacement topic input");
    recordTopicJobs();
    runtime.process();
    assertTrue(runOwners.isEmpty());
    assertEquals("blocked", runtime.preview(OWNER).active().getFirst().state());
    assertThrows(CallerFault.class, () -> activateRelay(OWNER, workspace, false));
  }

  private void activateRelay(String owner, Path root, boolean paused) throws Exception {
    Files.writeString(
        root.resolve("Resources/subscriptions.json"),
        """
        {"version":1,"subscriptions":[{"id":"prices","source":{
          "kind":"relay","project":"research","topic":"research.completed"},
          "handler":{"kind":"skill","agent":"assistant","name":"track"},"paused":%s}]}
        """
            .formatted(paused));
    var draft = runtime.preview(owner);
    runtime.activate(
        owner, new PersonalEventSubscriptions.Activation(draft.revision(), draft.activeRevision()));
  }

  private void interruptStart() {
    when(jobs.submitSubscriptionSkill(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new AssertionError("worker died before reserving a job id"));
    assertThrows(AssertionError.class, runtime::process);
    assertEquals("starting", runtime.preview(OWNER).active().getFirst().state());
    jdbc.update(
        "UPDATE personal_mailbox_subscriptions SET updated_at=clock_timestamp()-interval '2 minutes'");
  }

  private void recordTopicJobs() {
    doAnswer(
            call -> {
              String owner = call.getArgument(4);
              assertEquals(Home.of(PersonalSpaces.name(owner)), call.getArgument(2));
              var run = (EventRuns.SubscriptionSkill) call.getArgument(7);
              assertEquals(new EventRuns.ProjectSource(sourceId), run.source());
              run.requireSource().run();
              String job = "job_Topic" + owner;
              run.started().accept(job);
              runOwners.add(owner);
              prompts.add(call.getArgument(1));
              return new JobStore.EventRun(job, "cnv_topic");
            })
        .when(jobs)
        .submitSubscriptionSkill(any(), any(), any(), any(), any(), any(), any(), any());
  }

  private void topic(Duration retention) {
    relay.registerTopic(sourceTopic, RelayPayload.Kind.TEXT, new Relay.Policy(retention, null));
  }

  private void publishTopic(String event, String text) {
    relay.publish(
        sourceTopic,
        new Relay.Draft(
            event, "publisher", Instant.now(), null, null, new RelayPayload.Text(text)));
  }

  @Test
  void a_personal_schedule_opt_in_enqueues_once_with_the_committed_unread_result() {
    long source =
        jdbc.queryForObject(
            "INSERT INTO schedule_sources(account,project_id,source) VALUES (?,?,'server') RETURNING id",
            Long.class,
            OWNER,
            projectId);
    var name = "scheduled-" + source + "-" + SCHEDULE;
    jdbc.update(
        """
        INSERT INTO schedule_files(source_id,name,internal_name,definition,status)
        VALUES (?,?,?,CAST(? AS jsonb),'active')
        """,
        source,
        SCHEDULE,
        name,
        "{\"target\":{\"kind\":\"mailbox\"},\"notifyPersonalBot\":{\"route\":\"personal-alerts\"}}");
    String firing = firing(name, 4);
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  inbox.deliver(
                      OWNER, firing, "cnv_original", "ANSWERED", "A result", Instant.now());
                  throw new IllegalStateException("roll back result and notification");
                }));
    scheduleAlerts.process();
    verifyNoInteractions(messages);

    var report =
        inbox.deliver(OWNER, firing, "cnv_original", "ANSWERED", "A result", Instant.now());
    var reference = new MessageService.PersonalAlert(report.id(), firing);
    when(messages.sendPersonalAlert(eq(OWNER), eq("personal-alerts"), eq(reference), any()))
        .thenReturn("bdm_Queued");
    scheduleAlerts.process();
    scheduleAlerts.process();
    verify(messages).sendPersonalAlert(eq(OWNER), eq("personal-alerts"), eq(reference), any());
    assertEquals(
        "bdm_Queued",
        jdbc.queryForObject(
            "SELECT message FROM personal_schedule_bot_alerts WHERE item=?",
            String.class,
            report.id()));
    assertNull(inbox.scheduledResult(OWNER, report.id(), firing).orElseThrow().readAt());
  }

  @Test
  void an_application_reaction_can_alert_on_a_large_result_without_reading_its_body()
      throws Exception {
    activateNative(
        OWNER,
        workspace,
        """
        {"id":"result","source":{"kind":"application-inbox","project":"research","copy":"project"},
         "handler":{"kind":"message","route":"personal-alerts"},"paused":false}
        """);
    var large = new Notice(OWNER, "finding", null, null, "x".repeat(40_000), null, false);
    applicationRows.append(sourceId, "large", large, List.of(), Instant.now());
    String item = applications.page(OWNER, PROJECT, true, 0, 1).items().getFirst().id();
    var reference = new MessageService.PersonalAlert(item, null);
    when(messages.sendPersonalAlert(eq(OWNER), eq("personal-alerts"), eq(reference), any()))
        .thenReturn("bdm_Application");
    assertEquals(1, publish(new Relay.TopicKey(sourceId, PersonalNativeSources.APPLICATION_TOPIC)));
    runtime.process();
    verify(messages).sendPersonalAlert(eq(OWNER), eq("personal-alerts"), eq(reference), any());
    verifyNoInteractions(jobs);
    assertEquals("Queued message bdm_Application", status(OWNER, "result").detail());
    assertEquals(1, applications.page(OWNER, PROJECT, true, 0, 1).unread());
  }

  @Test
  void a_personal_reaction_queues_the_exact_result_without_starting_a_skill() throws Exception {
    Files.writeString(
        workspace.resolve("Resources/subscriptions.json"),
        """
        {"version":1,"subscriptions":[{"id":"prices","source":{
          "kind":"mailbox","event":"item.added","schedule":{"project":"research","name":"price-watch"},
          "ending":"ANSWERED"},"handler":{"kind":"message","route":"personal-alerts"},"paused":false}]}
        """);
    var draft = runtime.preview(OWNER);
    runtime.activate(
        OWNER, new PersonalEventSubscriptions.Activation(draft.revision(), draft.activeRevision()));
    var report = deliver("Price is 123", 6);
    var reference = new MessageService.PersonalAlert(report.id(), report.firing());
    when(messages.sendPersonalAlert(eq(OWNER), eq("personal-alerts"), eq(reference), any()))
        .thenReturn("bdm_Reaction");
    assertEquals(1, publish());
    runtime.process();
    runtime.process();
    verify(messages).sendPersonalAlert(eq(OWNER), eq("personal-alerts"), eq(reference), any());
    verifyNoInteractions(jobs);
    var status = runtime.preview(OWNER).active().getFirst();
    assertEquals("completed", status.state());
    assertEquals("Queued message bdm_Reaction", status.detail());
    assertNull(inbox.scheduledResult(OWNER, report.id(), report.firing()).orElseThrow().readAt());
  }

  @Test
  void committed_schedule_result_starts_once_and_remains_unread() throws Exception {
    var historical = deliver("Historical price", 1);
    activate(false);
    var rolledBack = firing(2);
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  inbox.deliver(
                      OWNER, rolledBack, "cnv_original", "ANSWERED", "Rolled back", Instant.now());
                  throw new IllegalStateException("roll back result and event");
                }));
    var report = deliver("Price is 123", 3);
    var retry =
        inbox.deliver(
            OWNER,
            report.firing(),
            report.conversation(),
            report.ending(),
            report.answer(),
            Instant.now());
    assertEquals(report.id(), retry.id());
    assertEquals(2, publish());
    recordJobs();

    runtime.process();
    assertTrue(prompts.isEmpty()); // The pre-activation outbox event arrived late.
    runtime.process();
    runtime.process();
    assertEquals(1, prompts.size());
    assertTrue(prompts.getFirst().contains(report.id()));
    assertTrue(prompts.getFirst().contains("Price is 123"));
    assertFalse(prompts.getFirst().contains(historical.answer()));
    assertEquals(2, inbox.unread(OWNER));
    assertNull(inbox.scheduledResult(OWNER, report.id(), report.firing()).orElseThrow().readAt());
    assertTrue(inbox.scheduledResult("another", report.id(), report.firing()).isEmpty());
    assertEquals("job_Mailbox", runtime.preview(OWNER).active().getFirst().job());
  }

  @Test
  void paused_subscription_and_pre_start_crash_recover_the_retained_result() throws Exception {
    activate(true);
    var report = deliver("Price is 456", 1);
    assertEquals(1, publish());
    runtime.process();
    assertTrue(prompts.isEmpty());
    activate(false);
    when(jobs.submitSubscriptionSkill(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new AssertionError("worker died before reserving a job id"));
    assertThrows(AssertionError.class, runtime::process);
    assertEquals("starting", runtime.preview(OWNER).active().getFirst().state());
    jdbc.update(
        "UPDATE personal_mailbox_subscriptions SET updated_at=clock_timestamp()-interval '2 minutes'");
    recordJobs();
    runtime.process();
    runtime.process();
    assertEquals(1, prompts.size());
    assertTrue(prompts.getFirst().contains(report.id()));
    assertEquals(1, inbox.unread(OWNER));
  }

  @Test
  void transient_unlinked_start_failure_retries_the_retained_result() throws Exception {
    activate(false);
    var report = deliver("Price is 789", 1);
    assertEquals(1, publish());
    when(jobs.submitSubscriptionSkill(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Job admission temporarily unavailable"));
    runtime.process();
    assertEquals("starting", status(OWNER, "prices").state());

    ageSubscription();
    recordJobs();
    runtime.process();
    assertEquals(1, prompts.size());
    assertTrue(prompts.getFirst().contains(report.id()));
  }

  @Test
  void a_linked_start_without_a_live_worker_becomes_uncertain_and_waits_for_evidence()
      throws Exception {
    activate(false);
    deliver("Price is 789", 1);
    assertEquals(1, publish());
    recordJobs();
    runtime.process();
    jobLog.started("job_Mailbox", "assistant", Home.of(PERSONAL), Instant.now());

    when(jobs.subscriptionJobActive("job_Mailbox")).thenReturn(true);
    ageSubscription();
    runtime.process();
    assertEquals("running", status(OWNER, "prices").state());
    when(jobs.subscriptionJobActive("job_Mailbox")).thenReturn(false);
    ageSubscription();
    runtime.process();
    assertEquals("uncertain", status(OWNER, "prices").state());
    assertEquals("job_Mailbox", status(OWNER, "prices").job());

    assertTrue(jobLog.ended("job_Mailbox", Outcome.Ending.ANSWERED, 1, 1, Instant.now()));
    runtime.process();
    assertEquals("completed", status(OWNER, "prices").state());
    assertEquals(1, prompts.size());
  }

  private void ageSubscription() {
    jdbc.update(
        "UPDATE personal_mailbox_subscriptions SET updated_at=clock_timestamp()-interval '2 minutes'");
  }

  private void recordJobs() {
    doAnswer(
            call -> {
              assertEquals(Home.of(PERSONAL), call.getArgument(2));
              assertEquals(OWNER, call.getArgument(4));
              var mailbox = (EventRuns.SubscriptionSkill) call.getArgument(7);
              assertEquals(new EventRuns.LogSource("cnv_original"), mailbox.source());
              mailbox.started().accept("job_Mailbox");
              mailbox.requireSource().run();
              prompts.add(call.getArgument(1));
              return new JobStore.EventRun("job_Mailbox", "cnv_handler");
            })
        .when(jobs)
        .submitSubscriptionSkill(any(), any(), any(), any(), any(), any(), any(), any());
  }

  private void activate(boolean paused) throws Exception {
    Files.writeString(
        workspace.resolve("Resources/subscriptions.json"),
        """
        {"version":1,"subscriptions":[{"id":"prices","source":{
          "kind":"mailbox","event":"item.added","schedule":{"project":"research","name":"price-watch"},
          "ending":"ANSWERED"},"handler":{"kind":"skill","agent":"assistant","name":"track"},"paused":%s}]}
        """
            .formatted(paused));
    var draft = runtime.preview(OWNER);
    runtime.activate(
        OWNER, new PersonalEventSubscriptions.Activation(draft.revision(), draft.activeRevision()));
  }

  private String firing(int seconds) {
    return firing(SCHEDULE, seconds);
  }

  private String firing(String schedule, int seconds) {
    Instant at = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(seconds);
    return firings
        .arrive(
            trigger.event(), new EventPayload.Scheduled(schedule, at), schedule, at, trigger, at)
        .orElseThrow()
        .id();
  }

  private InboxItem deliver(String answer, int seconds) {
    return inbox.deliver(OWNER, firing(seconds), "cnv_original", "ANSWERED", answer, Instant.now());
  }

  private int publish() {
    return publish(PersonalSubscriptionRuntime.topic(projectId));
  }

  private int publish(Relay.TopicKey topic) {
    int count = 0;
    while (publisher.publishNext(topic)) count++;
    return count;
  }
}
