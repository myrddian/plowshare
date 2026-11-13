package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.a2a.IngressRefused;
import io.aeyer.plowshare.a2a.Receiver;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyProvenanceImportTest {
  @TempDir Path directory;
  final String principal = "@service/11111111-1111-1111-1111-111111111111";
  final UUID task = UUID.randomUUID(), context = UUID.randomUUID(), original = UUID.randomUUID();
  final Ingress.Source source =
      new Ingress.Source(
          "historical-message",
          "ROLE_USER",
          null,
          null,
          List.of(new Ingress.TextPart("Original question", "text/plain")),
          new Ingress.Metadata("/skill:review --mode=DIRECT"),
          List.of("earlier-task"),
          List.of("urn:retained-extension"));

  IngressConfiguration config() {
    var binding = RelayIngressBridgeTest.BINDING;
    return new IngressConfiguration(
        "https://server.example.invalid",
        "fixture-token",
        directory,
        binding,
        new Receiver.Config(
            "127.0.0.1",
            0,
            "https://receiver.example.invalid/rpc",
            "project",
            "reviewer",
            Map.of("alice", "fixture-caller"),
            200),
        principal);
  }

  RelayIngressBridgeTest.Messages server() {
    var server = new RelayIngressBridgeTest.Messages();
    server.tasks.put(
        task,
        new IngressWire.Task(
            task.toString(),
            context.toString(),
            original.toString(),
            "reviewer",
            "bdm_Legacy",
            "SUBMITTED",
            null,
            List.of(),
            RelayIngressBridgeTest.TIME));
    server.clients.put(task, "alice");
    return server;
  }

  RelayIngressBridge bridge(IngressJournal journal, RelayIngressBridgeTest.Messages server) {
    return new RelayIngressBridge(
        config().binding(),
        server,
        journal,
        () -> new Ingress.Catalog("reviewer", "Review", true, List.of()),
        () -> RelayIngressBridgeTest.TIME);
  }

  LegacyProvenanceBundle bundle(Ingress.Source value, boolean known) {
    return new LegacyProvenanceBundle(
        "plowshare-a2a-provenance/1",
        config().origin(),
        "project",
        principal,
        List.of(
            new LegacyProvenanceBundle.Row(
                "alice",
                task,
                context,
                "reviewer",
                "bdm_Legacy",
                RelayIngressBridgeTest.TIME,
                value,
                known)));
  }

  @Test
  void import_preserves_exact_source_ids_and_duplicate_old_message_only_inspects()
      throws Exception {
    var server = server();
    var config = config();
    try (var journal =
        new FileIngressJournal(directory, config.fingerprint(), config.identityFingerprint())) {
      var bridge = bridge(journal, server);
      var export = bundle(source, true);
      assertEquals(
          1,
          LegacyProvenanceImport.apply(
              config, export, bridge, journal, () -> RelayIngressBridgeTest.TIME));
      UUID external =
          LegacyProvenanceImport.externalRequest(
              "project", "reviewer", "alice", source.messageId());
      var input =
          new Ingress.Receive(
              "project",
              "alice",
              "reviewer",
              external,
              null,
              "Original question",
              source.metadata().plowshareCommand(),
              source);
      var recovered = bridge.receive(input);
      assertEquals(task, recovered.id());
      assertEquals(context, recovered.context());
      assertEquals(source, recovered.source());
      assertEquals(0, server.receives);
      assertEquals(0, journal.submission(external).stream().count());
      assertEquals(original, journal.imported("alice", original).orElseThrow().originalRequest());
      assertEquals(
          1,
          LegacyProvenanceImport.apply(
              config, export, bridge, journal, () -> RelayIngressBridgeTest.TIME.plusSeconds(30)));
      var changed =
          new Ingress.Source(
              source.messageId(), List.of(new Ingress.TextPart("Changed question")), null);
      assertThrows(
          IngressRefused.class,
          () ->
              bridge.receive(
                  new Ingress.Receive(
                      "project",
                      "alice",
                      "reviewer",
                      external,
                      null,
                      "Changed question",
                      null,
                      changed)));
      assertEquals(0, server.receives);
      journal.requireHealthy();
    }
    try (var journal =
        new FileIngressJournal(directory, config.fingerprint(), config.identityFingerprint())) {
      assertEquals(
          source,
          bridge(journal, server).status(new Ingress.Id("project", "alice", task)).source());
      assertEquals(0, server.receives);
    }
  }

  @Test
  void foreign_bundle_scope_and_changed_owned_tuple_are_refused_before_installing_source()
      throws Exception {
    var server = server();
    var config = config();
    var export = bundle(source, true);
    try (var journal =
        new FileIngressJournal(directory, config.fingerprint(), config.identityFingerprint())) {
      var bridge = bridge(journal, server);
      for (var foreign :
          List.of(
              new LegacyProvenanceBundle(
                  export.schema(),
                  "https://foreign.example.invalid",
                  export.project(),
                  export.principal(),
                  export.tasks()),
              new LegacyProvenanceBundle(
                  export.schema(), export.origin(), "foreign", export.principal(), export.tasks()),
              new LegacyProvenanceBundle(
                  export.schema(), export.origin(), export.project(), "foreign", export.tasks())))
        assertThrows(
            IOException.class,
            () ->
                LegacyProvenanceImport.apply(
                    config, foreign, bridge, journal, () -> RelayIngressBridgeTest.TIME));
      assertEquals(0, server.inspects);
      var wrong =
          new LegacyProvenanceBundle(
              export.schema(),
              export.origin(),
              export.project(),
              export.principal(),
              List.of(
                  new LegacyProvenanceBundle.Row(
                      "alice",
                      task,
                      UUID.randomUUID(),
                      "reviewer",
                      "bdm_Legacy",
                      RelayIngressBridgeTest.TIME,
                      source,
                      true)));
      assertThrows(
          IOException.class,
          () ->
              LegacyProvenanceImport.apply(
                  config, wrong, bridge, journal, () -> RelayIngressBridgeTest.TIME));
      assertTrue(journal.imported("alice", original).isEmpty());
      journal.requireHealthy();
      LegacyProvenanceImport.apply(
          config, export, bridge, journal, () -> RelayIngressBridgeTest.TIME);
      var changed =
          new Ingress.Source(
              source.messageId(), List.of(new Ingress.TextPart("Forged source")), null);
      assertThrows(
          IOException.class,
          () ->
              LegacyProvenanceImport.apply(
                  config,
                  bundle(changed, true),
                  bridge,
                  journal,
                  () -> RelayIngressBridgeTest.TIME));
      assertEquals(source, journal.imported("alice", original).orElseThrow().source());
      journal.requireHealthy();
    }
  }

  @Test
  void absent_historical_source_is_explicit_and_cannot_open_a_guessed_new_context()
      throws Exception {
    var server = server();
    var config = config();
    try (var journal =
        new FileIngressJournal(directory, config.fingerprint(), config.identityFingerprint())) {
      var bridge = bridge(journal, server);
      LegacyProvenanceImport.apply(
          config, bundle(null, false), bridge, journal, () -> RelayIngressBridgeTest.TIME);
      assertEquals(null, bridge.status(new Ingress.Id("project", "alice", task)).source());
      assertTrue(journal.ambiguousClient("alice"));
      assertThrows(
          IngressRefused.class,
          () ->
              bridge.receive(
                  RelayIngressBridgeTest.request(
                      UUID.randomUUID(), "alice", null, "Unknown original identity")));
      assertEquals(0, server.receives);
    }
  }

  @Test
  void interrupted_import_requires_same_bundle_and_never_starts_listener_as_complete()
      throws Exception {
    var config = config();
    var server = server();
    var export = bundle(source, true);
    String hash = RelayPort.hash(IngressCodec.write(export));
    try (var journal =
        new FileIngressJournal(directory, config.fingerprint(), config.identityFingerprint())) {
      journal.beginImport(hash);
      assertThrows(IllegalStateException.class, journal::requireHealthy);
    }
    try (var journal =
        new FileIngressJournal(directory, config.fingerprint(), config.identityFingerprint())) {
      assertThrows(IllegalStateException.class, journal::requireHealthy);
      assertThrows(IOException.class, () -> journal.beginImport("a".repeat(64)));
      assertEquals(
          1,
          LegacyProvenanceImport.apply(
              config, export, bridge(journal, server), journal, () -> RelayIngressBridgeTest.TIME));
      journal.requireHealthy();
      assertEquals(0, server.receives);
    }
  }

  @Test
  void private_bundle_decoder_keeps_optional_legacy_source_fields_but_rejects_undeclared_values()
      throws Exception {
    var file = directory.resolve("bundle.json");
    Files.writeString(file, IngressCodec.write(bundle(source, true)));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    assertEquals(source, LegacyProvenanceBundle.read(file).tasks().getFirst().source());
    String original = Files.readString(file);
    Files.writeString(
        file,
        original.replace("\"originalContextKnown\":true", "\"originalContextKnown\":\"true\""));
    assertThrows(IOException.class, () -> LegacyProvenanceBundle.read(file));
    Files.writeString(file, original.replace("\"tasks\":", "\"extra\":true,\"tasks\":"));
    assertThrows(IOException.class, () -> LegacyProvenanceBundle.read(file));
    Files.writeString(file, original);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(IOException.class, () -> LegacyProvenanceBundle.read(file));
  }
}
