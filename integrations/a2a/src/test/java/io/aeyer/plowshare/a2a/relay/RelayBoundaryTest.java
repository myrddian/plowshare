package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayBoundaryTest {
  @TempDir Path directory;

  @Test
  void request_codec_is_strict_and_preserves_text() throws Exception {
    String wire = RelayWorkCodec.writeRequest(RelayAdapterTest.send());
    assertEquals("  question\n", RelayWorkCodec.request(wire).message().parts().getFirst().text());
    for (String bad :
        new String[] {
          wire + " {}",
          wire.replace("\"action\":\"SEND\"", "\"action\":\"send\""),
          wire.replace("\"task\":null,", ""),
          wire.replace("\"task\":null", "\"task\":123"),
          wire.replace("\"task\":null", "\"task\":null,\"extra\":true"),
          wire.replace("\"task\":null", "\"task\":null,\"task\":null"),
          wire.replace("\"messageId\":null", "\"messageId\":\"caller-chosen\"")
        }) {
      if (!bad.equals(wire)) assertThrows(IOException.class, () -> RelayWorkCodec.request(bad));
    }
    assertThrows(
        IOException.class,
        () -> RelayWorkCodec.request("x".repeat(RelayWorkCodec.MAX_REQUEST_BYTES + 1)));
  }

  @Test
  void journal_locks_owner_generation_and_corruption_fail_closed() throws Exception {
    try (var journal = new FileDispatchJournal(directory, "a".repeat(64))) {
      journal.generation(RelayAdapterTest.GENERATION);
      assertThrows(IOException.class, () -> new FileDispatchJournal(directory, "a".repeat(64)));
      assertThrows(IOException.class, () -> journal.generation(RelayAdapterTest.CONSUMER));
    }
    assertThrows(IOException.class, () -> new FileDispatchJournal(directory, "b".repeat(64)));
    String original = Files.readString(directory.resolve("dispatch.json"));
    assertFalse(original.contains("fixture-secret"));
    Files.writeString(directory.resolve("dispatch.json"), "{}");
    assertThrows(IOException.class, () -> new FileDispatchJournal(directory, "a".repeat(64)));
    Files.delete(directory.resolve("dispatch.json"));
    assertThrows(IOException.class, () -> new FileDispatchJournal(directory, "a".repeat(64)));
  }

  @Test
  void explicit_configuration_validates_every_field_before_credentials_or_network_use()
      throws Exception {
    Path config = directory.resolve("config.json");
    String source =
        """
      {"origin":"https://plowshare.example.invalid","tokenEnv":"SERVICE_TOKEN","journal":"JOURNAL",
       "project":"project","requests":"adapter.requests","replies":"adapter.replies","group":"adapter",
       "consumer":"11111111-1111-1111-1111-111111111111","replyPublisher":"PUBLISHER",
       "producers":["PRODUCER"],"maxDepth":32,"peers":{"peer":{"endpoint":"https://peer.example.invalid/rpc","bearerEnv":"PEER_TOKEN","principal":"research-account"}}}
      """
            .replace("JOURNAL", directory.resolve("journal").toString())
            .replace("PUBLISHER", RelayAdapterTest.PUBLISHER)
            .replace("PRODUCER", RelayAdapterTest.PRODUCER);
    Files.writeString(config, source);
    var environment = Map.of("SERVICE_TOKEN", "fixture-secret", "PEER_TOKEN", "peer-secret");
    var parsed = RelayConfiguration.read(config, environment::get);
    assertEquals("https://plowshare.example.invalid/", parsed.origin());
    assertFalse(parsed.toString().contains("fixture-secret"));
    assertFalse(parsed.peers().toString().contains("peer-secret"));
    assertTrue(parsed.fingerprint().matches("[a-f0-9]{64}"));
    for (String bad :
        new String[] {
          source.replace("\"maxDepth\":32", "\"maxDepth\":\"32\""),
          source.replace("\"maxDepth\":32", "\"maxDepth\":33"),
          source.replace("\"tokenEnv\":\"SERVICE_TOKEN\",", ""),
          source.replace(
              "https://plowshare.example.invalid", "https://user:secret@example.invalid"),
          source.replace("\"maxDepth\":32", "\"maxDepth\":32,\"surprise\":true"),
          source.replace("adapter.replies", "adapter.requests"),
          source.replace(
              "\"maxDepth\":32", "\"maxDepth\":32,\"replicaMode\":\"INDEPENDENT_JOURNALS\""),
          source.replace(",\"principal\":\"research-account\"", "")
        }) {
      Files.writeString(config, bad);
      assertThrows(IOException.class, () -> RelayConfiguration.read(config, environment::get));
    }
    Files.writeString(config, source);
    assertThrows(IOException.class, () -> RelayConfiguration.read(config, key -> null));
  }
}
