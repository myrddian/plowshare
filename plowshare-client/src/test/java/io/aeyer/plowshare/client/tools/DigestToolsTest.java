package io.aeyer.plowshare.client.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.client.HttpServerClient;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class DigestToolsTest {
  @Test
  void navigation_preserves_level_sources_and_partial_status() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(
                  "{\"level\":\"fold_summary\",\"ids\":[\"dig_a\"],\"text\":\"Only the summary survives\",\"complete\":false,\"modelCalls\":2}"));
      var tools = new DigestTools(new HttpServerClient(server.url("/").toString()));
      String result =
          tools.navigate(Map.of("project", "payments", "question", "why retries?")).toString();
      assertTrue(result.contains("fold_summary"));
      assertTrue(result.contains("complete=false"));
      assertTrue(result.contains("dig_a"));
      var request = server.takeRequest();
      assertEquals("/v1/memories/navigate", request.getPath());
      assertTrue(request.getBody().readUtf8().contains("payments"));
    }
  }

  @Test
  void a_digest_build_returns_a_job_and_missing_project_means_global() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody("{\"id\":\"job-1\",\"agent\":\"memory_digester\"}"));
      var tools = new DigestTools(new HttpServerClient(server.url("/").toString()));
      assertTrue(tools.digest(Map.of()).toString().contains("job-1"));
      var request = server.takeRequest();
      assertEquals("/v1/memories/digest", request.getPath());
      assertEquals("{\"project\":null}", request.getBody().readUtf8());
    }
  }

  @Test
  void malformed_questions_are_refused_before_transport() {
    var tools = new DigestTools(null);
    assertThrows(IllegalArgumentException.class, () -> tools.navigate(Map.of("question", " ")));
    assertThrows(IllegalArgumentException.class, () -> tools.navigate(Map.of("question", 123)));
  }
}
