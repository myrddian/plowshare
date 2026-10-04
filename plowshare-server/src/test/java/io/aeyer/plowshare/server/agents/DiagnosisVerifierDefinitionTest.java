package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The clean-context verifier Daedalus uses for claims about project reality. */
class DiagnosisVerifierDefinitionTest {

  private static AgentDefinition shipped() {
    return AgentRegistry.of(ShippedDefinitions.asOneSet(), BoundTools.boundByThisServer())
        .get("diagnosis_verifier");
  }

  @Test
  void verifier_is_private_delegable_and_cannot_delegate_further() {
    assertFalse(shipped().bot());
    assertFalse(shipped().exported());
    assertTrue(shipped().delegable());
    assertEquals(List.of(), shipped().calls());
  }

  @Test
  void verifier_reads_cited_history_and_current_files_without_search_or_memory() {
    assertEquals(
        List.of(
            FileTools.CODE_MAP_NAME,
            FileTools.ROOTS_NAME,
            FileTools.GLOB_NAME,
            FileTools.GREP_NAME,
            FileTools.READ_NAME,
            FileTools.STAT_NAME,
            ConversationTrajectoryTool.NAME),
        shipped().tools());
    assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.READ)), shipped().scopes());
    assertFalse(shipped().tools().contains(ConversationSearchTool.NAME));
    assertFalse(shipped().tools().contains(MemoryNavigateTool.NAME));
    assertFalse(shipped().tools().contains(MemoryTools.RECALL_NAME));
    assertFalse(shipped().tools().contains(MemoryTools.READ_NAME));
    assertFalse(shipped().tools().contains(FileTools.EDIT_NAME));
    assertFalse(shipped().tools().contains(RunTool.NAME));
  }

  @Test
  void verifier_is_told_to_test_each_claim_and_preserve_historical_uncertainty() {
    String prompt = shipped().prompt().replaceAll("\\s+", " ");
    assertTrue(prompt.contains("call `file_roots` first"), prompt);
    assertTrue(prompt.contains("do not require a project workspace"), prompt);
    assertTrue(
        prompt.contains("Search for a named item before concluding that it does not exist"),
        prompt);
    assertTrue(prompt.contains("report the full absolute path whenever"), prompt);
    assertTrue(
        prompt.contains("They cannot prove what existed at the time of a historical run"), prompt);
    assertTrue(prompt.contains("`verified`, `refuted`, or `unverifiable`"), prompt);
    assertTrue(prompt.contains("Do not fill gaps"), prompt);
  }
}
