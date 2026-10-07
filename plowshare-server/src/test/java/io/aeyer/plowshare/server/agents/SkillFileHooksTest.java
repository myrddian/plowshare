package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Stage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Skill write checks need fenced file access and parser behavior, never PostgreSQL. */
class SkillFileHooksTest {
  @TempDir Path temporary;
  private Path root;
  private Path skill;
  private LocalProvider provider;
  private ProviderRouter router;
  private SkillFileHooks hooks;
  private final HookContext context =
      new HookContext("writer", true, Set.of("file_edit"), "project", "log", HookContext.SERVER);

  private static final String VALID =
      "---\nname: weather\ndescription: Capture weather\n---\nRead the weather.\n";

  @BeforeEach
  void filesystem() throws Exception {
    root = temporary.toRealPath();
    skill = root.resolve("Resources/skills/weather/SKILL.md");
    provider =
        spy(
            LocalProvider.over(
                FileAccess.of(List.of(root), List.of()),
                List.of(new Grant(Scope.WORKSPACE, Mode.WRITE))));
    router = new ProviderRouter(home -> List.of(provider));
    hooks = new SkillFileHooks(router);
  }

  private static String arguments(Path path, String content) throws Exception {
    return new ObjectMapper()
        .writeValueAsString(Map.of("path", path.toString(), "content", content));
  }

  private String patch(Path path, String old, String replacement) throws Exception {
    return new ObjectMapper()
        .writeValueAsString(Map.of("path", path.toString(), "old", old, "new", replacement));
  }

  @Test
  void missing_frontmatter_is_denied_with_correction_and_no_write() throws Exception {
    String args = arguments(skill, "Capture the weather.");
    var pre = hooks.toolPre(context, "file_edit", args);
    assertTrue(pre.isDenied());
    assertTrue(pre.denied().contains("SKILL.md needs YAML frontmatter"));
    assertTrue(pre.denied().contains("non-empty description"));
    assertEquals(HookRecord.DENY, pre.records().getFirst().decision());
    assertEquals(Stage.TOOL_PRE, pre.records().getFirst().stage());
    assertEquals(args, pre.arguments());
    assertFalse(Files.exists(skill));
    verify(provider, never()).create(any(), anyString());
    verify(provider, never()).write(any(), anyString());
  }

  @Test
  void duplicate_policy_key_is_denied_before_it_can_refuse_the_entire_catalog() throws Exception {
    Path policy = root.resolve("Resources/skills.yml");
    var pre =
        hooks.toolPre(
            context,
            "file_edit",
            arguments(
                policy,
                "skills:\n  weather:\n    agentVisible: true\n  weather:\n    agentVisible: false\n"));
    assertTrue(pre.isDenied());
    assertTrue(pre.denied().contains("duplicate key weather"));
    assertTrue(pre.denied().contains("Merge duplicate entries"));
    assertTrue(pre.denied().contains("line 4, column 3"), pre.denied());
    assertFalse(pre.denied().contains("frontmatter"), pre.denied());
    assertFalse(Files.exists(policy));
  }

  @Test
  void valid_source_keeps_arguments_and_does_not_explicitly_allow_permissions() throws Exception {
    String args = arguments(skill, VALID);
    var pre = hooks.toolPre(context, "file_edit", args);
    assertFalse(pre.isDenied());
    assertFalse(pre.explicitlyAllowed());
    assertEquals(args, pre.arguments());
    String written = new FileTools.Edit(router).run(args, Home.of("project"));
    var post = hooks.toolPost(context, "file_edit", args, written);
    assertTrue(post.result().startsWith(written + "\n"));
    assertTrue(post.result().contains("saved skill source is valid"));
    assertEquals(VALID, Files.readString(skill));
    assertEquals(HookRecord.NOTE, post.records().getFirst().decision());
  }

