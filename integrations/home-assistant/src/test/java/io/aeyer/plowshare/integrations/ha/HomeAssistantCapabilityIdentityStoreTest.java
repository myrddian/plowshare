package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.IdentityKind;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilityIdentityStore.Anchor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeAssistantCapabilityIdentityStoreTest {
  @TempDir Path directory;

  @BeforeEach
  void privatePath() throws IOException {
    directory = directory.toRealPath();
  }

  private static final String OWNER = Json.hash("owner");
  private static final String NAMESPACE = Json.hash("configured-instance-and-binding");

  @Test
  void durableAnchorReopensWithoutValuesAndExclusiveOwnerCannotOverlap() throws Exception {
    Path path = directory.resolve("identities");
    String key = Json.hash("native-registry-id");
    var anchor =
        new Anchor(
            "fixed:office",
            key,
            IdentityKind.REGISTRY,
            "climate.office",
            "r_" + Json.hash(NAMESPACE + "\0" + key),
            "Office",
            List.of("office"),
            Optional.empty(),
            Optional.empty(),
            List.of());
    try (var store = new HomeAssistantCapabilityIdentityStore(path, OWNER, NAMESPACE)) {
      store.install(List.of(anchor));
      assertThrows(
          IOException.class,
          () -> new HomeAssistantCapabilityIdentityStore(path, OWNER, NAMESPACE));
      assertEquals(
          PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path));
      assertEquals(
          PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(path.resolve("identities.json")));
    }
    try (var later = new HomeAssistantCapabilityIdentityStore(path, OWNER, NAMESPACE)) {
      assertEquals(List.of(anchor), later.state().anchors());
      assertFalse(
          Files.readString(path.resolve("identities.json")).contains("current_temperature"));
    }
    assertThrows(
        IOException.class,
        () -> new HomeAssistantCapabilityIdentityStore(path, Json.hash("other-owner"), NAMESPACE));
    assertThrows(
        IOException.class,
        () -> new HomeAssistantCapabilityIdentityStore(path, OWNER, Json.hash("other-instance")));
  }

  @Test
  void missingRetainedFileCannotResetIdentityAndCorruptFileIsPreserved() throws Exception {
    Path path = directory.resolve("identities");
    try (var store = new HomeAssistantCapabilityIdentityStore(path, OWNER, NAMESPACE)) {
      assertTrue(store.state().anchors().isEmpty());
    }
    Files.delete(path.resolve("identities.json"));
    assertThrows(
        IOException.class, () -> new HomeAssistantCapabilityIdentityStore(path, OWNER, NAMESPACE));
    assertFalse(Files.exists(path.resolve("identities.json")));
    Files.writeString(path.resolve("identities.json"), "{invalid retained bytes");
    assertThrows(
        IOException.class, () -> new HomeAssistantCapabilityIdentityStore(path, OWNER, NAMESPACE));
    assertEquals("{invalid retained bytes", Files.readString(path.resolve("identities.json")));
  }

  @Test
  void symlinkAncestorsAndIdentityLeafAreRefused() throws Exception {
    Path real = directory.resolve("real");
    Files.createDirectories(real);
    Path alias = directory.resolve("alias");
    Files.createSymbolicLink(alias, real);
    assertThrows(
        IOException.class,
        () -> new HomeAssistantCapabilityIdentityStore(alias.resolve("child"), OWNER, NAMESPACE));
    Files.createSymbolicLink(
        real.resolve("identities.json"), directory.resolve("not-an-identity-file"));
    assertThrows(
        IOException.class, () -> new HomeAssistantCapabilityIdentityStore(real, OWNER, NAMESPACE));
  }

  @Test
  void retainedProviderMarkersPreventRecreatingAnEntireMissingIdentityDirectory() throws Exception {
    Path provider = directory.resolve("provider");
    Path identities = directory.resolve("identities");
    assertFalse(HomeAssistantToolJournal.hasRetainedState(provider));
    HomeAssistantCapabilityIdentityStore.requireRetainedSnapshot(identities, false);
    assertFalse(Files.exists(identities));
    Files.createDirectories(provider);
    Files.writeString(provider.resolve("owner.lock"), "");
    assertTrue(HomeAssistantToolJournal.hasRetainedState(provider));
    assertThrows(
        IOException.class,
        () ->
            HomeAssistantCapabilityIdentityStore.requireRetainedSnapshot(
                identities, HomeAssistantToolJournal.hasRetainedState(provider)));
    assertFalse(Files.exists(identities));
    try (var store = new HomeAssistantCapabilityIdentityStore(identities, OWNER, NAMESPACE)) {
      assertTrue(store.state().anchors().isEmpty());
      HomeAssistantCapabilityIdentityStore.requireRetainedSnapshot(identities, true);
    }
    Files.delete(identities.resolve("identities.json"));
    Files.delete(identities.resolve("owner.lock"));
    Files.delete(identities);
    assertThrows(
        IOException.class,
        () ->
            HomeAssistantCapabilityIdentityStore.requireRetainedSnapshot(
                identities, HomeAssistantToolJournal.hasRetainedState(provider)));
  }

  @Test
  void stateMetadataProbeNeverTreatsNonregularOrSymlinkMarkersAsNewState() throws Exception {
    Path provider = directory.resolve("provider");
    Files.createDirectories(provider);
    Files.createDirectories(provider.resolve("tools.json"));
    assertThrows(IOException.class, () -> HomeAssistantToolJournal.hasRetainedState(provider));
    Files.delete(provider.resolve("tools.json"));
    Files.createSymbolicLink(provider.resolve("tools.json"), directory.resolve("missing"));
    assertThrows(IOException.class, () -> HomeAssistantToolJournal.hasRetainedState(provider));
  }
}
