package io.aeyer.plowshare.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.hooks.script.Decision;
import io.aeyer.plowshare.server.orchestrations.OrchestrationState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The TypeScript contract a hook author imports and the Java constants the server reads decisions
 * with are one vocabulary, pinned here as the file wire is pinned in the TUI's {@code
 * mirrors-the-server.test.ts}.
 */
class HookContractMirrorTest {

  private static String contract() throws Exception {
    // The server module's working directory in a Gradle test run is the module.
    return Files.readString(Path.of("..", "plowshare-hooks", "index.ts"));
  }

  private static String stripComments(String source) {
    // Remove /* ... */ blocks (non-greedy, DOTALL).
    String stripped = source.replaceAll("(?s)/\\*.*?\\*/", "");
    // Remove // line comments.
    stripped = stripped.replaceAll("//[^\n]*", "");
    return stripped;
  }

  private static List<String> union(String source, String typeName) {
    Matcher found = Pattern.compile("export type " + typeName + " =([^\\n]+)").matcher(source);
    assertTrue(found.find(), "no `export type " + typeName + "` in plowshare-hooks/index.ts");
    Matcher literal = Pattern.compile("'([^']+)'").matcher(found.group(1));
    List<String> values = new java.util.ArrayList<>();
    while (literal.find()) {
      values.add(literal.group(1));
    }
    return values;
  }

