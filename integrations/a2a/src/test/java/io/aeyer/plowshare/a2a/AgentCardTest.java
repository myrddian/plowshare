package io.aeyer.plowshare.a2a;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;

class AgentCardTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static Map<String, Object> card(String endpoint) {
    return Map.of(
        "name",
        "Research peer",
        "description",
        "Finds sources for research questions",
        "version",
        "1.0.0",
        "supportedInterfaces",
        List.of(Map.of("url", endpoint, "protocolBinding", "JSONRPC", "protocolVersion", "1.0")),
        "capabilities",
        Map.of("streaming", false),
        "defaultInputModes",
        List.of("text/plain"),
        "defaultOutputModes",
        List.of("text/plain"),
        "skills",
        List.of(
            Map.of(
                "id",
                "research",
                "name",
                "Research",
                "description",
                "Find cited evidence",
                "tags",
                List.of("research"))));
  }

  @Test
  void discovers_typed_skills_using_conditional_cache() throws Exception {
    try (var server = new MockWebServer();
        var client =
            new A2aClient(server.url("/rpc").toString(), "peer-secret", Duration.ofSeconds(2))) {
      var data = new LinkedHashMap<>(card(server.url("/rpc").toString()));
      server.enqueue(
          new MockResponse()
              .setBody(JSON.writeValueAsString(data))
              .setHeader("ETag", "\"v1\"")
              .setHeader("Cache-Control", "max-age=0"));
      server.enqueue(
          new MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=300"));
      assertEquals(
          io.aeyer.plowshare.sdk.AgentCardCodec.read(JSON.writeValueAsString(data)),
          client.agentCard());
      assertEquals(
          io.aeyer.plowshare.sdk.AgentCardCodec.read(JSON.writeValueAsString(data)),
          client.agentCard());
      assertEquals(
          io.aeyer.plowshare.sdk.AgentCardCodec.read(JSON.writeValueAsString(data)),
          client.agentCard());
      assertEquals(2, server.getRequestCount());
      var first = server.takeRequest();
      assertEquals("GET", first.getMethod());
      assertEquals("/.well-known/agent-card.json", first.getPath());
      assertEquals("Bearer peer-secret", first.getHeader("Authorization"));
      assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"));
    }
  }

  @Test
  void invalid_refresh_cannot_reuse_an_expired_card() throws Exception {
    try (var server = new MockWebServer();
        var client = new A2aClient(server.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
      server.enqueue(
          new MockResponse()
              .setBody(JSON.writeValueAsString(card(server.url("/rpc").toString())))
              .setHeader("Cache-Control", "no-cache"));
      client.agentCard();
      server.enqueue(
          new MockResponse()
              .setBody(JSON.writeValueAsString(card(server.url("/different-rpc").toString()))));
      assertThrows(IOException.class, client::agentCard);
      server.enqueue(new MockResponse().setResponseCode(503));
      assertThrows(IOException.class, client::agentCard);
      assertEquals(3, server.getRequestCount());
    }
  }

  @Test
  void cached_revalidation_keeps_no_cache_policy_and_accounts_for_response_age() throws Exception {
    try (var server = new MockWebServer();
        var client = new A2aClient(server.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
      server.enqueue(
          new MockResponse()
              .setBody(JSON.writeValueAsString(card(server.url("/rpc").toString())))
              .setHeader("Cache-Control", "no-cache")
              .setHeader("ETag", "\"v1\""));
      server.enqueue(new MockResponse().setResponseCode(304));
      server.enqueue(
          new MockResponse()
              .setResponseCode(304)
              .setHeader("Cache-Control", "max-age=300")
              .setHeader("Age", "300"));
      server.enqueue(
          new MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=300"));
      for (int i = 0; i < 5; i++) client.agentCard();
      assertEquals(4, server.getRequestCount());
    }
  }

  @Test
  void cards_cannot_redirect_credentials_or_select_an_unconfigured_endpoint() throws Exception {
    try (var server = new MockWebServer();
        var foreign = new MockWebServer();
        var client =
            new A2aClient(
                server.url("/rpc").toString(),
                foreign.url("/card").toString(),
                "secret",
                Duration.ofSeconds(2))) {
      foreign.enqueue(
          new MockResponse().setBody(JSON.writeValueAsString(card(server.url("/rpc").toString()))));
      client.agentCard();
      assertNull(foreign.takeRequest().getHeader("Authorization"));
      assertEquals(0, server.getRequestCount());
    }
    try (var server = new MockWebServer();
        var foreign = new MockWebServer();
        var client =
            new A2aClient(server.url("/rpc").toString(), "secret", Duration.ofSeconds(2))) {
      server.enqueue(
          new MockResponse().setResponseCode(302).setHeader("Location", foreign.url("/card")));
      assertThrows(IOException.class, client::agentCard);
      assertEquals(0, foreign.getRequestCount());
      server.enqueue(
          new MockResponse()
              .setBody(JSON.writeValueAsString(card(foreign.url("/rpc").toString()))));
      assertThrows(IOException.class, client::agentCard);
      assertEquals(0, foreign.getRequestCount());
    }
  }

  @Test
  void incompatible_versions_required_extensions_and_malformed_cards_are_refused()
      throws Exception {
    try (var server = new MockWebServer();
        var client = new A2aClient(server.url("/rpc").toString(), null, Duration.ofSeconds(2))) {
      var data = new LinkedHashMap<>(card(server.url("/rpc").toString()));
      data.put(
          "supportedInterfaces",
          List.of(
              Map.of(
                  "url",
                  server.url("/rpc").toString(),
                  "protocolBinding",
                  "JSONRPC",
                  "protocolVersion",
                  "0.3")));
      server.enqueue(new MockResponse().setBody(JSON.writeValueAsString(data)));
      assertThrows(IOException.class, client::agentCard);
      data = new LinkedHashMap<>(card(server.url("/rpc").toString()));
      data.put(
          "capabilities",
          Map.of(
              "extensions",
              List.of(Map.of("uri", "https://example.invalid/required", "required", true))));
      server.enqueue(new MockResponse().setBody(JSON.writeValueAsString(data)));
      assertThrows(IOException.class, client::agentCard);
      server.enqueue(new MockResponse().setBody("{}"));
      assertThrows(IOException.class, client::agentCard);
      server.enqueue(new MockResponse().setBody(" ".repeat(64 * 1024 + 1)));
      assertThrows(IOException.class, client::agentCard);
    }
  }
}
