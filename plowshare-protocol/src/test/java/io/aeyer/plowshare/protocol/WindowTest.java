package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The window arithmetic, and the boundaries where the two limits meet.
 *
 * <h2>What is actually at stake in these tests</h2>
 *
 * <p>Most of them are about {@link Span#stoppedBy()} rather than about the lines. A window that
 * returned its whole line allowance and a window that ran out of file hand back the same shape —
 * some lines, and a stop — and they call for opposite next moves. So the cases that earn their
 * place here are the ones where the two are one line apart: a line allowance exactly consumed by
 * the last lines of a file, a byte ceiling reached on the file's final line, and the two limits
 * landing on the same line together.
 *
 * <h2>The fixtures are built from the constants, not from numbers</h2>
 *
 * <p>{@link Window#MAX_WINDOW_BYTES} is a decision that can move. A fixture spelling its value
 * would keep passing while measuring nothing the day it does, so every byte-ceiling fixture here is
 * sized from the constant and the cost rule — a line's UTF-8 length plus one separator — and stays
 * a boundary case wherever the constant goes.
 */
class WindowTest {

  private static final List<String> TEN =
      List.of("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10");

  @Test
  void a_window_inside_the_file_returns_exactly_its_lines_and_says_more_remains() {
    Span span = Window.of(2, 3).cut(TEN);
    assertEquals(List.of("l3", "l4", "l5"), span.lines());
    assertEquals(2, span.offset());
    assertEquals(10, span.totalLines());
    assertTrue(span.more(), "seven lines are still unread");
    assertEquals(Span.LINES, span.stoppedBy());
  }

  @Test
  void a_window_reaching_the_last_line_says_nothing_remains() {
    Span span = Window.of(7, 5).cut(TEN);
    assertEquals(List.of("l8", "l9", "l10"), span.lines());
    assertFalse(span.more(), "the file ended inside the window");
    assertEquals(
        Span.END, span.stoppedBy(), "the end of the file stopped this, not the line limit");
  }

  @Test
  void a_line_allowance_exactly_consumed_by_the_last_lines_still_says_end() {
    // The case one line away from the test above, and the one a model gets
    // wrong: five lines asked for, five lines returned, and nothing left. A
    // window that reported LINES here would be paged again for nothing, and
    // an implementation that decided by `taken.size() == limit` alone would
    // do exactly that.
    Span span = Window.of(5, 5).cut(TEN);
    assertEquals(List.of("l6", "l7", "l8", "l9", "l10"), span.lines());
    assertFalse(span.more());
    assertEquals(Span.END, span.stoppedBy(), "the allowance was spent and the file ran out");
  }

  @Test
  void an_offset_past_the_end_is_empty_and_is_not_an_error() {
    // A model paging forward reaches this exactly once, and it is how it
    // learns to stop. An exception here would cost it a turn to discover
    // what an empty answer tells it for free.
    Span span = Window.of(50, 5).cut(TEN);
    assertTrue(span.lines().isEmpty());
    assertFalse(span.more());
    assertEquals(10, span.totalLines(), "the total is still the truth about the file");
    assertEquals(Span.END, span.stoppedBy());
  }

  @Test
  void an_offset_exactly_at_the_end_is_the_same_empty_answer() {
    // The offset a model that paged correctly actually arrives at, as
    // opposed to the wild one above: the line after the last.
    Span span = Window.of(10, 5).cut(TEN);
    assertTrue(span.lines().isEmpty());
    assertFalse(span.more());
    assertEquals(Span.END, span.stoppedBy());
  }

  @Test
  void the_byte_ceiling_stops_a_window_the_line_limit_would_not() {
    // One line that spends the whole ceiling by itself: the line limit says
    // five, the bytes say one.
    String wide = "x".repeat(Window.MAX_WINDOW_BYTES);
    Span span = Window.of(0, 5).cut(List.of(wide, "small", "small"));
    assertEquals(1, span.lines().size());
    assertEquals(
        Span.BYTES, span.stoppedBy(), "the caller must be able to tell which limit cut this");
    assertTrue(span.more());
  }

  @Test
  void the_byte_ceiling_stops_before_the_line_that_would_cross_it() {
    // The ordinary shape of a byte-cut window, and the one the test above
    // does not reach: many modest lines, stopped by the first one that will
    // not fit rather than by one that overspends on its own. Two lines, each
    // a little over half the ceiling.
    String half = "x".repeat(Window.MAX_WINDOW_BYTES / 2);
    Span span = Window.of(0, 500).cut(List.of(half, half, half));
    assertEquals(1, span.lines().size(), "the second line would have crossed the ceiling");
    assertEquals(Span.BYTES, span.stoppedBy());
    assertTrue(span.more());
  }

  @Test
  void a_lines_cost_is_its_bytes_and_not_its_characters() {
    // Two lines that fit the ceiling counted as characters and do not fit
    // counted as UTF-8. The ceiling is a transport bound, so characters are
    // the wrong unit and an implementation using String.length would carry
    // roughly twice what it promised.
    String accented = "é".repeat(Window.MAX_WINDOW_BYTES / 3);
    Span span = Window.of(0, 500).cut(List.of(accented, accented));
    assertEquals(
        1,
        span.lines().size(),
        "two of these are two thirds of the ceiling in characters and four thirds"
            + " of it in bytes");
    assertEquals(Span.BYTES, span.stoppedBy());
  }

  @Test
  void a_single_line_over_the_ceiling_refuses_the_read_rather_than_returning_it() {
    // The behaviour this replaced returned such a line whole, on the
    // argument that a window returning nothing while reporting more is a
    // loop a model cannot get out of. It is — and a refusal is not a window,
    // so it is not in the loop. What returning it cost was measured: a
    // two-million-character line is a frame no affordable buffer carries,
    // the container closes 1009 and the session goes with it, so the choice
    // was between an unreadable file and a fatal one.
    String huge = "x".repeat(Window.MAX_WINDOW_BYTES * 2);

    Window.LineTooWide refused =
        assertThrows(Window.LineTooWide.class, () -> Window.of(0, 5).cut(List.of(huge, "after")));

    assertEquals(0, refused.offset(), "which line, in the coordinate offset is written in");
    assertEquals(
        Window.MAX_WINDOW_BYTES * 2L,
        refused.bytes(),
        "and how big it is, so the sentence can say why no limit reaches it");
  }

  @Test
  void the_refusal_names_the_line_its_size_and_the_search_that_still_works() {
    // The three facts a model can act on. The size beside the ceiling is
    // what says this is not a transient shortage and no smaller limit helps;
    // the tool name is what turns the refusal into a move rather than a dead
    // end. FileToolsTest holds the cross-module half of that name, since
    // nothing in this module can see the constant that spells it.
    String huge = "x".repeat(Window.MAX_WINDOW_BYTES + 1);

    Window.LineTooWide refused =
        assertThrows(Window.LineTooWide.class, () -> Window.of(0, 5).cut(List.of(huge)));

    String said = refused.getMessage();
    assertTrue(said.contains("line 0 of this file"), said);
    assertTrue(
        said.contains(String.valueOf(Window.MAX_WINDOW_BYTES + 1)),
        "the line's own size, or a model cannot tell how far past the bound it is — " + said);
    assertTrue(
        said.contains(String.valueOf(Window.MAX_WINDOW_BYTES)),
        "and the bound it is past — " + said);
    assertTrue(
        said.contains("file_grep"),
        "a refusal that only says no costs a turn and teaches nothing — " + said);
    assertTrue(
        said.contains(String.valueOf(Needle.MAX_LINE_CHARS)),
        "and it says what that search hands back, which is not the whole line — " + said);
  }

  @Test
  void a_line_of_exactly_the_ceiling_is_carried_and_not_refused() {
    // The boundary the refusal is drawn on, and it is drawn on the line's
    // own bytes rather than on its cost. A line of exactly the ceiling is
    // over budget only by the separator this arithmetic charges and the
    // frame does not hold, so refusing it would refuse a line that fits.
    String exact = "x".repeat(Window.MAX_WINDOW_BYTES);

    Span span = Window.of(0, 5).cut(List.of(exact, "after"));

    assertEquals(List.of(exact), span.lines());
    assertEquals(Span.BYTES, span.stoppedBy());
    assertTrue(span.more());
  }

  @Test
  void an_oversized_line_further_down_stops_the_window_in_front_of_it() {
    // The half of the rule that is not a refusal, and the case the old
    // behaviour got wrong twice: it stopped here too, and then killed the
    // session on the very next page. Now the lines before it are read
    // normally and the refusal waits at the line that cannot be carried, so
    // a caller loses only what no window could have delivered.
    String huge = "x".repeat(Window.MAX_WINDOW_BYTES * 2);
    List<String> file = List.of("first", "second", huge, "after");

    Span span = Window.of(0, 5).cut(file);

    assertEquals(
        List.of("first", "second"),
        span.lines(),
        "everything up to the line that will not fit, which is what any line too big"
            + " for the remaining budget does");
    assertEquals(Span.BYTES, span.stoppedBy());
    assertTrue(span.more());

    // Paging on from where that window ended is where the refusal is. The
    // two answers are one rule rather than two: a window refuses only when
    // it has nothing else to return instead.
    Window.LineTooWide refused =
        assertThrows(
            Window.LineTooWide.class,
            () -> Window.of(span.offset() + span.lines().size(), 5).cut(file));
    assertEquals(
        2,
        refused.offset(),
        "and it names the line the caller paged to, not the one it started at");
  }

  @Test
  void a_byte_ceiling_reached_on_the_files_last_line_says_end_and_not_bytes() {
    // The one case where the ceiling stops the loop and there is genuinely
    // nothing after it. Reporting BYTES here would be true about the loop
    // and a lie about the file, and it is the reply a model would page
    // again on.
    //
    // Exactly the ceiling rather than past it: past it is now a refusal, and
    // this branch is about a window that came back.
    String huge = "x".repeat(Window.MAX_WINDOW_BYTES);
    Span span = Window.of(0, 5).cut(List.of(huge));
    assertEquals(1, span.lines().size());
    assertFalse(span.more());
    assertEquals(
        Span.END, span.stoppedBy(), "the ceiling stopped the loop; the file stopped the answer");
  }

  @Test
  void both_limits_reached_together_is_reported_as_the_byte_ceiling() {
    // Three lines that spend the ceiling exactly and exhaust the allowance
    // in the same step. Both answers are true, and they are not equally
    // useful: BYTES says a wider `limit` would not have helped, which is the
    // fact the caller needs. LINES would invite a request that comes back
    // the same size.
    String third = "x".repeat(Window.MAX_WINDOW_BYTES / 3 - 1);
    Span span = Window.of(0, 3).cut(List.of(third, third, third, "after"));
    assertEquals(3, span.lines().size());
    assertTrue(span.more());
    assertEquals(
        Span.BYTES, span.stoppedBy(), "a wider line limit would not have carried a fourth line");
  }

  @Test
  void a_limit_above_the_cap_is_brought_down_to_it_rather_than_refused() {
    assertEquals(Window.MAX_WINDOW_LINES, Window.of(0, Window.MAX_WINDOW_LINES * 10).limit());
  }

  @Test
  void a_limit_above_the_cap_is_refused_when_the_constructor_is_called_directly() {
    // `of` is not the only door — Jackson and any caller can reach the
    // canonical constructor — so the cap is an invariant of the type and not
    // a courtesy of one factory. Without this, a limit arriving from a
    // client built against a different build is a window nobody bounded.
    assertThrows(IllegalArgumentException.class, () -> new Window(0, Window.MAX_WINDOW_LINES + 1));
  }

  @Test
  void a_negative_offset_is_a_caller_defect() {
    assertThrows(IllegalArgumentException.class, () -> Window.of(-1, 5));
  }

  @Test
  void a_limit_of_zero_is_refused_rather_than_quietly_becoming_a_window() {
    // `of` clamps downward, so a zero survives the clamp untouched and the
    // constructor is the only thing between it and a window that returns
    // nothing while reporting that more remains — which reads exactly like
    // a file that cannot be read at all.
    assertThrows(IllegalArgumentException.class, () -> Window.of(0, 0));
    assertThrows(IllegalArgumentException.class, () -> Window.of(0, -5));
  }
}
