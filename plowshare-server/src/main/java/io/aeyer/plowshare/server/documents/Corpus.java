package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.UUID;

/**
 * What the corpus does and does not hold, said once so that two surfaces say it the same way.
 *
 * <h2>Why this exists beside {@code DocumentStore}</h2>
 *
 * <p>Four of {@code DocumentController}'s endpoints read something by id and answer 404 when it is
 * not there, and each of the four says something different about the absence: an ask points at the
 * citations listing, a read points at {@code GET /v1/documents} and its {@code q}, a chunk explains
 * that a chunk id is the one id here a re-ingest may not preserve, and a stance explains that zero
 * is a real reading and therefore not the answer for a document nothing has embedded. Those four
 * sentences were inline in the controller, which meant a frame handler calling the same store would
 * either restate them or answer the absence in different words — the drift a parity test's sentence
 * comparison exists to catch, reintroduced by the shape of the code. <b>The answer is to widen what
 * both surfaces call, not to copy the sentence</b>, which is what {@code archive.Conversations} did
 * for the conversation reads.
 *
 * <h2>Static methods over an injected store, and not a bean</h2>
 *
 * <p>{@code requests.RequestedProjectId}'s shape: a rule with no state of its own, taking the store
 * its caller already holds. A bean would have had to reach {@code DocumentController}'s
 * constructor, and that constructor is named by {@code DocumentControllerTest} and {@code
 * CorpusReadControllerTest}, both of which the breadth plan requires to be untouched — so a bean
 * would have made a rule-move into a test edit. The store is still the one the caller was injected
 * with, and these still call the same methods on it, which is what the parity claim actually rests
 * on.
 *
 * <p><b>{@link NotFoundFault} and not {@code api.NotFoundException}</b>, for the reason {@code
 * faults}' own javadoc gives: this is below the HTTP surface and may not hold that package's type.
 * Both resolve through {@code Faults} to the same 404 with the same slug and the same detail, on
 * both surfaces.
 */
public final class Corpus {

  private Corpus() {}

  /**
   * {@code id}, once the corpus is known to hold a document under it — the check {@code POST
   * /v1/documents/&#123;id&#125;/ask} makes before it starts a job.
   *
   * <p>Answered now rather than inside the job, on the upload's stated rule: a caller handed a job
   * id and told to poll it to discover it named a document that never existed is worse off than one
   * refused.
   *
   * @param documents the same store the controller is injected with
   * @param id the document, already parsed
   * @throws NotFoundFault if the corpus holds no document under it
   */
  public static UUID theOneToAsk(DocumentStore documents, UUID id) {
    if (documents.find(id).isEmpty()) {
      throw new NotFoundFault(
          "the corpus holds no document with the id "
              + id
              + ". Documents are"
              + " filed under the name they were uploaded as; GET"
              + " /v1/documents/citations names the ids of the ones answers have"
              + " drawn on");
    }
    return id;
  }

  /**
   * The document {@code GET /v1/documents/&#123;id&#125;} reads back.
   *
   * @param documents the same store the controller is injected with
   * @param id the document, already parsed
   * @throws NotFoundFault if the corpus holds no document under it
   */
  public static DocumentStore.StoredDocument theOneToRead(DocumentStore documents, UUID id) {
    return documents
        .find(id)
        .orElseThrow(
            () ->
                new NotFoundFault(
                    "the corpus holds no document with the id "
                        + id
                        + ". GET"
                        + " /v1/documents lists everything it does hold, and takes a `q`"
                        + " that matches a document's filed name or its title"));
  }

  /**
   * The chunk {@code GET /v1/documents/chunks/&#123;id&#125;} reads back.
   *
   * @param documents the same store the controller is injected with
   * @param id the chunk, already parsed
   * @throws NotFoundFault if the corpus holds no chunk under it — which is also what a chunk whose
   *     document was re-ingested looks like
   */
  public static DocumentStore.ChunkWithAncestors theChunk(DocumentStore documents, UUID id) {
    return documents
        .chunk(id)
        .orElseThrow(
            () ->
                new NotFoundFault(
                    "the corpus holds no chunk with the id "
                        + id
                        + ". A chunk id is the"
                        + " one id here a re-ingest may not preserve -- a chunk is an"
                        + " artefact of the chunker -- so a chunk that was there and is"
                        + " not looks exactly like this. The paragraph id beside it in"
                        + " whatever answer you read it from is the durable one"));
  }

  /**
   * The reading {@code POST /v1/documents/&#123;id&#125;/stance} answers with.
   *
   * <p><b>A document with no summary vector is a 404 rather than a zero.</b> Zero is a real reading
   * — it is what a paper unrelated to both the claim and its negation scores — so answering an
   * unembedded document with one would be indistinguishable from a genuine result. The 404 says the
   * same thing as a 404 for a document that does not exist, which is the one thing this shape
   * costs, and the message says which read tells the two apart.
   *
   * @param retrieval the same service the controller is injected with
   * @param id the document, already parsed
   * @param claim the claim to score, already read
   * @throws NotFoundFault if there is no summary vector to score against
   */
  public static DocumentStore.Stance theStance(RetrievalService retrieval, UUID id, String claim) {
    return theStance(retrieval, id, claim, null);
  }

  public static DocumentStore.Stance theStance(
      RetrievalService retrieval,
      UUID id,
      String claim,
      io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
    return (owner == null ? retrieval.stance(id, claim) : retrieval.stance(id, claim, owner))
        .orElseThrow(
            () ->
                new NotFoundFault(
                    "there is no summary vector for document "
                        + id
                        + ", so nothing"
                        + " was scored. Either the corpus does not hold that document --"
                        + " GET /v1/documents/"
                        + id
                        + " says which -- or it holds"
                        + " it and nothing has embedded its summary yet, which POST"
                        + " /v1/documents/rank reports as `unranked` and a restart"
                        + " repairs"));
  }
}
