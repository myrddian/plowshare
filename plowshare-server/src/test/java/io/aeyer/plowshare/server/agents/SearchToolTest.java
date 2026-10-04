package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.server.search.SearchService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code search} as a model reads it — the tool the previous slice exported over MCP and the CLI
 * and never built here, so a foreign harness could search and Plowshare's own agents could not.
 *
 * <p>Against a mocked {@link SearchService}, {@code FetchToolTest}'s own reasoning applied to this
 * collaborator: what is under test here is the rendering and the argument reading, not {@link
 * SearchService#search}'s own four-step algorithm, which {@code SearchServiceTest} already covers
 * against a real ladder and a real store. {@link SearchService#search} is neither final nor static,
 * so a plain Mockito mock is enough.
 *
 * <p>{@link #oneHit} and {@link #refusing} each build one such mock, stubbed to answer {@link
 * SearchService#search} however the test needs a page or a refusal shaped, regardless of the {@code
 * query}/{@code pageSize}/{@code max}/{@code page} the tool under test actually passes through.
 */
class SearchToolTest {

  private static final Home home = Home.global();

  /**
   * Unstubbed on purpose, {@code FetchToolTest.service}'s own reason: both tests that use this
   * field never reach {@link SearchService#search} at all — one only builds a schema, the other
   * fails to read its 'query' before any collaborator is called.
   */
  private final SearchService service = mock(SearchService.class);

  @Test
  void the_description_and_schema_name_no_vendor() {
    String all =
        (SearchTool.DESCRIPTION + new SearchTool(service).schema().parameters()).toLowerCase();
    assertFalse(all.contains("brave"));
    assertFalse(all.contains("searxng"));
    assertFalse(all.contains("provider"));
  }

  @Test
  void a_title_carrying_a_newline_cannot_forge_a_line_of_its_own() {
    String rendered =
        new SearchTool(oneHit("https://e.example/a", "A\nnot a real heading", "snippet"))
            .run("{\"query\":\"q\"}", home);
    assertEquals(0L, rendered.lines().filter(l -> l.startsWith("not a real heading")).count());
  }

  @Test
  void a_refusal_reaches_the_model_as_text() {
    String rendered =
        new SearchTool(refusing("no search provider is configured")).run("{\"query\":\"q\"}", home);
    assertTrue(rendered.startsWith("SEARCH FAILED — "));
    assertTrue(rendered.contains("no search provider is configured"));
  }

  /**
   * A refusal is composed by {@code SearchLadder.messageOrDefault} out of a failing provider's own
   * message, quoted verbatim — {@link SearchPage}'s own javadoc says so, and the client-side MCP
   * renderer ({@code SearchTools.render}) flattens it for exactly that reason. A provider that
   * failed on purpose could otherwise put a newline in its message and open a line of its own
   * inside this tool's answer, indistinguishable from this renderer's own prose — worse here than
   * on the MCP surface, because this text lands inside a running agent's own conversation turn
   * rather than a foreign harness one step further from autonomous action.
   */
  @Test
  void a_refusal_carrying_a_newline_cannot_forge_a_line_of_its_own() {
    String rendered =
        new SearchTool(refusing("brave failed: fake\nnot a real heading"))
            .run("{\"query\":\"q\"}", home);
    assertEquals(0L, rendered.lines().filter(l -> l.startsWith("not a real heading")).count());
  }

  @Test
  void a_blank_query_comes_back_as_a_message_rather_than_throwing() {
    String rendered = new SearchTool(service).run("{\"query\":\"  \"}", home);
    assertTrue(rendered.toLowerCase().contains("query"));
  }

  @Test
  void the_hits_carry_the_url_so_a_model_can_hand_it_to_fetch() {
    String rendered =
        new SearchTool(oneHit("https://e.example/a", "T", "s")).run("{\"query\":\"q\"}", home);
    assertTrue(
        rendered.contains("https://e.example/a"),
        "search and fetch compose: the url is the handle between them");
  }

  /**
   * A {@link SearchService} whose {@code search} always answers with one hit — {@code total} 1,
   * {@code hasMore} false, no refusal — regardless of the query/pageSize/max/page it is called
   * with.
   */
  private static SearchService oneHit(String url, String title, String snippet) {
    SearchService fake = mock(SearchService.class);
    when(fake.search(anyString(), anyInt(), anyInt(), anyInt()))
        .thenAnswer(
            invocation -> {
              int page = invocation.getArgument(3);
              return new SearchPage(
                  List.of(new Hit(url, title, snippet)), page, 10, 1, false, null);
            });
    return fake;
  }

  /**
   * A {@link SearchService} whose {@code search} always refuses with {@code message}, on {@link
   * SearchPage}'s own shape for a refusal: no hits, {@code total} 0, {@code hasMore} false.
   */
  private static SearchService refusing(String message) {
    SearchService fake = mock(SearchService.class);
    when(fake.search(anyString(), anyInt(), anyInt(), anyInt()))
        .thenAnswer(
            invocation -> {
              int page = invocation.getArgument(3);
              return new SearchPage(List.of(), page, 10, 0, false, message);
            });
    return fake;
  }
}
