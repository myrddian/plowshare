package io.aeyer.plowshare.server.fetch;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * A page reduced to prose: no network, no database, nothing but markup in and an {@link
 * ExtractedPage} out. That is what makes this the first piece of the fetch slice and the easiest
 * one to test — every other class in it either calls out to something or stores something; this one
 * only reads.
 *
 * <h2>Every newline in the output is one this class chose</h2>
 *
 * <p>{@link #extract} does whitespace collapsing <em>per block, before</em> joining, not on the
 * assembled document afterward. That ordering is the whole point of this class, and it is worth
 * arguing rather than merely stating, because the alternative reads as equivalent and is not.
 *
 * <p>Collapse and join in the other order — build the paragraph text first, <code>
 * String.join("\n\n", blocks)</code>, and normalise the result — and a source line break sitting
 * inside one block's own markup (a hand-wrapped <code>&lt;p&gt;</code> in someone's CMS, a template
 * that indents its output) survives whitespace collapsing exactly as well as the <code>"\n\n"
 * </code> this class inserted between blocks. Nothing downstream can tell the two kinds of newline
 * apart once they are both sitting in the same string, because by then they are both just newlines.
 * Collapse each block on its own <em>first</em>, and the property is structural rather than
 * incidental: every newline that reaches {@link ExtractedPage#text} was inserted by {@link
 * String#join}, because nothing else in this method ever writes one. A later task in this slice
 * quotes a window of that text to a model verbatim — see spec §9 — and it can only do that safely
 * because a line at column zero is never something the page itself was able to forge. {@code
 * a_block_arrives_with_its_own_whitespace_already_collapsed} and {@code
 * blocks_are_separated_by_a_blank_line_so_structure_survives} are the two tests that pin this: the
 * first would still pass under either ordering on simple input, which is exactly why it is not
 * enough on its own to prove the ordering was chosen deliberately — only reading this method
 * settles that.
 *
 * <p>Normalising the whole document in one pass, rather than block by block, fails the same way
 * from the other direction: it has no blocks left to insert separators between, so structure — the
 * fact that a heading and the paragraph under it are different things — is gone before {@link
 * String#join} ever runs.
 *
 * <h2>The six steps, and why each one is here</h2>
 *
 * <ol>
 *   <li>Parse with jsoup, against {@code baseUrl}. The base is unused by anything this class
 *       currently does with the resulting text, but it is what jsoup needs to resolve a relative
 *       {@code href} or {@code src} correctly, and a caller of a page-extraction function is one
 *       that fetched that page from somewhere — the URL is always in hand at the call site, so
 *       there is nothing gained by making this method pretend it might not be.
 *   <li>Strip chrome that is never prose — {@code script}, {@code style}, {@code noscript}, {@code
 *       svg}, {@code nav}, {@code header}, {@code footer}, {@code aside}, {@code form}, {@code
 *       button}, {@code dialog} — before anything else looks at the page, so a scoring step never
 *       gets to credit a sidebar for having a lot of text in it.
 *   <li>Score a content root among the common article-container shapes, preferring whichever
 *       candidate carries the most paragraph text, and fall back to {@code body} when none of them
 *       match. A page that names none of {@code article}, {@code main}, {@code [role=main]}, or one
 *       of the common class-name conventions is still a page an agent asked to read, and returning
 *       nothing because no container happened to be tagged correctly would be a worse failure than
 *       occasionally including a stray line of boilerplate the scoring did not exclude.
 *   <li><b>Walk the block-level tags inside the winner and normalise each one's whitespace on its
 *       own.</b> This is the step argued above.
 *   <li>Collect into a {@link LinkedHashSet} rather than a {@link java.util.List}, so a block
 *       repeated verbatim — the "sign up for our newsletter" paragraph a template stamps into every
 *       article on a site — is kept once, at its first position, instead of once per occurrence.
 *   <li>Join what survives with a blank line between blocks. Two newlines and not one: a single
 *       {@code "\n"} would be indistinguishable from a hard line wrap inside a block that this same
 *       method just finished collapsing away, and this class does not want to reintroduce at the
 *       seam the ambiguity step 4 exists to remove at the block level.
 * </ol>
 *
 * <h2>What "too short to be prose" means here</h2>
 *
 * <p>{@link #MIN_BLOCK_LENGTH} is deliberately small. A block that survives whitespace collapsing
 * to nothing (an empty {@code <p></p>}, or one holding only a non-breaking space) is discarded
 * outright; a block one or two characters long — a bare bullet glyph, a table cell holding only a
 * dash — is discarded as a fragment rather than a sentence. It is not a summariser: a genuinely
 * short heading like a single word is prose and is kept, which is why the threshold sits at the
 * floor of "not obviously noise" rather than anywhere near an editorial judgement about what counts
 * as a real sentence.
 */
public final class PageExtractor {

  /**
   * Elements that are never prose, removed before the content root is even chosen. Ordered as the
   * brief states them; the CSS selector itself does not care about order.
   */
  private static final String CHROME_SELECTOR =
      "script, style, noscript, svg, nav, header, footer, aside, form, button, dialog";

  /**
   * The common shapes of "this is the article" across the sites this extractor will actually meet:
   * a semantic tag first ({@code article}, {@code main}, {@code [role=main]}), then the class-name
   * conventions that predate widespread use of those tags and are still what a great deal of the
   * web ships.
   */
  private static final String CONTENT_ROOT_SELECTOR =
      "article, main, [role=main], "
          + ".article, .article-body, .entry-content, .post-content, .story-body, "
          + ".main-content";

  /** Block-level tags worth reading as a paragraph of prose on their own. */
  private static final String BLOCK_SELECTOR = "h1, h2, h3, p, li, blockquote, figcaption, td";

  /** Any run of whitespace — space, tab, newline — collapses to one space. */
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  /**
   * Below this many characters a block is a fragment (an empty tag, a lone glyph) rather than
   * prose. See the class javadoc for why it is small.
   */
  private static final int MIN_BLOCK_LENGTH = 2;

  private PageExtractor() {}

  /**
   * Reduce {@code html} to its title and its readable prose.
   *
   * <p>Never throws on malformed or empty input — jsoup itself does not, and there is nothing here
   * that could fail on a document jsoup accepted. Markup that yields no title and no blocks (empty
   * input, or input with no text jsoup would call an element) answers with two empty strings via
   * {@link ExtractedPage}'s canonicalising constructor, not with a null or a thrown exception —
   * {@code markup_that_is_not_a_page_yields_empty_text_rather_than_throwing} is the test for
   * exactly that shape.
   *
   * @param html raw markup, from wherever the caller fetched it
   * @param baseUrl the page's own URL, for jsoup's relative-link resolution
   */
  public static ExtractedPage extract(String html, String baseUrl) {
    Document document = Jsoup.parse(html, baseUrl);
    document.select(CHROME_SELECTOR).remove();

    Element root = contentRoot(document);

    Set<String> blocks = new LinkedHashSet<>();
    for (Element block : root.select(BLOCK_SELECTOR)) {
      String normalized = normalizeWhitespace(block.text());
      if (normalized.length() >= MIN_BLOCK_LENGTH) {
        blocks.add(normalized);
      }
    }

    return new ExtractedPage(document.title(), String.join("\n\n", blocks));
  }

  /**
   * The best candidate among {@link #CONTENT_ROOT_SELECTOR}'s matches, by total paragraph text
   * length, or {@code document.body()} when nothing matched at all.
   *
   * <p>Scored by paragraph text rather than by all text, so a candidate that is mostly nested
   * {@code <div>} soup with one real paragraph is not preferred over a smaller, denser article body
   * purely for being bigger — though with the chrome selectors above already stripped, the two
   * measures agree often enough that this is a tie-breaker more than a distinct signal.
   */
  private static Element contentRoot(Document document) {
    Elements candidates = document.select(CONTENT_ROOT_SELECTOR);
    Element best = null;
    int bestScore = -1;
    for (Element candidate : candidates) {
      int score = candidate.select("p").text().length();
      if (score > bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best != null ? best : document.body();
  }

  /** Collapse every run of whitespace to one space, then trim the ends. */
  private static String normalizeWhitespace(String text) {
    return WHITESPACE_RUN.matcher(text).replaceAll(" ").trim();
  }
}
