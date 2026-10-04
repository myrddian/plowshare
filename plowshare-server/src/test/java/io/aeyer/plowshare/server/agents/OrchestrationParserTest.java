package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Stage;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Trigger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrchestrationParserTest {

  // Includes the todo tools deliberately: JobRuntime.knownTools() adds TodoTools.NAMES whenever
  // a board is wired, so a conductor's declared 'todo_write' is KEPT rather than dropped in
  // production — the case a knownTools set without them would miss entirely (fix round 1,
  // finding 1).
  private static final Set<String> TOOLS =
      Set.of("file_read", "file_write", TodoTools.READ_NAME, TodoTools.WRITE_NAME);

  @Test
  void manual_read_only_workflow_uses_the_real_stage_and_grant_grammar() throws Exception {
    Path example = Path.of("../docs/examples/orchestrations/source_note.md");
    OrchestrationDefinition definition =
        OrchestrationParser.parse(
            new DefinitionSource.Definition(
                "source_note", "manual example", Files.readString(example)),
            TOOLS,
            OrchestrationDefinition.Tier.PROJECT);
    assertEquals(
        List.of("read_brief", "draft_note", "review_note"),
        definition.stages().stream().map(Stage::id).toList());
    assertEquals(List.of("read_brief", "draft_note"), definition.stages().get(2).mayReturnTo());
    assertEquals(List.of("file_read"), definition.conductor().tools());
    assertTrue(definition.conductor().calls().isEmpty());
    assertEquals(List.of(new Trigger("/source-note", true)), definition.triggers());
    assertEquals(2, definition.maxReturns());
  }

  private static final String STAGES =
      """
            stages:
              - {id: goal, done-when: "the goal is restated"}
              - {id: code}
              - {id: review, may-return-to: [code]}
            """;

  private static DefinitionSource.Definition file(String name, String extra, String tools) {
    return new DefinitionSource.Definition(
        name,
        "test",
        "---\nname: "
            + name
            + "\n"
            + "description: Takes a task to reviewed code.\nmodel: reasoning\n"
            + "max-turns: 60\nmax-model-calls: 400\ntools: "
            + tools
            + "\ncalls: [planner]\n"
            + extra
            + "---\nYou are conducting a code implementation.\n");
  }

  private static DefinitionSource.Definition file(String name, String extra) {
    return file(name, extra, "[file_read]");
  }

  private static OrchestrationDefinition parse(String extra) {
    return OrchestrationParser.parse(
        file("code_implementation", extra), TOOLS, OrchestrationDefinition.Tier.PROJECT);
  }

  private static String refusal(String name, String extra) {
    return assertThrows(
            IllegalStateException.class,
            () ->
                OrchestrationParser.parse(
                    file(name, extra), TOOLS, OrchestrationDefinition.Tier.PROJECT))
        .getMessage();
  }

  private static String refusalWithTools(String tools) {
    return assertThrows(
            IllegalStateException.class,
            () ->
                OrchestrationParser.parse(
                    file("code_implementation", STAGES, tools),
                    TOOLS,
                    OrchestrationDefinition.Tier.PROJECT))
        .getMessage();
  }

  private static final String PREFIX = "the orchestration definition 'code_implementation' (test)";

  private static String refusedWith(String extra) {
    return refusal("code_implementation", extra);
  }

  private static List<Stage> parsedStages(String extra) {
    return parse(extra).stages();
  }

  @Test
  void a_file_is_a_conductor_and_its_stages() {
    OrchestrationDefinition d = parse(STAGES);

    assertEquals("code_implementation", d.name());
    assertEquals("Takes a task to reviewed code.", d.description());
    assertEquals("reasoning", d.conductor().model());
    assertEquals(List.of("planner"), d.conductor().calls());
    assertTrue(d.conductor().prompt().startsWith("You are conducting"));
    assertEquals(
        List.of(
            new Stage("goal", "the goal is restated", List.of()),
            new Stage("code", null, List.of()),
            new Stage("review", null, List.of("code"))),
        d.stages());
    assertEquals(OrchestrationParser.DEFAULT_MAX_RETURNS, d.maxReturns());
    assertNull(d.artifacts());
    assertEquals(List.of(), d.triggers());
    assertEquals("test", d.origin());
    assertEquals(OrchestrationDefinition.Tier.PROJECT, d.tier());
  }

  /**
   * Spec 2026-09-13 §4.3: a conductor is given {@code agent_run} over its {@code calls} without
   * declaring it. Measured on 2026-09-25: every one of the 29 {@code agent_run} calls conductors
   * had ever made was refused with "there is no tool called 'agent_run'", so no phase ever reached
   * coder, test_designer or code_reviewer — the conductors wrote the code themselves and marked
   * review done with no reviewer.
   */
  @Test
  void a_conductor_that_names_calls_is_given_agent_run_without_declaring_it() {
    OrchestrationDefinition d =
        OrchestrationParser.parse(
            file("code_implementation", STAGES),
            Set.of("file_read", "agent_run"),
            OrchestrationDefinition.Tier.PROJECT);

    assertTrue(d.conductor().canDelegate(), () -> "tools: " + d.conductor().tools());
    assertEquals(List.of("planner"), d.conductor().calls());
  }

  @Test
  void a_conductor_that_names_no_calls_is_not_given_agent_run() {
    DefinitionSource.Definition alone =
        new DefinitionSource.Definition(
            "code_implementation",
            "test",
            "---\nname: code_implementation\ndescription: Works alone.\n"
                + "model: reasoning\nmax-turns: 60\nmax-model-calls: 400\ntools: [file_read]\n"
                + STAGES
                + "---\nYou are conducting.\n");

    OrchestrationDefinition d =
        OrchestrationParser.parse(
            alone, Set.of("file_read", "agent_run"), OrchestrationDefinition.Tier.PROJECT);

    assertFalse(d.conductor().canDelegate());
  }

  @Test
  void the_hash_is_the_sha256_of_the_text_and_changes_with_it() {
    OrchestrationDefinition one = parse(STAGES);
    OrchestrationDefinition same = parse(STAGES);
    OrchestrationDefinition other = parse(STAGES + "max-returns: 2\n");

    assertTrue(one.hash().matches("sha256:[0-9a-f]{64}"));
    assertEquals(one.hash(), same.hash());
    assertFalse(one.hash().equals(other.hash()));
  }

  @Test
  void max_returns_artifacts_and_triggers_are_read() {
    OrchestrationDefinition d =
        parse(
            STAGES
                + "max-returns: 5\n"
                + "artifacts: docs/orchestrations/{date}-{name}-{id}/\n"
                + "triggers: [\"implement\", \"build a feature\", \"/implement\"]\n");

    assertEquals(5, d.maxReturns());
    assertEquals("docs/orchestrations/{date}-{name}-{id}/", d.artifacts());
    assertEquals(
        List.of(
            new Trigger("implement", false),
            new Trigger("build a feature", false),
            new Trigger("/implement", true)),
        d.triggers());
  }

  @Test
  void a_name_that_cannot_be_a_tool_name_is_refused() {
    assertEquals(
        "the orchestration definition 'Code-Impl' (test) is named 'Code-Impl', and an"
            + " orchestration's name becomes the tool orchestrate_<name>, so it is lower-case"
            + " letters, digits and underscores",
        refusal("Code-Impl", STAGES));
  }

  @Test
  void stages_are_required_and_not_empty() {
    assertEquals(
        PREFIX + " has no stages: an orchestration is a list of at least one stage",
        refusal("code_implementation", ""));
    assertEquals(
        PREFIX + " has no stages: an orchestration is a list of at least one stage",
        refusal("code_implementation", "stages: []\n"));
  }

  @Test
  void a_stage_is_a_map_with_a_well_formed_unique_id() {
    assertEquals(
        PREFIX + " has a stage that is not a map with an 'id': goal",
        refusal("code_implementation", "stages: [goal]\n"));
    assertEquals(
        PREFIX
            + " has the stage id 'Goal', and a stage id is lower-case letters,"
            + " digits and underscores",
        refusal("code_implementation", "stages:\n  - {id: Goal}\n"));
    assertEquals(
        PREFIX + " has two stages with the id 'goal'",
        refusal("code_implementation", "stages:\n  - {id: goal}\n  - {id: goal}\n"));
    assertEquals(
        PREFIX
            + " has the stage 'goal' with the unrecognised key 'done_when'. A stage"
            + " takes [acceptance, check, children, done-when, id, may-return-to]",
        refusal("code_implementation", "stages:\n  - {id: goal, done_when: x}\n"));
  }

  @Test
  void may_return_to_names_only_an_earlier_stage() {
    assertEquals(
        PREFIX
            + " lets the stage 'code' return to 'review', which is not an earlier"
            + " stage. A stage may only return to one before it",
        refusal(
            "code_implementation",
            "stages:\n  - {id: code, may-return-to: [review]}\n  - {id: review}\n"));
    assertEquals(
        PREFIX
            + " lets the stage 'review' return to 'nowhere', which is not an earlier"
            + " stage. A stage may only return to one before it",
        refusal(
            "code_implementation",
            "stages:\n  - {id: code}\n  - {id: review, may-return-to: [nowhere]}\n"));
  }

  @Test
  void max_returns_is_a_positive_whole_number() {
    assertEquals(
        PREFIX + " has 'max-returns: 0', and it is a whole number of at least 1",
        refusal("code_implementation", STAGES + "max-returns: 0\n"));
    assertEquals(
        PREFIX + " has 'max-returns: lots', and it is a whole number of at least 1",
        refusal("code_implementation", STAGES + "max-returns: lots\n"));
  }

  @Test
  void artifacts_is_a_relative_visible_path_using_only_the_three_placeholders() {
    String absolute = refusal("code_implementation", STAGES + "artifacts: /tmp/{id}\n");
    String upward = refusal("code_implementation", STAGES + "artifacts: docs/../{id}\n");
    String hidden = refusal("code_implementation", STAGES + "artifacts: .plowshare/{id}\n");
    String unknown = refusal("code_implementation", STAGES + "artifacts: docs/{user}/\n");

    assertEquals(
        PREFIX
            + " has 'artifacts: /tmp/{id}', and it must be a path relative to the"
            + " project, with no '.' or '..' segment and no segment starting with '.'",
        absolute);
    assertEquals(
        PREFIX
            + " has 'artifacts: docs/../{id}', and it must be a path relative to the"
            + " project, with no '.' or '..' segment and no segment starting with '.'",
        upward);
    assertEquals(
        PREFIX
            + " has 'artifacts: .plowshare/{id}', and it must be a path relative to"
            + " the project, with no '.' or '..' segment and no segment starting with '.'",
        hidden);
    assertEquals(
        PREFIX
            + " has 'artifacts: docs/{user}/', which uses '{user}'. The placeholders"
            + " are {date}, {name} and {id}",
        unknown);
  }

  @Test
  void a_trigger_is_a_phrase_or_a_slash_command_and_is_not_repeated() {
    assertEquals(
        PREFIX
            + " has the trigger '/do it', and a command trigger is '/' followed by"
            + " lower-case letters, digits, '_' or '-'",
        refusal("code_implementation", STAGES + "triggers: [\"/do it\"]\n"));
    assertEquals(
        PREFIX + " has a blank trigger",
        refusal("code_implementation", STAGES + "triggers: [\"  \"]\n"));
    assertEquals(
        PREFIX + " has the trigger 'Implement' twice",
        refusal("code_implementation", STAGES + "triggers: [implement, Implement]\n"));
  }

  @Test
  void a_conductor_is_never_exported_delegable_a_bot_or_an_inbox() {
    for (String key :
        List.of("exported: true", "delegable: false", "bot: true", "announces-inbox: true")) {
      String name = key.substring(0, key.indexOf(':'));
      assertEquals(
          PREFIX
              + " has the frontmatter key '"
              + name
              + "', which this kind of"
              + " definition does not take",
          refusal("code_implementation", STAGES + key + "\n"));
    }
  }

  @Test
  void the_source_is_the_file_s_text() {
    DefinitionSource.Definition entry = file("code_implementation", STAGES);

    OrchestrationDefinition d =
        OrchestrationParser.parse(entry, TOOLS, OrchestrationDefinition.Tier.PROJECT);

    assertEquals(entry.text(), d.source());
  }

  @Test
  void a_conductor_may_not_declare_a_refusal_fallback() {
    assertEquals(
        PREFIX
            + " has the frontmatter key 'fallback', which this kind of"
            + " definition does not take",
        refusal(
            "code_implementation",
            STAGES + "fallback: {when: [refusal], model: m, max-attempts: 1}\n"));
  }

  @Test
  void a_conductor_may_be_granted_orchestrations_and_hold_their_tools() {
    String text =
        "---\nname: code_implementation\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\norchestrations: [implement_task]\n"
            + "tools: [orchestrate_implement_task, orchestration_answer, orchestration_status,"
            + " orchestration_cancel]\n"
            + STAGES
            + "---\nbody\n";
    Set<String> knownTools =
        Set.of(
            "orchestrate_implement_task",
            "orchestration_answer",
            "orchestration_status",
            "orchestration_cancel");

    OrchestrationDefinition parsed =
        OrchestrationParser.parse(
            new DefinitionSource.Definition("code_implementation", "test", text),
            knownTools,
            OrchestrationDefinition.Tier.PROJECT);

    assertEquals(List.of("implement_task"), parsed.conductor().orchestrations());
    assertTrue(parsed.conductor().tools().contains("orchestrate_implement_task"));
  }

  @Test
  void a_tool_this_server_does_not_bind_disables_the_orchestration() {
    String text =
        "---\nname: code_implementation\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\ntools: [file_read, file_teleport]\n"
            + STAGES
            + "---\nbody\n";
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                OrchestrationParser.parse(
                    new DefinitionSource.Definition("code_implementation", "test", text),
                    TOOLS,
                    OrchestrationDefinition.Tier.PROJECT));
    assertEquals(
        PREFIX
            + " names the tool 'file_teleport', which this server does not bind. A"
            + " conductor without a tool its stages need would fail mid-run, so the"
            + " orchestration is not offered at all",
        refused.getMessage());
  }

  @Test
  void a_harness_tool_a_conductor_tried_to_declare_is_refused_by_name() {
    // These are RunExtras tools, so they are never in knownTools and the generic refusal would
    // only say the server does not bind them — true of the registry, useless to the author.
    String text =
        "---\nname: code_implementation\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\norchestrations: [implement_task]\n"
            + "tools: [orchestration_answer]\n"
            + STAGES
            + "---\nbody\n";

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                OrchestrationParser.parse(
                    new DefinitionSource.Definition("code_implementation", "test", text),
                    TOOLS,
                    OrchestrationDefinition.Tier.PROJECT));

    assertEquals(
        PREFIX
            + " names the tool 'orchestration_answer', which a conductor does not"
            + " declare: the harness hands orchestration_ask, orchestration_check,"
            + " orchestration_finish, todo_read and todo_write to every conductor, and"
            + " orchestrate_<name> with orchestration_answer, orchestration_status and"
            + " orchestration_cancel to one that holds an 'orchestrations:' grant. Drop it from"
            + " 'tools:' — the grant is what offers it",
        refused.getMessage());
    for (String harnessTool :
        List.of(
            "orchestrate_implement_task",
            "orchestration_ask",
            "orchestration_check",
            "orchestration_finish",
            "orchestration_status",
            "orchestration_cancel",
            "todo_read",
            "todo_write")) {
      assertTrue(
          assertThrows(
                  IllegalStateException.class,
                  () ->
                      OrchestrationParser.parse(
                          new DefinitionSource.Definition(
                              "code_implementation",
                              "test",
                              text.replace("orchestration_answer", harnessTool)),
                          TOOLS,
                          OrchestrationDefinition.Tier.PROJECT))
              .getMessage()
              .contains(
                  "names the tool '" + harnessTool + "', which a conductor" + " does not declare"));
    }
  }

  @Test
  void a_conductor_may_not_declare_the_todo_tools_the_harness_hands_it() {
    assertTrue(refusalWithTools("[file_read, todo_write]").contains("todo_write"));
  }

  @Test
  void the_shared_agent_refusals_speak_of_an_orchestration() {
    String message = refusal("code_implementation", STAGES.replace("stages", "stagse"));
    assertTrue(
        message.startsWith(PREFIX + " has the unrecognised frontmatter key 'stagse'"), message);
  }

  @Test
  void a_key_written_with_no_value_is_refused_not_thrown() {
    assertEquals(
        PREFIX + " has no stages: an orchestration is a list of at least one stage",
        refusal("code_implementation", "stages:\n"));

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                OrchestrationParser.parse(
                    file("code_implementation", STAGES + "artifacts:\n"),
                    TOOLS,
                    OrchestrationDefinition.Tier.PROJECT));
    assertTrue(
        refused.getMessage().startsWith(PREFIX + " has 'artifacts: null'"), refused.getMessage());
  }

  @Test
  void a_done_when_that_is_not_text_is_refused() {
    assertEquals(
        PREFIX + " has the stage 'goal' whose 'done-when' is not text",
        refusal("code_implementation", "stages:\n  - {id: goal, done-when: 3}\n"));
  }

  @Test
  void a_bound_tool_withheld_by_name_is_reported_with_its_real_reason() {
    String text =
        "---\nname: scribe\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\ntools: [memory_write]\n"
            + STAGES
            + "---\nbody\n";
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                OrchestrationParser.parse(
                    new DefinitionSource.Definition("scribe", "test", text),
                    Set.of("memory_write"),
                    OrchestrationDefinition.Tier.PROJECT));
    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "the orchestration definition 'scribe' (test) may not hold the tool"
                    + " 'memory_write': "),
        refused.getMessage());
    assertFalse(refused.getMessage().contains("the agent definition '"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains(": the orchestration definition 'scribe' (test)"),
        refused.getMessage());
  }

  @Test
  void a_stage_key_that_is_not_text_is_refused_not_thrown() {
    assertEquals(
        PREFIX
            + " has the stage 'a' with the unrecognised key 'null'. A stage takes"
            + " [acceptance, check, children, done-when, id, may-return-to]",
        refusal("code_implementation", "stages:\n  - {id: a, ~: x}\n"));
  }

  @Test
  void a_non_string_trigger_is_refused() {
    assertEquals(
        PREFIX + " has the trigger '3', which is not text",
        refusal("code_implementation", STAGES + "triggers: [3]\n"));
  }

  @Test
  void a_doubled_slash_in_artifacts_is_refused_but_a_trailing_slash_is_not() {
    assertEquals(
        PREFIX
            + " has 'artifacts: docs//{id}', and it must be a path relative to the"
            + " project, with no '.' or '..' segment and no segment starting with '.'",
        refusal("code_implementation", STAGES + "artifacts: docs//{id}\n"));

    OrchestrationDefinition d =
        parse(STAGES + "artifacts: docs/orchestrations/{date}-{name}-{id}/\n");
    assertEquals("docs/orchestrations/{date}-{name}-{id}/", d.artifacts());
  }

  @Test
  void a_stage_may_be_checked_and_only_as_required() {
    OrchestrationDefinition d =
        parse(
            """
                stages:
                  - {id: test_design}
                  - {id: code, check: required}
                  - {id: review, check: required, may-return-to: [code]}
                """);

    assertEquals(List.of(false, true, true), d.stages().stream().map(Stage::checked).toList());
    assertTrue(
        refusal(
                "code_implementation",
                """
                stages:
                  - {id: test_design}
                  - {id: code, check: yes}
                """)
            .contains("has the stage 'code' whose 'check' is not 'required'"));
  }

  @Test
  void a_checked_first_stage_is_refused_because_nothing_comes_before_it_to_set_the_check() {
    assertTrue(
        refusal(
                "code_implementation",
                """
                stages:
                  - {id: code, check: required}
                """)
            .contains("no stage comes before it"));
  }

  @Test
  void an_acceptance_stage_needs_a_stage_that_writes_its_commands_before_it() {
    String refusal =
        refusedWith(
            """
                stages:
                  - {id: spec}
                  - {id: acceptance, acceptance: required}
                """);
    assertTrue(
        refusal.contains(
            "'acceptance: required' needs an earlier stage with" + " 'acceptance: written'"),
        refusal);
  }

  @Test
  void acceptance_takes_written_or_required_once_each() {
    assertTrue(
        refusedWith(
                """
                stages:
                  - {id: spec, acceptance: maybe}
                """)
            .contains("whose 'acceptance' is not 'written' or 'required'"));
    assertTrue(
        refusedWith(
                """
                stages:
                  - {id: spec, acceptance: written}
                  - {id: more, acceptance: written}
                """)
            .contains("more than one stage with 'acceptance: written'"));
  }

  @Test
  void a_stage_says_which_acceptance_it_is() {
    List<OrchestrationDefinition.Stage> stages =
        parsedStages(
            """
                stages:
                  - {id: spec, acceptance: written}
                  - {id: code}
                  - {id: acceptance, acceptance: required, may-return-to: [code]}
                """);
    assertEquals("written", stages.get(0).acceptance());
    assertNull(stages.get(1).acceptance());
    assertEquals("required", stages.get(2).acceptance());
  }

  /**
   * Measured 2026-09-29/30, orc_318DFD3782228160: a root filed its phases under `plan`, and the
   * refusal could not say where they belonged because nothing marked the stage that holds them.
   */
  @Test
  void a_stage_may_hold_the_phases_once_and_only_with_an_orchestrations_grant() {
    List<Stage> stages =
        parsedStages(
            """
                orchestrations: [code_implementation]
                stages:
                  - {id: plan}
                  - {id: phases, children: phases}
                  - {id: review, may-return-to: [phases]}
                """);
    assertEquals(List.of(false, true, false), stages.stream().map(Stage::holdsPhases).toList());

    assertTrue(
        refusedWith(
                """
                orchestrations: [code_implementation]
                stages:
                  - {id: phases, children: runs}
                """)
            .contains("has the stage 'phases' whose 'children' is not 'phases'"));
    assertTrue(
        refusedWith(
                """
                orchestrations: [code_implementation]
                stages:
                  - {id: phases, children: phases}
                  - {id: more, children: phases}
                """)
            .contains("more than one stage with 'children: phases'"));
    assertTrue(
        refusedWith(
                """
                stages:
                  - {id: phases, children: phases}
                """)
            .contains("holds no 'orchestrations:' grant to start them"));
  }

  @Test
  void a_conductor_may_declare_the_studio_tools_though_no_agent_can() {
    OrchestrationDefinition parsed =
        OrchestrationRegistry.parsePinned(
            "studio",
            "test",
            """
                ---
                name: studio
                description: d
                model: m
                max-turns: 2
                max-model-calls: 4
                tools: [orchestration_catalog, orchestration_validate, orchestration_install]
                stages:
                  - {id: goal}
                artifacts: docs/orchestrations/{date}-{name}-{id}/
                ---
                body
                """,
            Set.of("file_read"),
            OrchestrationDefinition.Tier.PROJECT);

    assertEquals(
        List.of("orchestration_catalog", "orchestration_validate", "orchestration_install"),
        parsed.conductor().tools());
  }

  @Test
  void install_needs_an_artifacts_directory_to_install_from() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                OrchestrationRegistry.parsePinned(
                    "studio",
                    "test",
                    """
                        ---
                        name: studio
                        description: d
                        model: m
                        max-turns: 2
                        max-model-calls: 4
                        tools: [orchestration_install]
                        stages:
                          - {id: goal}
                        ---
                        body
                        """,
                    Set.of(),
                    OrchestrationDefinition.Tier.PROJECT));

    assertTrue(
        refused
            .getMessage()
            .contains(
                "orchestration_install installs a draft from the"
                    + " run's artifacts directory, and this definition names none"),
        refused.getMessage());
  }

  // --- checker: (spec 2026-10-01, the acceptance checker §2) ---------------------------------

  private static final String CHECKED_STAGES =
      """
            stages:
              - {id: spec, acceptance: written}
              - {id: plan}
              - {id: acceptance, acceptance: required}
            artifacts: docs/orchestrations/{date}-{name}-{id}/
            """;

  @Test
  void checker_names_the_agent_and_absent_is_none() {
    assertEquals(
        "acceptance_checker", parse(CHECKED_STAGES + "checker: acceptance_checker\n").checker());
    assertEquals(null, parse(CHECKED_STAGES).checker());
  }

  @Test
  void a_checker_needs_an_acceptance_to_hold_the_run_to_and_an_artifacts_directory() {
    assertTrue(
        refusedWith(STAGES + "checker: acceptance_checker\n")
            .contains("has no stage" + " with 'acceptance: required'"));
    assertTrue(
        refusedWith(
                CHECKED_STAGES.replace("artifacts: docs/orchestrations/{date}-{name}-{id}/\n", "")
                    + "checker: acceptance_checker\n")
            .contains("no 'artifacts:' directory"));
    assertTrue(
        refusedWith(CHECKED_STAGES + "checker: [a, b]\n").contains("it names one" + " agent"));
  }

  @Test
  void a_conductor_does_not_declare_checker_answer_the_harness_hands_it() {
    assertTrue(
        refusalWithTools("[file_read, checker_answer]")
            .contains("names the tool" + " 'checker_answer', which a conductor does not declare"));
  }
}
