package io.aeyer.plowshare.protocol;

import java.util.List;

/**
 * What a read actually returned, and which limit stopped it.
 *
 * <h2>{@code stoppedBy} is the part that carries a decision</h2>
 *
 * <p><b>A full window and a finished file look identical from the lines alone.</b> Both hand back
 * some text and stop, and they call for opposite next moves — page again, or stop asking. A model
 * that cannot tell them apart either pages for ever off the end of a file or stops one window short
 * of the thing it was looking for. {@link #LINES}, {@link #BYTES} and {@link #END} exist for that
 * one distinction, and {@link Window#cut} sets them on branches that are all load-bearing.
 *
 * <p>{@link #BYTES} carries a second fact beyond "there is more": <b>a wider line limit would not
 * have helped.</b> That is why it is separate from {@link #LINES} rather than folded into one
 * "there is more" flag — the two say different things about what the caller should ask for next.
 *
 * <h2>Why these are strings and not an enum</h2>
 *
 * <p>The reason {@link FileReply#outcome()} is one: the two halves of this wire ship separately,
 * and a client built against a later server must not fail to bind a frame merely because a word in
 * it is new. A reader that does not recognise a value should treat the window as one it cannot page
 * past, which is the same conservative direction that record's javadoc argues for.
 *
 * @param lines the text, already cut, and never null — an empty list is the ordinary answer for an
 *     offset at or past the end of the file
 * @param offset the line the window started at, echoed so a reply is legible without the request
 *     beside it
 * @param totalLines how many lines the file has, which is what makes paging plannable rather than
 *     exploratory: a caller can tell before it starts how many windows a file is
 * @param more whether anything follows this window. Redundant with {@link #stoppedBy} for anything
 *     {@link Window#cut} produced — that method's javadoc says exactly how — and kept because it is
 *     the question a caller asks most often and the one it should not have to spell as a string
 *     comparison
 * @param stoppedBy {@link #LINES}, {@link #BYTES} or {@link #END}
 */
public record Span(List<String> lines, int offset, int totalLines, boolean more, String stoppedBy) {

  /** The line allowance was spent, and the file continues past it. */
  public static final String LINES = "lines";

  /** The byte ceiling was reached first, so asking for more lines will not help. */
  public static final String BYTES = "bytes";

  /** The file ended inside the window. Nothing follows. */
  public static final String END = "end";

  public Span {
    // Defensive rather than decorative: a Span crosses a socket and is read
    // by a tool that formats it for a model, and a list still held by the
    // provider that produced it is a list that can change under both.
    lines = List.copyOf(lines);
  }
}
