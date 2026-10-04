package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.documents.StructuralRef;
import java.util.UUID;

/**
 * One chapter or section as a reader of this API sees it.
 *
 * <p><b>The only place a {@link StructuralRef} becomes JSON on this server</b>, and a type of its
 * own for exactly that reason. Three routes render a structural unit — a retrieve hit, a chunk read
 * back, a document's outline — and Anchor renders the same thing at four controllers with four
 * inline ternaries over the raw flag. §3.2's decision is that every render site goes through the
 * gate with the policy as an argument; the sharpest form of that is for there to be one site.
 *
 * <p><b>{@link #title} is {@code null} exactly when {@link #synthetic} is true.</b> That pairing is
 * not written here as a branch: {@link StructuralRef#render} produces the first and {@link
 * StructuralRef#isSynthetic} the second, from one value that already knows which it is. {@link
 * StructuralRef.WhenSynthetic#OMIT} is the policy this boundary chose, and its own javadoc says
 * what it buys — the reader is told there is no title rather than told a title that is not one.
 *
 * <p><b>A null is not the end of the problem, and that is worth stating where the null is made.</b>
 * Anchor's controllers reach the same JSON by ternary, and Anchor's shell then prints it: {@code "#
 * " + chapter.title()} renders a synthetic chapter as the four letters "null" in a document's
 * outline, and a retrieve hit as {@code [null]}. The sentinel's whole design is that a bypass is
 * loud, and passing through JSON as {@code null} is what makes it quiet again. So every renderer
 * downstream of this record is expected to name the absence in its own words — {@code
 * StructuralRef.UNNAMED} is the wording for prose a person reads — and the CLI and MCP surfaces in
 * {@code plowshare-client} do.
 *
 * @param id the row, which is present whether or not the unit has a name
 * @param title the document's own words, or {@code null} for a unit the parser invented
 * @param synthetic whether the parser invented this unit. <b>The field that carries the meaning</b>
 *     when {@code title} is null, and the reason a null title is not ambiguous on the wire
 * @param summary what the cascade says this unit covers, or {@code null} for one it has not reached
 */
public record UnitView(UUID id, String title, boolean synthetic, String summary) {

  /** The gate. Nothing else in this package derives the rule from the flag. */
  public static UnitView of(UUID id, StructuralRef ref, String summary) {
    return new UnitView(
        id, ref.render(StructuralRef.WhenSynthetic.OMIT), ref.isSynthetic(), summary);
  }
}
