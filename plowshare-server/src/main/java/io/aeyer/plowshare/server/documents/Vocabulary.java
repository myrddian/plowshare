package io.aeyer.plowshare.server.documents;

import java.util.Locale;

/**
 * <b>What the source document calls its own top-level groupings.</b>
 *
 * <p>Anchor's {@code ChapterDetector.Vocabulary}, a top-level enum here because it is stored in a
 * column of its own ({@code documents.top_level_label}) and read by prompts that have no business
 * importing a detector. The values and every derived spelling port unchanged, as does the reason it
 * exists:
 *
 * <blockquote>
 *
 * Used by the deliberation prompts so we don't tell the model "YOUR CHAPTERS" when the paper itself
 * uses "Section". Mismatch was the root cause of deliberation outputs that read like fabrication:
 * the model was correctly citing our internal labels but those labels contradicted the document's
 * own self-references.
 *
 * </blockquote>
 *
 * <p>That failure is exactly the one this repository's untrusted-content rule is about seen from
 * the other side. A model handed a document and told its parts are "chapters", when every
 * cross-reference inside the document says "section", has been given two accounts of one structure
 * and no way to tell which is the server's. It resolves that by writing prose a reader can check
 * against the paper and find wrong.
 *
 * <p>The name of this enum's constants is the stored value, and V26's {@code
 * documents_top_level_label_is_one_of_three} is a CHECK over exactly the three — so a fourth is a
 * migration and a prompt change together rather than a string nothing refuses.
 */
public enum Vocabulary {

  /** A book. Its parts are chapters and theirs are sections. */
  CHAPTER,

  /**
   * An academic paper. Its parts are sections and theirs are subsections — which is the distinction
   * that lets a prompt say "subsection 2.3" and mean what the paper means by it.
   */
  SECTION,

  /**
   * A document in parts.
   *
   * <p>Its mid level is "section" and not "chapter", and Anchor says why: "we don't model the
   * part→chapter→section hierarchy fully — the part-document's middle level collapses to 'section'
   * in our two-level-only schema." The schema here is two-level for the same reason, so the
   * collapse ports with it.
   */
  PART;

  public String singular() {
    return name().toLowerCase(Locale.ROOT);
  }

  public String plural() {
    return singular() + "s";
  }

  public String singularCap() {
    return name().charAt(0) + singular().substring(1);
  }

  public String pluralCap() {
    return singularCap() + "s";
  }

  /** What the document calls the level <em>below</em> its top-level groupings. */
  public String midLevel() {
    return switch (this) {
      case CHAPTER, PART -> "section";
      case SECTION -> "subsection";
    };
  }

  public String midLevelPlural() {
    return midLevel() + "s";
  }

  public String midLevelPluralCap() {
    return Character.toUpperCase(midLevel().charAt(0)) + midLevelPlural().substring(1);
  }
}
