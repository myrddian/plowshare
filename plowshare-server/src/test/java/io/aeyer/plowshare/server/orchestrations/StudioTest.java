package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.FakeFiles;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.OrchestrationWriter;
import io.aeyer.plowshare.server.agents.StructuredAnswers;
import io.aeyer.plowshare.server.agents.StructuredQuestions;
import io.aeyer.plowshare.server.agents.StudioTools.Installing;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Studio over a real resolver and project tier — spec 2026-09-29-orchestration-studio §3. */
class StudioTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> TOOLS = Set.of("file_read", "file_edit", "agent_run");
  private static final String DIR = "docs/orchestrations/2026-09-30-design_orchestration-orc_1/";
  private static final String PATH = DIR + "triage.md";

  @TempDir Path data;

  private DataLayout layout;
  private OrchestrationStore store;
  private ConversationStore conversations;
  private Callers callers;
  private DefinitionResolver agents;
  private OrchestrationResolver resolver;
  private final List<List<String>> asked = new ArrayList<>();
  private Studio studio;
  private final DefinitionResolver.Caller caller = new DefinitionResolver.Caller(7L, null);

  @BeforeEach
  void setUp() throws Exception {
    layout = new DataLayout(data).initialise();
    Path agentsDir = data.resolve("boot-agents");
    Files.createDirectories(agentsDir);
    Files.writeString(
        agentsDir.resolve("interlocutor.md"),
        "---\nname: interlocutor\n"
            + "description: Talks with the person.\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\ntools: [file_read]\n---\nYou talk.\n");
    AgentRegistry registry = AgentRegistry.of(agentsDir, TOOLS);
    store = mock(OrchestrationStore.class);
    conversations = mock(ConversationStore.class);
    callers = mock(Callers.class);
    agents = mock(DefinitionResolver.class);
    when(agents.forCaller(any())).thenReturn(registry);
    resolver =
        new OrchestrationResolver(
            OrchestrationRegistry.Loaded.EMPTY,
            layout,
            id -> true,
            TOOLS,
            new FakeFiles(),
            session -> true,
            (projectId, session) -> true,
            agents::forCaller,
            DefinitionChecks.NONE);
    when(store.find("orc_1")).thenReturn(Optional.of(run()));
    when(store.artifactsDir("orc_1")).thenReturn(Optional.of(DIR));
    when(callers.callerForConversation("cnv_conductor", "ses_1")).thenReturn(caller);
    origin(Origin.TURN);
    studio =
        new Studio(
            store,
            conversations,
            callers,
            agents,
            resolver,
            new OrchestrationWriter(layout),
            TOOLS,
            (run, question, structure) -> {
              asked.add(List.of(run, question, structure));
              return Optional.empty();
            });
  }

  private void origin(Origin origin) {
    ConversationRecord root = mock(ConversationRecord.class);
    when(root.origin()).thenReturn(origin);
    when(conversations.find("cnv_person")).thenReturn(Optional.of(root));
  }

  private static OrchestrationRecord run() {
    return new OrchestrationRecord(
        "orc_1",
        "design_orchestration",
        OrchestrationDefinition.Tier.GLOBAL,
        "sha256:x",
        "unparsed",
        "test",
        List.of(),
        3,
        0,
        "story",
        "cnv_conductor",
        "cnv_person",
        "interlocutor",
        "enzo",
        "ses_1",
        null,
        0,
        null,
        OrchestrationState.RUNNING,
        null,
        null,
        null,
        0,
        0,
        false,
        null,
        Instant.EPOCH,
        null);
  }

  private static String draft(String name, String extra, String body) {
    return "---\nname: "
        + name
        + "\ndescription: Triages bugs.\nmodel: m\nmax-turns: 2\n"
        + "max-model-calls: 4\n"
        + extra
        + "stages:\n  - {id: goal}\n  - {id: fix, check: required}\n---\n"
        + body
        + "\n";
  }

  private static final String CLEAN = draft("triage", "tools: [file_read]\n", "Triage it.");

  private String askedStructure(String path, String text) {
    assertInstanceOf(Installing.Asked.class, studio.install("orc_1", path, text));
    return asked.get(asked.size() - 1).get(2);
  }

  private static OrchestrationMessage question(String structure) {
    return new OrchestrationMessage(
        "msg_q",
        "orc_1",
        OrchestrationMessage.Kind.QUESTION,
        "Install triage into this project?",
        "harness",
        Instant.EPOCH,
        null,
        "install",
        structure);
  }

  private static OrchestrationMessage chose(String label) {
    return new OrchestrationMessage(
        "msg_a",
        "orc_1",
        OrchestrationMessage.Kind.ANSWER,
        "1. [Install] chose \"" + label + "\"",
        "enzo",
        Instant.EPOCH,
        null,
        "install",
        StructuredAnswers.structure(
            List.of(new StructuredAnswers.Choice("Install", List.of(label), null, null))));
  }

  private Path installed(String name) {
    return data.resolve("projects/7/orchestrations/" + name + ".md");
  }

  @Test
  void a_path_outside_the_artifacts_directory_is_refused() {
    assertEquals(
        "src/x.md is not a draft in this run's artifacts directory ("
            + DIR
            + "): a"
            + " draft is "
            + DIR
            + "<name>.md.",
        studio.validate("orc_1", "src/x.md", CLEAN));
  }

  @Test
  void not_a_draft_answers_the_path_refusal_before_any_draft_is_read() {
    assertEquals(
        Optional.of(
            "src/x.md is not a draft in this run's artifacts directory ("
                + DIR
                + "): a draft is "
                + DIR
                + "<name>.md."),
        studio.notADraft("orc_1", "src/x.md"));
    assertEquals(Optional.empty(), studio.notADraft("orc_1", PATH));
    assertEquals(
        Optional.of("No orchestration orc_9 exists; the Studio acts only for a live" + " run."),
        studio.notADraft("orc_9", PATH));
  }

  @Test
  void a_path_that_climbs_out_of_the_artifacts_directory_is_refused() {
    String climbing = DIR + "../elsewhere/triage.md";
    assertTrue(
        studio
            .validate("orc_1", climbing, CLEAN)
            .startsWith(climbing + " is not a draft in this run's artifacts directory"));
  }

  @Test
  void a_clean_draft_validates_with_its_summary() {
    String report = studio.validate("orc_1", PATH, CLEAN);

    assertTrue(report.startsWith("The loader accepts this draft."), report);
    assertTrue(report.contains("  tool file_read"), report);
  }

  @Test
  void a_run_in_no_project_is_told_so() {
    when(callers.callerForConversation("cnv_conductor", "ses_1"))
        .thenReturn(new DefinitionResolver.Caller(null, null));

    assertEquals(
        "This run is in no project, so there is no project tier to install into.",
        studio.validate("orc_1", PATH, CLEAN));
  }

  @Test
  void a_project_tier_that_cannot_be_read_is_answered_not_thrown() throws Exception {
    Files.createDirectories(data.resolve("projects/7"));
    Files.writeString(data.resolve("projects/7/orchestrations"), "not a directory");

    String answer = studio.validate("orc_1", PATH, CLEAN);

    assertTrue(answer.startsWith("triage could not be trialled: "), answer);
    String settled = studio.settle(run(), question(askedStructureOf(CLEAN)), chose("Install"));
    assertTrue(
        settled.startsWith("Nothing was installed: triage could not be trialled: "), settled);
    assertTrue(settled.endsWith(" The draft stays at " + PATH + "."), settled);
  }

  /** A structure as install would build it, for a tier install itself cannot trial. */
  private static String askedStructureOf(String text) {
    com.fasterxml.jackson.databind.node.ObjectNode root = JSON.createObjectNode();
    root.put("name", "triage");
    root.put("path", PATH);
    root.put("text", text);
    root.put("sha256", Studio.digest(text));
    return root.toString();
  }

  /**
   * Final review 4: the bytes settle writes are the ones the question was asked with — a stored
   * text that no longer matches its stored digest installs nothing.
   */
  @Test
  void settle_refuses_a_draft_that_does_not_match_its_digest() throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode structure =
        (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(askedStructure(PATH, CLEAN));
    structure.put("text", draft("triage", "tools: [file_read, file_edit]\n", "Triage it."));

    String answer = studio.settle(run(), question(structure.toString()), chose("Install"));

    assertEquals(
        "The install question's draft does not match its digest; nothing was"
            + " installed. The draft stays at "
            + PATH
            + ".",
        answer);
    assertFalse(Files.exists(installed("triage")));
  }

  /**
   * Final review 4: what the Studio leans on failing is a sentence the model reads, never a throw
   * out of a tool.
   */
  @Test
  void a_failing_agent_tier_resolver_or_asker_is_answered_not_thrown() {
    when(agents.forCaller(any())).thenThrow(new IllegalStateException("the tier is unreadable"));
    assertEquals("The catalog could not be read: the tier is unreadable.", studio.catalog("orc_1"));
    assertEquals(
        "triage could not be trialled: the tier is unreadable",
        studio.validate("orc_1", PATH, CLEAN));

    OrchestrationResolver failing = mock(OrchestrationResolver.class);
    when(failing.find(any(), any())).thenThrow(new IllegalStateException("disk gone"));
    Studio broken =
        new Studio(
            store,
            conversations,
            callers,
            agents,
            failing,
            new OrchestrationWriter(layout),
            TOOLS,
            (run, question, structure) -> {
              throw new IllegalStateException("the store refused");
            });
    assertEquals("triage could not be read: disk gone.", broken.read("orc_1", "triage"));

    when(callers.callerForConversation("cnv_conductor", "ses_1"))
        .thenThrow(new IllegalStateException("no such account"));
    assertEquals(
        PATH + " could not be validated: no such account.", studio.validate("orc_1", PATH, CLEAN));
  }

  @Test
  void install_answers_an_asker_that_throws() {
    studio =
        new Studio(
            store,
            conversations,
            callers,
            agents,
            resolver,
            new OrchestrationWriter(layout),
            TOOLS,
            (run, question, structure) -> {
              throw new IllegalStateException("the store refused");
            });

    assertEquals(
        new Installing.Refused("The install question could not be asked: the store" + " refused."),
        studio.install("orc_1", PATH, CLEAN));
  }

  @Test
  void an_ended_run_is_refused() {
    OrchestrationRecord ended =
        new OrchestrationRecord(
            "orc_1",
            "design_orchestration",
            OrchestrationDefinition.Tier.GLOBAL,
            "sha256:x",
            "unparsed",
            "test",
            List.of(),
            3,
            0,
            "story",
            "cnv_conductor",
            "cnv_person",
            "interlocutor",
            "enzo",
            "ses_1",
            null,
            0,
            null,
            OrchestrationState.FINISHED,
            null,
            "done",
            null,
            0,
            0,
            false,
            null,
            Instant.EPOCH,
            Instant.EPOCH);
    when(store.find("orc_1")).thenReturn(Optional.of(ended));

    assertEquals(
        "Orchestration orc_1 has ended; the Studio acts only for a live run.",
        studio.catalog("orc_1"));
  }

  @Test
  void catalog_marks_the_caller_s_grants_and_says_whether_a_person_attends() {
    String catalog = studio.catalog("orc_1");

    assertTrue(catalog.contains("* file_read"), catalog);
    assertTrue(catalog.contains("  file_edit"), catalog);
    assertFalse(catalog.contains("* file_edit"), catalog);
    assertTrue(catalog.contains("Attended: yes"), catalog);
  }

  @Test
  void read_answers_a_reachable_source_with_its_tier() throws Exception {
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(installed("triage"), CLEAN);

    String read = studio.read("orc_1", "triage");

    assertTrue(read.startsWith("triage (project, "), read);
    assertTrue(read.endsWith(":\n\n" + CLEAN), read);
    assertEquals(
        "No orchestration named nothing is reachable here.", studio.read("orc_1", "nothing"));
  }

  @Test
  void install_on_an_attended_run_asks_with_the_whole_draft_in_its_structure() throws Exception {
    Installing answer = studio.install("orc_1", PATH, CLEAN);

    assertInstanceOf(Installing.Asked.class, answer);
    assertTrue(
        ((Installing.Asked) answer)
            .question()
            .startsWith("Install triage into this project?\n\nStages:"));
    assertEquals(1, asked.size());
    assertEquals("orc_1", asked.get(0).get(0));
    String structure = asked.get(0).get(2);
    StructuredQuestions.Asked parsed = StructuredQuestions.parse(structure);
    assertEquals(1, parsed.questions().size());
    assertEquals("Install", parsed.questions().get(0).header());
    assertEquals(
        List.of("Install", "Don't install"),
        parsed.questions().get(0).options().stream()
            .map(StructuredQuestions.Option::label)
            .toList());
    assertEquals(CLEAN, JSON.readTree(structure).get("text").asText());
    assertEquals("triage", JSON.readTree(structure).get("name").asText());
    assertEquals(PATH, JSON.readTree(structure).get("path").asText());
    assertTrue(JSON.readTree(structure).get("sha256").asText().startsWith("sha256:"));
  }

  @Test
  void install_of_a_replacement_previews_its_changes_within_the_cap() throws Exception {
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(installed("triage"), draft("triage", "tools: [file_read]\n", "The old way."));
    String longBody = "Triage it.\n" + "a line of the new body\n".repeat(400);

    String structure = askedStructure(PATH, draft("triage", "tools: [file_read]\n", longBody));

    String preview =
        StructuredQuestions.parse(structure).questions().get(0).options().get(0).preview();
    assertTrue(preview.contains("\n\nChanges:\n- The old way.\n+ Triage it."), preview);
    assertTrue(preview.length() <= StructuredQuestions.MOST_PREVIEW, "" + preview.length());
    assertTrue(preview.endsWith("\n… the whole draft is " + PATH), preview);
  }

  /**
   * Final review 1: the modal always draws an option's description and drops the preview first, so
   * what the draft grants beyond its caller leads the Install option — and the preview, which lists
   * it before the stages.
   */
  @Test
  void install_names_the_grants_beyond_the_caller_where_the_modal_always_draws_them() {
    String structure =
        askedStructure(PATH, draft("triage", "tools: [file_read, file_edit]\n", "Triage it."));

    StructuredQuestions.Option install =
        StructuredQuestions.parse(structure).questions().get(0).options().get(0);
    assertTrue(
        install
            .description()
            .startsWith(
                "Grants beyond interlocutor: tool file_edit." + " Write it into the project; "),
        install.description());
    assertTrue(
        install.preview().startsWith("Grants beyond interlocutor:\n  tool file_edit\n" + "Stages:"),
        install.preview());
  }

  @Test
  void install_says_so_when_the_draft_grants_nothing_beyond_the_caller() {
    String structure = askedStructure(PATH, CLEAN);

    String description =
        StructuredQuestions.parse(structure).questions().get(0).options().get(0).description();
    assertTrue(
        description.startsWith(
            "Grants nothing beyond interlocutor. Write it into the" + " project; "),
        description);
  }

  @Test
  void grants_beyond_the_caller_are_cut_with_how_many_more_to_fit_the_room() {
    List<String> beyond = new ArrayList<>();
    for (int at = 0; at < 40; at++) {
      beyond.add("scope workspace:write:src/module_" + at);
    }

    String said = Studio.grantsBeyond("interlocutor", beyond, 200);

    assertTrue(said.length() <= 200, "" + said.length());
    assertTrue(
        said.startsWith("Grants beyond interlocutor: scope workspace:write:src/module_0,"), said);
    assertTrue(said.matches(".*… and \\d+ more\\.$"), said);
    assertEquals(
        "Grants beyond interlocutor: tool file_edit, callee coder.",
        Studio.grantsBeyond("interlocutor", List.of("tool file_edit", "callee coder"), 300));
  }

  @Test
  void install_on_an_unattended_run_with_a_grant_beyond_the_caller_is_refused_and_nothing_asked() {
    origin(Origin.EVENT);

    Installing answer =
        studio.install(
            "orc_1", PATH, draft("triage", "tools: [file_read, file_edit]\n", "Triage it."));

    Installing.Refused refused = assertInstanceOf(Installing.Refused.class, answer);
    assertTrue(
        refused.why().startsWith("Not installed; nothing was asked.\n\nREFUSED"), refused.why());
    assertTrue(
        refused.why().contains("tool file_edit is beyond what interlocutor holds"), refused.why());
    assertTrue(asked.isEmpty());
  }

  @Test
  void install_answers_the_engine_s_refusal_to_ask() {
    studio =
        new Studio(
            store,
            conversations,
            callers,
            agents,
            resolver,
            new OrchestrationWriter(layout),
            TOOLS,
            (run, question, structure) -> Optional.of("this run is already asking"));

    assertEquals(
        new Installing.Refused("this run is already asking"), studio.install("orc_1", PATH, CLEAN));
  }

  @Test
  void settle_install_writes_the_bytes_the_question_held_even_if_the_file_changed()
      throws Exception {
    String structure = askedStructure(PATH, CLEAN);

    String answer = studio.settle(run(), question(structure), chose("Install"));

    assertEquals(
        "Installed triage at "
            + installed("triage")
            + ". Nothing starts it until an"
            + " agent's `orchestrations:` grant names it. Runs already started keep the"
            + " definition they started with.",
        answer);
    assertEquals(CLEAN, Files.readString(installed("triage")));
    assertTrue(resolver.forCaller(caller).containsKey("triage"));
  }

  @Test
  void settle_keeps_what_it_replaces_as_prev_and_says_so() throws Exception {
    String old = draft("triage", "tools: [file_read]\n", "The old way.");
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(installed("triage"), old);
    String structure = askedStructure(PATH, CLEAN);

    String answer = studio.settle(run(), question(structure), chose("Install"));

    Path prev = data.resolve("projects/7/orchestrations/triage.md.prev");
    assertEquals(
        "Installed triage at "
            + installed("triage")
            + ". The one it replaced is kept"
            + " as "
            + prev
            + "; rename it back to undo. Nothing starts it until an agent's"
            + " `orchestrations:` grant names it. Runs already started keep the definition"
            + " they started with.",
        answer);
    assertEquals(old, Files.readString(prev));
    assertEquals(CLEAN, Files.readString(installed("triage")));
  }

  @Test
  void settle_again_after_a_restart_writes_nothing_and_keeps_the_prev() throws Exception {
    String old = draft("triage", "tools: [file_read]\n", "The old way.");
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(installed("triage"), old);
    String structure = askedStructure(PATH, CLEAN);
    studio.settle(run(), question(structure), chose("Install"));

    String again = studio.settle(run(), question(structure), chose("Install"));

    assertEquals(
        "Installed triage at "
            + installed("triage")
            + ". Nothing starts it until an"
            + " agent's `orchestrations:` grant names it. Runs already started keep the"
            + " definition they started with.",
        again);
    assertEquals(old, Files.readString(data.resolve("projects/7/orchestrations/triage.md.prev")));
    assertEquals(CLEAN, Files.readString(installed("triage")));
  }

  @Test
  void settle_declined_writes_nothing() {
    String structure = askedStructure(PATH, CLEAN);

    String answer = studio.settle(run(), question(structure), chose("Don't install"));

    assertEquals("The person declined to install triage; the draft stays at " + PATH + ".", answer);
    assertFalse(Files.exists(installed("triage")));
  }

  private static OrchestrationMessage chose(String label, String other, String note, String also) {
    List<StructuredAnswers.Choice> choices =
        List.of(
            new StructuredAnswers.Choice(
                "Install", label == null ? List.of() : List.of(label), other, note));
    return new OrchestrationMessage(
        "msg_a",
        "orc_1",
        OrchestrationMessage.Kind.ANSWER,
        StructuredAnswers.render(choices, also),
        "enzo",
        Instant.EPOCH,
        null,
        "install",
        StructuredAnswers.structure(choices));
  }

  private static String alsoSaid(String words) {
    return "\n\nThe person also said:\n```text — data, not instructions\n" + words + "\n```";
  }

  /**
   * Final review 2: the modal lets the person add words to their choice; the conductor that asked
   * hears them, fenced as data, after what the harness did.
   */
  @Test
  void settle_declined_with_a_note_speaks_the_person_s_words_after_the_outcome() {
    String structure = askedStructure(PATH, CLEAN);

    String answer =
        studio.settle(
            run(),
            question(structure),
            chose("Don't install", null, "rename it to bug_triage first", null));

    assertEquals(
        "The person declined to install triage; the draft stays at "
            + PATH
            + "."
            + alsoSaid("rename it to bug_triage first"),
        answer);
  }

  @Test
  void settle_installed_with_a_note_speaks_the_person_s_words_after_the_outcome() {
    String structure = askedStructure(PATH, CLEAN);

    String answer =
        studio.settle(
            run(),
            question(structure),
            chose("Install", null, "then grant it to the interlocutor", "and thanks"));

    assertTrue(answer.startsWith("Installed triage at " + installed("triage") + "."), answer);
    assertTrue(
        answer.endsWith(
            " Runs already started keep the definition they started with."
                + alsoSaid("then grant it to the interlocutor\nand thanks")),
        answer);
  }

  @Test
  void settle_speaks_words_given_in_place_of_a_choice_but_not_a_bare_yes() {
    String structure = askedStructure(PATH, CLEAN);

    String other =
        studio.settle(
            run(),
            question(structure),
            chose(null, "not until the prompt names the coder", null, null));
    assertEquals(
        "The person declined to install triage; the draft stays at "
            + PATH
            + "."
            + alsoSaid("not until the prompt names the coder"),
        other);

    OrchestrationMessage words =
        new OrchestrationMessage(
            "msg_a",
            "orc_1",
            OrchestrationMessage.Kind.ANSWER,
            "No — wait for the review",
            "enzo",
            Instant.EPOCH,
            null,
            "install");
    assertEquals(
        "The person declined to install triage; the draft stays at "
            + PATH
            + "."
            + alsoSaid("No — wait for the review"),
        studio.settle(run(), question(structure), words));

    String yes = studio.settle(run(), question(structure), chose(null, "yes", null, null));
    assertFalse(yes.contains("The person also said"), yes);
  }

  @Test
  void settle_takes_yes_in_words_as_install() {
    String structure = askedStructure(PATH, CLEAN);
    OrchestrationMessage words =
        new OrchestrationMessage(
            "msg_a",
            "orc_1",
            OrchestrationMessage.Kind.ANSWER,
            " Yes ",
            "enzo",
            Instant.EPOCH,
            null,
            "install");

    String settled = studio.settle(run(), question(structure), words);
    assertTrue(settled.startsWith("Installed triage"), settled);
    assertFalse(settled.contains("The person also said"), settled);
    assertTrue(Files.exists(installed("triage")));
  }

  @Test
  void settle_refuses_a_draft_that_no_longer_loads() throws Exception {
    String path = DIR + "inner.md";
    String inner = draft("inner", "orchestrations: [outer]\n", "Run outer.");
    String structure = askedStructure(path, inner);
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(
        installed("outer"), draft("outer", "orchestrations: [inner]\n", "Run inner."));

    String answer = studio.settle(run(), question(structure), chose("Install"));

    assertTrue(answer.startsWith("Nothing was installed: "), answer);
    assertTrue(answer.endsWith(". The draft stays at " + path + "."), answer);
    assertFalse(Files.exists(installed("inner")));
  }
}