  @Test
  void information_targets_have_the_same_fields_in_java_and_typescript() throws Exception {
    Matcher document =
        Pattern.compile("(?s)export interface DocumentContext \\{(.*?)\\n\\}")
            .matcher(stripComments(contract()));
    assertTrue(document.find());
    List<String> fields = new ArrayList<>();
    Matcher field = Pattern.compile("readonly (\\w+)\\??:").matcher(document.group(1));
    while (field.find()) fields.add(field.group(1));
    assertEquals(
        Arrays.stream(HookContext.Document.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList(),
        fields);
    assertTrue(contract().contains("readonly document?: DocumentContext"));
  }

  @Test
  void the_stage_names_are_the_servers() throws Exception {
    assertEquals(
        Arrays.stream(Stage.values()).map(Stage::wireName).toList(), union(contract(), "Stage"));
  }

  @Test
  void the_modes_are_the_servers() throws Exception {
    assertEquals(
        Arrays.stream(Mode.values()).map(Mode::wireName).toList(), union(contract(), "Mode"));
  }

  @Test
  void every_decision_key_the_server_reads_is_declared() throws Exception {
    String source = stripComments(contract());
    for (String key :
        List.of(
            "add", "mode", "note", "redact", "allow", "deny", "rewrite", "ask", "keep", "notify")) {
      assertTrue(
          source.contains("readonly " + key + ":"),
          "plowshare-hooks/index.ts declares no `" + key + "` decision field");
    }
  }

  @Test
  void the_contract_is_types_only() throws Exception {
    String source = contract();
    assertTrue(
        !source.contains("export function") && !source.contains("export const"),
        "the contract ships runtime code; it must be erasable types only");
  }

  @Test
  void the_origins_are_the_servers() throws Exception {
    assertEquals(
        Arrays.stream(Origin.values()).map(Origin::wireName).toList(), union(contract(), "Origin"));
  }

  /**
   * {@code log.close}'s ending (spec 2026-09-28-hooks-reach-the-log §3), lower case from every
   * door: a run's ending but AWAITING, which never closes a log; an orchestration's terminal state;
   * a lifecycle a person's conversation reaches out of ACTIVE.
   */
  @Test
  void the_log_endings_are_the_servers() throws Exception {
    TreeSet<String> server = new TreeSet<>();
    Arrays.stream(Outcome.Ending.values())
        .filter(ending -> ending != Outcome.Ending.AWAITING)
        .forEach(ending -> server.add(ending.name().toLowerCase(Locale.ROOT)));
    Arrays.stream(OrchestrationState.values())
        .filter(OrchestrationState::terminal)
        .forEach(state -> server.add(state.wire()));
    Arrays.stream(ConversationLifecycle.values())
        .filter(lifecycle -> lifecycle.reachedFrom().contains(ConversationLifecycle.ACTIVE))
        .forEach(lifecycle -> server.add(lifecycle.wireName()));

    Matcher found = Pattern.compile("(?s)export type LogEnding =(.*?)\\n\\n").matcher(contract());
    assertTrue(found.find(), "no `export type LogEnding` in plowshare-hooks/index.ts");
    TreeSet<String> declared = new TreeSet<>();
    Matcher literal = Pattern.compile("'([^']+)'").matcher(found.group(1));
    while (literal.find()) {
      declared.add(literal.group(1));
    }
    assertEquals(server, declared);
  }

  /**
   * The per-stage decision table (spec 2026-09-28-hooks-reach-the-log §4): each stage's TypeScript
   * decision type names exactly the keys {@link Decision#keys} lets it return.
   */
  @Test
  void each_stage_s_decision_type_names_exactly_the_keys_the_server_reads() throws Exception {
    String source = stripComments(contract());
    for (Stage stage : Stage.values()) {
      String decision = decisionTypeOf(source, stage);
      assertEquals(
          new TreeSet<>(Decision.keys(stage)),
          new TreeSet<>(keysOf(source, decision)),
          stage.wireName() + "'s " + decision + " in plowshare-hooks/index.ts");
    }
  }

  private static String decisionTypeOf(String source, Stage stage) {
    Matcher declared =
        Pattern.compile(
                "readonly '"
                    + Pattern.quote(stage.wireName())
                    + "'\\?: (?:RunStage|ToolStage|LogStage)<\\w+, (\\w+)>")
            .matcher(source);
    assertTrue(
        declared.find(),
        "Hook.stages declares no '" + stage.wireName() + "' in plowshare-hooks/index.ts");
    return declared.group(1);
  }

  private static List<String> keysOf(String source, String typeName) {
    Matcher body =
        Pattern.compile("export type " + typeName + " =(.*?)(?=\\nexport |\\z)", Pattern.DOTALL)
            .matcher(source);
    assertTrue(body.find(), "no `export type " + typeName + "` in plowshare-hooks/index.ts");
    Matcher key = Pattern.compile("readonly (\\w+):").matcher(body.group(1));
    List<String> keys = new ArrayList<>();
    while (key.find()) {
      keys.add(key.group(1));
    }
    return keys;
  }

  /** Slices 2–4 gave every stage a seam, so none is typed as a placeholder any more. */
  @Test
  void no_stage_is_typed_as_pending() throws Exception {
    assertFalse(
        contract().contains("PendingEvent"),
        "plowshare-hooks/index.ts still types a stage with PendingEvent");
  }

  /** What approval.pre offers and approval.post reports is what approval.answer accepts. */
  @Test
  void the_approval_scopes_are_the_servers() throws Exception {
    assertEquals(RunApproval.SCOPES, union(contract(), "ApprovalScope"));
  }

  @Test
  void the_approval_decisions_are_the_servers() throws Exception {
    assertEquals(
        List.of(ApprovalAnswer.ALLOW, ApprovalAnswer.DENY, ApprovalAnswer.REVOKE),
        union(contract(), "ApprovalDecision"));
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log §3 and decision 5: every log event's context can carry
   * both.
   */
  @Test
  void a_log_context_can_carry_an_orchestration_and_a_run_environment() throws Exception {
    String source = stripComments(contract());
    Matcher context =
        Pattern.compile("(?s)export interface LogContext \\{(.*?)\\n\\}").matcher(source);
    assertTrue(context.find(), "no `export interface LogContext` in plowshare-hooks/index.ts");
    assertTrue(
        context.group(1).contains("readonly orchestration?: OrchestrationContext"),
        context.group(1));
    assertTrue(
        context.group(1).contains("readonly environment?: RunEnvironment"), context.group(1));
  }

  /** Spec 2026-09-30-local-hooks-are-served cost 4: the README says what a local allow is worth. */
  @Test
  void the_readme_tells_a_local_hook_author_where_it_lives_and_what_its_allow_is_worth()
      throws Exception {
    String readme = Files.readString(Path.of("..", "plowshare-hooks", "README.md"));

    assertTrue(readme.contains(".plowshare/hooks/"), "the README never names .plowshare/hooks/");
    assertTrue(
        readme.contains("counts only for a command on the local side"),
        "the README never says a local allow counts only on the local side");
    assertTrue(
        readme.contains("the next conversation"),
        "the README never says an edit waits for the next log");
  }
}
