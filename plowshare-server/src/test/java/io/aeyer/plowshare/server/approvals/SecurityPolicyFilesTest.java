package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityPolicyFilesTest {
  @TempDir Path root;

  @Test
  void absent_deployment_policy_uses_the_shipped_rules_and_an_empty_override_replaces_them()
      throws Exception {
    var source = SecurityPolicyFiles.directory(root);
    assertEquals(
        List.of(ApprovalPolicy.Effect.PERSONAL_READ_INTENT),
        SecurityPolicyFiles.deployment(source).rules().stream()
            .map(ApprovalPolicy.Rule::effect)
            .toList());
    Files.writeString(root.resolve("security.md"), header("[]"));
    assertEquals(List.of(), SecurityPolicyFiles.deployment(source).rules());
    assertEquals("# Notes", SecurityPolicyFiles.deployment(source).guidance());
  }

  @Test
  void only_named_fragments_in_the_security_directory_are_loaded() throws Exception {
    Path main = root.resolve("security.md");
    Files.writeString(main, header("[project.json]"));
    Path security = Files.createDirectory(root.resolve("security"));
    Files.writeString(
        security.resolve("project.json"),
        """
        [{"id":"review","effect":"PERSONAL_READ_INTENT","kind":"FILE_READ","project":null,"provider":null,
          "cwd":null,"inputDigest":null,"argv":[],"paths":[],"guidance":null}]
        """);
    Files.writeString(security.resolve("ignored.json"), "invalid");
    var source = SecurityPolicyFiles.directory(root);
    assertEquals("review", SecurityPolicyFiles.deployment(source).rules().getFirst().id());
    Files.writeString(main, header("[../project.json]"));
    assertThrows(IllegalArgumentException.class, () -> SecurityPolicyFiles.deployment(source));
    Files.writeString(main, header("[missing.json]"));
    assertThrows(IllegalArgumentException.class, () -> SecurityPolicyFiles.deployment(source));
    Files.writeString(main, header("[project.json]").replace("rules: []", "rules: []\nversion: 1"));
    assertThrows(RuntimeException.class, () -> SecurityPolicyFiles.deployment(source));
  }

  @Test
  void linked_fragments_cannot_change_the_policy() throws Exception {
    Files.writeString(root.resolve("security.md"), header("[project.json]"));
    Path security = Files.createDirectory(root.resolve("security"));
    Path elsewhere = Files.writeString(root.resolve("elsewhere.json"), "[]");
    Files.createSymbolicLink(security.resolve("project.json"), elsewhere);
    assertThrows(
        IllegalStateException.class,
        () -> SecurityPolicyFiles.deployment(SecurityPolicyFiles.directory(root)));
  }

  @Test
  void workspace_files_supply_markdown_guidance_and_cannot_declare_rule_effects() throws Exception {
    Path main = root.resolve("security.md");
    Files.writeString(main, "# Workspace policy");
    var source = SecurityPolicyFiles.directory(root);
    assertEquals("# Workspace policy", SecurityPolicyFiles.workspace(source));
    Files.writeString(main, "---\nversion: 1\nincludes: [edits.md]\n---\n# Workspace policy");
    Files.writeString(
        Files.createDirectory(root.resolve("security")).resolve("edits.md"),
        "Review file edits closely.");
    assertEquals(
        "# Workspace policy\n\nReview file edits closely.", SecurityPolicyFiles.workspace(source));
    Files.writeString(main, header("[]"));
    assertThrows(IllegalArgumentException.class, () -> SecurityPolicyFiles.workspace(source));
  }

  private static String header(String includes) {
    return "---\nversion: 1\nincludes: " + includes + "\nrules: []\n---\n# Notes\n";
  }
}
