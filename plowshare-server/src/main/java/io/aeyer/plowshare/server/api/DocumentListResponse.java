package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.DocumentStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What {@code GET /v1/documents} answers with — <b>the corpus as a list</b>.
 *
 * <p>Until this shipped, nothing on this server could enumerate what it held: {@code POST
 * /v1/documents/search} answers with passages, {@code POST /v1/documents/&#123;id&#125;/ask} needs
 * an id, and the only door onto an id was a search hit. A person who could not remember a paper's
 * name could not find it.
 *
 * @param documents the page, newest first
 * @param total how many the naming reaches, across every page. The number a caller pages against
 * @param limit the figure actually used, after the store's cap
 * @param offset how many were skipped
 * @param naming what narrowed the listing, or {@code null} for the whole corpus. Echoed for {@code
 *     CitationsResponse#scope}'s reason: an empty {@code documents} means <em>the corpus is
 *     empty</em> or <em>nothing is called that</em>, and those are two different facts that come
 *     back as the same empty array
 */
public record DocumentListResponse(
    List<Listed> documents, int total, int limit, int offset, String naming) {

  /**
   * One document, and how much of it there is.
   *
   * <p><b>Four counts where Anchor has three.</b> Anchor reports chapters, sections and chunks. The
   * paragraph is the unit a citation names here and the unit V18's identity rule preserves across a
   * re-ingest, so a listing that counted everything except it would omit the one number a reader
   * can hold against {@code GET /v1/documents/citations}.
   *
   * @param summary what the document argues, or {@code null} for one the cascade has not reached.
   *     An ordinary state and not a gap: the text is committed before any model is called
   * @param vocabulary what this document calls its own top-level parts, or {@code null} for a row
   *     written before V26. Sent because a caller rendering the outline below wants the document's
   *     own word for its parts, which is the whole reason the column exists
   * @param chunks how many chunks, which is <b>not</b> how many are searchable: a chunk written
   *     while the embedding endpoint was down holds its text and no vector. {@code POST
   *     /v1/documents/search}'s {@code searchable}/{@code unsearchable} pair is where that
   *     difference is answered, corpus-wide, and it is not restated per document here
   */
  public record Listed(
      UUID documentId,
      String sourceName,
      String title,
      String summary,
      String vocabulary,
      Instant ingestedAt,
      String ingestedBy,
      long byteSize,
      int chapters,
      int sections,
      int paragraphs,
      int chunks) {

    static Listed of(DocumentStore.Listed row) {
      DocumentStore.StoredDocument document = row.document();
      return new Listed(
          document.id(),
          document.sourceName(),
          document.title(),
          document.summary(),
          document.vocabulary() == null ? null : document.vocabulary().name(),
          document.ingestedAt(),
          document.ingestedBy(),
          document.byteSize(),
          row.chapters(),
          row.sections(),
          row.paragraphs(),
          row.chunks());
    }
  }

  /**
   * The view, built from what the store answered with.
   *
   * <p><b>Public rather than package-private</b>, which it was while the HTTP surface was the only
   * surface that built one. {@code ws.DocumentFrames}' handlers answer with this same record, and a
   * narrower visibility would have meant either a frame handler living in {@code api} -- the
   * accident {@code requests}' package javadoc describes -- or a second view record with the same
   * fields. The narrower statement is gone, and is the same cost that move charged.
   */
  public static DocumentListResponse of(
      List<DocumentStore.Listed> page, int total, int limit, int offset, String naming) {
    return new DocumentListResponse(
        page.stream().map(Listed::of).toList(), total, limit, offset, naming);
  }
}
