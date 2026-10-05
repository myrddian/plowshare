package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.DocumentStore;
import java.util.UUID;

/**
 * <b>One chunk and everything above it</b> — Anchor's {@code ChunkDetailResponse}, and the body of
 * every hit {@code POST /v1/documents/retrieve} answers with.
 *
 * <h2>One record for two routes, which is the whole claim being ported</h2>
 *
 * <p>Anchor's {@code findChunksForRetrieve} promises <em>"one row per chunk with no follow-up
 * reads"</em>, and Anchor then builds the follow-up read's response out of a <b>second</b> record
 * with the same fifteen fields written out again. Two shapes for one chunk are two things that can
 * drift, and the day they do the follow-up read stops being unnecessary and starts being wrong.
 * Here they are the same record, so it cannot say anything different from the hit it follows up on.
 * {@code CorpusReadControllerTest} pins that.
 *
 * <h2>The text is not this server's</h2>
 *
 * <p>{@link #text} is uploaded prose and so is nothing else here except the summaries, which the
 * cascade wrote. <b>This is a JSON body and not a rendered one</b>, which is the difference from
 * {@code DocumentTools}' column-zero rule: that rule exists because a model reading a flat block of
 * text has no other way to tell the renderer's words from the document's, and JSON already draws
 * that line — a key is the server's and a string value is whatever was put in it. So no quoting is
 * applied here, and the duty moves out to whoever renders this into prose. Every surface in {@code
 * external clients} that prints one of these indents it, for exactly the reason that rule gives.
 *
 * @param chunkId what matched. <b>Deliberately not a citation</b>: a chunk is an artefact of the
 *     chunker and a re-ingest may not produce the same one, which is why {@link #paragraphId} is
 *     the id worth writing down
 * @param text the evidence, verbatim. Uploaded text; this server did not write it and cannot vouch
 *     for it
 * @param paragraphId <b>the citation</b>. V18's identity rule preserves it across a re-ingest whose
 *     text did not change
 * @param paragraphOrdinal where in the document the paragraph sits. Position and not identity — it
 *     moves when a paragraph is inserted above it
 * @param paragraphSummary what the cascade says this paragraph claims, or {@code null} for a
 *     paragraph nothing has summarised
 * @param section the section this chunk sits in, or <b>{@code null} for a paragraph in no
 *     section</b>. An explicit null and not an omitted key, which is {@code TurnView}'s recorded
 *     policy and the right one here for its own reason: {@code DocumentStore.Placement.InNoSection}
 *     exists to keep that state in sight, and a key that is simply not there is the state going
 *     back out of it
 * @param chapter the chapter above that section, {@code null} under the same condition and never
 *     under any other — V26 makes every section belong to a chapter
 * @param sourceName what the corpus filed the document under, and the name a person names it by.
 *     Anchor's row has no counterpart: it has one name
 * @param documentSummary what the document argues, or {@code null} for one nothing has summarised
 *     yet
 */
public record ChunkDetailResponse(
    UUID chunkId,
    String text,
    UUID paragraphId,
    int paragraphOrdinal,
    String paragraphSummary,
    UnitView section,
    UnitView chapter,
    UUID documentId,
    String sourceName,
    String title,
    String documentSummary) {

  /**
   * The stored row as a body. The one construction path, so the two routes that answer with this
   * cannot answer differently.
   */
  public static ChunkDetailResponse of(DocumentStore.ChunkWithAncestors chunk) {
    UnitView section = null;
    UnitView chapter = null;
    // A switch and not an instanceof chain, so a third Placement variant
    // fails compilation here rather than rendering as "in no section" --
    // which is StructuralRef's own argument, one level out, and the reason
    // Placement is sealed.
    switch (chunk.placement()) {
      case DocumentStore.Placement.InSection placed -> {
        section = UnitView.of(placed.sectionId(), placed.sectionTitle(), placed.sectionSummary());
        chapter = UnitView.of(placed.chapterId(), placed.chapterTitle(), placed.chapterSummary());
      }
      case DocumentStore.Placement.InNoSection ignored -> {
        // Both stay null. The document holds this passage and the
        // hierarchy cannot place it, which is a fact about the corpus
        // and not a gap in this response.
      }
    }
    return new ChunkDetailResponse(
        chunk.chunkId(),
        chunk.chunkText(),
        chunk.paragraphId(),
        chunk.paragraphOrdinal(),
        chunk.paragraphSummary(),
        section,
        chapter,
        chunk.documentId(),
        chunk.sourceName(),
        chunk.documentTitle(),
        chunk.documentSummary());
  }
}
