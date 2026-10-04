package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One run's whole decision, below the surface that asked for it.
 *
 * <p>{@code AgentControllerTest} still measures the same refusals through HTTP, and deliberately:
 * what that file proves is that the status and the body a caller sees did not move when the
 * decision did. This file proves the decision itself — every refusal, and above all <b>the order
 * they are reached in</b>, which is the half no status can show.
 *
 * <p><b>The ordering tests are the point of this class.</b> Three of them send a body that is wrong
 * in two ways at once and assert which refusal wins: resolution before the task check, the task
 * check before the conversation's own two, and the image refusal before the
 * conversation-and-project one. A rewrite that keeps every check and swaps two of them passes every
 * other test here and fails these.
 */
class RunsTest {

  private JobStore jobs;
  private Turn turns;
  private ProjectStore projects;
  private AgentRegistry registry;
  private Runs runs;

  /**
   * What the surface was asked to turn into pictures, for the tests that prove when it is asked and
   * with what.
   */
  private final List<String> asked = new ArrayList<>();

  private Home askedIn;

  @BeforeEach
  void setUp() {
    jobs = mock(JobStore.class);
    turns = mock(Turn.class);
    projects = mock(ProjectStore.class);
    registry =
        new AgentRegistry(
            Map.of(
                "promotion_judge", agent("promotion_judge"),
                "scribe", agent("scribe")));
    // Every conversation in this file is opened in the global tier unless
    // the test says otherwise -- a stub and not Mockito's unstubbed
    // default, since resolving a turn's caller reads this home before
    // anything else in the body is looked at.
    when(turns.homeOf(anyString())).thenReturn(Home.global());
    wire(resolverOver(registry, DataLayout.NONE, false));
  }

