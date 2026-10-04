package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PageExtractorTest {

  private static String page(String body) {
    return "<html><head><title>A Title</title></head><body>" + body + "</body></html>";
  }

  @Test
  void the_title_comes_from_the_head() {
    assertEquals("A Title", PageExtractor.extract(page("<p>one</p>"), "https://e.example").title());
  }

  @Test
  void chrome_is_stripped_before_anything_else_looks_at_the_page() {
    // NAVIGATION/MASTHEAD/FOOTER are nested INSIDE the article — the
    // winning content root — each wrapped in its own <p>, and that
    // placement is load-bearing twice over:
    //
    //   - a bare <nav>NAVIGATION</nav> is never surfaced by
    //     BLOCK_SELECTOR regardless of stripping, because <nav> is not
    //     one of the block-level tags the walk selects; wrapping it in a
    //     <p> makes it a block the walk WOULD find if the strip step did
    //     not remove the <nav> around it first;
    //   - a <nav>/<header>/<footer> placed OUTSIDE the article is never
    //     reached either, because the block walk runs on
    //     `root.select(...)`, scoped to the content root, and never
    //     revisits the whole document. Only a chrome element sitting
    //     WITHIN the winning root can prove the strip step actually ran,
    //     which is why it lives inside <article> here rather than beside
    //     it.
    //
    // The <script> assertion below is the one exception, kept as a
    // statement of intent rather than a live check: jsoup 1.18.1's
    // Element.text() never returns a <script> element's payload —
    // `new Element("script").text()` is "", the text lives in
    // `.data()` instead — so "SCRIPT" cannot appear in this extractor's
    // output whether or not CHROME_SELECTOR strips the tag. Removing
    // `script` from that selector would still be a real regression on
    // this codebase's terms (defence in depth against a future change
    // that reads more than block text), but this assertion is not able
    // to catch it today, and no fixture shape changes that.
    String html =
        page(
            "<article>"
                + "<nav><p>NAVIGATION</p></nav>"
                + "<header><p>MASTHEAD</p></header>"
                + "<p>the actual sentence</p>"
                + "<footer><p>FOOTER</p></footer>"
                + "<script>var x = 'SCRIPT';</script>"
                + "</article>");
    String text = PageExtractor.extract(html, "https://e.example").text();
    assertTrue(text.contains("the actual sentence"));
    assertFalse(text.contains("NAVIGATION"));
    assertFalse(text.contains("MASTHEAD"));
    assertFalse(text.contains("FOOTER"));
    assertFalse(text.contains("SCRIPT"));
  }

  @Test
  void the_content_root_wins_over_boilerplate_outside_it() {
    String html =
        page(
            "<div><p>stray</p></div>"
                + "<article><p>first paragraph of the article</p>"
                + "<p>second paragraph of the article</p></article>");
    String text = PageExtractor.extract(html, "https://e.example").text();
    assertTrue(text.contains("first paragraph of the article"));
    assertTrue(text.contains("second paragraph of the article"));
    // Presence alone does not pin the content-root choice: a wrong fallback
    // to document.body() would walk the stray <div> too and still contain
    // both article paragraphs. This is the assertion that actually fails
    // when the root selection is wrong.
    assertFalse(text.contains("stray"));
  }

  @Test
  void a_block_arrives_with_its_own_whitespace_already_collapsed() {
    // This does NOT by itself discriminate normalise-then-join from
    // join-then-normalise: jsoup's own Element.text() already collapses a
    // block's internal \n and \t to single spaces before this extractor's
    // normalizeWhitespace ever runs, and this fixture is a single block, so
    // there is no "\n\n" separator here for a wrong ordering to corrupt.
    // The assertion below would hold under either ordering, or even with
    // normalizeWhitespace deleted outright.
    //
    // What it does pin: a block handed downstream is prose, not markup —
    // nobody downstream has to re-collapse whitespace to read it. The test
    // that actually discriminates the ordering is
    // blocks_are_separated_by_a_blank_line_so_structure_survives, whose
    // expected string has exactly one "\n\n" per block boundary and would
    // read "Heading first second" (no newlines at all) if collapsing ran on
    // the whole joined document instead of per block.
    String html = page("<article><p>one\nline\tbroken\n\nacross source lines</p></article>");
    String text = PageExtractor.extract(html, "https://e.example").text();
    assertTrue(
        text.contains("one line broken across source lines"),
        "a block's whitespace arrives collapsed to single spaces, whichever "
            + "step collapsed it. Got: "
            + text);
  }

  @Test
  void blocks_are_separated_by_a_blank_line_so_structure_survives() {
    String html = page("<article><h1>Heading</h1><p>first</p><p>second</p></article>");
    assertEquals(
        "Heading\n\nfirst\n\nsecond", PageExtractor.extract(html, "https://e.example").text());
  }

  @Test
  void an_identical_block_repeated_is_kept_once() {
    String html =
        page(
            "<article><p>repeated boilerplate</p><p>real content</p>"
                + "<p>repeated boilerplate</p></article>");
    assertEquals(
        "repeated boilerplate\n\nreal content",
        PageExtractor.extract(html, "https://e.example").text());
  }

  @Test
  void a_page_with_no_recognisable_root_falls_back_rather_than_returning_nothing() {
    String text =
        PageExtractor.extract(
                page("<p>bare paragraph, no article element</p>"), "https://e.example")
            .text();
    assertTrue(text.contains("bare paragraph"));
  }

  @Test
  void markup_that_is_not_a_page_yields_empty_text_rather_than_throwing() {
    assertEquals("", PageExtractor.extract("", "https://e.example").text());
  }
}
