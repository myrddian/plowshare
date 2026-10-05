package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoutingConfigurationCodecTest {
  @Test
  void routing_is_immutable_and_does_not_interpret_other_manifest_settings() {
    var manifest =
        RoutingConfigurationCodec.manifest(
            """
        {"version":1,"name":"project","commands":{"local":{}},
         "routing":{"acceptFrom":["source"],"sendTo":["destination"],"routeFiles":["routes.json"]}}
        """);
    assertEquals("source", manifest.routing().acceptFrom().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> manifest.routing().sendTo().clear());
    var route =
        RoutingConfigurationCodec.routes(
                """
        {"version":1,"routes":[{"name":"review","project":"destination","agent":"reviewer","retainConversation":true}]}
        """)
            .routes()
            .getFirst();
    assertTrue(route.address().retainConversation());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"version\":\"1\",\"name\":\"project\"}",
        "{\"version\":1.2,\"name\":\"project\"}",
        "{\"version\":1,\"name\":\"project\",\"routing\":null}",
        "{\"version\":1,\"name\":\"project\",\"routing\":{\"sendTo\":null}}",
        "{\"version\":1,\"name\":\"project\",\"routing\":{\"sendTo\":[12]}}",
        "{\"version\":1,\"name\":\"project\",\"routing\":{\"routeFiles\":[\"../routes\"]}}",
        "{\"version\":1,\"name\":\"project\",\"routing\":{\"uncheckedInstruction\":true}}",
        "{\"version\":1,\"name\":\"project\",\"name\":\"second\"}",
        "{\"version\":1,\"name\":\"project\"} {}"
      })
  void invalid_manifests_are_refused_before_a_policy_is_used(String wire) {
    assertThrows(Board.Refused.class, () -> RoutingConfigurationCodec.manifest(wire));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"name\":\"route\",\"project\":\"destination\",\"agent\":\"reviewer\",\"retainConversation\":1}",
        "{\"name\":\"route\",\"project\":\"destination\",\"agent\":\"reviewer\",\"retainConversation\":null}",
        "{\"name\":\"route\",\"project\":\"destination\",\"agent\":\"ins_existing\"}",
        "{\"name\":\"route\",\"project\":\"destination\",\"agent\":\"reviewer\",\"conversation\":\"cnv_valid\",\"retainConversation\":false}",
        "{\"name\":\"route\",\"project\":\"destination\",\"agent\":\"reviewer\",\"unchecked\":{\"execute\":true}}"
      })
  void route_entries_are_closed_and_cannot_select_instance_or_conversation_ambiguously(
      String entry) {
    assertThrows(
        Board.Refused.class,
        () -> RoutingConfigurationCodec.routes("{\"version\":1,\"routes\":[" + entry + "]}"));
  }
}
