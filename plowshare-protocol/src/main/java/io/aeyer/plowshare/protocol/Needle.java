package io.aeyer.plowshare.protocol;

import java.util.List;

/**
 * What a search looks for, what counts as finding it, and how much of what it
 * found may come back.
 *
 * <h2>Why this is in the protocol module</h2>
 *
 * <p>{@link Window}'s reason, and it is sharper here. Two implementations of
 * "does this line match" would let a file's contents depend on where the job
 * ran — and unlike a window, which at least reports the range it returned, a
 * search that matched differently hands back a shorter list with nothing in it
 * to say a line was missed. {@link GlobSpellings} draws the line this sits on:
 * what changes an <em>answer</em> is shared, and what produces a
 * <em>refusal</em> may differ between a laptop and a server.
 *
 * <p><b>Both numbers on this record are on the shared side too, and that is the
 * one place this differs from every other cap in the system.</b> {@code
 * LocalProvider.MAX_FILE_BYTES} and {@code MAX_MATCHES} are each machine's own
 * because they produce a refusal a model reads. These two do not: they change
 * what comes back and say so in the answer, so a client capping at forty and a
 * server at fifty would put two different result sets behind one identical
 * {@link Found#MATCHES}.
 *
 * <h2>It matches literally, and the reason is not the one first written down</h2>
 *
 * <p><b>The spec refused regex on a ReDoS argument and then withdrew it, having
 * measured.</b> Four standard catastrophic-backtracking examples were run on
 * this build's JVM — JetBrains Runtime 21.0.8 — and all four finished in about a
 * millisecond; the interrupt question could not even be asked, because {@code
 * Future.cancel(true)} kept returning false on matches that had already
 * completed. That does not make regex safe, since the general problem is
 * undecidable and the optimiser handles some shapes and not others. It does mean
 * the specific danger was asserted rather than run, and this javadoc is not
 * going to be the place it survives.
 *
 * <p>What survives is smaller and is enough. <b>Literal is what the measured
 * need requires</b>: the run that produced this tool was looking for a heading,
 * and finding a definition or a caller is the same shape. It is the simplest
 * thing that removes a fourteen-read walk, and a second backend — a bounded
 * regex, a shelled-out {@code ripgrep} — is the change that would justify
 * extracting a seam for it. {@link #matches} is the whole of what such a seam
 * would take over, which is why nothing else in this system says the word
 * "literal": not the wire records, not the providers, and not the tool schema
 * beyond one sentence of description. That sentence still has to exist, or a
 * model discovers the difference by writing {@code .*} and being told there are
 * no matches.
 *
 * <p>The cost of what is here is the line's length times the needle's, with no
 * state carried between positions — which is a bound rather than an argument
 * about one.
 *
 * @param text what to look for. Never blank: every line contains the
 *     empty string, so an unchecked empty needle is the first {@link
 *     #MAX_MATCHES} lines of the tree returned as matches
 * @param ignoreCase whether {@code a} finds {@code A}. False is the default a
 *     frame with no such key gets, because a case-sensitive search is the one
 *     whose answer is never a surprise
 */
public record Needle(String text, boolean ignoreCase) {

    /**
     * The most matches one search hands back.
     *
     * <p><b>Sized so that finding is cheaper than reading</b>, which is the
     * property the whole tool rests on. The measurement that asked for {@code
     * file_grep} was fourteen reads to reach one line; a search is only a win if
     * the search plus the read it enables costs less than the walk it replaces,
     * and the honest way to guarantee that is for a <em>saturated</em> search to
     * cost less than a single read.
     *
     * <p>{@code FileTools.MAX_READ_LINES} is 300 lines, which that constant's
     * javadoc measures at 16 000 to 18 500 bytes over this repository. A match
     * at its own ceiling is {@link #MAX_LINE_CHARS} of line plus a path and a
     * number — call it 270 bytes — so fifty of them is about 13 500, comfortably
     * under one read and about a fifth of what {@link Window#MAX_WINDOW_BYTES}
     * would allow onto a socket. Under it, and not equal to it: the two answer
     * to different things, and a search that cost exactly a read would make the
     * pair cost two.
     *
     * <p><b>And it is high enough that the ordinary question is not capped.</b>
     * The searches this tool exists for — where is this heading, where is this
     * method defined, who calls it — return a handful of lines in a repository
     * this size. Fifty is where a search stops being an answer and starts being
     * a listing, which is the point at which the remedy is to narrow the needle
     * rather than to raise a number.
     *
     * <p><b>Reported at, not refused at</b>, which is the opposite of {@code
     * LocalProvider.MAX_MATCHES} for {@code file_glob} and is deliberate. A
     * truncated list of <em>paths</em> reads as a complete one and there is
     * nothing useful in a prefix of it, so a glob refuses. A prefix of a
     * search is different in kind: fifty real matches with {@link
     * Found#MATCHES} on them is an answer a model can act on — the first hit is
     * often the one wanted — and refusing outright would hand back nothing at
     * all for a needle that was merely common.
     */
    public static final int MAX_MATCHES = 50;

