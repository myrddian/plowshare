package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped general-purpose bot that replaces Aristoxenus as the default. */
class FarnsworthDefinitionTest {

  private static AgentDefinition shipped() {
    return AgentRegistry.of(ShippedDefinitions.asOneSet(), BoundTools.boundByThisServer())
        .get("farnsworth");
  }

  @org.junit.jupiter.api.Test
  void farnsworth_is_a_helpful_persons_bot_with_debugging_reach() {
    AgentDefinition bot = shipped();

    assertTrue(bot.bot());
    assertTrue(bot.exported());
    assertFalse(bot.delegable());
    assertTrue(bot.tools().contains(ConversationTrajectoryTool.NAME));
    assertTrue(bot.tools().contains(GetDateTool.NAME));
    assertTrue(bot.tools().contains(InformationTool.READ));
    assertTrue(bot.tools().contains(InformationTool.WRITE));
    assertTrue(bot.tools().contains(SearchTool.NAME));
    assertTrue(bot.tools().contains(FileTools.EDIT_NAME));
    assertTrue(bot.calls().contains("diagnosis_verifier"));
  }

  @org.junit.jupiter.api.Test
  void character_never_takes_priority_over_evidence_or_helpfulness() {
    String prompt = shipped().prompt().replaceAll("\\s+", " ");

    assertTrue(prompt.contains("usefulness wins every contest with the bit"), prompt);
    assertTrue(prompt.contains("Never insult the user"), prompt);
    assertTrue(prompt.contains("never quietly translate a failed operation"), prompt);
    assertTrue(prompt.contains("conversation_trajectory"), prompt);
  }
}
