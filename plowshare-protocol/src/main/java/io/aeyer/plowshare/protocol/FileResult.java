package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Objects;

/**
 * What a change to a file did, or why it was not made — as facts, for the
 * server to word.
 *
 * <h2>Why facts and not a sentence</h2>
 *
 * <p>The server owns the file tools, and a model reads what it is told after a
 * file action. Until this record, that was written by whichever side held the
 * file — the server's own provider, the Java client, the terminal client — and
 * carried back as {@link FileReply#sentence()}: one message with three authors,
 * measured on 2026-09-30 to have drifted (a client not rebuilt kept sending the
 * old one-line answer; two clients disagreed on four wordings). The spec
 * (2026-09-30, the file side reports facts; the server words them) moves every
 * word to the server: <b>the side with the file does only what must be local
 * and answers with this</b>, and one renderer on the server turns it into what
 * the model reads.
 *
 * <h2>One record, and the fields a kind does not use are null</h2>
 *
 * <p>{@link FileRequest}'s shape and its argument: {@link #kind} says which
 * fields mean anything, the factories are how it is built, and a reader who
 * wants to know what an edit that did not match carries reads {@link #noMatch}.
 * <b>{@link #kind} and {@link #reason} are strings and not enums</b> for {@link
 * FileReply#outcome}'s reason — a client built against a later server must still
 * bind — and a renderer meeting one it does not know says so rather than
 * guessing.
 *
 * <h2>Versioned</h2>
 *
 * <p>{@link #version} is {@link #VERSION} from every factory. A server reading
 * a result from a later client sees a larger number, and anything it does not
 * recognise in it is worded as unknown rather than read as something it knows.
 * A reply with no result at all is a client built before this record, and the
 * server passes its {@link FileReply#sentence()} through.
 *
 * @param version {@link #VERSION}
 * @param kind what happened: {@link #EDITED}, {@link #WRITTEN}, {@link #DELETED},
 *     {@link #MOVED} for a change that was made; {@link #NO_MATCH}, {@link
 *     #MANY_MATCHES}, {@link #NO_FILE}, {@link #NOT_TEXT}, {@link #REFUSED} for
 *     one that was not
 * @param op the {@link FileRequest#op()} this answers
 * @param path the file, spelled as the request spelled it — for a refusal by
 *     the fence, the path the fence refused, which for a move may be its
 *     destination
 * @param to a move's destination, as the request spelled it
 * @param reason for {@link #REFUSED}, which rule: {@link #NO_WORKSPACE}, {@link
 *     #NO_PATH}, {@link #UNNAMEABLE}, {@link #WORKSPACE_MOVED}, {@link #OUTSIDE},
 *     {@link #DIRECTORY}, {@link #LINK}, {@link #NOT_REGULAR}, {@link
 *     #TOO_LARGE}, {@link #EXISTS}, {@link #DESTINATION_EXISTS}, {@link
 *     #FAILED}, {@link #MISSING}, {@link #EMPTY_OLD}, and the other ops' reasons
 *     declared below them; for {@link #NOT_TEXT}, why it is not: {@link
 *     #NOT_UTF8} or {@link #UNCONVERTED}; for {@link #UNAVAILABLE}, {@link
 *     #ROOT_GONE}, {@link #ROOT_NOT_DIRECTORY} or {@link #INTERNAL}
 * @param argument for {@link #MISSING}, the {@link FileRequest} field that was
 *     absent: {@code replacing}, {@code content} or {@code to}
 * @param roots for {@link #OUTSIDE}, {@link #HIDDEN} and {@link #WORKSPACE_MOVED},
 *     the roots this session holds now
 * @param detail for {@link #FAILED} and {@link #UNNAMEABLE}, what the file
 *     system said — the platform's words, not the client's
 * @param bytes how big the file is: written, removed, moved, or — for {@link
 *     #TOO_LARGE} — found
 * @param limit for {@link #TOO_LARGE}, the most the file side will load
 * @param count for {@link #MANY_MATCHES}, how many times {@code old} occurs; for
 *     {@link #NO_MATCH}, how many characters {@link #foreign} stands for
 * @param lines how many lines were written or removed, counted as {@link
 *     String#lines()} counts; null when the file side did not count them
 * @param first for {@link #EDITED}, the first line the new text is on — or, for
 *     text replaced by nothing, the line it was on
 * @param last for {@link #EDITED}, the last line the new text is on
 * @param removed for {@link #EDITED}, whether the replacement was empty
 * @param excerpt for {@link #EDITED}, the new text's lines with {@link
 *     Replacement#CONTEXT_LINES} either side; for {@link #NO_MATCH}, the part
 *     of the file nearest {@code old}, or null when nothing resembles it
 * @param near for {@link #NO_MATCH}, what the excerpt is: {@link #LOOKALIKE},
 *     {@link #WHITESPACE}, {@link #CLOSEST}, or {@link #EMPTY_FILE}; null when
 *     nothing in the file resembles {@code old}
 * @param differences for a {@link #LOOKALIKE} match, each distinct pair of a
 *     character in {@code old} and the file's character at that place, in the
 *     order they first occur
 * @param foreign for {@link #NO_MATCH} with no look-alike or whitespace match,
 *     the non-ASCII code points of {@code old} that are nowhere in the file —
 *     at most {@link Replacement#MAX_LISTED}, with {@link #count} saying how
 *     many there were
 * @param format for {@link #NAMED}, {@link #UNCONVERTED}, {@link #ENCRYPTED},
 *     {@link #DAMAGED}, {@link #IMAGE_REFUSED} and {@link #IMAGE_UNREACHED},
 *     what the file is: {@code ImageFormat.declared()} for a picture, the
 *     converter's own name ({@code PDF}) for a document
 * @param image for {@link #NAMED}, the id the picture was named with
 * @param status for {@link #IMAGE_REFUSED}, the status the server's image
 *     store answered the upload with
 * @param pattern for {@link #ABSOLUTE_PATTERN}, {@link #BAD_PATTERN} and
 *     {@link #TOO_MANY_WILDCARDS}, the glob as the request spelled it
 *
 * <h2>Not only changes</h2>
 *
 * <p>Step 2 of the spec: a read, a stat, a glob and a search answer their
 * refusals with this record too, and a read of a picture answers with {@link
 * #NAMED} rather than with a line the file side wrote. {@link #op} says which
 * op it answers, and the kinds and reasons below are shared by all of them —
 * a fence is one fence whichever op met it. Only {@link FileRequest#RUN}'s own
 * refusals (its consent, its shell, its bounds) are still sentences; its
 * working directory's fence is facts like every other path's.
 */
