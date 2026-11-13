package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.fetch.PageExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The extraction/derivation seam must keep the context that isolated labels and cells lost. */
class HtmlParagraphDerivationTest {
  @Test
  void a_short_release_listing_stays_together_under_its_heading() {
    var page =
        PageExtractor.extract(
            """
        <main><h1>The model release tracker</h1>
        <p>Gemini Decision 1</p><p>🇺🇸 DeepMind</p>
        <p>Bielik 11B v3.1</p><p>🇵🇱 SpeakLeash</p>
        <h2>Weekly digest</h2><p>The week's releases, once a week</p>
        <p>A Monday email of releases, updates and retirements.</p></main>
        """,
            "https://models.example");
    var document = derive(page.text());
    assertEquals(
        List.of(
            "Gemini Decision 1 🇺🇸 DeepMind Bielik 11B v3.1 🇵🇱 SpeakLeash",
            "The week's releases, once a week A Monday email of releases, updates and retirements."),
        document.paragraphs().stream().map(DerivedParagraph::text).toList());
    assertTrue(document.paragraphs().stream().allMatch(p -> ParagraphPacking.isShort(p.text())));
    assertEquals(
        List.of("The model release tracker", "Weekly digest"),
        document.chapters().getFirst().sections().stream()
            .map(section -> section.title().render(StructuralRef.WhenSynthetic.BLANK))
            .toList());
  }

  @Test
  void a_short_table_is_one_processing_paragraph_with_the_column_context_intact() {
    var page =
        PageExtractor.extract(
            """
        <h2>Cash rate decisions</h2><table>
        <tr><th>Month</th><th>Change</th><th>Target</th></tr>
        <tr><td>November</td><td>+0.25</td><td>4.85</td></tr>
        <tr><td>October</td><td>0.00</td><td>4.60</td></tr></table>
        """,
            "https://rates.example");
    var document = derive(page.text());
    assertEquals(1, document.paragraphs().size());
    assertTrue(
        document
            .paragraphs()
            .getFirst()
            .text()
            .contains("Month: November | Change: +0.25 | Target: 4.85"));
    assertTrue(ParagraphPacking.isShort(document.paragraphs().getFirst().text()));
  }

  @Test
  void paragraph_packing_stops_at_sections_even_when_the_entire_document_is_short() {
    var document =
        derive(
            "## First topic\nA short claim.\n\nAnother claim.\n\n"
                + "## Second topic\nAn unrelated claim.");
    assertEquals(
        List.of("A short claim. Another claim.", "An unrelated claim."),
        document.paragraphs().stream().map(DerivedParagraph::text).toList());
  }

  private static DerivedDocument derive(String text) {
    return Derivation.derive(
        new Extracted("Fixture", "hash", text, List.of(), "html-fixture"), ShippedChunking.SHIPPED);
  }
}
