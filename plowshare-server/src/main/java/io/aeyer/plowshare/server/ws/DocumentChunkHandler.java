package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ChunkDetailResponse;
import io.aeyer.plowshare.server.documents.Corpus;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.requests.RequestedDocument;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code document.chunk} — one chunk and everything above it. The frame equivalent of {@code GET
 * /v1/documents/chunks/&#123;id&#125;}.
 *
 * <h2>The payload says {@code chunk} and not {@code document}</h2>
 *
 * <p>{@link Payloads}' convention names what a type acts on by its noun, and the noun here is a
 * chunk: this is the one read in the area whose id is not a document's. Spelling it {@code
 * document} would make a client hold two different kinds of id under one payload key, and a chunk
 * id is precisely the id this corpus is least willing to honour — {@code DocumentStore.Passage}
 * says in as many words that it is "deliberately not a citation", because a chunk is an artefact of
 * the chunker. {@link Corpus#theChunk}'s refusal says so, which is why the sentence is there and
 * not here.
 *
 * <h2>Why it exists on this surface at all</h2>
 *
 * <p>The same caller the endpoint exists for: one that held a chunk id and came back. It answers
 * with the record a retrieve hit already carries, and {@code client.Capabilities} declares that
 * neither main nor the console reaches it and gives the argument. A socket-only client is no more
 * likely to want it — but leaving the one route with no frame would have made this the endpoint
 * whose absence read as a migration that stalled rather than as a decision.
 */
public final class DocumentChunkHandler implements FrameHandler {

  private final DocumentStore documents;

  /**
   * @param documents the one store both surfaces read a chunk from
   */
  public DocumentChunkHandler(DocumentStore documents) {
    this.documents = Objects.requireNonNull(documents, "documents");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    UUID chunk =
        RequestedDocument.askedAbout(
            Payloads.required(
                payload,
                "chunk",
                FrameTypes.DOCUMENT_CHUNK,
                "the chunk id a retrieve hit carried. Nothing was read."));
    return Outcome.ok(ChunkDetailResponse.of(Corpus.theChunk(documents, chunk)));
  }
}
