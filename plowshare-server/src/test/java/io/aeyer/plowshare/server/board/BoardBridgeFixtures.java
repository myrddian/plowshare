package io.aeyer.plowshare.server.board;

import java.time.Instant;
import java.util.List;

final class BoardBridgeFixtures {
  static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");

  private BoardBridgeFixtures() {}

  static BoardTopic topic(String state) {
    return new BoardTopic(
        "bdt_Topic",
        "destination",
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
