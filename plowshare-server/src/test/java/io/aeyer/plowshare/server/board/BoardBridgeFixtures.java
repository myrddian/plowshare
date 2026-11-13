package io.aeyer.plowshare.server.board;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class BoardBridgeFixtures {
  static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");

  static final UUID REPLY_GENERATION = UUID.fromString("44444444-4444-4444-4444-444444444444");

  private BoardBridgeFixtures() {}

  static BoardTopic topic(String state) {
    return topic(state, "destination");
  }

  static BoardTopic topic(String state, String project) {
    return new BoardTopic(
        "bdt_Topic",
        project,
        null,
        "bdt_Topic",
        0,
        "Title",
        "Label",
        "owner",
        "person",
        "owner",
        null,
        state,
        "closed".equals(state) ? "bdm_Resolution" : null,
        20,
        "exhausted".equals(state) ? 20 : 0,
        2,
        null,
        NOW,
        "closed".equals(state) ? NOW : null);
  }

  static BoardMessage message(String id, String kind) {
    return new BoardMessage(
        id,
        "bdt_Topic",
        null,
        "person",
        "publisher",
        null,
        null,
        kind,
        null,
        "Full body",
        false,
        List.of(),
        NOW);
  }
}