  private void wire(DefinitionResolver resolver) {
    runs =
        new Runs(
            new Callers(
                resolver,
                projects,
                turns,
                org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class)),
            jobs,
            turns);
  }

  @SuppressWarnings("unchecked")
  private static org.springframework.beans.factory.ObjectProvider<LogStages> hooksProvider(
      LogStages stages) {
    var provider =
        (org.springframework.beans.factory.ObjectProvider<LogStages>)
            mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getObject()).thenReturn(stages);
    return provider;
  }

  @Test
  void new_conversations_use_global_or_selected_home_and_the_existing_budget_and_hooks() {
    var conversations = mock(io.aeyer.plowshare.server.archive.ConversationStore.class);
    var properties = new io.aeyer.plowshare.server.api.ConversationsProperties();
    properties.setDefaultBudget(17);
    var hooks = mock(LogStages.class);
    runs.useConversations(conversations, properties, hooksProvider(hooks));
    when(turns.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_new");
    for (String project : java.util.Arrays.asList(null, "payments")) {
      var opened = mock(io.aeyer.plowshare.server.archive.ConversationRecord.class);
      String id = project == null ? "cnv_global" : "cnv_project";
      when(opened.id()).thenReturn(id);
      when(opened.home()).thenReturn(project == null ? Home.global() : Home.of(project));
      when(conversations.open(
              eq(project == null ? Home.global() : Home.of(project)), any(), any(), eq("alice")))
          .thenAnswer(
              call -> {
                assertEquals(17, ((Budget) call.getArgument(1)).remaining());
                return opened;
              });
      var started =
          runs.start(
              new Runs.Ask(
                  "scribe",
                  "go",
                  project,
                  "desk",
                  null,
                  null,
                  null,
                  List.of(),
                  Speaker.person("alice"),
                  "alice",
                  true),
              shown);
      assertEquals(id, started.conversation());
      assertEquals("job_new", started.id());
      verify(hooks).opened(LogStages.LogOpened.ofConversation(opened, "desk"));
      verify(turns)
          .speak(
              eq(id),
              eq(registry.get("scribe")),
              eq("go"),
              eq("desk"),
              isNull(),
              eq(Speaker.person("alice")),
              any());
    }
    org.mockito.Mockito.verifyNoInteractions(jobs);
  }

  @Test
  void refused_new_conversation_starts_do_not_open_logs_or_submit_jobs() {
    var conversations = mock(io.aeyer.plowshare.server.archive.ConversationStore.class);
    var members = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    runs =
        new Runs(
            new Callers(
                resolverOver(registry, DataLayout.NONE, false),
                projects,
                turns,
                new CallerAccess(new io.aeyer.plowshare.server.session.SessionRegistry(), members)),
            jobs,
            turns);
    runs.useConversations(
        conversations,
        new io.aeyer.plowshare.server.api.ConversationsProperties(),
        hooksProvider(LogStages.NONE));
    for (var ask :
        List.of(
            new Runs.Ask(
                "scribe",
                "go",
                "private",
                null,
                null,
                null,
                null,
                List.of(),
                Speaker.person("alice"),
                "alice",
                true),
            new Runs.Ask(
                "unknown",
                "go",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                Speaker.person("alice"),
                "alice",
                true),
            new Runs.Ask(
                "scribe",
                "go",
                null,
                null,
                "cnv_old",
                null,
                null,
                List.of(),
                Speaker.person("alice"),
                "alice",
                true),
            new Runs.Ask(
                "scribe",
                "go",
                null,
                null,
                null,
                null,
                null,
                List.of("img_1"),
                Speaker.person("alice"),
                "alice",
                true))) {
      assertThrows(CallerFault.class, () -> runs.start(ask, shown));
    }
    org.mockito.Mockito.verifyNoInteractions(conversations, jobs);
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }

  @Test
  void another_accounts_session_is_refused_before_a_job_is_submitted() {
    var sessions = new io.aeyer.plowshare.server.session.SessionRegistry();
    sessions.claim("desk", "alice");
    var members = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    runs =
        new Runs(
            new Callers(
                resolverOver(registry, DataLayout.NONE, false),
                projects,
                turns,
                new CallerAccess(sessions, members)),
            jobs,
            turns);
    assertThrows(
        CallerFault.class,
        () ->
            runs.start(
                new Runs.Ask(
                    "scribe",
                    "go",
                    null,
                    "desk",
                    null,
                    null,
                    null,
                    List.of(),
                    Speaker.person("bob")),
                shown));
    org.mockito.Mockito.verifyNoInteractions(jobs);
  }

  @Test
  void a_non_members_project_is_refused_for_submissions_and_conversation_turns() {
    var members = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    runs =
        new Runs(
            new Callers(
                resolverOver(registry, DataLayout.NONE, false),
                projects,
                turns,
                new CallerAccess(new io.aeyer.plowshare.server.session.SessionRegistry(), members)),
            jobs,
            turns);
    assertThrows(
        CallerFault.class,
        () ->
            runs.start(
                new Runs.Ask(
                    "scribe",
                    "go",
                    "ledger",
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    Speaker.person("bob")),
                shown));
    when(turns.homeOf("cnv_ledger")).thenReturn(Home.of("ledger"));
    assertThrows(
        CallerFault.class,
        () ->
            runs.start(
                new Runs.Ask(
                    "scribe",
                    "go",
                    null,
                    null,
                    "cnv_ledger",
                    null,
                    null,
                    List.of(),
                    Speaker.person("bob")),
                shown));
    org.mockito.Mockito.verifyNoInteractions(jobs);
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }

  @Test
  void an_approval_reply_checks_the_persons_access_and_records_the_harness_as_speaker() {
    var sessions = new io.aeyer.plowshare.server.session.SessionRegistry();
    sessions.claim("desk", "alice");
    var members = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    when(members.mayUse("ledger", "alice")).thenReturn(true);
    when(members.mayWork("ledger", "alice")).thenReturn(true);
    when(turns.homeOf("cnv_ledger")).thenReturn(Home.of("ledger"));
    runs =
        new Runs(
            new Callers(
                resolverOver(registry, DataLayout.NONE, false),
                projects,
                turns,
                new CallerAccess(sessions, members)),
            jobs,
            turns);
    runs.start(
        new Runs.Ask(
            "scribe",
            "approved",
            null,
            "desk",
            "cnv_ledger",
            null,
            null,
            List.of(),
            Speaker.approval("apr_1"),
            "alice"),
        shown);
    verify(turns)
        .speak(
            eq("cnv_ledger"),
            any(),
            eq("approved"),
            eq("desk"),
            any(),
            eq(Speaker.approval("apr_1")),
            any());
  }

  private final Runs.Shown shown =
      (definition, home, uids) -> {
        asked.addAll(uids);
        askedIn = home;
        List<Content.Image> pictures = new ArrayList<>(uids.size());
        for (String uid : uids) {
          pictures.add(new Content.Image(uid, "data:image/png;base64,AAAA"));
        }
        return pictures;
      };

  // --- the two refusals that came down out of the controller ------------------

  /**
   * A picture reaches one turn and is not recorded, so a conversation given one would have a
   * history that cannot be replayed. Refused rather than dropped.
   */
  @Test
  void an_utterance_in_a_conversation_cannot_carry_images() {
    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> start(ask("scribe").conversation("cnv_1").images("img_1")));

    assertTrue(
        refused.getMessage().contains("an utterance in a conversation cannot carry images"),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("cannot be replayed"), refused.getMessage());
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
    assertTrue(asked.isEmpty(), "nothing should have been resolved into a picture");
  }

  /**
   * Two answers to one question — which home this run answers from — and taking either quietly is
   * how somebody comes to believe a run reached a project it never touched.
   */
  @Test
  void a_run_naming_both_a_conversation_and_a_project_is_refused() {
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> start(ask("scribe").conversation("cnv_1").project("payments")));

    assertTrue(
        refused.getMessage().contains("this run names both a conversation and a project"),
        refused.getMessage());
    assertTrue(
        refused.getMessage().contains("leave 'project' out of an utterance"), refused.getMessage());
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }

  // --- the ordering ------------------------------------------------------------

  /**
   * Resolution runs first, and this is the whole reason the order is load-bearing: an unknown agent
   * named in a turn is refused with the set the <em>conversation's own project</em> resolves, never
   * the global boot set.
   *
   * <p>The project's own {@code bots/} holds {@code librarian} and the boot set does not. A refusal
   * that names it can only have been built from a caller resolved through {@code Turn.homeOf} —
   * which is the run this request would actually have reached.
   */
  @Test
  void a_turns_unknown_agent_lists_the_conversations_own_project_set(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(9L), "librarian", "exported: true\n", "the project's own librarian");
    wire(resolverOver(registry, layout, true));
    when(projects.id("payments")).thenReturn(9L);
    when(turns.homeOf("cnv_1")).thenReturn(Home.of("payments"));

    CallerFault refused =
        assertThrows(CallerFault.class, () -> start(ask("cartographer").conversation("cnv_1")));

    assertTrue(refused.getMessage().contains("cartographer"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains("librarian"),
        "the refusal must list the conversation's own set: " + refused.getMessage());
  }

  /**
   * The contrast that proves the test above measures something: the same unknown name, in a
   * conversation whose home is global, lists the boot set and knows nothing of the project's own
   * {@code bots/}.
   */
  @Test
  void the_same_unknown_agent_in_a_global_conversation_lists_only_the_boot_set(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    write(layout.botsFor(9L), "librarian", "exported: true\n", "the project's own librarian");
    wire(resolverOver(registry, layout, true));

    CallerFault refused =
        assertThrows(CallerFault.class, () -> start(ask("cartographer").conversation("cnv_1")));

    assertTrue(refused.getMessage().contains("scribe"), refused.getMessage());
    assertFalse(
        refused.getMessage().contains("librarian"),
        "a global conversation cannot see a project's own bots: " + refused.getMessage());
  }

  /**
   * Resolution before the task check, sent as one body that is wrong in both ways.
   *
   * <p>Swap the two and the caller is told it sent no task for an agent that does not exist, which
   * is the correction it cannot act on.
   */
  @Test
  void an_unknown_agent_is_named_before_a_missing_task_is() {
    CallerFault refused =
        assertThrows(CallerFault.class, () -> start(ask("cartographer").task(null)));

    assertTrue(
        refused.getMessage().contains("no agent called 'cartographer'"), refused.getMessage());
  }

  /**
   * The task check before the conversation's own two, sent as one body that is wrong in both ways.
   */
  @Test
  void a_missing_task_is_named_before_the_conversation_and_project_conflict() {
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> start(ask("scribe").task(null).conversation("cnv_1").project("payments")));

    assertTrue(refused.getMessage().contains("was given no task to do"), refused.getMessage());
  }

  /**
   * And the conversation's own two in their own order: images first, so a turn carrying both a
   * picture and a project hears about the picture.
   */
  @Test
  void an_utterances_images_are_refused_before_its_project_is() {
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> start(ask("scribe").conversation("cnv_1").project("payments").images("img_1")));

    assertTrue(refused.getMessage().contains("cannot be replayed"), refused.getMessage());
  }

  // --- what a run that is not refused does -------------------------------------

  @Test
  void a_plain_run_is_submitted_in_the_home_it_named() {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    Runs.Started started = start(ask("promotion_judge").project("payments"));

    assertEquals("job_000001", started.id());
    assertEquals("promotion_judge", started.agent());
    // true: a plain submission with no conversation is a person's own words arriving fresh —
    // spec §6 — exactly as an utterance into one is through Turn.speak.
    verify(jobs)
        .submit(
            registry.get("promotion_judge"),
            "go",
            Home.of("payments"),
            null,
            null,
            List.of(),
            true,
            null);
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }

  /**
   * The pictures are resolved against the run's own home, and only after that home is decided —
   * nothing else could hand a UID to the right tier.
   */
  @Test
  void the_images_a_run_names_are_resolved_in_the_runs_own_home() {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    start(ask("promotion_judge").project("payments").images("img_1", "img_2"));

    assertEquals(List.of("img_1", "img_2"), asked);
    assertEquals(Home.of("payments"), askedIn);
  }

  @Test
  void a_turn_is_spoken_into_its_conversation_and_never_submitted() {
    when(turns.homeOf("cnv_1")).thenReturn(Home.of("ledger"));
    when(turns.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_000009");

    Runs.Started started = start(ask("scribe").conversation("cnv_1"));

    assertEquals("job_000009", started.id());
    assertEquals("scribe", started.agent());
    verify(turns)
        .speak(
            eq("cnv_1"),
            eq(registry.get("scribe")),
            eq("go"),
            isNull(),
            isNull(),
            eq(Speaker.person(null)),
            any());
    verify(jobs, never())
        .submit(any(AgentDefinition.class), any(), any(), any(), any(), any(), anyBoolean(), any());
    assertTrue(asked.isEmpty(), "a turn names no pictures and asks for none");
  }

  @Test
  void a_turn_is_spoken_as_whoever_the_ask_names() {
    when(turns.homeOf("cnv_1")).thenReturn(Home.of("ledger"));
    when(turns.speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any()))
        .thenReturn("job_000010");

    runs.start(
        new Runs.Ask(
            "scribe", "go", null, null, "cnv_1", null, null, List.of(), Speaker.approval("apr_1")),
        shown);

    verify(turns)
        .speak(
            eq("cnv_1"),
            eq(registry.get("scribe")),
            eq("go"),
            isNull(),
            isNull(),
            eq(Speaker.approval("apr_1")),
            any());
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log decision 8: the signed-in person owns a submission's log;
   * the harness, speaking for an approval, owns none.
   */
  @Test
  void a_plain_run_s_log_is_owned_by_the_person_who_asked_and_never_the_harness() {
    when(jobs.submit(
            any(AgentDefinition.class),
            anyString(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()))
        .thenReturn("job_000001");

    runs.start(
        new Runs.Ask(
            "scribe", "go", "payments", null, null, null, null, List.of(), Speaker.person("enzo")),
        shown);
    runs.start(
        new Runs.Ask(
            "scribe", "go", "payments", null, null, null, null, List.of(), Speaker.harness()),
        shown);

    verify(jobs)
        .submit(
            registry.get("scribe"), "go", Home.of("payments"), null, null, List.of(), true, "enzo");
    verify(jobs)
        .submit(
            registry.get("scribe"), "go", Home.of("payments"), null, null, List.of(), true, null);
  }

  /**
   * Present and empty is the one thing a session may not be, and this door refuses it before
   * anything is resolved.
   */
  @Test
  void a_present_and_empty_session_is_refused() {
    CallerFault refused = assertThrows(CallerFault.class, () -> start(ask("scribe").session("")));

    assertTrue(
        refused.getMessage().contains("'session' was given as an empty value"),
        refused.getMessage());
  }

  // --- fixtures ----------------------------------------------------------------

  private DefinitionResolver resolverOver(
      AgentRegistry bootSet, DataLayout layout, boolean projectsExist) {
    return new DefinitionResolver(
        bootSet,
        layout,
        id -> projectsExist,
        Set.of(),
        Set.of(),
        mock(SessionChannel.class),
        session -> true,
        DefinitionChecks.NONE);
  }

  /**
   * The one door this class measures, given a body built by {@link #ask}. The {@link Runs.Shown}
   * every call passes is this class's own recorder, so a test can say both what was resolved and
   * when.
   */
  private Runs.Started start(Asking asking) {
    return runs.start(
        new Runs.Ask(
            asking.agent,
            asking.task,
            asking.project,
            asking.session,
            asking.conversation,
            null,
            null,
            asking.images),
        shown);
  }

  /**
   * A body under construction, so each test names only the field it is about and the rest read as
   * the ordinary run they are.
   */
  private static Asking ask(String agent) {
    return new Asking(agent);
  }

  private static final class Asking {
    private final String agent;
    private String task = "go";
    private String project;
    private String session;
    private String conversation;
    private List<String> images = List.of();

    private Asking(String agent) {
      this.agent = agent;
    }

    private Asking task(String value) {
      this.task = value;
      return this;
    }

    private Asking project(String value) {
      this.project = value;
      return this;
    }

    private Asking session(String value) {
      this.session = value;
      return this;
    }

    private Asking conversation(String value) {
      this.conversation = value;
      return this;
    }

    private Asking images(String... values) {
      this.images = List.of(values);
      return this;
    }
  }

  private static void write(Path dir, String name, String extraFrontmatter, String body)
      throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: d\nmodel: fast\nmax-turns: 2\n"
            + "max-model-calls: 4\n"
            + extraFrontmatter
            + "---\n"
            + body
            + "\n");
  }

  private static AgentDefinition agent(String name) {
    return new AgentDefinition(
        name,
        "a fixture",
        "fast",
        List.of(),
        List.of(),
        List.of(),
        2,
        4,
        "You do one thing.",
        true,
        true);
  }

  @Test
  void global_history_cannot_accept_a_new_turn() {
    when(turns.homeOf("cnv_1")).thenReturn(Home.global());
    var denied =
        assertThrows(
            CallerFault.class,
            () ->
                runs.start(
                    new Runs.Ask(
                        "scribe",
                        "go",
                        null,
                        null,
                        "cnv_1",
                        null,
                        null,
                        List.of(),
                        Speaker.person(null)),
                    shown));
    assertTrue(denied.getMessage().contains("Global holds shared setup"));
    verify(turns, never())
        .speak(anyString(), any(), anyString(), any(), any(), any(Speaker.class), any());
  }
}
