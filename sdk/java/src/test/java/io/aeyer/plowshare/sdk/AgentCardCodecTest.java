package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.AgentCard;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentCardCodecTest {
  private static final String CARD =
      """
      {"name":"Research","description":"Finds evidence","version":"1",
       "skills":[{"id":"research","name":"Research","description":"Finds sources","tags":["research"]}],
       "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],
       "supportedInterfaces":[{"url":"https://example.com/rpc","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],
       "capabilities":{"streaming":false},
       "securitySchemes":{"bearer":{"httpAuthSecurityScheme":{"scheme":"Bearer"}}},
       "securityRequirements":[{"schemes":{"bearer":{"list":[]}}}]}
      """;

  @Test
  void nested_discovery_and_security_values_are_typed_and_immutable() throws Exception {
    var card = AgentCardCodec.read(CARD);
    assertEquals("research", card.skills().getFirst().id());
    assertEquals("Bearer", card.securitySchemes().get("bearer").httpAuthSecurityScheme().scheme());
    assertThrows(UnsupportedOperationException.class, () -> card.skills().clear());
    assertThrows(UnsupportedOperationException.class, () -> card.securitySchemes().clear());
    assertEquals(card, AgentCardCodec.read(new ObjectMapper().writeValueAsString(card)));
    var flow =
        new AgentCard.AuthorizationCode(
            "https://example.com/auth",
            "https://example.com/token",
            null,
            java.util.Map.of("read", "Read data"),
            true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AgentCard.OAuthFlows(
                flow,
                new AgentCard.ClientCredentials(
                    "https://example.com/token", null, java.util.Map.of()),
                null,
                null,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentCard.OpenIdConnect(null, "http://example.com/openid"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"streaming\":1}",
        "{\"streaming\":\"false\"}",
        "{\"streaming\":false,\"extensionCode\":{\"unchecked\":true}}",
        "{\"extensions\":[{\"uri\":\"relative\",\"required\":false}]}",
        "{\"extensions\":[{\"uri\":\"https://example.com/ext\",\"params\":{\"unchecked\":true}}]}"
      })
  void malformed_or_unregistered_capabilities_are_refused(String capabilities) throws Exception {
    var tree = new ObjectMapper().readTree(CARD);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
        .set("capabilities", new ObjectMapper().readTree(capabilities));
    assertThrows(IOException.class, () -> AgentCardCodec.read(tree.toString()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"apiKeySecurityScheme\":{\"location\":\"body\",\"name\":\"key\"}}",
        "{\"httpAuthSecurityScheme\":{\"scheme\":42}}",
        "{\"httpAuthSecurityScheme\":{\"scheme\":\"Bearer\"},\"mtlsSecurityScheme\":{}}",
        "{\"oauth2SecurityScheme\":{\"flows\":{\"clientCredentials\":{\"tokenUrl\":\"https://example.com/token\",\"authorizationUrl\":\"https://example.com/auth\",\"scopes\":{}}}}}"
      })
  void security_scheme_union_and_each_flow_have_closed_fields(String scheme) throws Exception {
    var json = new ObjectMapper();
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(CARD);
    tree.withObject("securitySchemes").set("bearer", json.readTree(scheme));
    assertThrows(IOException.class, () -> AgentCardCodec.read(tree.toString()));
  }
}
