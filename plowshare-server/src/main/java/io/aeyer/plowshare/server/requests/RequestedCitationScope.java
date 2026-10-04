package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;

/**
 * Which of {@code GET /v1/documents/citations}' three scopes a request named — what one
 * conversation cited, what has cited one document, or the most recent citations in the corpus.
 *
 * <h2>The refusal and the branch travel together, and that is the point</h2>
 *
 * <p>The endpoint refuses a request that names both scopes, and then chooses between three {@link
 * CitationStore} reads on the same two fields. Moving only the refusal would have left the
 * three-armed branch in the controller for a frame handler to write out a second time, which is the
 * drift a parity test cannot see: two surfaces reading the same citations through two copies of one
 * {@code if} agree until somebody edits one of them. So this type carries both — {@link #in}
 * decides which scope was named, and {@link #from} is the only place the three reads are chosen
 * between.
 *
 * <h2>The order the two refusals happen in is part of the answer</h2>
 *
 * <p>The endpoint refuses both-scopes-at-once first, refuses a limit under one second, and only
 * then reads the {@code document} field as an id. A request that gets two of those wrong is told
 * about the first, and <b>{@link #in} therefore does not parse anything</b>: the parse waits for
 * {@link #from}, which runs after {@link RequestedCorpusPage#cited} has had its say. A type that
 * parsed eagerly would answer a different one of three refusals for that request while agreeing
 * with the endpoint on every other input.
 *
 * @param scope what {@code CitationsResponse.scope} echoes — {@code "conversation"}, {@code
 *     "document"} or {@code "all"}. Echoed because an empty list is an answer and not an absence of
 *     one: nothing has cited anything, this conversation cited nothing, and this document has never
 *     been cited are three different facts that come back as the same empty array
 * @param conversation the conversation the request named, or null
 * @param document the document the request named, unparsed, or null
 */
public record RequestedCitationScope(String scope, String conversation, String document) {

  /**
   * Which scope this request named, or a refusal for one that named two.
   *
   * <p>Refused rather than one silently winning: both were sent on purpose, and answering one of
   * them would be this server deciding which the caller meant.
   *
   * @param conversation the query string's own {@code conversation}, or null
   * @param document the query string's own {@code document}, or null
   * @throws CallerFault if both are named
   */
  public static RequestedCitationScope in(String conversation, String document) {
    if (conversation != null && document != null) {
      throw new CallerFault(
          "`conversation` and `document` are two different questions and this endpoint"
              + " answers one at a time: what one conversation cited, or what has"
              + " cited one document. Send one of them, or neither for the most"
              + " recent citations in the corpus");
    }
    if (conversation != null) {
      return new RequestedCitationScope("conversation", conversation, null);
    }
    if (document != null) {
      return new RequestedCitationScope("document", null, document);
    }
    return new RequestedCitationScope("all", null, null);
  }

  /**
   * The citations this scope names, read through the one store both surfaces read them through.
   *
   * <p><b>No 404 for a conversation or a document that does not exist.</b> A citation listing is
   * not a read of that row and does not pretend to be one: it answers "what citations name this
   * id", and for an id nothing names the true answer is none. Refusing would make this the second
   * place on the server that decides whether a conversation exists, and it would disagree with the
   * first the moment retention changed.
   *
   * @param citations the same store the controller is injected with
   * @param most how many at most, already read through {@link RequestedCorpusPage#cited}
   * @throws CallerFault if a named document is not the shape of a document id
   */
  public List<CitationStore.Cited> from(CitationStore citations, int most) {
    if (conversation != null) {
      return citations.madeIn(conversation.strip(), most);
    }
    if (document != null) {
      return citations.of(RequestedDocument.documentId(document), most);
    }
    return citations.recent(most);
  }
}
