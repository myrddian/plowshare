package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RequestedCorpusPageTest {

  /** The five limits, in the order the controller declares their endpoints. */
  private static final List<Function<Integer, Integer>> LIMITS =
      List.of(
          RequestedCorpusPage::retrieved,
          RequestedCorpusPage::listed,
          RequestedCorpusPage::ranked,
          RequestedCorpusPage::cited,
          RequestedCorpusPage::searched);

  @Test
  void an_absent_limit_is_the_endpoints_own_default_and_never_zero() {
    assertEquals(RetrievalService.DEFAULT_RETRIEVED, RequestedCorpusPage.retrieved(null));
    assertEquals(RequestedCorpusPage.DEFAULT_LISTED, RequestedCorpusPage.listed(null));
    assertEquals(RetrievalService.DEFAULT_RANKED, RequestedCorpusPage.ranked(null));
    assertEquals(CitationStore.MOST_LISTED, RequestedCorpusPage.cited(null));
    assertEquals(RetrievalService.DEFAULT_HITS, RequestedCorpusPage.searched(null));
  }

  @Test
  void a_limit_the_caller_named_is_passed_through_uncapped() {
    // The caps belong to the stores -- RetrievalService.MAX_HITS,
    // CitationStore.MOST_LISTED, DocumentStore.MOST_LISTED -- and each
    // answer reports the figure it really used. A second spelling of a
    // bound here is the copy that would go on saying ten after the other
    // one moved.
    for (Function<Integer, Integer> reading : LIMITS) {
      assertEquals(9_999, reading.apply(9_999).intValue());
    }
  }

  @Test
  void zero_and_below_are_refused_rather_than_answered_with_a_default() {
    for (Function<Integer, Integer> reading : LIMITS) {
      assertThrows(CallerFault.class, () -> reading.apply(0));
      assertThrows(CallerFault.class, () -> reading.apply(-3));
    }
  }

  @Test
  void each_limit_refusal_names_its_own_noun_and_its_own_default() {
    Set<String> said =
        Set.of(
            refusalOf(RequestedCorpusPage::retrieved),
            refusalOf(RequestedCorpusPage::listed),
            refusalOf(RequestedCorpusPage::ranked),
            refusalOf(RequestedCorpusPage::cited),
            refusalOf(RequestedCorpusPage::searched));

    assertEquals(5, said.size(), said.toString());
    assertTrue(refusalOf(RequestedCorpusPage::retrieved).contains("no chunks"));
    assertTrue(refusalOf(RequestedCorpusPage::listed).contains("no documents"));
    assertTrue(refusalOf(RequestedCorpusPage::ranked).contains("no documents"));
    assertTrue(refusalOf(RequestedCorpusPage::cited).contains("no citations"));
    assertTrue(refusalOf(RequestedCorpusPage::searched).contains("no hits"));
  }

  @Test
  void an_absent_offset_is_the_beginning_and_a_negative_one_is_refused() {
    assertEquals(0, RequestedCorpusPage.skipped(null));
    assertEquals(40, RequestedCorpusPage.skipped(40));
    // Refused rather than clamped: a negative offset is a caller's
    // arithmetic having gone wrong above the call, and the first page would
    // hide it behind a plausible answer.
    String said =
        assertThrows(CallerFault.class, () -> RequestedCorpusPage.skipped(-1)).getMessage();
    assertTrue(said.contains("`offset` is -1"), said);
  }

  private static String refusalOf(Function<Integer, Integer> reading) {
    return assertThrows(CallerFault.class, () -> reading.apply(0)).getMessage();
  }
}
