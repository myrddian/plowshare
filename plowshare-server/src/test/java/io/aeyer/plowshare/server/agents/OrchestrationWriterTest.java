package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Installing an orchestration — spec 2026-09-29-orchestration-studio §3.4, step 4. */
class OrchestrationWriterTest {

  @Test
  void cannot_install_or_acknowledge_a_project_replacement_of_the_system_builder(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationWriter writer = new OrchestrationWriter(layout);
    Path target = layout.orchestrationsFor(7L).resolve("design_orchestration.md");
    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () -> writer.write(7L, "design_orchestration", "replacement"));
    assertTrue(refusal.getMessage().contains("required system capability"));
    assertFalse(Files.exists(target));

    Files.createDirectories(target.getParent());
    Files.writeString(target, "existing shadow");
    assertTrue(writer.holding(7L, "design_orchestration", "existing shadow").isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> writer.write(7L, "design_orchestration", "replacement"));
    assertEquals("existing shadow", Files.readString(target));
    assertFalse(Files.exists(target.resolveSibling("design_orchestration.md.prev")));
  }

  @Test
  void writes_a_new_file_with_nothing_kept(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();

    OrchestrationWriter.Written written =
        new OrchestrationWriter(layout).write(7L, "triage", "new");

    assertEquals(layout.orchestrationsFor(7L).resolve("triage.md"), written.file());
    assertNull(written.previous());
    assertEquals("new", Files.readString(written.file()));
  }

  @Test
  void keeps_what_it_replaces_as_prev_replacing_an_older_prev(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path dir = layout.orchestrationsFor(7L);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("triage.md"), "second");
    Files.writeString(dir.resolve("triage.md.prev"), "first");

    OrchestrationWriter.Written written =
        new OrchestrationWriter(layout).write(7L, "triage", "third");

    assertEquals("third", Files.readString(dir.resolve("triage.md")));
    assertEquals("second", Files.readString(dir.resolve("triage.md.prev")));
    assertEquals(dir.resolve("triage.md.prev"), written.previous());
  }

  @Test
  void refuses_a_name_that_is_not_an_orchestration_s_and_an_oversized_text(@TempDir Path data) {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationWriter writer = new OrchestrationWriter(layout);

    assertThrows(IllegalArgumentException.class, () -> writer.write(7L, "../evil", "x"));
    assertThrows(IllegalArgumentException.class, () -> writer.write(7L, "Triage", "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> writer.write(7L, "triage", "x".repeat(DefinitionWriter.MAX_DEFINITION_BYTES + 1)));
    assertFalse(Files.exists(layout.orchestrationsFor(7L).resolve("triage.md")));
  }

  @Test
  void makes_a_copy_of_the_old_file_before_writing(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationWriter writer = new OrchestrationWriter(layout);

    // Write an initial orchestration
    Path dir = layout.orchestrationsFor(7L);
    Path target = dir.resolve("triage.md");
    writer.write(7L, "triage", "original");

    // Update to a new version
    OrchestrationWriter.Written written = writer.write(7L, "triage", "updated");

    // Verify the new content is written
    assertEquals("updated", Files.readString(target));

    // Verify the old content is preserved at .prev before the update took effect
    assertTrue(Files.exists(written.previous()));
    assertEquals("original", Files.readString(written.previous()));
  }

  @Test
  void installing_a_script_changes_the_extension_and_keeps_the_previous_markdown(@TempDir Path data)
      throws Exception {
    var layout = new DataLayout(data).initialise();
    var writer = new OrchestrationWriter(layout);
    var original = writer.write(7L, "research", "previous Markdown definition");
    String script =
        io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram.MARKER
            + "\nexport const manifest={};export function step(){}";
    var installed = writer.write(7L, "research", script);
    assertEquals(layout.orchestrationsFor(7L).resolve("research.js"), installed.file());
    assertFalse(Files.exists(original.file()));
    assertEquals("previous Markdown definition", Files.readString(installed.previous()));
    assertEquals(installed.file(), writer.holding(7L, "research", script).orElseThrow());
    assertNull(writer.write(8L, "research", script).previous());
  }
}
