package io.aeyer.plowshare.client.tools;

import io.aeyer.plowshare.client.ServerClient;

/**
 * <b>What a chapter or a section is called, on the one side of the wire that cannot ask the
 * server.</b>
 *
 * <p>The corpus stores a sentinel — {@code __SYNTHETIC_SEGMENT__}, {@code __SYNTHETIC_HEAP__} — in
 * the title of any structural unit its parser invented, and the server's {@code StructuralRef}
 * discards it at the one place that builds a JSON body. So what arrives here is honest: {@code
 * title} is null and {@code synthetic} is true. <b>What arrives here is also not renderable.</b>
 * Anchor reaches the identical JSON and its shell then writes {@code "# " + chapter.title()},
 * printing the four letters "null" as a document's heading and {@code [null]} beside a retrieved
 * passage — the sentinel's whole design is that a bypass is loud, and passing through a JSON null
 * is what makes it quiet again.
 *
 * <p><b>So this is the client's gate, and there is one of it.</b> The server's rule is that every
 * render site goes through {@code StructuralRef} with the degradation as an argument; that type
 * does not cross the module boundary — it is a server class and this is a separate process talking
 * HTTP — so what crosses instead is the discipline: one method here, called by every surface on
 * this side that prints a unit's name, and no surface deriving the rule from the flag itself.
 *
 * <p>The wording is {@code StructuralRef.UNNAMED}'s, repeated rather than shared for the same
 * reason. It is the wording for prose a person or a model reads, where an empty string would look
 * like a rendering fault rather than like a document that named nothing.
 */
public final class Structural {

  /**
   * {@code StructuralRef.UNNAMED} on the server. A second copy of one string because the type it
   * belongs to is not on this side of the wire.
   */
  public static final String UNNAMED = "(unnamed segment)";

  /**
   * A unit the corpus does not hold at all — the paragraph is in no section, so there is no chapter
   * above it either. Deliberately not {@link #UNNAMED}: that means the document named nothing, this
   * means there is nothing to name, and the server keeps the two apart all the way down to its
   * {@code Placement} type.
   */
  public static final String NO_SECTION = "(in no section)";

  private Structural() {}

  /**
   * What to print for one unit.
   *
   * @param unit the chapter or section, or {@code null} for a chunk the hierarchy cannot place
   */
  public static String name(ServerClient.Unit unit) {
    if (unit == null) {
      return NO_SECTION;
    }
    // The flag decides and the string does not, which is the server's own
    // rule: a body that carried the flag and a title anyway is still a unit
    // nobody named, and printing the title would be this renderer deciding
    // it knew better than the gate.
    return unit.synthetic() || unit.title() == null || unit.title().isBlank()
        ? UNNAMED
        : MemoryTools.oneLine(unit.title());
  }

  /**
   * What to print for a chapter of an outline, which carries its fields flat rather than as a
   * {@link ServerClient.Unit}.
   *
   * <p>Two spellings of one rule and not two rules: the wire shape differs because a chapter has
   * sections under it, and this overload exists so that the <em>rule</em> still has one
   * implementation.
   */
  public static String name(String title, boolean synthetic) {
    return name(new ServerClient.Unit(null, title, synthetic, null));
  }
}
