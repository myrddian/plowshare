package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
  @TempDir Path directory;

  @Test
  void one_owner_atomic_reopen_and_no_pruning_uncertain_work() throws Exception {
    try (Journal journal = new Journal(directory)) {
      assertThrows(IOException.class, () -> new Journal(directory));
      journal.commit(
          Map.of("action", Json.object().put("status", "UNKNOWN")),
          "house",
          Json.object().put("count", 1));
      assertThrows(IOException.class, () -> journal.prune(Set.of("action")));
      assertEquals(1, journal.state("house").path("count").asInt());
    }
    try (Journal reopened = new Journal(directory)) {
      assertEquals("UNKNOWN", reopened.record("action").path("status").asText());
      assertEquals(1, reopened.state("house").path("count").asInt());
    }
  }

  @Test
  void over_limit_transaction_preserves_previous_state_and_records() throws Exception {
    try (Journal journal = new Journal(directory)) {
      journal.commit(
          Map.of("before", Json.object().put("status", "DONE")),
          "house",
          Json.object().put("count", 1));
      assertThrows(
          IOException.class,
          () ->
              journal.commit(
                  Map.of("after", Json.object()),
                  "house",
                  Json.object().put("data", "x".repeat(65537))));
      assertTrue(journal.record("after").isMissingNode());
      assertEquals(1, journal.state("house").path("count").asInt());
    }
  }

  @Test
  void malformed_existing_journal_is_not_overwritten() throws Exception {
    Files.writeString(directory.resolve("journal.json"), "{\"version\":1}");
    assertThrows(IOException.class, () -> new Journal(directory));
    assertEquals("{\"version\":1}", Files.readString(directory.resolve("journal.json")));
  }
}
