package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InformationReportTitleTest {
  private static InformationProcessingRepository.Revision source(String name, String kind) {
    return new InformationProcessingRepository.Revision(
        name,
        null,
        "text/markdown",
        null,
        null,
        kind,
        null,
        null,
        null,
        null,
        null,
        java.util.List.of(),
        0,
        0,
        0,
        null,
        false);
  }

  @Test
  void report_heading_is_display_title_without_changing_resource_identity_or_retained_text() {
    String name = "research-00000000-0000-0000-0000-000000000099.md";
    String body = "# Which evidence supports the claim?\n\nOriginal findings.";
    var extracted =
        InformationLifecycle.extract(source(name, "report"), body.getBytes(StandardCharsets.UTF_8));
    assertEquals("Which evidence supports the claim?", extracted.title());
    assertEquals(body, extracted.text());
    var source =
        InformationLifecycle.extract(source(name, "source"), body.getBytes(StandardCharsets.UTF_8));
    assertTrue(source.title().startsWith("research 00000000"));
  }

  @Test
  void report_without_a_heading_keeps_filename_fallback() {
    var extracted =
        InformationLifecycle.extract(
            source("retained-report.md", "report"),
            "Original findings.".getBytes(StandardCharsets.UTF_8));
    assertEquals("retained report", extracted.title());
  }
}
