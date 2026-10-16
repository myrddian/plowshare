package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.InformationInterchange.*;
import io.aeyer.plowshare.protocol.InformationInterchange.Provenance;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class InformationInterchangeTest {
  final UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
  final byte[] body = "# Safety 🔎\r\nPreserve exact bytes.\r\n".getBytes(StandardCharsets.UTF_8);

  Descriptor descriptor(String path, List<UUID> dependencies) {
    return new Descriptor(
        resource,
        revision,
        1,
        "source.md",
        "Source",
        path,
        "text/markdown; charset=utf-8",
        InformationInterchange.hash(body),
        List.of("billing"),
        null,
        new Provenance(
            "foreign-owner",
            "source",
            "2026-10-16T00:00:00Z",
            InformationInterchange.hash(body),
            dependencies,
            List.of(),
            null,
            null,
            null));
  }

  Document document(String path, List<UUID> dependencies) {
    return new Document(descriptor(path, dependencies), Base64.getEncoder().encodeToString(body));
  }

  Bundle bundle(List<Document> documents, List<Link> links) {
    return new Bundle(
        InformationInterchange.FORMAT,
        UUID.randomUUID(),
        documents,
        links,
        InformationInterchange.COVERAGE);
  }

  @Test
  void portable_paths_refuse_absolute_traversal_reserved_names_and_cross_platform_collisions() {
    for (String path :
        List.of(
            "/absolute.md",
            "../source.md",
            "safe/../source.md",
            "safe//source.md",
            "C:/source.md",
            "safe\\source.md",
            "safe/CON.md",
            "safe/.git/config",
            "safe/nul",
            "file.",
            ".hidden",
            "safe/"))
      assertThrows(
          IllegalArgumentException.class, () -> InformationInterchange.safePath(path), path);
    assertEquals("documents/source.md", InformationInterchange.safePath("documents/source.md"));
  }

  @Test
  void original_bytes_are_canonical_and_hash_checked_before_admission() {
    var d = document("documents/source.md", List.of());
    assertArrayEquals(body, InformationInterchange.bytes(d.bytesBase64()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Document(
                d.descriptor(),
                Base64.getEncoder().encodeToString("changed".getBytes(StandardCharsets.UTF_8))));
    for (String encoded : List.of("", "QQ", "QR==", "QQ==\n"))
      assertThrows(IllegalArgumentException.class, () -> InformationInterchange.bytes(encoded));
  }

  @Test
  void complete_dependency_identity_and_pinned_targets_are_required() {
    assertThrows(
        IllegalArgumentException.class,
        () -> bundle(List.of(document("source.md", List.of(UUID.randomUUID()))), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            bundle(
                List.of(document("source.md", List.of())),
                List.of(new Link(resource, UUID.randomUUID(), null, "reference"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            bundle(
                List.of(document("source.md", List.of())),
                List.of(new Link(resource, resource, UUID.randomUUID(), "reference"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            bundle(
                List.of(document("source.md", List.of()), document("different.md", List.of())),
                List.of()));
    assertDoesNotThrow(
        () ->
            bundle(
                List.of(document("source.md", List.of())),
                List.of(new Link(resource, resource, revision, "reference"))));
  }

  @Test
  void import_needs_explicit_collision_and_complete_mapping_choices() {
    var b = bundle(List.of(document("source.md", List.of())), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Destination(resource, "destination.md", "revise", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Destination(resource, "destination.md", "create", revision));
    assertThrows(IllegalArgumentException.class, () -> new ImportPlan(b, List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ImportPlan(
                b, List.of(), List.of(), List.of(new Reuse(revision, UUID.randomUUID())), false));
    var p =
        new ImportPlan(
            b, List.of(new Destination(resource, "destination.md", "create", null)), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InformationRequest.InterchangeImport(
                new InformationRequest.Selection(
                    new InformationRequest.Scope("shared", null, false), "documents", null),
                UUID.randomUUID(),
                p));
  }
}
