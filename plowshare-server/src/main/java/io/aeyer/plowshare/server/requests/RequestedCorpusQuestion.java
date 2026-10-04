package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The prose a corpus request carries — a question, a query or a claim — or the refusal for a
 * request that carried none.
 *
 * <h2>Five factories and one rule, on {@link RequestedDocument}'s precedent</h2>
 *
 * <p>Five of {@code DocumentController}'s endpoints take a string of prose and refuse a blank one,
 * and all five refuse it in different words: an ask names the deliberation it did not run, a
 * retrieve and a search name the difference between finding nothing and asking nothing, a ranking
 * names closeness, and a stance names scoring zero. <b>A single shared message would be wrong on
 * four endpoints out of five</b> — which is {@link RequestedDocument}'s own argument for holding
 * three factories over one parse, and it holds harder here. What is shared is the rule (null or
 * blank is refused, and what comes back is stripped), and that is all that is shared.
 *
 * <h2>Why it is not {@code RequestedQuestion}</h2>
 *
 * <p>{@link RequestedLogQuestion}'s javadoc asks for exactly this restraint: a generically named
 * type invites the next task to route some other endpoint's blank-question refusal through a
 * message that names the wrong corpus. This one is named for the document corpus and its factories
 * are named for the five endpoints that call them, so there is no general door to push a sixth
 * caller through. {@code POST /v1/memories/recall} and {@code POST /v1/memories/navigate} keep
 * their own sentences.
 *
 * <h2>Why the stance's field is here although it is a claim</h2>
 *
 * <p>{@link #scored} reads {@code claim} rather than {@code query}, and it is the same rule about
 * the same shape: one prose field on one corpus request, refused when it is blank, stripped when it
 * is not. Putting it in a class of its own for the sake of the field's name would be a sixth file
 * that repeated four lines and shared no sentence with anything.
 *
 * <h2>What both surfaces get out of it</h2>
 *
 * <p>These five sentences were inline {@code BadRequestException}s on {@code DocumentController}
 * until the breadth plan's Task 2, which is to say they were reachable from HTTP and from nothing
 * else. {@code document.ask}, {@code document.retrieve}, {@code document.rank}, {@code
 * document.stance} and {@code document.search} refuse the same field, and a handler restating any
 * of these sentences would be a second copy to keep in step with the first.
 */
public final class RequestedCorpusQuestion {

  private RequestedCorpusQuestion() {}

  /**
   * {@code POST /v1/documents/&#123;id&#125;/ask}'s {@code question}.
   *
   * @param question the request's own {@code question} field
   * @throws CallerFault if it is null or blank
   */
  public static String asked(String question) {
    return required(
        question,
        "an ask needs a `question`: what you want to know about this document, in"
            + " plain language. Nothing was deliberated, which is not the same"
            + " as the document having no answer");
  }

  /**
   * {@code POST /v1/documents/retrieve}'s {@code query}.
   *
   * @param query the request's own {@code query} field
   * @throws CallerFault if it is null or blank
   */
  public static String retrieved(String query) {
    return required(
        query,
        "a retrieve needs a `query`: what you want to know, in plain language."
            + " Nothing was retrieved, which is not the same as nothing being"
            + " found");
  }

  /**
   * {@code POST /v1/documents/rank}'s {@code query}.
   *
   * @param query the request's own {@code query} field
   * @throws CallerFault if it is null or blank
   */
  public static String ranked(String query) {
    return required(
        query,
        "a ranking needs a `query`: what you want to find a paper about, in plain"
            + " language. Nothing was ranked, which is not the same as nothing"
            + " being close");
  }

  /**
   * {@code POST /v1/documents/&#123;id&#125;/stance}'s {@code claim}.
   *
   * @param claim the request's own {@code claim} field
   * @throws CallerFault if it is null or blank
   */
  public static String scored(String claim) {
    return required(
        claim,
        "a stance needs a `claim`: the statement you want scored against this"
            + " document, in plain language. Nothing was scored, which is not the"
            + " same as scoring zero");
  }

  /**
   * {@code POST /v1/documents/search}'s {@code query}.
   *
   * @param query the request's own {@code query} field
   * @throws CallerFault if it is null or blank
   */
  public static String searched(String query) {
    return required(
        query,
        "a search needs a `query`: what you want to know, in plain language."
            + " Nothing was searched, which is not the same as nothing being"
            + " found");
  }

  /**
   * The rule the five share: refuse a null or blank field with the sentence its own endpoint has
   * always sent, and strip what survives.
   *
   * <p>Stripped here rather than at each call site because every one of the five did it,
   * immediately after its own check, and prose pasted out of a terminal arrives with whatever the
   * paste brought.
   */
  private static String required(String prose, String said) {
    if (prose == null || prose.isBlank()) {
      throw new CallerFault(said);
    }
    return prose.strip();
  }
}
