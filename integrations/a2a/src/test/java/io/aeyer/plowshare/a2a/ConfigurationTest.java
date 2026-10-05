package io.aeyer.plowshare.a2a;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
  private final ObjectMapper json = new ObjectMapper();

  private ObjectNode receiving() throws Exception {
    return (ObjectNode)
        json.readTree(
            """
      {"bind":"192.0.2.10","port":8127,"publicUrl":"https://agent.example.invalid/rpc",
       "agent":"reviewer","clients":{"remote":{"bearerEnv":"CALLER_TOKEN"}}}
      """);
  }

  @Test
  void explicit_deployment_values_survive_conversion() throws Exception {
    var config = Main.receiving(receiving(), "project", ignored -> "fixture-token");
    assertEquals("192.0.2.10", config.bind());
    assertEquals(8127, config.port());
    assertEquals(30000, config.waitMillis());
    assertEquals(Map.of("remote", "fixture-token"), config.clients());
  }

  @Test
  void omitted_addresses_and_coerced_or_out_of_range_numbers_fail_before_startup()
      throws Exception {
    for (String field : new String[] {"bind", "port", "publicUrl"}) {
      ObjectNode value = receiving();
      value.remove(field);
      assertThrows(
          IllegalArgumentException.class,
          () -> Main.receiving(value, "project", ignored -> "fixture-token"));
    }
    for (String port :
        new String[] {"\"8127\"", "8127.0", "0", "-1", "65536", "2147483648", "null"}) {
      ObjectNode value = receiving();
      value.set("port", json.readTree(port));
      assertThrows(
          IllegalArgumentException.class,
          () -> Main.receiving(value, "project", ignored -> "fixture-token"));
    }
    for (String wait : new String[] {"\"30000\"", "30000.0", "0", "300001", "null"}) {
      ObjectNode value = receiving();
      value.set("waitMs", json.readTree(wait));
      assertThrows(
          IllegalArgumentException.class,
          () -> Main.receiving(value, "project", ignored -> "fixture-token"));
    }
  }

  @Test
  void invalid_receiver_identity_deadline_or_unknown_settings_fail_without_a_bridge()
      throws Exception {
    ObjectNode value = receiving();
    value.put("unknown", true);
    assertThrows(
        IllegalArgumentException.class,
        () -> Main.receiving(value, "project", ignored -> "fixture-token"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Main.receiving(receiving(), null, ignored -> "fixture-token"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Receiver.Config(
                " ",
                8127,
                "https://agent.example.invalid/rpc",
                "project",
                "reviewer",
                Map.of("remote", "fixture-token"),
                30000));
  }

  @Test
  void health_probe_has_no_local_fallback_or_credential_redirect_target() {
    assertEquals(
        "192.0.2.10",
        HealthCheck.probe("http://192.0.2.10:8127/.well-known/agent-card.json").getHost());
    for (String value :
        new String[] {
          null,
          "",
          "http://host:0/.well-known/agent-card.json",
          "http://host:65536/.well-known/agent-card.json",
          "ftp://host/.well-known/agent-card.json",
          "http://user:secret@host/.well-known/agent-card.json",
          "http://host/rpc",
          "http://host/.well-known/agent-card.json?token=secret",
          "http://host/.well-known/agent-card.json#fragment",
          " http://host/.well-known/agent-card.json"
        }) assertThrows(IllegalArgumentException.class, () -> HealthCheck.probe(value));
  }
}
