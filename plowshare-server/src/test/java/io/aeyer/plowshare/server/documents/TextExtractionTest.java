package io.aeyer.plowshare.server.documents;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What this server will turn into a corpus, and what it refuses to.
 *
 * <p>The refusals are the interesting half. This server extracts text-like formats and PDF — no
 * Tika — so every other format has to be turned away by a message that says <em>what to do about
 * it</em> rather than by a decoder failure several frames down. The PDF path is {@link
 * PdfExtractionTest}.
 */
class TextExtractionTest {

  private static byte[] utf8(String text) {
    return text.getBytes(UTF_8);
  }

  // --- what it accepts -----------------------------------------------------

  @Test
  void utf8_text_comes_back_as_itself() {
    Extracted extracted = TextExtraction.extract("notes.md", utf8("One.\n\nTwo.\n"));

    assertEquals("One.\n\nTwo.\n", extracted.text());
  }

  @Test
  void a_title_is_derived_from_the_name_when_the_document_carries_none() {
    assertEquals(
        "retry budget notes", TextExtraction.extract("retry_budget-notes.md", utf8("x")).title());
    assertEquals("README", TextExtraction.extract("README", utf8("x")).title());
  }

  /**
   * Windows line endings become newlines, because paragraph splitting is "a blank line is a break"
   * and a blank line ending in CR is not blank to anything that has not been told about it.
   */
  @Test
  void line_endings_are_one_thing_by_the_time_a_paragraph_splitter_sees_them() {
    Extracted extracted = TextExtraction.extract("crlf.txt", utf8("One.\r\n\r\nTwo.\r\n"));

    assertEquals("One.\n\nTwo.\n", extracted.text());
  }

  /** A byte-order mark is not the first character of the first paragraph. */
  @Test
  void a_byte_order_mark_does_not_become_part_of_the_text() {
    Extracted extracted = TextExtraction.extract("bom.txt", utf8("﻿The first word."));

    assertEquals("The first word.", extracted.text());
  }

  /**
   * The hash is over the bytes as they arrived, before any of the normalisation above.
   *
   * <p>It is the dedup gate — the same upload twice is not a second ingest — so it has to be a fact
   * about what was sent rather than about what this class made of it.
   */
  @Test
  void the_content_hash_is_sha256_of_the_bytes_that_arrived() {
    // echo -n "abc" | shasum -a 256
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        TextExtraction.extract("abc.txt", utf8("abc")).contentHash());
  }

  /**
   * A text upload says that nothing converted it, and carries no outline.
   *
   * <p>The two are the same statement from different sides: there is no structure to lift out of a
   * text file, and an offset into this text <em>is </em> a position in the bytes that were sent —
   * which is true here and of nothing that came through a converter.
   */
  @Test
  void text_records_that_nothing_converted_it_and_offers_no_outline() {
    Extracted extracted = TextExtraction.extract("notes.md", utf8("One.\n\nTwo.\n"));

    assertEquals(Extracted.NOT_CONVERTED, extracted.converter());
    assertEquals(List.of(), extracted.outlineTopLevel());
  }

  /** Two different names over the same bytes hash the same; the name is not part of the content. */
  @Test
  void the_name_is_not_part_of_the_content_hash() {
    assertEquals(
        TextExtraction.extract("one.txt", utf8("same")).contentHash(),
        TextExtraction.extract("other.md", utf8("same")).contentHash());
  }

  // --- what it refuses, and how ---------------------------------------------

  /**
   * A PDF is not refused any more — {@link PdfExtractionTest} owns that whole path — but a
   * PDF-shaped fragment that is not a document still is, and it is refused as a <em>damaged
   * document</em> rather than as a format.
   *
   * <p>This is the assertion that used to say conversion belonged elsewhere. What survives of it is
   * the part that still matters: the message names the document and says PDF, because "not valid
   * UTF-8" for a PDF sends whoever reads it to look at an encoding.
   */
  @Test
  void a_pdf_that_is_not_a_document_is_refused_as_a_document_and_not_as_a_format() {
    byte[] fragment = "%PDF-1.7\n%âãÏÓ\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1);

    UnreadableDocumentException refused =
        assertThrows(
            UnreadableDocumentException.class, () -> TextExtraction.extract("paper.pdf", fragment));

    assertTrue(refused.getMessage().contains("PDF"), refused.getMessage());
    assertTrue(refused.getMessage().contains("paper.pdf"), refused.getMessage());
  }

  /**
   * DOCX, EPUB, ODT and every other zip container answer with one name, because from here they are
   * one thing: a container this slice does not open.
   */
  @Test
  void a_zip_container_is_refused_as_one() {
    byte[] docx = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};

    UnreadableDocumentException refused =
        assertThrows(
            UnreadableDocumentException.class, () -> TextExtraction.extract("report.docx", docx));

    assertTrue(
        refused.getMessage().toLowerCase(java.util.Locale.ROOT).contains("zip"),
        refused.getMessage());
  }

  @Test
  void bytes_that_are_not_utf8_are_refused_rather_than_decoded_into_question_marks() {
    // 0xFF is not a legal UTF-8 lead byte anywhere.
    byte[] latin1 = {(byte) 0x54, (byte) 0x68, (byte) 0xE9, (byte) 0xFF};

    UnreadableDocumentException refused =
        assertThrows(
            UnreadableDocumentException.class, () -> TextExtraction.extract("mystery.txt", latin1));

    assertTrue(refused.getMessage().contains("UTF-8"), refused.getMessage());
    assertTrue(refused.getMessage().contains("mystery.txt"), refused.getMessage());
  }

  /**
   * A NUL byte decodes as a legal UTF-8 character and means the file is not text.
   *
   * <p>Without this a UTF-16 document, or any binary that happens to avoid an illegal lead byte, is
   * accepted and becomes a corpus of mojibake that nothing downstream can tell from prose.
   */
  @Test
  void a_nul_byte_means_this_is_not_text() {
    byte[] utf16ish = {0x54, 0x00, 0x68, 0x00, 0x65, 0x00};

    UnreadableDocumentException refused =
        assertThrows(
            UnreadableDocumentException.class, () -> TextExtraction.extract("wide.txt", utf16ish));

    assertTrue(refused.getMessage().contains("NUL"), refused.getMessage());
  }

  @Test
  void an_empty_upload_is_refused() {
    UnreadableDocumentException refused =
        assertThrows(
            UnreadableDocumentException.class,
            () -> TextExtraction.extract("empty.txt", new byte[0]));

    assertTrue(refused.getMessage().contains("empty.txt"), refused.getMessage());
  }

  @Test
  void a_document_with_no_name_is_refused() {
    assertThrows(
        UnreadableDocumentException.class, () -> TextExtraction.extract("   ", utf8("text")));
    assertThrows(
        UnreadableDocumentException.class, () -> TextExtraction.extract(null, utf8("text")));
  }

  /**
   * A name that is all whitespace once the extension is off still has to produce a title, or a
   * document arrives with none.
   */
  @Test
  void a_name_that_derives_no_title_falls_back_to_the_name_itself() {
    assertEquals(".gitignore", TextExtraction.extract(".gitignore", utf8("x")).title());
  }
}
