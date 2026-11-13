package io.aeyer.plowshare.server.fetch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Reduce HTML to readable, ordered text without network or persistence access.
 *
 * <p>Whitespace is collapsed inside each block before blank-line separators are inserted, so a
 * source line wrap cannot masquerade as a paragraph boundary. Headings retain explicit Markdown
 * markers for the structure detector. Tables retain captions, headers and complete rows together;
 * list items and quotations are emitted once rather than again for every nested paragraph tag.
 *
 * <p>Chrome is removed before choosing the article container with the most paragraph text. A page
 * with no recognised article container falls back to its body. Repeated prose blocks are retained
 * only at their first occurrence, but table cells and rows are never deduplicated independently:
 * repeated numbers and empty positions can be meaningful data.
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

  /** Containers own their descendants: a table cell or a paragraph inside a list is not a unit. */
  private static final String BLOCK_SELECTOR =
      "h1, h2, h3, h4, h5, h6, p, li, blockquote, figcaption, table, dl";

  /** Any run of whitespace — space, tab, newline — collapses to one space. */
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

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

    List<String> blocks = new ArrayList<>();
    Set<String> seenProse = new HashSet<>();
    for (Element block : root.select(BLOCK_SELECTOR)) {
      if (insideSelectedBlock(block, root)) continue;
      String normalized = normalizeWhitespace(block.text());
      // Length is not a content filter: even a one-digit value can be meaningful in a table.
      if (normalized.isBlank()) continue;
      if (block.tagName().matches("h[1-6]")) {
        blocks.add("#".repeat(Integer.parseInt(block.tagName().substring(1))) + " " + normalized);
      } else if (block.tagName().equals("table")) {
        String table = tableText(block);
        if (!table.isBlank()) blocks.add(table);
      } else if (seenProse.add(normalized)) {
        blocks.add(normalized);
      }
    }

    return new ExtractedPage(document.title(), String.join("\n\n", blocks));
  }

  /** Select only the outermost semantic block, avoiding parent/child text duplication. */
  private static boolean insideSelectedBlock(Element block, Element root) {
    for (Element parent = block.parent();
        parent != null && parent != root;
        parent = parent.parent()) {
      if (parent.is(BLOCK_SELECTOR)) return true;
    }
    return false;
  }

  /**
   * Keep a table together and label values only when an unambiguous, unspanned header row exists.
   * Spanned layouts retain ordered cells without inventing column associations. Row separators and
   * empty positions survive flattening; repeated values are not boilerplate.
   */
  private static String tableText(Element table) {
    List<String> rows = new ArrayList<>();
    for (Element caption : table.select("caption")) {
      if (caption.closest("table") == table) rows.add(normalizeWhitespace(caption.text()));
    }
    List<String> headers = List.of();
    for (Element row : table.select("tr")) {
      if (row.closest("table") != table) continue;
      List<Element> cells = row.children().stream().filter(cell -> cell.is("th, td")).toList();
      if (cells.isEmpty()) continue;
      List<String> values = cells.stream().map(cell -> normalizeWhitespace(cell.text())).toList();
      boolean spanned =
          cells.stream().anyMatch(cell -> cell.hasAttr("colspan") || cell.hasAttr("rowspan"));
      if (cells.stream().allMatch(cell -> cell.tagName().equals("th"))) {
        headers = spanned ? List.of() : values;
      } else if (spanned) {
        // A row span can shift following rows too. Do not resume inferred column labels.
        headers = List.of();
      } else if (headers.size() == values.size()) {
        List<String> labelled = new ArrayList<>();
        for (int column = 0; column < values.size(); column++) {
          labelled.add(headers.get(column) + ": " + values.get(column));
        }
        values = labelled;
      }
      rows.add(String.join(" | ", values));
    }
    return String.join("; ", rows);
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
