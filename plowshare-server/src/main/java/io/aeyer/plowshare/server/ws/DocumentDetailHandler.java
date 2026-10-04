package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.DocumentDetailResponse;
import io.aeyer.plowshare.server.documents.Corpus;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.requests.RequestedDocument;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code document.detail} — one document's structure, with none of its text in it. The frame
 * equivalent of {@code GET /v1/documents/&#123;id&#125;}.
 *
 * <h2>The two refusals are different answers to different mistakes</h2>
 *
 * <p>Something that is not an id at all is a {@code BAD_REQUEST} through {@link
 * RequestedDocument#askedAbout}, and an id the corpus does not hold is a {@code NOT_FOUND} through
 * {@link Corpus#theOneToRead}. Answering the first as "no such document" would say the corpus was
 * asked when nothing was. The endpoint draws exactly that line and this reaches the same two
 * methods for it.
 *
 * <p><b>There is no literal-versus-variable ambiguity to reproduce here.</b> {@code GET
 * /v1/documents/citations} matches the endpoint's path pattern too, and Spring's preference for the
 * literal is what keeps the two apart on HTTP. A frame type is a flat string, so {@code
 * document.citations} and {@code document.detail} cannot collide; the pin in {@code
 * CorpusReadControllerTest} stays exactly as necessary for the surface that has the ambiguity.
 */
public final class DocumentDetailHandler implements FrameHandler {

  private final DocumentStore documents;

  /**
   * @param documents the one store both surfaces read a document and its hierarchy from
   */
  public DocumentDetailHandler(DocumentStore documents) {
    this.documents = Objects.requireNonNull(documents, "documents");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    UUID document =
        RequestedDocument.askedAbout(
            Payloads.required(
                payload,
                "document",
                FrameTypes.DOCUMENT_DETAIL,
                "the id document.list answers with. Nothing was read."));
    DocumentStore.StoredDocument found = Corpus.theOneToRead(documents, document);
    return Outcome.ok(DocumentDetailResponse.of(found, documents.hierarchy(document)));
  }
}
