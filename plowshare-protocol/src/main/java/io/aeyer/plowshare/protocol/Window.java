package io.aeyer.plowshare.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * How much of a file one read may carry, and the arithmetic that cuts it.
 *
 * <h2>Why this is in the protocol module</h2>
 *
 * <p>The same reason {@link FileAccess} is: <b>the client and the server must
 * compute the same window from the same arguments</b>, and the only way to be
 * sure of that is for both to execute this code rather than each to implement
 * it. A remote provider that cut differently from a local one would make a
 * file's contents depend on where the job happened to run — the same drift
 * {@code FileAccess} exists to prevent for containment, in a place where it
 * would be even quieter, because two different windows are both plausible
 * answers and neither looks like a failure.
 *
 * <h2>Lines, with a byte ceiling behind them</h2>
 *
 * <p>The interface is lines because a model reading source thinks in lines and
 * cites them; a byte offset into source is close to useless to it. But lines
 * bound nothing on their own — one minified file with a single enormous line
 * would carry as much as the whole file — so a window is cut by whichever limit
 * is reached first and {@link Span#stoppedBy()} says which.
 *
 * <p><b>A line longer than the ceiling is not returned at all: the read is
 * refused, and {@link LineTooWide} names the line and its size.</b> Returning
 * such a line whole was this class's original answer, and it was measured rather
 * than argued away: a single 2 000 000-character line serialises to a 2 000
 * 134-character frame, which no transport buffer this application can afford
 * carries — the receiving container closes with {@code 1009}, the session goes
 * with the channel, and the job ends. <b>A minified bundle or a one-line JSON
 * document is exactly that shape</b>, so this is the ordinary file rather than
 * the contrived one.
 *
 * <p>What that original answer was protecting is still true, and is answered a
 * different way. A window that returned nothing while reporting more would be a
 * loop a caller cannot leave — so <b>the refusal is a third thing rather than an
 * empty window</b>: it is raised and never returned, no {@link Span} can express
 * it, and a caller reads it as an ordinary tool error naming a remedy. The file
 * becomes unreadable rather than fatal, and unreadable only through this window
 * — the refusal names the search that still works on it.
 *
 * <h2>What this class is not</h2>
 *
 * <p>It never opens a file and it never counts one. It is handed lines somebody
 * else split and it cuts them, so the questions of how a file becomes lines —
 * what a CRLF costs, whether the last line ends in a newline, what a decoder
 * does with a byte that is not text — belong to the providers, and every one of
 * those decisions is visible here only as the strings that arrive.
 */
public record Window(int offset, int limit) {

    /**
     * The most lines one read may carry <b>across a socket</b>.
     *
     * <p>Not derived from {@code FileTools.MAX_DISPLAY_CHARS}: that is a display
     * limit on what a model is shown and this is a transport limit on what
     * crosses a socket, and a value that served as both would go wrong silently
     * the first time either moved.
     *
     * <p><b>Nor is it the number a tool hands a model.</b> {@code
     * FileTools.MAX_READ_LINES} is that, it is far smaller, and it is applied
     * before a {@code Window} is ever built. The two exist separately because
     * they answer to different things and neither can stand in for the other: a
     * socket cares how many bytes arrive in one frame, and a model's turn cares
     * how much of its context one tool result spends. This one was chosen for
     * transport safety and was never a throughput decision — a live run found
     * that a window sized only by this constant is carried perfectly and then
     * cannot be read, because 96 KiB is a fifth of a 9B model's loaded context
     * and its prompt outlives the chat timeout. That constant's javadoc carries
     * the measurements.
     *
     * <p>So this bound is doing its job when a read never reaches it. It is not
     * dead: {@link #of} is called from the wire as well — a client built against
     * a different release, a frame with a large {@code limit} in it — and there
     * it is the only bound there is.
     */
    public static final int MAX_WINDOW_LINES = 2_000;

    /**
     * The most bytes one read may carry across a socket, whatever the line count
     * says.
     *
     * <p><b>A frame's bound, like {@link #MAX_WINDOW_LINES} beside it, and not a
     * turn's.</b> What a model can read in one turn is {@code
     * FileTools.MAX_READ_LINES} and lives with the tool; keeping the two apart
     * is deliberate, because this class knows about sockets and nothing about
     * context windows, and a single value serving both is how the {@code 1009}
     * close this class was written for happened.
     *
     * <p>This still fires ahead of the turn cap for a file dense enough — a few
     * hundred lines of several hundred bytes each — and {@link Span#BYTES} is how
     * a caller is told which of the two stopped it.
     *
     * <p><b>Counted over the text and not over the frame that carries it.</b>
     * JSON encoding adds quoting and escaping on top of this, so the transport
     * buffer has to be set above this constant with headroom rather than equal
     * to it. Those two numbers living in different files is exactly how the bug
     * this class exists for happened, and a test pins the relation between them
     * rather than leaving it to whoever reads both.
     */
    public static final int MAX_WINDOW_BYTES = 96 * 1024;

    /**
     * <b>The cap is an invariant of the type, not a courtesy of {@link #of}.</b>
     * The canonical constructor is reachable by anything that can name the
     * record — Jackson binding a frame from a client built against a different
     * release, most of all — so a cap enforced only in the factory is a cap the
     * wire can walk around. {@link #of} clamps before it gets here, so this
     * throw is unreachable from that direction and is the whole guard from every
     * other.
     */
    public Window {
        if (offset < 0) {
            throw new IllegalArgumentException(
                    "a window's offset is a line number and cannot be negative; got " + offset);
        }
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "a window must carry at least one line; got a limit of " + limit);
        }
        if (limit > MAX_WINDOW_LINES) {
            throw new IllegalArgumentException(
                    "a window may carry at most "
                            + MAX_WINDOW_LINES
                            + " lines; got a limit of "
                            + limit
                            + ". Window.of clamps rather than refusing");
        }
    }

    /**
     * A window from a caller's arguments, with the line limit brought down to
     * {@link #MAX_WINDOW_LINES} rather than refused.
     *
     * <p><b>Reached from the wire, and no longer from {@code FileTools}.</b> That
     * tool clamps to its own, smaller bound first, so the {@code Math.min} below
     * is a no-op for every read a model makes and is the whole of the guard for a
     * {@code limit} that arrived in a frame. Two clamps, in that order, is the
     * point rather than an accident: the tool's is the one a model is told about,
     * because it is the one that changes what came back.
     *
     * <p>Clamping and not refusing because <b>asking for more than a window can
     * hold is what a caller that has not read a file yet should do</b>: it does
     * not know how long the file is, the reply tells it, and a refusal would
     * cost it a turn to learn a number this method already knows. The reply
     * carries the range actually returned, so nothing about the clamp is silent.
     *
     * <p>It clamps in one direction only. A limit of zero or below survives
     * {@code Math.min} untouched and the constructor refuses it, which is the
     * intended split: a limit that is too large is a caller being optimistic,
     * and a limit that is not positive is a caller being wrong — a window that
     * returns nothing while reporting that more remains reads exactly like a
     * file that cannot be read at all.
     */
    public static Window of(int offset, int limit) {
        return new Window(offset, Math.min(limit, MAX_WINDOW_LINES));
    }

    /**
     * One line is wider than any window, so the read is refused.
     *
     * <h2>The sentence is here and is carried unchanged</h2>
     *
     * <p><b>Both halves of the wire hand this message to the model verbatim</b>,
     * which is {@code GlobSpellings}' arrangement and not {@code
     * FileSearch.TooManyMatches}'s. The rule those two are split on is whether
     * anything in the sentence is a fact about the machine that wrote it: a
     * server and a laptop may spend different amounts of their own memory and so
     * word "this file is too big" differently, but <b>every fact in this one — a
     * line number, its size, {@link #MAX_WINDOW_BYTES}, and what a search would
     * do instead — is the same on either side</b>. Two copies of it would be two
     * things to keep in step for no gain, and the model would be reading a
     * different sentence about one file depending on where its job ran.
     *
     * <h2>Why it names a tool</h2>
     *
     * <p>A refusal that only says no costs a turn and teaches nothing; this one
     * is a redirection, because {@code file_grep} works on precisely the files
     * this refuses. {@link Needle#MAX_LINE_CHARS} truncates a matching line, so a
     * search never has to carry what a window cannot — <b>the shape that makes a
     * file unreadable here is the shape that constant was added for.</b>
     *
     * <p>It is worded to say what a search actually answers — which lines contain
     * something, and where — and not to suggest it is another way to read the
     * file. It is not: the line comes back cut, and there is no offset that ever
     * returns it whole.
     *
     * <p><b>The tool's name is a string in this module and a constant in the
     * server's</b>, which is the one thing here that can drift. {@code
     * FileTools.GREP_NAME} is not visible from {@code plowshare-protocol} and
     * must not become so — this module is the shared vocabulary of a wire, and a
     * dependency on the agent layer to spell a word would invert that. The
     * relation is held by a test that reads both, the same way the relation
     * between {@link #MAX_WINDOW_BYTES} and the transport buffer is.
     */
    public static final class LineTooWide extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final int offset;

        private final long bytes;

        LineTooWide(int offset, long bytes) {
            super("line " + offset + " of this file is " + bytes + " bytes, and one read"
                    + " carries at most " + MAX_WINDOW_BYTES + " bytes however few lines are"
                    + " asked for, so no offset or limit returns this line and this file"
                    + " cannot be read. That is a bound on what one answer carries and not on"
                    + " the file. file_grep searches this file without returning it: it gives"
                    + " the offset of every line that contains the text you name, and the"
                    + " first " + Needle.MAX_LINE_CHARS + " characters of that line.");
            this.offset = offset;
            this.bytes = bytes;
        }

        /** Which line, counted as {@link Window#offset()} counts. */
        public int offset() {
            return offset;
        }

        /** How many UTF-8 bytes that line is, without the separator that would
         *  have ended it. */
        public long bytes() {
            return bytes;
        }
    }

    /**
     * Cut {@code all} to this window.
     *
     * <h2>What a line costs</h2>
     *
     * <p>Its UTF-8 length plus one, for the separator that put it on its own
     * line. UTF-8 and not {@code String.length} because the ceiling bounds a
     * transport and not a display: counting characters would carry roughly twice
     * what it promised for text outside ASCII, and the files worth reading are
     * full of it.
     *
     * <p><b>The separator is charged even for a last line that has none</b>,
     * which over-counts by one byte. Only one line in a file can lack a trailing
     * newline and only the window containing it is affected, so the error is at
     * most a byte per read and it is on the conservative side — it can make a
     * window stop one line early and can never make one overflow. Charging it
     * conditionally would mean this method knowing something about the file that
     * the list of lines it is handed does not carry.
     *
     * <h2>Which limit gets named when two of them are true</h2>
     *
     * <p><b>The ceiling outranks the allowance.</b> A window whose last line
     * both spends the byte budget and exhausts the line limit is reported as
     * {@link Span#BYTES}, because that is the answer that changes what the
     * caller does next: it says a wider {@code limit} would have come back the
     * same size, where {@link Span#LINES} invites exactly that wasted request.
     *
     * <p><b>The end of the file outranks both.</b> The ceiling can stop the loop
     * on a line that happens to be the file's last, and reporting {@link
     * Span#BYTES} there would be true about the loop and a lie about the file —
     * a reply a caller pages again on, into nothing. So a window with nothing
     * after it says {@link Span#END} whatever stopped the loop, and the
     * consequence is an invariant worth relying on: <b>for anything this method
     * returns, {@code stoppedBy} is {@link Span#END} exactly when {@code more}
     * is false.</b> The other direction needs no rewriting — the line allowance
     * is only ever spent while an unread line remains, because it is checked
     * before a line is taken and not after.
     *
     * <h2>The one line no window can carry</h2>
     *
     * <p><b>{@link LineTooWide} is raised for a line wider than {@link
     * #MAX_WINDOW_BYTES} on its own, and only when this window would have had to
     * take it first.</b> Both halves of that are load-bearing.
     *
     * <p>Refusing rather than returning it is the promise this method gave up,
     * and the class javadoc carries the measurement that made it. What matters
     * here is that the two are not symmetrical: a window that returns nothing and
     * reports more is a loop, and a raised refusal is not in the loop at all.
     *
     * <p><b>An oversized line further down stops the window in front of it
     * instead</b>, on {@link Span#BYTES}, exactly as any line that will not fit
     * does — nothing here inspects the rest of the file. So a caller paging
     * forward reads every line up to that one and is refused when it arrives at
     * it, rather than being refused a file it could have read most of. Refusing
     * on sight of such a line anywhere in the window would throw away lines this
     * window carries perfectly well, and would make one read succeed or fail
     * depending on how wide a {@code limit} was asked for.
     *
     * <p>So the refusal is about a window with no choice, and not about a file
     * that contains a wide line — and there is no path through this method by
     * which such a line is returned: reaching it with lines already taken stops
     * the window, and reaching it with none taken raises.
     *
     * <p><b>The comparison is the line's own bytes against the ceiling, and not
     * its cost.</b> A line of exactly {@link #MAX_WINDOW_BYTES} bytes is still
     * carried; it exceeds the ceiling only in this method's arithmetic, by the
     * separator the cost section above charges for and the frame does not hold.
     * Refusing it would be refusing a line that fits, for a byte that is not
     * there.
     *
     * @throws LineTooWide if the first line this window would take cannot be
     *     carried at all. Never a truncated line and never an empty window: the
     *     read is refused, and the message names the line, its size and the
     *     search that still reaches inside the file
     */
    public Span cut(List<String> all) {
        int total = all.size();
        if (offset >= total) {
            // Not an error. A caller paging forward reaches this exactly once
            // and it is how it learns to stop; an exception would cost it a turn
            // to discover what an empty answer tells it for free. The total is
            // still the truth about the file, which is what makes an offset well
            // past the end recoverable rather than merely tolerated.
            return new Span(List.of(), offset, total, false, Span.END);
        }
        List<String> taken = new ArrayList<>();
        long bytes = 0;
        String stoppedBy = Span.END;
        for (int at = offset; at < total; at++) {
            if (taken.size() == limit) {
                stoppedBy = Span.LINES;
                break;
            }
            String line = all.get(at);
            long size = line.getBytes(StandardCharsets.UTF_8).length;
            long cost = size + 1L;
            // `taken.isEmpty()` was what made an oversized line come back whole,
            // and it is now what makes the read refuse: a line the ceiling cannot
            // hold on its own is only this window's problem when this window has
            // nothing else to return instead of it. Reached with lines already
            // taken, the clause below stops in front of it like any other line
            // that will not fit.
            //
            // `at` and `offset` are the same number wherever this throws, since
            // nothing is taken only on the first pass — the mutant that swaps
            // them survives the suite and is equivalent rather than untested.
            // `at` is written because it is the line being looked at.
            if (taken.isEmpty() && size > MAX_WINDOW_BYTES) {
                throw new LineTooWide(at, size);
            }
            if (!taken.isEmpty() && bytes + cost > MAX_WINDOW_BYTES) {
                stoppedBy = Span.BYTES;
                break;
            }
            taken.add(line);
            bytes += cost;
            if (bytes >= MAX_WINDOW_BYTES) {
                stoppedBy = Span.BYTES;
                break;
            }
        }
        boolean more = offset + taken.size() < total;
        return new Span(taken, offset, total, more, more ? stoppedBy : Span.END);
    }
}
