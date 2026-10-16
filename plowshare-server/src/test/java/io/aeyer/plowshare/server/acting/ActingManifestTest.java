package io.aeyer.plowshare.server.acting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActingManifestTest {
  static final String GRANT =
      """
      {"grant":"users","ceiling":"CONTRIBUTOR","personal":true,"proofs":["acting-token"]}
      """
          .strip();

  static String source(String acting) {
    return "{\"version\":1,\"name\":\"bridge\",\"service\":{\"handle\":\"bridge\",\"role\":\"CONTRIBUTOR\"},\"acting\":"
        + acting
        + "}";
  }

  @Test
  void accepts_explicit_owned_grant_and_withdrawn_proofs() throws Exception {
    var grants = ActingManifest.read(source("[" + GRANT + "]"), "bridge");
    assertEquals(1, grants.size());
    assertTrue(grants.getFirst().personal());
    assertEquals("bridge", grants.getFirst().application());
    assertDoesNotThrow(() -> WorkspaceApplicationPolicy.parse(source("[" + GRANT + "]"), "bridge"));
    assertTrue(
        ActingManifest.read(source("[" + GRANT.replace("\"acting-token\"", "") + "]"), "bridge")
            .getFirst()
            .proofs()
            .isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> grants.clear());
  }

  @Test
  void ordinary_and_personal_manifests_without_grants_are_unchanged() {
    assertTrue(ActingManifest.read("{}", "personal:fixture").isEmpty());
    assertTrue(ActingManifest.read("{\"acting\":[]}", "client:fixture").isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> ActingManifest.read("{\"acting\":[" + GRANT + "]}", "bridge"));
  }

  static Stream<String> invalid() {
    return Stream.of(
        "null",
        "{}",
        "[null]",
        "[" + GRANT + "," + GRANT + "]",
        "[" + GRANT.replace("CONTRIBUTOR", "MANAGER") + "]",
        "[" + GRANT.replace("true", "\"true\"") + "]",
        "[" + GRANT.replace("users", "bad name") + "]",
        "[" + GRANT.replace("acting-token", "oidc:untrusted") + "]",
        "[" + GRANT.replace("[\"acting-token\"]", "[\"acting-token\",\"acting-token\"]") + "]",
        "[" + GRANT.replace("\"personal\":true", "\"personal\":true,\"extra\":false") + "]",
        "[" + GRANT.replace("\"personal\":true", "\"personal\":true,\"personal\":false") + "]",
        "[" + GRANT.replace("\"proofs\":[\"acting-token\"]", "\"proofs\":[1]") + "]",
        "[" + GRANT.replace("\"personal\":true,", "") + "]");
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void refuses_invalid_declarations_on_both_paths(String acting) {
    String source = source(acting);
    assertThrows(IllegalArgumentException.class, () -> ActingManifest.read(source, "bridge"));
    assertThrows(
        IllegalArgumentException.class, () -> WorkspaceApplicationPolicy.parse(source, "bridge"));
  }

  @Test
  void bounds_list_and_refuses_trailing_json() {
    String repeated = "[" + String.join(",", java.util.Collections.nCopies(65, GRANT)) + "]";
    assertThrows(
        IllegalArgumentException.class, () -> ActingManifest.read(source(repeated), "bridge"));
    assertThrows(
        IllegalArgumentException.class, () -> ActingManifest.read(source("[]") + "{}", "bridge"));
  }
}
