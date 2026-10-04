package io.aeyer.plowshare.protocol;

import java.util.List;

/**
 * What a search found, and whether it stopped because there was nothing left or because there was
 * no room left.
 *
 * <h2>{@code stoppedBy} is the part that carries a decision</h2>
 *
 * <p>{@link Span}'s argument, arriving at the other tool. <b>A capped search and an exhausted one
 * look identical from the matches alone</b> — both hand back some lines and stop — and they call
 * for opposite next moves: narrow the needle, or believe the answer. A model that cannot tell them
 * apart either treats fifty of thousands of matches as the whole truth, or goes on narrowing a
 * needle that had already found everything there was.
 *
 * <p><b>The remedy this type points at is not the one {@link Span} points at.</b> A read that
 * stopped has a next offset to name, and paging to it is the fix. A search that stopped has no such
 * thing, and there is deliberately no "next" on this record: {@link Needle#MAX_MATCHES}'s javadoc
 * says a capped search means the needle was too common, and a caller offered a way to ask for the
 * next fifty would walk the tree again for each of them — which is the quadratic paging this whole
 * tool exists to remove, moved one tool along.
 *
 * <h2>There is no total, and that absence is measured rather than forgotten</h2>
 *
 * <p>The design spec asked for the shape {@code file_read}'s continuation uses — what was shown,
 * what the total was, what to do about it — and <b>the middle one is not available here</b>. {@link
 * Span#totalLines()} is honest because a read has already looked at the whole file to count its
 * lines. A search stops the moment its allowance is spent, precisely so that a common needle does
 * not walk and decode every remaining file in the tree; knowing how many matches there really are
 * means doing exactly that walk. Reporting a total would therefore mean paying the cost the cap
 * exists to avoid, and reporting a guessed one would be worse than reporting none.
 *
 * <h2>The line cap is per match, so it is reported per match</h2>
 *
 * <p>{@link Needle} has two bounds and only one of them can stop a search. {@link
 * Match#truncated()} carries the other, on the match it happened to, because that is where a caller
 * needs it and because it calls for a different move again: a truncated line is not narrowed, it is
 * read, with {@code file_read} at the offset the same match carries.
 *
 * <h2>Why {@code stoppedBy} is a string and not an enum</h2>
 *
 * <p>{@link Span}'s reason and {@link FileReply#outcome()}'s: the two halves of this wire ship
 * separately, and a client built against a later server must not fail to bind a frame because a
 * word in it is new. {@link #capped()} reads an unrecognised value as capped, which is the
 * conservative direction — a search treated as complete when it was not is the confident empty
 * answer with fifty lines in front of it.
 *
 * @param matches the lines that matched, in file order — never null, and empty is the ordinary
 *     answer for a needle that is simply not there
 * @param stoppedBy {@link #MATCHES} or {@link #END}
 */
public record Found(List<Match> matches, String stoppedBy) {

  /** The match allowance was spent and the search had more to give. */
  public static final String MATCHES = "matches";

  /** Everything in scope was searched. Nothing was left out. */
  public static final String END = "end";

  /**
   * One line that matched, and where to read it.
   *
   * @param path the file it is in. A string rather than a {@code Path} for {@code
   *     FileReply.paths}'s reason: it names a file on a filesystem the process reading this may not
   *     be able to see
   * @param offset which line it is, <b>counted exactly as {@link Window#offset()} counts</b> — the
   *     first line of a file is line zero. That is the whole point of the tool rather than a
   *     detail: this number goes straight into the next {@code file_read}, and a one-based number
   *     here would send every follow-up read one line late while every match still looked right.
   *     Anything that displays a match to a human owns the decision to add one, and owns saying so
   * @param line the text, cut to {@link Needle#MAX_LINE_CHARS}
   * @param truncated whether {@link #line} is shorter than the line in the file. Carried rather
   *     than left to a caller comparing lengths against a constant: a cut line with nothing to say
   *     so reads as a line that ends there, and the reader most likely to be fooled is the one that
   *     cannot go and look
   */
  public record Match(String path, int offset, String line, boolean truncated) {}

  public Found {
    // Defensive rather than decorative, for Span's reason: this crosses a
    // socket and is read by a tool that formats it, and a list still held by
    // the provider that built it is a list that can change under both.
    matches = List.copyOf(matches);
  }

  /**
   * Whether the allowance stopped this search — the question a caller asks most often, and the one
   * it should not have to spell as a string comparison.
   *
   * <p><b>Not a component of the record, which is the difference from {@link Span#more()}.</b> That
   * type carries the same fact twice because three stop reasons collapse onto two answers there;
   * here there are exactly two of each, so a second field would be a second name for one bit — and
   * two fields carrying one fact can disagree, which is the defect {@link FileReply}'s javadoc
   * records paying for once already. Derived, they cannot.
   *
   * <p>Anything unrecognised reads as capped. {@link FileReply#outcome()} argues the direction: a
   * word from a later build read as completeness is a wrong answer nobody can see, and read as a
   * cap it is at worst one wasted narrowing.
   */
  public boolean capped() {
    return !END.equals(stoppedBy);
  }

  /**
   * A result from the matches collected and whether anything was discarded.
   *
   * <p>The one place {@link #stoppedBy} is derived from that fact, so that the server half and the
   * client half cannot spell it differently. Both call this. {@code LocalProvider.stat} and {@code
   * ClientEnforcer.stat} are the cautionary pair — one expression, typed out twice, with nothing
   * but an agreement test behind them.
   */
  public static Found of(List<Match> matches, boolean capped) {
    return new Found(matches, capped ? MATCHES : END);
  }
}
