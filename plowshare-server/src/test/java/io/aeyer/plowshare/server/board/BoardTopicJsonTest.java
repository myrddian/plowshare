package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.ws.BoardPostingFrames;
import io.aeyer.plowshare.server.ws.FrameJson;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercise the actual wire mapper: Java record comparisons cannot detect getter collisions. */
class BoardTopicJsonTest {
  private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

  private static BoardTopic topic(String id, String parent) {
    return new BoardTopic(
        id,
        "project",
        parent,
        "bdt_root",
        parent == null ? 0 : 1,
        "Discussion",
        "Research",
        "person",
        BoardTopic.BY_PERSON,
        "person",
        null,
        BoardTopic.OPEN,
        null,
        parent == null ? 100 : null,
        parent == null ? 0 : null,
        parent == null ? 10 : null,
        null,
        NOW,
        null);
  }

  @Test
  void root_and_child_topics_keep_the_root_id_in_json() throws Exception {
    var mapper = FrameJson.answering();
    for (var topic : List.of(topic("bdt_root", null), topic("bdt_child", "bdt_root"))) {
      var wire = mapper.readTree(mapper.writeValueAsString(topic));
      assertTrue(wire.get("root").isTextual(), "root must be an ID, never isRoot()'s boolean");
      assertEquals("bdt_root", wire.get("root").asText());
      assertEquals(topic.parent() == null, topic.isRoot());
      assertFalse(wire.has("isRoot"));
      assertTrue(wire.has("quietNotifiedAt"));
      assertTrue(wire.get("quietNotifiedAt").isNull());
    }
  }

  @Test
  void a_person_topic_creation_receipt_keeps_its_root_id_on_the_wire() throws Exception {
    var topic = topic("bdt_root", null);
    var opening =
        new BoardMessage(
            "bdm_opening",
            topic.id(),
            null,
            BoardMessage.BY_PERSON,
            "person",
            null,
            null,
            BoardMessage.POST,
            null,
            "Research this",
            false,
            List.of(),
            NOW);
    var receipt = new BoardPostingFrames.OpenReceipt("request", topic, opening);
    var mapper = FrameJson.answering();
    var outcome = mapper.readTree(mapper.writeValueAsString(Outcome.ok(receipt)));
    assertEquals("OK", outcome.get("code").asText());
    var payload = outcome.get("payload");
    assertEquals("request", payload.get("requestId").asText());
    assertTrue(payload.get("topic").get("root").isTextual());
    assertEquals(payload.get("topic").get("id"), payload.get("topic").get("root"));
    assertEquals(payload.get("topic").get("id"), payload.get("message").get("topic"));
  }
}
