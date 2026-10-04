package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The question a read of the archive is for, or the refusal for a read that named none.
 *
 * <p>Moved out of {@code MemoryController.recall}, where it was a plain presence check on the
 * request's own {@code question} field — no archive, no domain object, nothing beyond the string —
 * which is {@link RequestedLogQuestion}'s shape exactly and this package's reason for existing. The
 * {@code memory.recall} frame reads the same field and has to refuse it in the same words.
 *
 * <p><b>Named for the archive and not for questions in general</b>, on {@link
 * RequestedLogQuestion}'s own argument: that one searches a tier's conversations and corrects a
 * caller by naming a trajectory read, which is no use to somebody asking the archive. A method per
 * endpoint rather than one shared {@code in} is {@link RequestedCorpusQuestion}'s shape, and it is
 * the shape this class keeps for the same reason — {@code POST /v1/memories/navigate} asks the
 * archive a question too, in a sentence of its own, and belongs beside this one when its own frame
 * is built.
 */
public final class RequestedMemoryQuestion {

  private RequestedMemoryQuestion() {}

  /**
   * {@code question}, or a {@link CallerFault} for a recall that asked nothing.
   *
   * <p><b>Blank is the case this is really about.</b> The empty string is what an unset field
   * arrives as, and a vector query built from it would rank the tier by its distance from nothing
   * at all and answer as if that were a result.
   *
   * <p>The sentence is the endpoint's own, word for word: it was already what a caller of {@code
   * POST /v1/memories/recall} read, and this move is about where the rule lives rather than about
   * what it says.
   *
   * @param question the request's own {@code question} field
   * @throws CallerFault if {@code question} is null or blank
   */
  public static String recalled(String question) {
    if (question == null || question.isBlank()) {
      throw new CallerFault("question must not be blank");
    }
    return question;
  }

  /**
   * {@code question}, or a {@link CallerFault} for a navigation that asked nothing.
   *
   * <p>The second method this class's own javadoc predicted: {@code POST /v1/memories/navigate}
   * asks the archive a question too, and it is {@code DigestController}'s rather than {@code
   * MemoryController}'s despite the shared path prefix.
   *
   * <p><b>A sentence of its own, kept word for word</b>, which is the point of a second method
   * rather than a second caller of {@link #recalled}. The two endpoints have always refused this in
   * different words, and the rule for a move is that nothing a caller reads changes — a navigation
   * that started answering {@code recall}'s sentence would be this move altering the thing it
   * exists to preserve. That they could be made to agree some day is a decision for whoever wants
   * to make it, in a commit that says so.
   *
   * <p>Blank as well as null, on {@link #recalled}'s reasoning: a navigation built from the empty
   * string would walk the digest tree away from nothing at all and answer as though that were a
   * finding.
   *
   * @param question the request's own {@code question} field
   * @throws CallerFault if {@code question} is null or blank
   */
  public static String navigated(String question) {
    if (question == null || question.isBlank()) {
      throw new CallerFault("A question is required");
    }
    return question;
  }
}
