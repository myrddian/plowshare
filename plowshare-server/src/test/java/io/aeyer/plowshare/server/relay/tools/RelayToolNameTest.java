package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolScopes;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayToolNameTest {
  @Test
  void full_names_preserve_schema_routes_and_exact_grants() {
    String name = "a".repeat(256);
    var definition =
        new RelayToolDefinition("fixture", "fixture", "owner", name, "Fixture", List.of(), 30);
    assertEquals(name, definition.schema().name());
    assertEquals("tool.fixture." + name + ".request", RelayPort.topic(definition.requests()));
    assertEquals("tool.fixture." + name + ".result", RelayPort.topic(definition.results()));
    assertEquals(
        List.of(name),
        new ToolScopes.Connect(
                "fixture", "scope", "fixture", "f_", List.of(name), List.of("agent"), 30)
            .grants());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayToolDefinition(
                "fixture", "fixture", "owner", name + "a", "Fixture", List.of(), 30));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolScopes.Connect(
                "fixture", "scope", "fixture", "f_", List.of(name + "a"), List.of("agent"), 30));
  }
}
