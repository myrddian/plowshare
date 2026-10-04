package io.aeyer.plowshare.server.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.server.fetch.FetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pure MVC test against a mocked {@link FetchService} — {@code SearchControllerTest}'s own shape,
 * this package's idiom for a controller that is nothing but a route around one service call.
 *
 * <p><b>The split with {@code FetchServiceTest} is deliberate and neither half is sufficient
 * alone</b>, {@code SearchControllerTest}'s own words. {@code FetchServiceTest} proves, against a
 * real Postgres, that a blank or unparseable {@code url} and a negative {@code offset} throw {@link
 * BadRequestException} rather than an {@code IllegalArgumentException} escaping three calls down.
 * It cannot prove what an HTTP caller then sees, because it never goes through a controller. This
 * class proves the other half: that the exception really does leave this route as <b>400</b> and
 * not as {@code ApiExceptionHandler}'s {@code Throwable} fallback, which would answer 500 and tell
 * the caller their own mistake was a fault in the server — and, in the other direction, that a
 * domain-level refusal ({@link FetchWindow#refusal()} non-null) leaves this route as <b>200</b> and
 * never as an error status, {@code FetchController}'s own javadoc's central claim and the one
 * behaviour here a status-code test could quietly invert without any other test noticing.
 *
 * <p>The mock is stubbed to return or throw whatever {@link FetchService}'s own suite proves it
 * actually returns or throws, rather than a real service being wired up here — the same division
 * {@code SearchControllerTest} draws against {@code SearchServiceTest}.
 */
class FetchControllerTest {

  private FetchService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(FetchService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new FetchController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  private static String body(String url, int offset) {
    return """
                {"url":%s,"offset":%d}"""
        .formatted(url == null ? "null" : "\"" + url + "\"", offset);
  }

  @Test
  void a_bad_request_from_the_service_is_400_and_not_a_500_about_a_fault_in_the_server()
      throws Exception {
    when(service.read(anyString(), anyInt()))
        .thenThrow(new BadRequestException("url is not a URL and cannot be read: bogus"));

    mvc.perform(post("/v1/fetch").contentType(MediaType.APPLICATION_JSON).content(body("bogus", 0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("url is not a URL and cannot be read: bogus"));
  }

  /**
   * A domain-level miss — a dead host, a suppressed domain, a store row that aged out from under a
   * stale paging offset — travels as <b>200 with a refusal</b>, not as a status. This is the case
   * most worth pinning: a refusal is an answer a caller acts on, not an error, and nothing short of
   * this test stops a future edit from "fixing" it into a 4xx the way a reviewer might otherwise
   * expect a failed fetch to look.
   */
  @Test
  void a_refusal_is_a_200_carrying_the_refusal_and_not_an_error_status() throws Exception {
    when(service.read(anyString(), anyInt()))
        .thenReturn(
            new FetchWindow(
                "https://blocked.example/a",
                null,
                null,
                0,
                0,
                0,
                false,
                "this deployment's fetcher is blocked at blocked.example"));

    mvc.perform(
            post("/v1/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://blocked.example/a", 0)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.refusal").value("this deployment's fetcher is blocked at blocked.example"))
        .andExpect(jsonPath("$.title").doesNotExist())
        .andExpect(jsonPath("$.text").doesNotExist());
  }

  /**
   * The two arguments reach the service in the order it declares them, and distinct values are used
   * deliberately: {@code FetchService.read(url, offset)} takes a {@code String} and an {@code int},
   * so there is no transposition to guard against the way {@code SearchControllerTest} does for
   * three adjacent {@code int}s — what is worth pinning here is only that the request body's two
   * fields actually bind onto the two parameters, rather than one of them being silently dropped or
   * defaulted.
   */
  @Test
  void the_request_body_binds_onto_the_service_call() throws Exception {
    when(service.read(anyString(), anyInt()))
        .thenReturn(
            new FetchWindow(
                "https://example.com/a", "Title", "some text", 40, 49, 49, false, null));

    mvc.perform(
            post("/v1/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://example.com/a", 40)))
        .andExpect(status().isOk());

    verify(service).read("https://example.com/a", 40);
  }
}
