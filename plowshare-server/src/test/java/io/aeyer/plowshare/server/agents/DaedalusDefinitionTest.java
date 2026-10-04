package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shipped diagnostic bot and the evidence it is structurally able to inspect. */
class DaedalusDefinitionTest {

  private static AgentDefinition shipped() {
    return AgentRegistry.of(ShippedDefinitions.asOneSet(), BoundTools.boundByThisServer())
        .get("daedalus");
  }

  @Test
  void daedalus_is_a_persons_bot_and_not_an_agents_callee() {
    assertTrue(shipped().bot());
    assertTrue(shipped().exported());
    assertFalse(shipped().delegable());
    assertEquals(List.of("diagnosis_verifier"), shipped().calls());
    assertEquals("diagnosis_verifier", shipped().reviewWith());
  }

  @Test
  void daedalus_reads_trajectories_and_project_evidence_without_file_edit_tools() {
    assertEquals(
        List.of(
            ConversationTrajectoryTool.NAME,
            GetDateTool.NAME,
            FileTools.CODE_MAP_NAME,
            FileTools.ROOTS_NAME,
            FileTools.GLOB_NAME,
            FileTools.GREP_NAME,
            FileTools.READ_NAME,
            FileTools.STAT_NAME,
            MemoryTools.RECALL_NAME,
            MemoryTools.READ_NAME,
            AgentRunTool.NAME,
            ArchiveReadTools.INDEX,
            ArchiveReadTools.LIST,
            MemoryNavigateTool.NAME,
            ConversationSearchTool.NAME,
            ArchiveReadTools.CHAT,
            ConversationContextTool.NAME,
            InformationTool.READ,
            InformationTool.WRITE),
        shipped().tools());
    assertEquals(List.of(new Grant(Scope.WORKSPACE, Mode.READ)), shipped().scopes());
    assertFalse(shipped().tools().contains(FileTools.EDIT_NAME));
    assertFalse(shipped().tools().contains(FileTools.DELETE_NAME));
    assertFalse(shipped().tools().contains(FileTools.MOVE_NAME));
    assertTrue(shipped().canRecall(), "Daedalus may query memory deliberately");
    assertFalse(
        shipped().canBeReminded(), "Daedalus must not begin with memories injected by the harness");
  }

  @Test
  void daedalus_is_told_to_follow_persisted_evidence_to_the_first_divergence() {
    String prompt = shipped().prompt().replaceAll("\\s+", " ");
    assertTrue(
        prompt.contains("use `conversation_trajectory` before relying on a projection"), prompt);
    assertTrue(
        prompt.contains("Identify the earliest point where actual behaviour diverged"), prompt);
    assertTrue(prompt.contains("Do not jump directly from symptom to fix"), prompt);
    assertTrue(prompt.contains("Do not invent missing events"), prompt);
    assertTrue(prompt.contains("current diagnostic question is the controlling scope"), prompt);
    assertTrue(
        prompt.contains("does **not** mean the original model saw truncated output"), prompt);
    assertTrue(prompt.contains("An ending of `CANCELLED` establishes"), prompt);
    assertTrue(prompt.contains("begin with `tail: true`"), prompt);
    assertTrue(prompt.contains("Do not call `diagnosis_verifier` yourself"), prompt);
    assertTrue(prompt.contains("runtime withholds that draft"), prompt);
    assertTrue(prompt.contains("only answer delivered to the operator"), prompt);
    assertTrue(prompt.contains("label an unverifiable historical claim as unknown"), prompt);
  }
}