    /**
     * The most characters of one matching line come back.
     *
     * <p><b>This is what a search can do that a window cannot, and it is why
     * {@link Window.LineTooWide} is a redirection rather than a wall.</b> A line
     * wider than {@link Window#MAX_WINDOW_BYTES} makes its file unreadable — that
     * refusal names this tool by name, and this constant is the reason it can.
     * The line number is the answer here and the text is only how a reader
     * confirms it is the right one, so cutting the text ordinarily costs nothing
     * that {@code file_read} at the offset in the same match does not give
     * straight back — and for a file that read now refuses, a cut line is the
     * only way anything inside it comes back at all. One minified bundle in a
     * tree is otherwise a single match whose line is the whole file.
     *
     * <p>200 is roughly twice the widest line this repository writes on purpose,
     * and three times its measured average — {@code FileTools.MAX_READ_LINES}
     * records 52.6 bytes a line for this file and 60.2 for {@code
     * implementation rationale}. So it truncates almost nothing that was written to be
     * read as a line, and everything that was not: a bundle, a base64 blob, a
     * one-line JSON document.
     *
     * <p><b>Characters and not bytes</b>, which is the other way round from
     * {@link Window#MAX_WINDOW_BYTES} and for the reason that constant gives
     * from its own side: that one bounds a transport, where what matters is what
     * arrives in a frame, and this one bounds what a reader is shown. {@link
     * #MAX_MATCHES} keeps the frame small enough that the transport question
     * does not arise — fifty times this, even at four bytes a character, is
     * under a tenth of {@code MAX_WINDOW_BYTES}.
     */
    public static final int MAX_LINE_CHARS = 200;

    public Needle {
        if (text == null || text.isBlank()) {
            // Blank and not merely empty. Every line contains the empty string,
            // and a line of source almost always contains a space, so either one
            // is a search that returns the first MAX_MATCHES lines of the tree
            // and calls them matches — the confident empty answer inverted.
            throw new IllegalArgumentException(
                    "a search needs something to look for, and '" + text + "' would match"
                            + " every line there is");
        }
    }