public record FileResult(
        Integer version,
        String kind,
        String op,
        String path,
        String to,
        String reason,
        String argument,
        List<String> roots,
        String detail,
        Long bytes,
        Long limit,
        Integer count,
        Integer lines,
        Integer first,
        Integer last,
        Boolean removed,
        Excerpt excerpt,
        String near,
        List<Difference> differences,
        List<Integer> foreign,
        String format,
        String image,
        Integer status,
        String pattern) {

    /** What every factory stamps; a larger one is a client built after this. */
    public static final int VERSION = 1;

    // --- kinds --------------------------------------------------------------------

    /** An edit was made. */
    public static final String EDITED = "edited";
    /** A whole file was written. */
    public static final String WRITTEN = "written";
    /** A file was removed. */
    public static final String DELETED = "deleted";
    /** A file was renamed. */
    public static final String MOVED = "moved";
    /** {@code old} is not in the file. */
    public static final String NO_MATCH = "no-match";
    /** {@code old} is in the file more than once. */
    public static final String MANY_MATCHES = "many-matches";
    /** There is no file at the path. */
    public static final String NO_FILE = "no-file";
    /** The file is not text this side can change. */
    public static final String NOT_TEXT = "not-text";
    /** A fence or a rule would not; {@link #reason} says which. */
    public static final String REFUSED = "refused";
    /**
     * A read, a stat or a search of a picture, answered with the id it was
     * named with rather than with lines: the server words the one line a
     * reader is handed, and cuts and counts it as it would a file's.
     */
    public static final String NAMED = "named";
    /**
     * The file side cannot be asked at all — its root went, or it failed —
     * answered {@link FileReply#UNAVAILABLE}; {@link #reason} says which.
     */
    public static final String UNAVAILABLE = "unavailable";

    // --- reasons ------------------------------------------------------------------

    /** No workspace is set for this session. */
    public static final String NO_WORKSPACE = "no-workspace";
    /** The request named no path. */
    public static final String NO_PATH = "no-path";
    /** The path is not one this machine can name at all. */
    public static final String UNNAMEABLE = "unnameable";
    /** The path was in this session's workspace before the workspace moved. */
    public static final String WORKSPACE_MOVED = "workspace-moved";
    /** The path is outside every root this session holds, or hidden. */
    public static final String OUTSIDE = "outside";
    /** The path is a directory. */
    public static final String DIRECTORY = "directory";
    /** The path, as named, is a link. */
    public static final String LINK = "link";
    /** The path is neither a regular file nor a directory. */
    public static final String NOT_REGULAR = "not-regular";
    /** The file is larger than this side will load. */
    public static final String TOO_LARGE = "too-large";
    /** A create-only write found a file there. */
    public static final String EXISTS = "exists";
    /** A move's destination is taken. */
    public static final String DESTINATION_EXISTS = "destination-exists";
    /** The file system failed; {@link #detail} is what it said. */
    public static final String FAILED = "failed";
    /** A field the op needs was not sent; {@link #argument} names it. */
    public static final String MISSING = "missing";
    /** An edit's {@code old} was empty, which occurs everywhere. */
    public static final String EMPTY_OLD = "empty-old";
    /** For {@link #NOT_TEXT}: the bytes are not UTF-8. */
    public static final String NOT_UTF8 = "not-utf8";
    /**
     * The path is inside a root, and a name in it below that root starts with
     * {@code .}: {@code FileAccess}'s "hidden means hidden".
     */
    public static final String HIDDEN = "hidden";
    /** The file could not be opened: its permissions; {@link #detail} is what the system said. */
    public static final String DENIED = "denied";
    /**
     * One line is wider than a whole read carries: {@link #first} is the line,
     * {@link #bytes} its size, {@link #limit} {@link Window#MAX_WINDOW_BYTES}.
     */
    public static final String LINE_TOO_WIDE = "line-too-wide";
    /**
     * The frame asks for something this file side cannot serve; {@link
     * #argument} is the field — {@code offset} ({@link #first} is it), {@code
     * limit} ({@link #limit} is it) or {@code needle} ({@link #detail} is it).
     */
    public static final String UNSERVABLE = "unservable";
    /** A glob was asked for with no pattern. */
    public static final String NO_PATTERN = "no-pattern";
    /** A glob pattern starts with {@code /}, and is matched against relative paths. */
    public static final String ABSOLUTE_PATTERN = "absolute-pattern";
    /** A glob pattern does not compile; {@link #detail} is what the matcher said. */
    public static final String BAD_PATTERN = "bad-pattern";
    /**
     * A glob pattern has more {@code **}{@code /} segments than are expanded:
     * {@link #count} of them, {@link #limit} the most.
     */
    public static final String TOO_MANY_WILDCARDS = "too-many-wildcards";
    /** More files match than one answer lists; {@link #limit} is how many. */
    public static final String TOO_MANY_MATCHES = "too-many-matches";
    /**
     * A directory a walk starts in could not be listed: {@link #path} is it,
     * {@link #detail} what the system said.
     */
    public static final String UNLISTABLE = "unlistable";
    /** The file side does not know {@link #op}: it was built before it. */
    public static final String UNKNOWN_OP = "unknown-op";
    /** For {@link #NOT_TEXT}: a format this file side recognises and does not convert. */
    public static final String UNCONVERTED = "unconverted";
    /** A document this file side converts, locked with a password it does not have. */
    public static final String ENCRYPTED = "encrypted";
    /** A document this file side converts, which its converter could not parse. */
    public static final String DAMAGED = "damaged";
    /**
     * A picture the server's image store would not take: {@link #status} is
     * its answer, {@link #detail} its words, {@link #bytes} the picture's size.
     */
    public static final String IMAGE_REFUSED = "image-refused";
    /** A picture that could not be uploaded because the server was not reached. */
    public static final String IMAGE_UNREACHED = "image-unreached";
    /** For {@link #UNAVAILABLE}: a root this session was set to is no longer there. */
    public static final String ROOT_GONE = "root-gone";
    /** For {@link #UNAVAILABLE}: a root this session was set to is no longer a directory. */
    public static final String ROOT_NOT_DIRECTORY = "root-not-directory";
    /** For {@link #UNAVAILABLE}: the file side failed; {@link #detail} is how. */
    public static final String INTERNAL = "internal";

    // --- what a no-match's excerpt is ---------------------------------------------

    /** {@code old} matches once look-alike characters are folded to ASCII. */
    public static final String LOOKALIKE = "look-alike";
    /** {@code old} matches once whitespace is ignored on both sides. */
    public static final String WHITESPACE = "whitespace";
    /** The window of the file sharing the most lines with {@code old}. */
    public static final String CLOSEST = "closest";
    /** The file has nothing in it. */
    public static final String EMPTY_FILE = "empty-file";

    public FileResult {
        Objects.requireNonNull(kind, "kind");
        roots = roots == null ? null : List.copyOf(roots);
        differences = differences == null ? null : List.copyOf(differences);
        foreign = foreign == null ? null : List.copyOf(foreign);
    }

    /**
     * A character {@code old} has, and the one the file has in its place.
     * UTF-16 units, as {@link String#charAt} gives them: the look-alikes are
     * all in the basic plane.
     */
    public record Difference(int sent, int there) {
    }

    /** Whether this says the change was made. */
    public boolean made() {
        return EDITED.equals(kind) || WRITTEN.equals(kind) || DELETED.equals(kind)
                || MOVED.equals(kind);
    }

    // --- made -----------------------------------------------------------------------

    public static FileResult edited(String path, int first, int last, boolean removed,
            Excerpt excerpt) {
        return new FileResult(VERSION, EDITED, FileRequest.EDIT, path, null, null, null, null,
                null, null, null, null, null, first, last, removed,
                Objects.requireNonNull(excerpt, "excerpt"), null, null, null,
                null, null, null, null);
    }

    public static FileResult written(String path, long bytes, int lines) {
        return new FileResult(VERSION, WRITTEN, FileRequest.WRITE, path, null, null, null, null,
                null, bytes, null, null, lines, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /** @param lines null when the file was too large to count */
    public static FileResult deleted(String path, long bytes, Integer lines) {
        return new FileResult(VERSION, DELETED, FileRequest.DELETE, path, null, null, null, null,
                null, bytes, null, null, lines, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static FileResult moved(String path, String to, long bytes) {
        return new FileResult(VERSION, MOVED, FileRequest.MOVE, path, to, null, null, null, null,
                bytes, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    // --- not made -------------------------------------------------------------------

    /**
     * {@code old} is not in the file, and what the file side found nearest it.
     *
     * @param excerpt null when {@code near} is null or {@link #EMPTY_FILE}
     * @param foreign with {@code foreignCount}, only when neither a look-alike
     *     nor a whitespace match was found; null otherwise
     */
    public static FileResult noMatch(String path, String near, Excerpt excerpt,
            List<Difference> differences, List<Integer> foreign, Integer foreignCount) {
        return new FileResult(VERSION, NO_MATCH, FileRequest.EDIT, path, null, null, null, null,
                null, null, null, foreignCount, null, null, null, null, excerpt, near,
                differences, foreign,
                null, null, null, null);
    }

    public static FileResult manyMatches(String path, int count) {
        return new FileResult(VERSION, MANY_MATCHES, FileRequest.EDIT, path, null, null, null,
                null, null, null, null, count, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static FileResult noFile(String op, String path) {
        return new FileResult(VERSION, NO_FILE, op, path, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static FileResult notText(String op, String path, String reason) {
        return new FileResult(VERSION, NOT_TEXT, op, path, null, reason, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /** A rule whose only datum is the path: {@link #DIRECTORY}, {@link #LINK}, and so on. */
    public static FileResult refused(String op, String reason, String path) {
        return new FileResult(VERSION, REFUSED, op, path, null, reason, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /** {@link #OUTSIDE}, {@link #HIDDEN} or {@link #WORKSPACE_MOVED}: the path, and the roots held now. */
    public static FileResult fenced(String op, String reason, String path, List<String> roots) {
        return new FileResult(VERSION, REFUSED, op, path, null, reason, null, roots, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static FileResult tooLarge(String op, String path, long bytes, long limit) {
        return new FileResult(VERSION, REFUSED, op, path, null, TOO_LARGE, null, null, null,
                bytes, limit, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * {@link #FAILED}, {@link #UNNAMEABLE}, {@link #DENIED} or {@link
     * #UNLISTABLE}: what the file system said.
     */
    public static FileResult failed(String op, String reason, String path, String to,
            String detail) {
        return new FileResult(VERSION, REFUSED, op, path, to, reason, null, null, detail, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public static FileResult destinationExists(String path, String to) {
        return new FileResult(VERSION, REFUSED, FileRequest.MOVE, path, to, DESTINATION_EXISTS,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null,
                null, null, null, null);
    }

    /** @param argument the {@link FileRequest} field that was absent */
    public static FileResult missing(String op, String argument) {
        return new FileResult(VERSION, REFUSED, op, null, null, MISSING, argument, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    // --- the other ops ---------------------------------------------------------------

    /** A picture a read, a stat or a search named rather than read. */
    public static FileResult named(String op, String path, String format, String image) {
        return new FileResult(VERSION, NAMED, op, path, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, format, image, null,
                null);
    }

    /** One line wider than any read carries. */
    public static FileResult lineTooWide(String op, String path, int line, long bytes,
            long limit) {
        return new FileResult(VERSION, REFUSED, op, path, null, LINE_TOO_WIDE, null, null, null,
                bytes, limit, null, null, line, null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * A frame asking for what cannot be served.
     *
     * @param argument {@code offset}, {@code limit} or {@code needle}
     * @param value the offset or the limit sent, for those two; null for a needle
     * @param needle the needle sent, for a needle; null otherwise
     */
    public static FileResult unservable(String op, String argument, Integer value,
            String needle) {
        boolean offset = "offset".equals(argument);
        boolean limit = "limit".equals(argument);
        return new FileResult(VERSION, REFUSED, op, null, null, UNSERVABLE, argument, null,
                needle, null, limit && value != null ? value.longValue() : null, null, null,
                offset ? value : null, null, null, null, null, null, null, null, null, null,
                null);
    }

    /**
     * A glob pattern that cannot be used: {@link #NO_PATTERN}, {@link
     * #ABSOLUTE_PATTERN}, or {@link #BAD_PATTERN} with what the matcher said.
     */
    public static FileResult pattern(String reason, String pattern, String detail) {
        return new FileResult(VERSION, REFUSED, FileRequest.GLOB, null, null, reason, null,
                null, detail, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, pattern);
    }

    /** A glob pattern with {@code count} recursive wildcards, past {@code limit}. */
    public static FileResult tooManyWildcards(String pattern, int count, int limit) {
        return new FileResult(VERSION, REFUSED, FileRequest.GLOB, null, null, TOO_MANY_WILDCARDS,
                null, null, null, null, (long) limit, count, null, null, null, null, null, null,
                null, null, null, null, null, pattern);
    }

    /** More than {@code limit} files match. */
    public static FileResult tooManyMatches(String op, long limit) {
        return new FileResult(VERSION, REFUSED, op, null, null, TOO_MANY_MATCHES, null, null,
                null, null, limit, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /** A document or a picture this side could not turn into lines, and why. */
    public static FileResult unreadable(String op, String kind, String reason, String path,
            String format) {
        return new FileResult(VERSION, kind, op, path, null, reason, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, format, null, null,
                null);
    }

    /** A picture the server's image store refused, with its status and its words. */
    public static FileResult imageRefused(String op, String path, String format, int status,
            String detail, long bytes) {
        return new FileResult(VERSION, REFUSED, op, path, null, IMAGE_REFUSED, null, null,
                detail, bytes, null, null, null, null, null, null, null, null, null, null, format,
                null, status, null);
    }

    /** A picture that could not be uploaded: the server was not reached. */
    public static FileResult imageUnreached(String op, String path, String format,
            String detail) {
        return new FileResult(VERSION, REFUSED, op, path, null, IMAGE_UNREACHED, null, null,
                detail, null, null, null, null, null, null, null, null, null, null, null, format,
                null, null, null);
    }

    /**
     * The file side cannot be asked: {@link #ROOT_GONE} or {@link
     * #ROOT_NOT_DIRECTORY} with the root as {@code path}, or {@link #INTERNAL}
     * with {@code detail}.
     */
    public static FileResult unavailable(String op, String reason, String path, String detail) {
        return new FileResult(VERSION, UNAVAILABLE, op, path, null, reason, null, null, detail,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    /**
     * How many lines {@code bytes} holds, counted as {@link String#lines()}
     * counts them: {@code \n}, {@code \r\n} and a lone {@code \r} each end one,
     * and a last line with no terminator is still a line. Asked of bytes and
     * not of text, so a file that is not UTF-8 is counted without decoding it.
     */
    public static int lineCount(byte[] bytes) {
        int lines = 0;
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '\n' || (b == '\r' && (i + 1 >= bytes.length || bytes[i + 1] != '\n'))) {
                lines++;
            }
        }
        if (bytes.length > 0) {
            byte end = bytes[bytes.length - 1];
            if (end != '\n' && end != '\r') {
                lines++;
            }
        }
        return lines;
    }
}