  @Test
  void partial_edit_checks_complete_saved_file_and_can_be_corrected() throws Exception {
    Files.createDirectories(skill.getParent());
    Files.writeString(skill, VALID.replace("\n", "\r\n"));
    String args = patch(skill, "name: weather", "name: weather\r\nname: weather");
    assertFalse(hooks.toolPre(context, "file_edit", args).isDenied());
    var tool = new FileTools.Edit(router);
    String result = tool.run(args, Home.of("project"));
    var post = hooks.toolPost(context, "file_edit", args, result);
    assertTrue(post.result().startsWith(result));
    assertTrue(post.result().contains("edit was saved"));
    assertTrue(post.result().contains("duplicate key name"));
    assertTrue(post.result().contains("Reason: "), post.result());
    assertTrue(post.result().contains("Correction: Merge duplicate entries"), post.result());
    assertFalse(post.result().contains("Add YAML frontmatter"), post.result());
    assertTrue(Files.readString(skill).contains("name: weather\r\nname: weather"));
    String fix = patch(skill, "name: weather\r\nname: weather", "name: weather");
    String fixed = tool.run(fix, Home.of("project"));
    assertTrue(
        hooks.toolPost(context, "file_edit", fix, fixed).result().contains("source is valid"));
    assertEquals(VALID.replace("\n", "\r\n"), Files.readString(skill));
    verify(provider, times(2)).edit(any(), anyString(), anyString());
  }

