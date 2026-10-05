package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SkillContextLogsTest {
  private static final String ROW =
      "{\"conversation_id\":\"parent\",\"ordinal\":1,\"turn_ordinal\":1,\"kind\":\"utterance\",\"content\":\"History\",\"speaker\":\"person\",\"speaker_name\":\"alice\"}";

  @Test
  void historical_metadata_is_preserved_without_an_untyped_snapshot_contract() {
    var log = SkillContextLogs.read("[" + ROW + "]");
    assertEquals(1, log.through());
    assertTrue(log.complete());
    assertEquals("alice", log.entries().getFirst().speakerName());
    assertEquals(log, SkillContextLogs.read(SkillContextLogs.write(log)));
    assertThrows(UnsupportedOperationException.class, () -> log.entries().clear());
  }

  @Test
  void every_field_is_checked_before_the_snapshot_can_be_handed_to_a_skill() {
    for (String invalid :
        new String[] {
          ROW.replace("\"parent\"", "7"),
          ROW.replace("\"ordinal\":1", "\"ordinal\":1.5"),
          ROW.replace("\"kind\":\"utterance\"", "\"kind\":\"invented\""),
          ROW.replace("\"content\":\"History\"", "\"content\":null"),
          ROW.replace("\"speaker\":\"person\"", "\"speaker\":\"system\""),
          ROW.replace("\"speaker_name\":\"alice\"", "\"speaker_name\":\"alice\\nforged\""),
          ROW.replace("\"speaker_name\":\"alice\"", "\"arbitrary_payload\":{}")
        }) {
      assertThrows(IllegalStateException.class, () -> SkillContextLogs.read("[" + invalid + "]"));
    }
  }
}
