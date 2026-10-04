package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What survives the wire, asked of the two frames together because they are the two halves of one
 * exchange.
 *
 * <h2>The mapper is built here to match the channel's, and that is a duplication with a reason</h2>
 *
 * <p>{@code FileChannelHandler} and {@code ChannelClient} each hold a mapper configured exactly as
 * {@link #wire} is, and this module cannot import either — it is the module they both depend on,
 * and its build file keeps databind off the main classpath entirely so that a stdio client cannot
 * reach a serialiser it has no business holding.
 *
 * <p><b>Testing through a fresh default mapper instead would be the mistake this class exists to
 * avoid.</b> A field that binds under a default configuration and not under the channel's is a
 * field that passes here and fails on the wire, and the setting that differs is the one that makes
 * a version skew survivable rather than fatal — so the copy is checked in {@link
 * #a_key_this_build_has_never_heard_of_does_not_stop_a_frame_binding}, which fails if either side
 * of the wire is ever made strict without this being made strict too.
 */
class FileFramesTest {

  /**
   * Configured as both ends of the file channel configure theirs: lenient about keys it does not
   * know, because the two halves ship separately.
   */
  private static ObjectMapper wire() {
    return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  private static FileRequest roundTrip(FileRequest request) throws Exception {
    ObjectMapper json = wire();
    return json.readValue(json.writeValueAsString(request), FileRequest.class);
  }

  private static FileReply roundTrip(FileReply reply) throws Exception {
    ObjectMapper json = wire();
    return json.readValue(json.writeValueAsString(reply), FileReply.class);
  }

  @Test
  void a_windowed_read_carries_its_window_across_the_wire() throws Exception {
    FileRequest bound =
        roundTrip(FileRequest.read("r1", "/laptop/repo/A.java", Window.of(40, 120)));

    assertEquals(FileRequest.READ, bound.op());
    assertEquals("/laptop/repo/A.java", bound.path());
    assertEquals(40, bound.offset(), "the window is the request, and this is half of it");
    assertEquals(120, bound.limit(), "the other half");
    assertEquals(Window.of(40, 120), bound.window());
  }

  @Test
  void an_edit_a_delete_a_move_and_a_create_carry_their_fields_across_the_wire() throws Exception {
    FileRequest edit = roundTrip(FileRequest.edit("e1", "/repo/A.java", "old\r\n", ""));
    assertEquals(FileRequest.EDIT, edit.op());
    assertEquals("old\r\n", edit.replacing());
    assertEquals("", edit.content());

    FileRequest delete = roundTrip(FileRequest.delete("d1", "/repo/A.java"));
    assertEquals(FileRequest.DELETE, delete.op());
    assertEquals("/repo/A.java", delete.path());

    FileRequest move = roundTrip(FileRequest.move("m1", "/repo/A.java", "/repo/B.java"));
    assertEquals(FileRequest.MOVE, move.op());
    assertEquals("/repo/B.java", move.to());

    assertTrue(roundTrip(FileRequest.create("c1", "/repo/A.java", "x")).creating());
    assertEquals(false, roundTrip(FileRequest.write("w1", "/repo/A.java", "x")).creating());
  }

  @Test
  void a_write_from_a_server_that_predates_create_only_is_an_ordinary_write() throws Exception {
    FileRequest bound =
        wire()
            .readValue(
                "{\"id\":\"w1\",\"op\":\"write\",\"path\":\"/repo/A.java\",\"content\":\"x\"}",
                FileRequest.class);
    assertNull(bound.createOnly());
    assertEquals(false, bound.creating());
  }

  @Test
  void marking_a_request_for_definitions_keeps_its_new_fields() {
    FileRequest marked = FileRequest.move("m1", "/repo/A.java", "/repo/B.java").forDefinitions();
    assertEquals("/repo/B.java", marked.to());
  }

  /** Spec 2026-09-30-local-hooks-are-served decision 2: its own mark, not the definitions'. */
  @Test
  void marking_a_request_for_hooks_keeps_its_fields_and_says_hooks() throws Exception {
    FileRequest marked = roundTrip(FileRequest.glob("g1", ".plowshare/hooks/*").forHooks());

    assertEquals("hooks", FileRequest.HOOKS);
    assertEquals(FileRequest.HOOKS, marked.purpose());
    assertEquals(".plowshare/hooks/*", marked.pattern());
    assertEquals(FileRequest.GLOB, marked.op());
  }

  @Test
  void a_run_and_its_outcome_and_a_cancel_carry_their_fields_across_the_wire() throws Exception {
    FileRequest run =
        roundTrip(
            FileRequest.run(
                "r1",
                "/repo",
                java.util.List.of("./gradlew", "test"),
                java.util.Map.of("GRADLE_OPTS", "-Xmx2g"),
                java.util.List.of("PATH"),
                60_000,
                1024,
                false));
    assertEquals(FileRequest.RUN, run.op());
    assertEquals("/repo", run.path());
    assertEquals(java.util.List.of("./gradlew", "test"), run.argv());
    assertEquals("-Xmx2g", run.env().get("GRADLE_OPTS"));
    assertEquals(60_000L, run.timeoutMillis());
    assertEquals(false, run.shells());

    FileReply ran =
        roundTrip(
            FileReply.ran(
                "r1", new CommandRunner.Outcome(1, false, false, "out", 5, "err", 0, 42)));
    CommandRunner.Outcome outcome = ran.commandOutcome();
    assertEquals(1, outcome.exitCode());
    assertEquals("out", outcome.stdout());
    assertEquals(5, outcome.stdoutCut());
    assertEquals(42, outcome.millis());

    CommandRunner.Outcome killed =
        roundTrip(
                FileReply.ran("r1", new CommandRunner.Outcome(null, true, false, "", 0, "", 0, 9)))
            .commandOutcome();
    assertTrue(killed.timedOut());
    assertNull(killed.exitCode());

    assertNull(
        roundTrip(FileReply.done("r1")).commandOutcome(),
        "a reply from a client that does not know run is not a command that printed nothing");
    assertEquals("r1", roundTrip(FileRequest.cancel("c1", "r1")).path());
  }

  @Test
  void a_stat_carries_a_path_and_no_window() throws Exception {
    FileRequest bound = roundTrip(FileRequest.stat("r1", "/laptop/repo/A.java"));

    assertEquals(FileRequest.STAT, bound.op());
    assertEquals("/laptop/repo/A.java", bound.path());
    assertNull(bound.offset(), "a stat moves no text, so it has no window to carry");
    assertNull(bound.limit());
    assertNull(bound.pattern());
    assertNull(bound.content());
  }

  /**
   * The boxed {@code Integer} earning itself: a frame that never mentioned a window and a frame
   * that asked for line zero must not arrive identical.
   */
  @Test
  void an_absent_window_and_a_window_starting_at_zero_are_different_frames() throws Exception {
    FileRequest none = roundTrip(FileRequest.stat("r1", "/laptop/repo/A.java"));
    FileRequest first = roundTrip(FileRequest.read("r2", "/laptop/repo/A.java", Window.of(0, 10)));

    assertNull(none.offset());
    assertEquals(0, first.offset());
  }

  @Test
  void a_reply_carrying_a_span_round_trips_every_part_of_it() throws Exception {
    Span cut = new Window(2, 3).cut(List.of("l1", "l2", "l3", "l4", "l5", "l6"));

    FileReply bound = roundTrip(FileReply.answered("r1", cut));

    assertEquals(FileReply.OK, bound.outcome());
    assertEquals(
        List.of("l3", "l4", "l5"),
        bound.span().lines(),
        "the lines are the content, and they are on this frame once");
    assertEquals(2, bound.span().offset());
    assertEquals(6, bound.span().totalLines());
    assertTrue(bound.span().more());
    assertEquals(
        Span.LINES,
        bound.span().stoppedBy(),
        "which limit stopped the read is the part a model decides on");
  }

  /** A roots or a glob or a write answers with no span, and null is how. */
  @Test
  void a_reply_that_moved_no_text_carries_no_span() throws Exception {
    assertNull(roundTrip(FileReply.listed("r1", List.of("/laptop/repo"))).span());
    assertNull(roundTrip(FileReply.done("r2")).span());
    assertNull(roundTrip(FileReply.refused("r3", "no workspace")).span());
  }

  /**
   * A change's answer is facts and no words: what was done or why not, as a {@link FileResult}
   * beside the outcome, with the sentence left empty for the server to write.
   */
  @Test
  void a_change_carries_its_result_across_the_wire_and_no_sentence() throws Exception {
    FileResult edited = Replacement.edit("a\nb\nc\n", "b", "B").result("/repo/A.java");
    FileResult missed =
        assertThrows(Replacement.Refused.class, () -> Replacement.apply("a\u2011b\n", "a-b", "x"))
            .result("/repo/A.java");

    FileReply ok = roundTrip(FileReply.changed("r1", edited));
    FileReply refused = roundTrip(FileReply.changed("r2", missed));

    assertEquals(FileReply.OK, ok.outcome());
    assertEquals(edited, ok.result());
    assertNull(ok.sentence());
    assertEquals(FileReply.REFUSED, refused.outcome());
    assertEquals(missed, refused.result());
    assertNull(refused.sentence());
    assertNull(roundTrip(FileReply.done("r3")).result());
  }

  /**
   * Every other op answers with facts too (spec 2026-09-30 step 2): a refusal of a read, a picture
   * a read named, an outage — and the fields only those carry (the format, the image id, the
   * status, the pattern) survive the wire.
   */
  @Test
  void a_read_a_refusal_and_an_outage_carry_their_facts_across_the_wire() throws Exception {
    FileResult named = FileResult.named(FileRequest.READ, "/repo/logo.png", "png", "img_1");
    FileResult refused =
        FileResult.imageRefused(FileRequest.READ, "/repo/big.png", "png", 413, "too big", 900_000);
    FileResult pattern = FileResult.tooManyWildcards("**/**/**/**/**/x", 5, 4);
    FileResult gone = FileResult.unavailable(FileRequest.GLOB, FileResult.ROOT_GONE, "/repo", null);

    FileReply picture = roundTrip(FileReply.named("r1", named));
    assertEquals(FileReply.OK, picture.outcome());
    assertEquals(named, picture.result());
    assertNull(picture.span(), "the line is the server's to write");
    assertNull(picture.sentence());
    assertEquals(refused, roundTrip(FileReply.refused("r2", refused)).result());
    assertEquals(FileReply.REFUSED, roundTrip(FileReply.refused("r2", refused)).outcome());
    assertEquals(pattern, roundTrip(FileReply.refused("r3", pattern)).result());
    FileReply outage = roundTrip(FileReply.unavailable("r4", gone));
    assertEquals(FileReply.UNAVAILABLE, outage.outcome());
    assertEquals(gone, outage.result());
    assertNull(outage.sentence());
  }

  /** A glob that cannot be expanded says why as facts, whichever side expanded it. */
  @Test
  void an_unusable_glob_carries_its_facts() {
    GlobSpellings.Unusable deep =
        assertThrows(
            GlobSpellings.Unusable.class, () -> GlobSpellings.matchers("**/**/**/**/**/x"));
    GlobSpellings.Unusable unclosed =
        assertThrows(
            GlobSpellings.Unusable.class, () -> GlobSpellings.matchers("**/[unclosed.java"));

    assertEquals(
        FileResult.tooManyWildcards("**/**/**/**/**/x", 5, GlobSpellings.MAX_RECURSIVE_WILDCARDS),
        deep.result());
    assertEquals(FileResult.BAD_PATTERN, unclosed.result().reason());
    assertEquals("**/[unclosed.java", unclosed.result().pattern());
    assertTrue(
        unclosed.getMessage().startsWith("'**/[unclosed.java' is not a usable glob: "),
        "the message every caller of the unchecked type read is the one it always was");
  }

  /**
   * A client built before results answers with a sentence and no result, and this build still binds
   * it: the server passes that sentence through.
   */
  @Test
  void a_reply_from_a_client_built_before_results_binds_with_no_result() throws Exception {
    String old =
        "{\"id\":\"r1\",\"outcome\":\"ok\",\"sentence\":\"The new text is on" + " line 1 now\"}";

    FileReply bound = wire().readValue(old, FileReply.class);

    assertEquals("The new text is on line 1 now", bound.sentence());
    assertNull(bound.result());
  }

  /**
   * A server built before this change sent exactly this: a read with no window keys at all. It
   * binds, and asks for the first window rather than for the whole file — the whole-file read is
   * the request that killed the session.
   */
  @Test
  void a_read_from_before_the_window_existed_binds_to_the_first_window() throws Exception {
    FileRequest old =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"op\":\"read\",\"path\":\"/laptop/repo/A.java\"}",
                FileRequest.class);

    assertNull(old.offset(), "the keys were not there to be read");
    assertNull(old.limit());
    assertEquals(
        new Window(0, Window.MAX_WINDOW_LINES),
        old.window(),
        "an unwindowed read is answered with the first window of the file");
  }

  /**
   * The clamp is the same one {@link Window#of} applies to a caller in this process, which is the
   * point: the two halves of the doubled enforcement must not disagree about a limit merely because
   * one of them arrived over a socket.
   */
  @Test
  void a_limit_above_the_cap_arriving_over_the_wire_is_clamped_and_not_refused() throws Exception {
    FileRequest greedy =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"op\":\"read\",\"path\":\"/a\",\"offset\":0,\"limit\":999999}",
                FileRequest.class);

    assertEquals(999_999, greedy.limit(), "the frame is kept as it was sent");
    assertEquals(
        Window.MAX_WINDOW_LINES,
        greedy.window().limit(),
        "and the window built from it is the one this build will serve");
  }

  /**
   * Clamping upward only, exactly as {@link Window#of} does. A limit that is too large is a caller
   * being optimistic about a file it has not read; a limit that is not positive is a caller being
   * wrong.
   */
  @Test
  void a_window_that_could_return_nothing_is_still_refused() throws Exception {
    FileRequest backwards =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"op\":\"read\",\"path\":\"/a\",\"offset\":-1,\"limit\":10}",
                FileRequest.class);
    FileRequest empty =
        wire()
            .readValue(
                "{\"id\":\"r2\",\"op\":\"read\",\"path\":\"/a\",\"offset\":0,\"limit\":0}",
                FileRequest.class);

    assertThrows(IllegalArgumentException.class, backwards::window);
    assertThrows(IllegalArgumentException.class, empty::window);
  }

  @Test
  void a_search_carries_its_needle_and_its_fold_across_the_wire() throws Exception {
    FileRequest bound =
        roundTrip(FileRequest.grep("r1", "/laptop/repo/src", new Needle("class A", true)));

    assertEquals(FileRequest.GREP, bound.op());
    assertEquals("/laptop/repo/src", bound.path());
    assertEquals("class A", bound.needle());
    assertEquals(true, bound.ignoreCase());
    assertNull(
        bound.pattern(),
        "a needle does not travel in the glob field: one is matched against a"
            + " path and the other against the text of a line");
    assertEquals(new Needle("class A", true), bound.sought());
  }

  @Test
  void a_search_with_no_path_is_a_frame_with_no_path_and_not_a_missing_argument() throws Exception {
    FileRequest bound = roundTrip(FileRequest.grep("r1", null, new Needle("x", false)));

    assertNull(bound.path(), "absent means every root, which is the common case");
    assertEquals("x", bound.needle());
  }

  /**
   * The boxed {@code Boolean} earning itself: what a server built before this op sends is a frame
   * with no such key, and the two halves must read that one way or one machine folds case and the
   * other does not.
   */
  @Test
  void a_search_frame_with_no_case_key_at_all_asks_for_a_case_sensitive_search() throws Exception {
    FileRequest old =
        wire()
            .readValue("{\"id\":\"r1\",\"op\":\"grep\",\"needle\":\"class A\"}", FileRequest.class);

    assertNull(old.ignoreCase(), "the key was not there to be read");
    assertEquals(new Needle("class A", false), old.sought());
  }

  @Test
  void a_search_frame_with_nothing_to_look_for_is_refused_rather_than_repaired() throws Exception {
    // Where `window()` clamps, this throws, and the difference is what can be
    // salvaged: an over-large limit has an obvious smaller value that is
    // certainly what the caller wanted, and an empty needle has no repair
    // except answering with the first lines of the tree.
    FileRequest empty =
        wire().readValue("{\"id\":\"r1\",\"op\":\"grep\",\"needle\":\"\"}", FileRequest.class);
    FileRequest none = wire().readValue("{\"id\":\"r2\",\"op\":\"grep\"}", FileRequest.class);

    assertThrows(IllegalArgumentException.class, empty::sought);
    assertThrows(IllegalArgumentException.class, none::sought);
  }

  @Test
  void a_reply_carrying_matches_round_trips_every_part_of_one() throws Exception {
    Found matched =
        Found.of(
            List.of(
                new Found.Match("/laptop/repo/A.java", 41, "class A implements B {", false),
                new Found.Match("/laptop/repo/big.js", 0, "var a=1;", true)),
            true);

    FileReply bound = roundTrip(FileReply.found("r1", matched));

    assertEquals(FileReply.OK, bound.outcome());
    assertEquals(matched, bound.found());
    assertEquals(
        41,
        bound.found().matches().get(0).offset(),
        "the offset is what the next read is opened at");
    assertTrue(
        bound.found().matches().get(1).truncated(),
        "and a cut line says it was cut, since nothing downstream can tell");
    assertTrue(bound.found().capped());
    assertEquals(Found.MATCHES, bound.found().stoppedBy());
  }

  /** A read or a glob or a write answers with no matches, and null is how. */
  @Test
  void a_reply_that_ran_no_search_carries_no_matches() throws Exception {
    assertNull(roundTrip(FileReply.listed("r1", List.of("/laptop/repo"))).found());
    assertNull(roundTrip(FileReply.done("r2")).found());
    assertNull(roundTrip(FileReply.refused("r3", "no workspace")).found());
  }

  /**
   * {@link Found#capped()} is derived and not a component, and this is what says the derivation
   * stays off the wire: a key nothing sends cannot be a key that disagrees with the word beside it.
   */
  @Test
  void whether_a_search_capped_is_not_a_field_of_its_own_on_the_frame() throws Exception {
    String json = wire().writeValueAsString(FileReply.found("r1", Found.of(List.of(), false)));

    assertFalse(json.contains("capped"), json);
    assertTrue(json.contains(Found.END), json);
  }

  /**
   * A reason for stopping this build does not recognise binds, and is read as capped — the
   * conservative direction, for {@link FileReply#outcome()}'s reason: a search treated as complete
   * when it was not is a wrong answer nobody can see.
   */
  @Test
  void a_reason_a_search_stopped_that_this_build_does_not_know_reads_as_capped() throws Exception {
    FileReply bound =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"outcome\":\"ok\",\"found\":{\"matches\":[],"
                    + "\"stoppedBy\":\"deadline\"}}",
                FileReply.class);

    assertEquals("deadline", bound.found().stoppedBy());
    assertTrue(
        bound.found().capped(),
        "anything unrecognised is treated as an answer with something left out");
  }

  /**
   * The setting that makes a version skew survivable, pinned on the configuration this class copies
   * from the channel.
   */
  @Test
  void a_key_this_build_has_never_heard_of_does_not_stop_a_frame_binding() throws Exception {
    FileRequest ahead =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"op\":\"read\",\"path\":\"/a\",\"offset\":0,\"limit\":10,"
                    + "\"encoding\":\"utf-16\"}",
                FileRequest.class);
    FileReply answered =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"outcome\":\"ok\",\"checksum\":\"deadbeef\"}", FileReply.class);

    assertEquals(10, ahead.limit());
    assertEquals(FileReply.OK, answered.outcome());
    assertNull(answered.span(), "a reply from a build with no spans in it binds to none");
  }

  /**
   * The same leniency pointing backwards rather than forwards, which is the direction this change
   * created. {@code text} was a component of {@link FileReply} until the span became its only
   * carrier, and an older client still sends it — so the key that used to be the whole answer is
   * now one this build has never heard of, and it must be as harmless as any other.
   */
  @Test
  void a_reply_still_carrying_the_text_field_binds_without_it() throws Exception {
    FileReply old =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"outcome\":\"ok\",\"text\":\"class A {}\\n\"}", FileReply.class);

    assertEquals(FileReply.OK, old.outcome());
    assertNull(old.span(), "and it carried no window, which is what made it dangerous");
  }

  /**
   * A span whose {@code stoppedBy} this build does not recognise still binds, for the reason {@link
   * Span}'s javadoc gives: the word is a string so that a new one is a value to be read
   * conservatively rather than a frame that cannot be read at all.
   */
  @Test
  void a_reason_for_stopping_that_this_build_does_not_know_still_binds() throws Exception {
    FileReply bound =
        wire()
            .readValue(
                "{\"id\":\"r1\",\"outcome\":\"ok\",\"span\":{\"lines\":[\"x\"],"
                    + "\"offset\":0,\"totalLines\":9,\"more\":true,\"stoppedBy\":\"budget\"}}",
                FileReply.class);

    assertEquals("budget", bound.span().stoppedBy());
    assertTrue(bound.span().more());
    assertFalse(bound.span().lines().isEmpty());
  }
}