  @Test
  void post_checks_saved_bytes_even_when_later_pre_hooks_rewrite_content() throws Exception {
    String original = arguments(skill, VALID);
    Hooks chain =
        Hooks.chain(
            hooks,
            new Hooks() {
              @Override
              public io.aeyer.plowshare.server.hooks.ToolPre toolPre(
                  HookContext context, String tool, String args) {
                try {
                  return io.aeyer.plowshare.server.hooks.ToolPre.allowed(
                      arguments(skill, "No frontmatter"));
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              }
            });
    var pre = chain.toolPre(context, "file_edit", original);
    assertFalse(pre.isDenied());
    String result = new FileTools.Edit(router).run(pre.arguments(), Home.of("project"));
    var post = chain.toolPost(context, "file_edit", pre.arguments(), result);
    assertTrue(post.result().contains("SKILL.md needs YAML frontmatter"));
    assertEquals("No frontmatter", Files.readString(skill));
  }

  @Test
  void refused_edits_and_unrelated_tools_are_not_observed() throws Exception {
    String args = arguments(skill, VALID);
    assertEquals("refused", hooks.toolPost(context, "file_edit", args, "refused").result());
    assertFalse(hooks.toolPre(context, "file_read", args).isDenied());
    assertEquals("read", hooks.toolPost(context, "file_read", args, "read").result());
    verifyNoInteractions(provider);
  }

  @Test
  void ordinary_markdown_support_files_and_malformed_calls_are_not_checked() throws Exception {
    for (Path path :
        List.of(
            root.resolve("docs/SKILL.md"),
            root.resolve("notes.md"),
            root.resolve("Resources/skills/weather/references/SKILL.md"),
            root.resolve("docs/skills.yml"))) {
      assertFalse(hooks.toolPre(context, "file_edit", arguments(path, "ordinary text")).isDenied());
    }
    for (String args :
        List.of(
            "not json",
            "{}",
            "{\"path\":\"SKILL.md\",\"content\":\"x\"}",
            arguments(skill, VALID).replace("\"content\":", "\"old\":"))) {
      assertFalse(hooks.toolPre(context, "file_edit", args).isDenied());
    }
    verify(provider, never()).fingerprint(any());
  }

  @Test
  void unverified_post_read_preserves_receipt_and_never_repeats_the_write() throws Exception {
    String args = arguments(skill, VALID);
    String written = new FileTools.Edit(router).run(args, Home.of("project"));
    for (RuntimeException failure :
        List.of(
            new WorkspaceRefusedException("client cannot serve raw snapshots"),
            new WorkspaceUnavailableException("source changed during snapshot"))) {
      doThrow(failure).when(provider).fingerprint(skill);
      var post = hooks.toolPost(context, "file_edit", args, written);
      assertTrue(post.result().startsWith(written));
      assertTrue(post.result().contains("do not repeat the acknowledged edit"));
      assertEquals(HookRecord.NOTE, post.records().getFirst().decision());
    }
    verify(provider, times(1)).create(any(), anyString());
    verify(provider, never()).write(any(), anyString());
    assertEquals(VALID, Files.readString(skill));
  }

  @Test
  void validation_snapshot_does_not_grant_read_before_replace_authority() throws Exception {
    Files.createDirectories(skill.getParent());
    Files.writeString(skill, VALID + "Supporting instruction.\n".repeat(40));
    var tool = new FileTools.Edit(router);
    String args = patch(skill, "Read the weather.", "Capture the weather.");
    String edited = tool.run(args, Home.of("project"));
    assertTrue(
        hooks.toolPost(context, "file_edit", args, edited).result().contains("source is valid"));
    String replacement = tool.run(arguments(skill, VALID), Home.of("project"));
    assertEquals(ToolLines.REFUSED, ToolLines.outcome("file_edit", replacement));
    assertTrue(Files.readString(skill).contains("Capture the weather."));
    verify(provider, never()).write(any(), anyString());
  }

  @Test
  void pre_validation_respects_provider_access_and_does_not_fall_back() throws Exception {
    FileProvider unavailable = mock(FileProvider.class);
    when(unavailable.name()).thenReturn("remote");
    when(unavailable.roots())
        .thenThrow(new WorkspaceUnavailableException("session is disconnected"));
    var isolated = new SkillFileHooks(new ProviderRouter(home -> List.of(unavailable)));
    var pre = isolated.toolPre(context, "file_edit", arguments(skill, VALID));
    assertTrue(pre.isDenied());
    assertTrue(pre.denied().contains("nothing was written"));
    assertEquals(HookRecord.FAILED, pre.records().getFirst().decision());
    verify(unavailable, never()).create(any(), anyString());
    verify(unavailable, never()).fingerprint(any());
  }

  @Test
  void rooted_client_paths_and_server_tier_roots_use_the_same_parser() {
    for (Path path :
        List.of(
            root.resolve(".plowshare/skills/weather/SKILL.md"),
            root.resolve("skills/weather/SKILL.md"),
            skill)) {
      var target = SkillFileValidation.target(path, List.of(root));
      assertNotNull(target);
      assertEquals("", target.check(VALID));
      assertTrue(
          target.check(VALID.replace("name: weather", "name: another")).contains("directory"));
    }
    assertNull(SkillFileValidation.target(skill, List.of(root.resolve("other-project"))));
  }

  @Test
  void discovery_constraints_and_source_limit_are_reused() {
    var target = SkillFileValidation.target(skill, List.of(root));
    for (String content :
        List.of(
            VALID.replace("description: Capture weather", "description: true"),
            VALID.replace(
                "description: Capture weather", "description: Capture weather\nunknown: true"),
            VALID.replace(
                "description: Capture weather",
                "description: Capture weather\nmode: DIRECT\nagent: helper"),
            "x".repeat((int) ChannelDefinitions.MAX_DEFINITION_BYTES + 1))) {
      assertFalse(target.check(content).isEmpty());
    }
  }

  @Test
  void rejections_report_the_specific_reason_and_matching_correction() throws Exception {
    for (var example :
        List.of(
            new DiagnosticCase(
                "---\nname: weather\ndescription: Capture weather\n",
                "no closing frontmatter fence",
                "Add the missing closing --- line"),
            new DiagnosticCase(
                VALID.replace("name: weather", "name: different"),
                "skill name must match its directory",
                "package directory 'weather'"),
            new DiagnosticCase(
                VALID.replace("description: Capture weather", "description: true"),
                "'description' must be non-empty text",
                "Correct the named field"),
            new DiagnosticCase(
                VALID.replace(
                    "description: Capture weather", "description: Capture weather\nunknown: true"),
                "unsupported skill field 'unknown'",
                "Remove the reported unsupported field"),
            new DiagnosticCase(
                VALID.replace(
                    "description: Capture weather",
                    "description: Capture weather\nmode: DIRECT\nagent: helper"),
                "DIRECT cannot select another agent",
                "Remove agent when using DIRECT"),
            new DiagnosticCase(
                VALID.replace(
                    "description: Capture weather",
                    "description: Capture weather\nmode: automatic"),
                "received 'automatic'",
                "listed uppercase values"))) {
      var pre = hooks.toolPre(context, "file_edit", arguments(skill, example.source()));
      assertTrue(pre.isDenied(), example.reason());
      assertTrue(pre.denied().contains("SKILL.md in skill 'weather'"), pre.denied());
      assertTrue(pre.denied().contains("Reason: "), pre.denied());
      assertTrue(pre.denied().contains(example.reason()), pre.denied());
      assertTrue(
          pre.denied().contains("Correction: " + example.correction())
              || pre.denied().contains(example.correction()),
          pre.denied());
      assertFalse(pre.denied().contains("Add YAML frontmatter"), pre.denied());
      assertEquals(pre.denied(), pre.records().getFirst().reason());
    }
  }

  private record DiagnosticCase(String source, String reason, String correction) {}

  @Test
  void multiple_field_failures_are_reported_in_deterministic_order_with_a_valid_example() {
    var target = SkillFileValidation.target(skill, List.of(root));
    String invalid =
        "---\nunknown-z: true\nunknown-a: true\nname: other\ndescription: false\n"
            + "mode: automatic\nagentVisible: 'true'\n---\n";
    List<String> problems =
        SkillDefinition.problems(new DefinitionSource.Definition("weather", "SKILL.md", invalid));
    assertEquals(7, problems.size());
    String report = target.check(invalid);
    int previous = -1;
    for (String problem : problems) {
      int position = report.indexOf("Reason: " + problem);
      assertTrue(position > previous, report);
      previous = position;
    }
    assertEquals(report, target.check(invalid));
    assertEquals(
        report,
        target.check(
            invalid.replace(
                "unknown-z: true\nunknown-a: true", "unknown-a: true\nunknown-z: true")));
    assertTrue(report.endsWith(target.example()));
    assertFalse(Files.exists(skill));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SkillDefinition.parse(
                new DefinitionSource.Definition("weather", "SKILL.md", invalid),
                OrchestrationDefinition.Tier.PROJECT));
  }

