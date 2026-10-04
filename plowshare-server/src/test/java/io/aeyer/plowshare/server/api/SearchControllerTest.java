package io.aeyer.plowshare.server.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.server.search.SearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pure MVC test against a mocked {@link SearchService} — no Spring context, no Postgres, no
 * provider process — on {@code MemoryControllerTest} and {@code ProjectControllerTest}'s shape,
 * which is this package's idiom for every one of its eleven controller tests.
 *
 * <p><b>The split with {@code SearchServiceTest} is deliberate and neither half is sufficient
 * alone.</b> {@code SearchServiceTest} proves, against a real Postgres, that a blank {@code query}
 * throws {@link BadRequestException} rather than letting an {@link IllegalArgumentException} escape
 * from {@code SearchAsk}'s constructor three calls down. It cannot prove what an HTTP caller then
 * sees, because it never goes through a controller. This class proves the other half: that the
 * exception really does leave this route as <b>400</b> and not as {@code ApiExceptionHandler}'s
 * {@code Throwable} fallback, which would answer 500 and tell the caller their own mistake was a
 * fault in the server. Both halves were asserted about this route by nothing at all before it
 * shipped.
 *
 * <p>The mock is stubbed to throw the exception the real service is proven to throw, rather than a
 * real service being wired up here — the same division {@code MemoryControllerTest} draws against
 * {@code ArchiveTest}.
 */
class SearchControllerTest {

  private SearchService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(SearchService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new SearchController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  private static String body(String query, int pageSize, int max, int page) {
    return """
                {"query":%s,"pageSize":%d,"max":%d,"page":%d}"""
        .formatted(query == null ? "null" : "\"" + query + "\"", pageSize, max, page);
  }

  @Test
  void a_blank_query_is_400_and_not_a_500_about_a_fault_in_the_server() throws Exception {
    when(service.search(anyString(), anyInt(), anyInt(), anyInt()))
        .thenThrow(new BadRequestException("query must not be blank"));

    mvc.perform(
            post("/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("   ", 10, 30, 1)))
        .andExpect(status().isBadRequest());
  }

  /**
   * The other three inputs {@code SearchService.validate} refuses reach the same status by the same
   * route. Written as one test over one of them rather than three near-identical ones: {@code
   * ApiExceptionHandler} maps the type, not the message, so a second and third case would re-prove
   * the same mapping. What is worth pinning separately is that the refusal <em>text</em> survives
   * to the caller, which is what an operator or a model has to read to fix the call.
   */
  @Test
  void the_refusal_message_reaches_the_caller_rather_than_being_swallowed() throws Exception {
    when(service.search(anyString(), anyInt(), anyInt(), anyInt()))
        .thenThrow(new BadRequestException("page must be at least 1, was 0"));

    mvc.perform(
            post("/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("q", 10, 30, 0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("page must be at least 1, was 0"));
  }

  /**
   * A domain-level miss travels as <b>200 with a refusal</b>, not as a status — {@code
   * SearchController}'s own javadoc's whole argument, and the one behaviour of this route that a
   * status-code test could quietly invert without any other test noticing.
   */
  @Test
  void an_exhausted_ladder_is_a_200_carrying_the_refusal_and_not_an_error_status()
      throws Exception {
    when(service.search(anyString(), anyInt(), anyInt(), anyInt()))
        .thenReturn(new SearchPage(List.of(), 1, 10, 0, false, "No search provider answered."));

    mvc.perform(
            post("/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("q", 10, 30, 1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refusal").value("No search provider answered."))
        .andExpect(jsonPath("$.hits").isEmpty());
  }

  /**
   * The four numbers arrive in the order the service declares them.
   *
   * <p>Not a formality: {@code search(query, pageSize, max, page)} takes three consecutive {@code
   * int}s, so transposing {@code pageSize} and {@code max} at this call site compiles, passes every
   * other test in this package and every test in {@code SearchServiceTest}, and silently changes
   * which stored set a caller's later pages address — {@code max} is part of the query key and
   * {@code pageSize} is not. Distinct values here, so a transposition cannot pass.
   */
  @Test
  void the_four_arguments_reach_the_service_in_the_order_it_declares_them() throws Exception {
    when(service.search(anyString(), anyInt(), anyInt(), anyInt()))
        .thenReturn(
            new SearchPage(List.of(new Hit("https://a.example", "t", "s")), 3, 7, 25, true, null));

    mvc.perform(
            post("/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("plowshare harness", 7, 41, 3)))
        .andExpect(status().isOk());

    verify(service).search("plowshare harness", 7, 41, 3);
  }
}
