package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RequestedCorpusQuestionTest {

  /** The five factories, in the order the controller declares their endpoints. */
  private static final List<Function<String, String>> ALL =
      List.of(
          RequestedCorpusQuestion::asked,
          RequestedCorpusQuestion::retrieved,
          RequestedCorpusQuestion::ranked,
          RequestedCorpusQuestion::scored,
          RequestedCorpusQuestion::searched);

  @Test
  void a_question_comes_back_stripped_of_whatever_the_paste_brought() {
    for (Function<String, String> reading : ALL) {
      assertEquals("what does this argue", reading.apply("  what does this argue \n"));
    }
  }

  @Test
  void a_null_and_a_blank_are_the_same_refusal_and_never_a_null_pointer() {
    for (Function<String, String> reading : ALL) {
      assertThrows(CallerFault.class, () -> reading.apply(null));
      assertThrows(CallerFault.class, () -> reading.apply("   "));
    }
  }

  @Test
  void each_refusal_names_its_own_endpoint_and_none_is_a_copy_of_another() {
    // Five factories because five endpoints refuse the same shape of
    // mistake in five different words, and a shared message would be wrong
    // on four of them. RequestedDocument makes the same argument for three.
    Set<String> said =
        Set.of(
            refusalOf(RequestedCorpusQuestion::asked),
            refusalOf(RequestedCorpusQuestion::retrieved),
            refusalOf(RequestedCorpusQuestion::ranked),
            refusalOf(RequestedCorpusQuestion::scored),
            refusalOf(RequestedCorpusQuestion::searched));

    assertEquals(5, said.size(), said.toString());
  }

  @Test
  void the_words_are_the_endpoints_own_words() {
    // Pinned literally, because these sentences are what the HTTP surface
    // has always sent and what a frame is now required to send back word
    // for word -- FrameParity.assertSameRefusal compares them.
    assertTrue(refusalOf(RequestedCorpusQuestion::asked).startsWith("an ask needs a `question`:"));
    assertTrue(
        refusalOf(RequestedCorpusQuestion::retrieved).startsWith("a retrieve needs a `query`:"));
    assertTrue(refusalOf(RequestedCorpusQuestion::ranked).startsWith("a ranking needs a `query`:"));
    assertTrue(refusalOf(RequestedCorpusQuestion::scored).startsWith("a stance needs a `claim`:"));
    assertTrue(
        refusalOf(RequestedCorpusQuestion::searched).startsWith("a search needs a `query`:"));
  }

  private static String refusalOf(Function<String, String> reading) {
    return assertThrows(CallerFault.class, () -> reading.apply("")).getMessage();
  }
}