  @Test
  void visibility_reports_multiple_invalid_entries_in_name_order() {
    var target = SkillFileValidation.target(root.resolve("Resources/skills.yml"), List.of(root));
    String invalid =
        "skills:\n  z-skill:\n    agentVisible: 'true'\n" + "  a-skill:\n    agentVisible: 7\n";
    String report = target.check(invalid);
    assertTrue(
        report.indexOf("Reason: skill 'a-skill'") < report.indexOf("Reason: skill 'z-skill'"),
        report);
    assertTrue(report.endsWith(target.example()));
    assertThrows(IllegalArgumentException.class, () -> SkillVisibility.parse(invalid));
  }

  @Test
  void default_examples_are_deterministic_and_accepted_by_discovery() {
    for (String name : List.of("weather", "true", "révision", "bad--name")) {
      var target = new SkillFileValidation.Target(name);
      String source = exampleSource(target);
      var parsed =
          SkillDefinition.parse(
              new DefinitionSource.Definition(
                  name.equals("bad--name") ? "example-skill" : name, "SKILL.md", source),
              OrchestrationDefinition.Tier.PROJECT);
      assertEquals(target.example(), target.example());
      assertNull(parsed.mode());
      assertFalse(parsed.agentVisible());
      assertFalse(parsed.agentSpecified());
    }
    var policy = new SkillFileValidation.Target(null);
    assertEquals(Map.of("example-skill", false), SkillVisibility.parse(exampleSource(policy)));
  }

  @Test
  void broken_yaml_preserves_location_and_warns_about_further_failures_with_the_example()
      throws Exception {
    Path policy = root.resolve("Resources/skills.yml");
    String invalid = "skills:\n  weather:\n    agentVisible: 7\n  weather:\n    unknown: true\n";
    var pre = hooks.toolPre(context, "file_edit", arguments(policy, invalid));
    assertTrue(pre.isDenied());
    assertTrue(pre.denied().contains("duplicate key weather"));
    assertTrue(pre.denied().contains("line 4, column 3"));
    assertTrue(pre.denied().contains("Other validation failures may remain"));
    assertTrue(pre.denied().endsWith(new SkillFileValidation.Target(null).example()));
    assertFalse(Files.exists(policy));
  }

  @Test
  void bounded_multi_error_reports_keep_the_default_example_and_disclose_omitted_failures() {
    var target = new SkillFileValidation.Target("weather");
    StringBuilder fields = new StringBuilder("---\nname: weather\ndescription: Weather\n");
    for (int at = 0; at < 100; at++) {
      fields.append("unknown-field-").append(at).append(": true\n");
    }
    fields.append("---\nCapture weather.\n");
    String report = target.check(fields.toString());
    assertTrue(report.contains("additional validation failures omitted"), report);
    assertTrue(report.length() < 10000);
    assertTrue(report.endsWith(target.example()));
    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SkillDefinition.parse(
                    new DefinitionSource.Definition("weather", "SKILL.md", fields.toString()),
                    OrchestrationDefinition.Tier.PROJECT));
    assertEquals("unsupported skill field 'unknown-field-0'", refusal.getMessage());
  }

  private static String exampleSource(SkillFileValidation.Target target) {
    String example = target.example();
    int start = example.indexOf('\n', example.indexOf("```")) + 1;
    return example.substring(start, example.lastIndexOf("\n```"));
  }
}
