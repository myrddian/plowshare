package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.CitationStore;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The body of {@code GET /v1/documents/citations}.
 *
 * <p>Echoes what was asked for beside what came back, as {@link DocumentSearchResponse} does and
 * for its reason: a listing in a log cannot be read without the request beside it. {@link #limit}
 * is <b>what was used</b>, which differs from what was asked exactly when the caller sent a number
 * above {@code CitationStore.MOST_LISTED}.
 *
 * @param scope what was listed — {@code "all"}, {@code "conversation"} or {@code "document"}.
 *     Echoed because an empty list means three different things under the three, and only this
 *     field says which was asked
 * @param citations the rows, and <b>an empty list is an answer</b>: it says no answer has cited
 *     anything in this scope, which is a fact about the corpus rather than an absence of one
 */
public record CitationsResponse(String scope, int limit, List<Cited> citations) {

  /**
   * One citation as it stands now.
   *
   * @param standing which of {@code CitationStore.Standing}'s three this is, lower-cased. <b>The
   *     field a reader has to look at</b>: a citation is not a link that either works or 404s, and
   *     V25 argues why the two ways of going stale are told apart rather than collapsed
   * @param paragraphId null once the citation has gone stale, which is V18's rule seen from the
   *     other end — a paragraph whose text was edited took a new id rather than keeping this one,
   *     so this address means the same words or means nothing
   * @param sourceName what the corpus filed the document as <b>when the citation was made</b>,
   *     stored on the row. It is what is left to say what was cited once the keys are null
   * @param paragraphOrdinal where the paragraph sat then. Position and not identity: a later ingest
   *     that inserted a paragraph above it moved it, and this number is the one the answer would
   *     have been reading
   * @param title the document's title as it stands today, or null once the document is gone
   * @param paragraphText <b>the words, as the corpus holds them today</b>, or null for a citation
   *     that no longer resolves. This is somebody's uploaded document and this server did not write
   *     it: anything that puts it in front of a reader owes the quoting rule {@code
   *     agents.DocumentTools} states, and {@code client.cli.Commands} pays it
   * @param conversationId where the citation was made, or null for a run that was in no
   *     conversation
   */
  public record Cited(
      UUID id,
      String standing,
      UUID paragraphId,
      UUID documentId,
      String sourceName,
      int paragraphOrdinal,
      String title,
      String paragraphText,
      String conversationId,
      Integer turnOrdinal,
      String agent,
      Instant citedAt) {

    static Cited of(CitationStore.Cited cited) {
      return new Cited(
          cited.id(),
          cited.standing().name().toLowerCase(Locale.ROOT),
          cited.paragraphId(),
          cited.documentId(),
          cited.sourceName(),
          cited.paragraphOrdinal(),
          cited.title(),
          cited.paragraphText(),
          cited.conversationId(),
          cited.turnOrdinal(),
          cited.agent(),
          cited.citedAt());
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
  public static CitationsResponse of(String scope, int limit, List<CitationStore.Cited> citations) {
    return new CitationsResponse(scope, limit, citations.stream().map(Cited::of).toList());
  }
}
