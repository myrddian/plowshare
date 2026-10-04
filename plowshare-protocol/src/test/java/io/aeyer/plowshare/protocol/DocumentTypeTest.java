package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DocumentTypeTest {
  @Test
  void source_language_is_a_filename_inference_and_prose_stays_separate() {
    assertEquals(
        new DocumentType("code", "typescript"),
        DocumentType.classify("C:\\repo\\Component.TSX", "text/plain"));
    assertEquals(
        new DocumentType("code", "java"), DocumentType.classify("/work/src/Main.java", null));
    assertEquals("documents", DocumentType.classify("design.md", null).corpus());
    assertEquals(
        new DocumentType("document", "pdf"), DocumentType.classify("Main.java", "application/pdf"));
    assertEquals(
        new DocumentType("document", "html"),
        DocumentType.classify("Main.java", "text/html; charset=utf-8"));
    assertEquals(
        new DocumentType("document", "text"), DocumentType.classify("unknown.extension", null));
  }
}