    /**
     * Whether this needle is somewhere in that line.
     *
     * <h2>The case fold is the platform's and never the machine's</h2>
     *
     * <p><b>{@code line.toLowerCase().contains(text.toLowerCase())} is wrong,
     * and it is wrong in the way this module exists to prevent.</b> The
     * no-argument overload folds in the <em>default locale</em>, and measured on
     * JDK 21.0.8: {@code "PUBLIC".toLowerCase()} under a Turkish default is
     * {@code publıc} with a dotless {@code ı}, which does not contain {@code
     * public}. A client on a Turkish desktop and a server in a container would
     * then return different matches for one search, with nothing in either
     * answer to say which fold it used — and every test on either machine alone
     * would pass.
     *
     * <p>{@code regionMatches(true, …)} folds per character through {@code
     * Character.toUpperCase(Character.toLowerCase(c))}, which takes no locale at
     * all. Measured on the same JVM, it matches {@code I} against {@code i}
     * whatever the default is. {@code toLowerCase(Locale.ROOT)} would also fix
     * the locale, and this is preferred over it because there is no second
     * string to allocate per line and because a reader cannot leave the argument
     * off by accident.
     *
     * <h2>The one method a second backend would replace</h2>
     *
     * <p><b>The seam, when there is one, is here.</b> The spec's §3a asks that
     * the first implementation avoid foreclosing a regex or a shelled-out engine
     * and says what that means in practice: the matching function takes a line
     * and answers whether it matched, and nothing about how it matched reaches
     * the wire, the providers or the tool. This method is that function, and
     * everything around it — the caps, the offsets, the collection — is the same
     * whichever way a line is compared.
     *
     * <p>Not extracted into an interface today, and that is this project's rule
     * rather than an oversight: one implementation behind an interface is a
     * guess about what varies, and two is a measurement.
     *
     * <p>The scan below costs the line's length times the needle's, worst case,
     * with no state carried between positions. The case-sensitive path is {@code
     * String.contains}, which is the same shape and faster.
     */
    public boolean matches(String line) {
        if (!ignoreCase) {
            return line.contains(text);
        }
        int last = line.length() - text.length();
        for (int at = 0; at <= last; at++) {
            if (line.regionMatches(true, at, text, 0, text.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add every line of one file that this needle names to {@code into}.
     *
     * <h2>One budget across the whole search, not one per file</h2>
     *
     * <p>{@code into} is passed in and appended to, as {@code
     * FileSearch.matching} takes its hits, and for the same reason: a caller
     * walking several roots keeps one allowance across all of them. A per-file
     * cap would return {@link #MAX_MATCHES} matches from every file in a tree,
     * which is the bound that does not bound anything.
     *
     * <h2>The return value is <em>a match was thrown away</em>, and not <em>the
     * list is full</em></h2>
     *
     * <p>The distinction is {@link Window#cut}'s "the end of the file outranks
     * both", arriving here. A search that found exactly {@link #MAX_MATCHES} and
     * had nothing else to find is complete, and reporting {@link Found#MATCHES}
     * for it would send a model off narrowing a needle that was already right —
     * the same wasted turn {@link Span#BYTES} exists to prevent on the read
     * side. So this says true only when a line really did match and had nowhere
     * to go, which a caller can then trust as its signal to stop walking.
     *
     * <p>A caller must therefore keep asking while this returns false even after
     * {@code into} has reached the cap: the next file may have nothing in it,
     * and the search is only capped once something is actually lost.
     *
     * @param path what to record as the file each match is in. A string, because
     *     {@link Found.Match} crosses a wire and names a file on a filesystem
     *     the far side cannot see — {@code FileReply.paths} says why that is a
     *     string too
     * @param lines the file, already split by whoever owns the disk. This record
     *     never opens a file and never counts one, exactly as {@link Window}
     *     does not
     * @param into the matches so far
     * @return whether a match was found and discarded for want of room
     */
    public boolean find(String path, List<String> lines, List<Found.Match> into) {
        for (int at = 0; at < lines.size(); at++) {
            String line = lines.get(at);
            if (!matches(line)) {
                continue;
            }
            if (into.size() >= MAX_MATCHES) {
                return true;
            }
            String shown = shorten(line);
            into.add(new Found.Match(path, at, shown, shown.length() != line.length()));
        }
        return false;
    }

    /**
     * One line, cut to {@link #MAX_LINE_CHARS} on a boundary a character
     * survives.
     *
     * <p><b>A plain {@code substring} at the cap is not enough, and the failure
     * is silent.</b> Measured on JDK 21.0.8: an emoji is two chars, and a string
     * cut between them holds a lone high surrogate whose {@code
     * getBytes(UTF_8)} is a single {@code 0x3F} — a question mark, substituted
     * with no error. That is the same defect as {@code new String(bytes,
     * UTF_8)}, which {@code LocalProvider}'s decoder comment refuses for the
     * same reason one level up; the strict encoder this project uses everywhere
     * else raises {@code MalformedInputException} on it instead, which would
     * turn a long line into a failed answer.
     *
     * <p>So a cut that would land inside a character takes the whole character
     * off, and the line comes back one shorter than the cap. Losing a character
     * costs nothing here — the match already carries the offset that reads the
     * line in full.
     */
    private static String shorten(String line) {
        if (line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        int end = MAX_LINE_CHARS;
        if (Character.isHighSurrogate(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
