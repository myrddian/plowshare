package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolScopes;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayToolNameTest {
  @Test
  void legacy_schema_configuration_and_bindings_can_span_chunks() throws Exception {
    var fields =
        java.util.stream.IntStream.range(0, 40)
            .mapToObj(
                i ->
                    "\"field_"
                        + i
                        + "\":{\"type\":\"string\",\"description\":\""
                        + "d".repeat(2048)
                        + "\"}")
            .toList();
    String source = "{\"type\":\"object\",\"properties\":{" + String.join(",", fields) + "}}";
    assertTrue(source.length() > 65536);
    var schema = RelayToolCodec.inputSchema(source);
    var definition =
        new RelayToolDefinition(
            "fixture", "fixture", "owner", "large", "Fixture", List.of(), 30, schema);
    var properties = new RelayToolProperties();
    properties.setBindings(List.of(definition));
    assertEquals(schema, properties.getBindings().getFirst().inputSchema());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RelayToolCodec.inputSchema(
                source.replace("\"type\":\"string\"", "\"type\":\"unsupported\"")));
  }

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
