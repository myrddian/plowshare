package io.aeyer.plowshare.server.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One derivation of one document: what it calls its parts, the parts, and the bibliography the
 * parts threw away.
 *
 * <p>Anchor's {@code ParsedTypes.ParsedDocument}, with two shape changes and no change of content.
 * It carries no title and no content hash — those are facts about the upload that {@link Extracted}
 * already holds, and copying them here would make a derivation look like a second answer to "what
 * document is this" — and its paragraphs carry the three facts the identity rule is written over,
 * which Anchor's do not because Anchor has no identity rule.
 *
 * <h2>The tree and the run are the same paragraphs</h2>
 *
 * <p>{@link #paragraphs()} is a view: it walks the chapters and their sections in order and hands
 * back the same {@link DerivedParagraph} objects the tree holds. <b>A document's paragraph ordinals
 * run across the whole document</b> — V18's {@code paragraphs.ordinal} is a position in a document
 * and this migration did not change it — so the flattening is already in ordinal order, and a
 * section's paragraphs are a contiguous run of it. {@code DocumentStore} relies on that contiguity
 * to point a whole section's paragraphs at their row in one statement.
 */
public record DerivedDocument(
    Vocabulary vocabulary, List<Chapter> chapters, List<ReferencesExtractor.Reference> references) {

  public DerivedDocument {
    Objects.requireNonNull(vocabulary, "vocabulary");
    chapters = List.copyOf(Objects.requireNonNull(chapters, "chapters"));
    references = List.copyOf(Objects.requireNonNull(references, "references"));
  }

  /**
   * Every paragraph of this document, in document order — the run V18's {@code paragraphs} table
   * stores and the identity rule is applied over.
   */
  public List<DerivedParagraph> paragraphs() {
    List<DerivedParagraph> all = new ArrayList<>();
    for (Chapter chapter : chapters) {
      for (Section section : chapter.sections()) {
        all.addAll(section.paragraphs());
      }
    }
    return List.copyOf(all);
  }

  /**
   * One chapter and the sections under it.
   *
   * @param ordinal where in the document, from 1 and dense over what survived detection
   * @param title the document's own heading, or {@link StructuralRef.Synthetic} for the chapter the
   *     parser invented over a document that declared none. <b>Not a string</b>: the sentinel is
   *     chosen once, by the persistence mapper, and nothing else can render past this type without
   *     saying how it degrades
   * @param sections in order. May be empty, for a chapter whose heading is immediately followed by
   *     the next one
   */
  public record Chapter(int ordinal, StructuralRef title, List<Section> sections) {

    public Chapter {
      Objects.requireNonNull(title, "title");
      sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
      if (ordinal < 1) {
        throw new IllegalArgumentException(
            "chapters are counted from 1, and this one is at " + ordinal);
      }
    }
  }

  /**
   * One section and the paragraphs under it.
   *
   * @param ordinal where in its chapter, from 1
   * @param title the document's own heading, or {@link StructuralRef.Synthetic}
   * @param paragraphs in order, and a contiguous run of the document's ordinals. May be empty, for
   *     a heading with nothing under it
   */
  public record Section(int ordinal, StructuralRef title, List<DerivedParagraph> paragraphs) {

    public Section {
      Objects.requireNonNull(title, "title");
      paragraphs = List.copyOf(Objects.requireNonNull(paragraphs, "paragraphs"));
      if (ordinal < 1) {
        throw new IllegalArgumentException(
            "sections are counted from 1, and this one is at " + ordinal);
      }
    }
  }
}
