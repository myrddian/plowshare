package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParagraphPackingTest {
  @Test
  void eighty_words_is_the_boundary_not_eighty_characters() {
    assertTrue(ParagraphPacking.isShort("longidentifier".repeat(100)));
    assertTrue(ParagraphPacking.isShort(words(79)));
    assertFalse(ParagraphPacking.isShort(words(80)));
    assertFalse(ParagraphPacking.isShort(words(81)));
  }

  @Test
  void short_adjacent_blocks_remain_together_and_a_short_tail_is_not_split_off() {
    assertEquals(
        List.of(words(79) + " " + words(2) + " tail"),
        ParagraphPacking.pack(List.of(words(79), words(2), "tail")));
    assertEquals(
        List.of(words(80), words(80) + " tail"),
        ParagraphPacking.pack(List.of(words(80), words(80), "tail")));
  }

  @Test
  void complete_source_blocks_are_never_cut_or_discarded() {
    String longBlock = words(300);
    assertEquals(
        List.of(longBlock, words(80)), ParagraphPacking.pack(List.of(longBlock, words(80))));
    assertEquals(
        List.of("4.85 Statement Gemini Decision 1"),
        ParagraphPacking.pack(List.of("4.85", "Statement", "Gemini Decision 1")));
    assertEquals(List.of(), ParagraphPacking.pack(List.of()));
  }

  @Test
  void unicode_whitespace_and_supplementary_characters_do_not_change_word_boundaries() {
    assertFalse(ParagraphPacking.isShort(("数字\u00a0🙂\u2003").repeat(40)));
    assertTrue(ParagraphPacking.isShort("数字🙂"));
  }

  static String words(int count) {
    return ("word ".repeat(count)).strip();
  }
}
