package io.aeyer.plowshare.protocol.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchContractTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void an_ask_survives_the_wire() throws Exception {
    SearchAsk sent = new SearchAsk("r1", "plowshare harness", 25, List.of("foxnews.com"));
    SearchAsk back = json.readValue(json.writeValueAsString(sent), SearchAsk.class);
    assertEquals(sent, back);
  }

  @Test
  void an_ask_with_no_ignore_list_is_an_empty_list_and_never_null() {
    assertTrue(new SearchAsk("r1", "q", 5, null).ignoredDomains().isEmpty());
  }

  @Test
  void an_ask_refuses_a_blank_query_at_construction() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> new SearchAsk("r1", "  ", 5, List.of()));
    assertTrue(e.getMessage().contains("query"));
  }

  @Test
  void an_ask_refuses_a_max_below_one() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> new SearchAsk("r1", "q", 0, List.of()));
    assertTrue(e.getMessage().contains("max"));
  }

  @Test
  void a_failed_answer_carries_its_message_and_no_hits() {
    SearchAnswer a = SearchAnswer.failed("r1", "brave", "connect timed out", 9000L);
    assertEquals(AnswerStatus.FAILED, a.status());
    assertTrue(a.hits().isEmpty());
    assertEquals("connect timed out", a.message());
  }

  @Test
  void facts_survive_the_wire_with_their_enums() throws Exception {
    ProviderFacts sent =
        new ProviderFacts(
            "searxng",
            "SearXNG",
            "0.1.0",
            "metasearch",
            Set.of(Verb.SEARCH),
            CostClass.FREE,
            NetworkTier.INTERNAL_NETWORK,
            25,
            512,
            true);
    ProviderFacts back = json.readValue(json.writeValueAsString(sent), ProviderFacts.class);
    assertEquals(sent, back);
  }

  @Test
  void a_page_survives_the_wire() throws Exception {
    SearchPage sent =
        new SearchPage(
            List.of(new Hit("https://example.com", "Example", "An example")),
            1,
            10,
            100,
            true,
            null);
    SearchPage back = json.readValue(json.writeValueAsString(sent), SearchPage.class);
    assertEquals(sent, back);
  }

  @Test
  void a_page_with_null_hits_becomes_an_empty_list() {
    assertTrue(new SearchPage(null, 1, 10, 100, true, null).hits().isEmpty());
  }
}
