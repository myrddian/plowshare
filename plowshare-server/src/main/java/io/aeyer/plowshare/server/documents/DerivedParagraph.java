package io.aeyer.plowshare.server.documents;

import java.util.List;
import java.util.Objects;

/**
 * One paragraph as this derivation found it — everything a row needs except an id.
 *
 * <p><b>The absence of an id is the design.</b> The spec's first decision is that a paragraph's
 * primary key is a surrogate with the content hash beside it, and that the rule matching a
 * re-ingested paragraph to an existing row <em>lives in code</em>: a content-hash primary key would
 * freeze the matching rule into the schema, and {@code MigrationsAreImmutableTest} freezes the
 * schema. So a derivation produces the three facts the rule is written over — {@link #contentHash},
 * {@link #occurrence}, {@link #ordinal} — and {@code DocumentStore} decides which of them keeps an
 * existing id.
 *
 * @param ordinal where this paragraph sits in the document, from 1. <b>Position, and deliberately
 *     not identity.</b> It is a mutable column: inserting a paragraph moves every ordinal after it
 *     and must not move a single id, because a citation pointing at a paragraph has to keep meaning
 *     the same text
 * @param text the paragraph, re-flowed onto one line
 * @param contentHash SHA-256, lower-case hex, of {@link #text}. Half of what the matching rule is
 *     written over, and an indexed column beside the surrogate key rather than the key itself
 * @param occurrence which of the identically-hashed paragraphs in <em>this</em> document this is,
 *     from 1. The other half. A repeated heading, a refrain or a boilerplate footer appears more
 *     than once with one hash, and without this term the second copy could never hold an id of its
 *     own.
 *     <p>Counted within a hash and not across the document, so the first "Notes" and the first
 *     "Other" are both 1
 * @param chunks what will be embedded, in order. Never empty: a paragraph that derived no chunks
 *     would be stored, be unsearchable, and have nothing say so
 */
public record DerivedParagraph(
    int ordinal, String text, String contentHash, int occurrence, List<Chunker.Chunk> chunks) {

  public DerivedParagraph {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(chunks, "chunks");
    if (ordinal < 1 || occurrence < 1) {
      throw new IllegalArgumentException(
          "a paragraph is at ordinal "
              + ordinal
              + " occurrence "
              + occurrence
              + "; both are counted from 1");
    }
    if (text.isBlank()) {
      throw new IllegalArgumentException("a blank paragraph is not a paragraph");
    }
    if (chunks.isEmpty()) {
      throw new IllegalArgumentException(
          "paragraph "
              + ordinal
              + " derived no chunks, so it would be stored and never"
              + " found");
    }
    chunks = List.copyOf(chunks);
  }
}
