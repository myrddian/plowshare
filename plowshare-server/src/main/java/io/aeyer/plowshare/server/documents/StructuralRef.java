package io.aeyer.plowshare.server.documents;

/**
 * The boundary between "this structural unit has a real, document-owned title that is safe to show"
 * and "this is a parser-invented unit whose stored title is a sentinel that must never reach a
 * model, an API or a reader".
 *
 * <p>Anchor's {@code domain.StructuralRef}, ported with its sealed shape, which is the enforcement:
 *
 * <blockquote>
 *
 * The sealed hierarchy makes the compiler complain if a new variant is ever added and a render site
 * forgets to handle it; grep for {@code .title()} outside this file + the persistence mapper finds
 * any boundary that skipped the helper.
 *
 * </blockquote>
 *
 * <h2>What is ported is the contract, not the implementation</h2>
 *
 * <p>Anchor's V5 says this type "gates every render boundary (prompt assembly, REST DTOs)". In
 * Anchor's tree it gates one: {@code AskService}'s prompt assembly. Four REST controllers, the
 * summariser prompts, and {@code AskService}'s own chunk block each re-derive the rule from the raw
 * flag, with three different degradations between them — {@code null} into JSON, {@code ""} into a
 * prompt, {@code "(unnamed segment)"} into the deliberation.
 *
 * <p><b>All three degradations are legitimate and all three are kept.</b> What is not kept is each
 * site re-deriving the rule: the policy is {@link WhenSynthetic}, passed to {@link #render}, so a
 * call site chooses how to degrade and never chooses whether to. That is fidelity to V5's stated
 * contract rather than to its implementation, which is a divergence and is recorded as one.
 *
 * <p>Plowshare's own rule points the same way. {@code DocumentTools}' "every line at column zero is
 * one this renderer wrote" already governs how uploaded text reaches a model; a parser-invented
 * title is the same class of hazard, and it is worse in one respect — it is the server's own
 * invention wearing the document's voice.
 */
public sealed interface StructuralRef {

  /**
   * A real title, taken verbatim from the source document. Safe to print — under the quoting rules
   * that govern any uploaded text.
   */
  record Named(String title) implements StructuralRef {

    public Named {
      if (title == null || title.isBlank()) {
        throw new IllegalArgumentException(
            "a named unit with no title is not a state the schema allows"
                + " (chapters_a_named_chapter_has_a_title); a unit the parser"
                + " invented is Synthetic and carries no title at all");
      }
    }
  }

  /** A unit the parser invented. It has no title, and the stored sentinel is not one. */
  record Synthetic() implements StructuralRef {}

  /**
   * How a call site degrades when the unit is the parser's invention.
   *
   * <p>Anchor's three, at the three kinds of boundary it has them at. There is deliberately no
   * default: a site that has not decided has not thought about what its output means with a hole in
   * it.
   */
  enum WhenSynthetic {

    /**
     * {@code null} — for a JSON field that may be absent. The reader is told there is no title
     * rather than told a title that is not one.
     */
    OMIT,

    /**
     * {@code ""} — for a prompt slot. Anchor's Policy C, and its reason is specific: "never feed it
     * into a summariser prompt (the model would echo it back into the generated summary,
     * contaminating the downstream context that uses summaries instead of raw text)".
     */
    BLANK,

    /**
     * {@code "(unnamed segment)"} — for prose a person reads, where an empty string would read as a
     * rendering fault rather than as a document that named nothing.
     */
    PLACEHOLDER
  }

  /** What the deliberation calls a unit the document did not name. */
  String UNNAMED = "(unnamed segment)";

  /**
   * The stored row's two columns as one value.
   *
   * <p><b>The flag decides and the string does not.</b> A writer that set {@code is_synthetic} and
   * forgot the sentinel is gated exactly as one that did both, and a document genuinely headed with
   * the sentinel's characters is still a document.
   *
   * @param storedTitle {@code chapters.title} or {@code sections.title}
   * @param synthetic {@code is_synthetic}
   */
  static StructuralRef of(String storedTitle, boolean synthetic) {
    return synthetic ? new Synthetic() : new Named(storedTitle);
  }

  /**
   * What to write into {@code title} for this unit: the document's words, or the sentinel. <b>The
   * one place the sentinel is chosen</b>, and the persistence mapper Anchor's javadoc excepts from
   * its grep.
   */
  default String storedTitle(String sentinel) {
    return switch (this) {
      case Named named -> named.title();
      case Synthetic ignored -> sentinel;
    };
  }

  /**
   * Whether this unit is the parser's invention — {@code is_synthetic}, and the only other thing
   * the persistence mapper asks.
   */
  default boolean isSynthetic() {
    return this instanceof Synthetic;
  }

  /**
   * What this unit is called at a boundary that degrades the given way.
   *
   * @return the document's own title, or the degradation this site chose — which for {@link
   *     WhenSynthetic#OMIT} is {@code null}
   */
  default String render(WhenSynthetic whenSynthetic) {
    return switch (this) {
      case Named named -> named.title();
      case Synthetic ignored ->
          switch (whenSynthetic) {
            case OMIT -> null;
            case BLANK -> "";
            case PLACEHOLDER -> UNNAMED;
          };
    };
  }
}
