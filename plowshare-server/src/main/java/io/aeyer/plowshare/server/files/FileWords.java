package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.Excerpt;
import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.FileResult;
import io.aeyer.plowshare.protocol.ImageFormat;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Replacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Every sentence a model reads about a file action, written from the facts the
 * file side reported — the one renderer.
 *
 * <h2>Why the server words it</h2>
 *
 * <p>The spec (2026-09-30, the file side reports facts; the server words them):
 * a write, an edit, a delete or a move is done by whichever machine holds the
 * file — this server's {@link LocalProvider}, the Java client, the terminal
 * client — and each used to write its own answer. Measured on 2026-09-30, the
 * answers had drifted: the terminal client sent one-line answers the Java
 * client had stopped sending, and the two disagreed on four wordings. Now each
 * sends a {@link FileResult} and <b>this class is the only author</b>: one set
 * of words, one test suite ({@code FileWordsTest}), one place for the rule that
 * a change reports the state it left.
 *
 * <p><b>Every op, since step 2 of that spec.</b> A read's, a stat's, a glob's
 * and a search's refusals, the fence's reasons, a picture a read named, and a
 * file side that cannot be asked at all are worded here too; a client carries
 * no sentence a model reads but a {@code run}'s own consent and bounds. Where
 * two sides used to say one thing two ways, the words here are the Java
 * client's, and where a client and this server's own provider differ on
 * purpose ("this client" / "this server"), {@code onClient} is the difference.
 *
 * <h2>What an edit shows, and why</h2>
 *
 * <p>Measured on one eleven-hour run (2026-09-29/30, 306 {@code file_edit}
 * calls): 27 were refused because {@code old} was not in the file, and every one
 * was followed by a read and then an edit that worked. Eleven were a model
 * editing a file it had already edited, from its copy from before that edit;
 * nine were paraphrases, two were indentation, one was a U+2011 typed for a
 * hyphen. So a success shows the lines the new text now occupies, and a miss
 * shows the nearest lines with what differs named — each the read the model
 * would otherwise have spent a turn on, cut to the lines that matter.
 *
 * <p><b>Numbered as a read numbers, and not per line.</b> A read carries a
 * file's lines bare, with one bracketed line in front saying which lines they
 * are, counted from 0 as {@code offset} counts. This does the same: what is
 * shown is what a model copies into {@code old}, and a number in front of every
 * line is text that is not in the file, which is the mismatch this exists to
 * end.
 *
 * <h2>Where the file is, said once</h2>
 *
 * <p>A client's facts are about a file on the person's machine, and the answer
 * says so ({@code " on this machine"}), as the clients' own sentences did; the
 * server's own files are not qualified. That is the only difference between
 * the two, and it is a parameter rather than two sets of words.
 *
 * <h2>No toolchain is named</h2>
 *
 * <p>Nothing here names a language, a build tool or a file type: the words are
 * about lines, paths and characters. {@code HarnessStaysLanguageNeutralTest}
 * scans this file with the rest of the harness.
 */
public final class FileWords {

    private FileWords() {
    }

    /**
     * What an edit that was not made needs to add to "there is no file at …":
     * the fourteen such calls in the run this class cites were each a model
     * trying to create a file with {@code old} and {@code new}.
     */
    public static final String TO_CREATE =
            "; to create it, send {\"path\", \"content\"} with the whole file";

    /** The opening of a block that shows every line of the file. */
    static final String WHOLE = "[The whole file";

    private static final String CUT = " [cut]";

    private static final String ON_THIS_MACHINE = " on this machine";

    /** The ops that change a file; every other op's refusal is about reading one. */
    private static final Set<String> CHANGES =
            Set.of(FileRequest.WRITE, FileRequest.EDIT, FileRequest.DELETE, FileRequest.MOVE);

    // --- a change that was made ---------------------------------------------------------

    /**
     * What an edit shows once it is made: where the new text is now, with {@link
     * Replacement#CONTEXT_LINES} lines either side. Empty when the facts carry no
     * lines, which only a file side built wrong sends.
     */
    public static String edited(FileResult facts) {
        Objects.requireNonNull(facts, "facts");
        Excerpt excerpt = facts.excerpt();
        if (excerpt == null) {
            return "";
        }
        if (excerpt.total() == 0) {
            return WHOLE + " is empty now.]";
        }
        int context = Replacement.CONTEXT_LINES;
        String lead;
        if (Boolean.TRUE.equals(facts.removed())) {
            lead = "The text was removed; this is where it was, with the " + context
                    + " lines either side:";
        } else {
            int first = facts.first() == null ? excerpt.from() : facts.first();
            int last = facts.last() == null ? first : facts.last();
            lead = "The new text is on " + range(first, last) + " now, shown with the " + context
                    + " lines either side:";
        }
        return lead + "\n" + block(excerpt);
    }

    // --- a change that was not made -----------------------------------------------------

    /**
     * Why a change was not made, from the facts of its refusal.
     *
     * @param onClient whether the file is on a client's machine rather than this
     *     server's, which the sentence says where the clients always said it
     */
    public static String refusal(FileResult facts, boolean onClient) {
        Objects.requireNonNull(facts, "facts");
        String where = onClient ? ON_THIS_MACHINE : "";
        String path = facts.path();
        String op = facts.op();
        if (!CHANGES.contains(op)) {
            return notRead(facts, onClient);
        }
        return switch (facts.kind()) {
            case FileResult.NO_MATCH -> {
                String refused = "the text to replace is not in " + path + "; it must match the"
                        + " file exactly, spaces and line breaks included — read the file again and"
                        + " copy it";
                String why = nearest(facts);
                yield why.isEmpty() ? refused : refused + "\n\n" + why;
            }
            case FileResult.MANY_MATCHES -> "the text to replace occurs " + facts.count()
                    + " times in " + path + ", and an edit replaces exactly one; include more of"
                    + " the surrounding text so it matches once";
            case FileResult.NO_FILE -> "there is no file at " + path + where + ", so nothing was "
                    + done(op) + (FileRequest.EDIT.equals(op) ? TO_CREATE : "");
            case FileResult.NOT_TEXT -> "path " + path + " is not UTF-8 text" + where
                    + ", so it cannot be " + done(op) + "; send the whole file as content";
            case FileResult.REFUSED -> refused(facts, where);
            default -> unknown(facts, where);
        };
    }

    /**
     * Why a read, a stat, a glob, a search or a run's working directory was
     * refused. The fence's reasons are {@link #refused}'s words for a change
     * too; what differs is what a refusal of a read says it could not do.
     */
    private static String notRead(FileResult facts, boolean onClient) {
        String where = onClient ? ON_THIS_MACHINE : "";
        String side = onClient ? "client" : "server";
        String path = facts.path();
        String reason = facts.reason() == null ? "" : facts.reason();
        switch (facts.kind()) {
            case FileResult.NO_FILE:
                return "there is no file at " + path + where;
            case FileResult.NOT_TEXT:
                if (FileResult.UNCONVERTED.equals(reason)) {
                    return "path " + path + " is " + described(facts.format()) + ", and this"
                            + " client reads text files only; the MCP client converts these and"
                            + " the terminal client does not yet";
                }
                return "path " + path + " is not UTF-8 text; these tools read text files only";
            case FileResult.UNAVAILABLE:
                return unavailable(facts);
            case FileResult.REFUSED:
                break;
            default:
                return unknown(facts, where);
        }
        return switch (reason) {
            case FileResult.DIRECTORY -> "path " + path + " is a directory" + where
                    + ", so there is nothing to read";
            case FileResult.NOT_REGULAR -> "path " + path + " is not a regular file" + where
                    + ", so there is nothing to read";
            case FileResult.TOO_LARGE -> "path " + path + " is " + facts.bytes() + " bytes, and"
                    + " this " + side + " will not read more than " + facts.limit() + " at once;"
                    + " name a smaller file";
            case FileResult.FAILED -> "path " + path + " could not be read" + where + ": "
                    + facts.detail();
            case FileResult.DENIED -> "path " + path + " could not be opened" + where + ": "
                    + facts.detail();
            case FileResult.LINE_TOO_WIDE -> "line " + facts.first() + " of this file is "
                    + facts.bytes() + " bytes, and one read carries at most " + facts.limit()
                    + " bytes however few lines are asked for, so no offset or limit returns"
                    + " this line and this file cannot be read. That is a bound on what one"
                    + " answer carries and not on the file. file_grep searches this file without"
                    + " returning it: it gives the offset of every line that contains the text"
                    + " you name, and the first " + Needle.MAX_LINE_CHARS + " characters of that"
                    + " line.";
            case FileResult.UNSERVABLE -> unservable(facts, side);
            case FileResult.NO_PATTERN -> "a glob pattern is required";
            case FileResult.ABSOLUTE_PATTERN -> "'" + facts.pattern() + "' is an absolute"
                    + " pattern, and a pattern is matched against paths relative to a root; write '"
                    + facts.pattern().substring(1) + "' instead";
            case FileResult.BAD_PATTERN -> "'" + facts.pattern() + "' is not a usable glob: "
                    + facts.detail();
            case FileResult.TOO_MANY_WILDCARDS -> "'" + facts.pattern() + "' has "
                    + facts.count() + " '**/' segments and at most " + facts.limit()
                    + " are expanded; one is almost always what is meant";
            case FileResult.TOO_MANY_MATCHES -> "more than " + facts.limit() + " files match"
                    + where + "; narrow the pattern rather than being handed a prefix of the"
                    + " answer";
            case FileResult.UNLISTABLE -> "the tree under " + path + " could not be "
                    + (FileRequest.GREP.equals(facts.op()) ? "searched" : "listed") + " in full: "
                    + facts.detail();
            case FileResult.UNKNOWN_OP -> "this " + side + " does not know how to '"
                    + facts.op() + "'; it was built before the server that asked, and the two"
                    + " builds need matching";
            case FileResult.ENCRYPTED -> converted(facts)
                    + "it is encrypted, and this client has no password for it";
            case FileResult.DAMAGED -> converted(facts) + "it could not be parsed; the file is"
                    + " damaged, or it is a variant of the format this client does not read";
            case FileResult.IMAGE_REFUSED -> imageRefused(facts);
            case FileResult.IMAGE_UNREACHED -> "path " + path + " is a " + facts.format()
                    + " image, and it could not be uploaded because the server was not reached: "
                    + facts.detail();
            // The fence and the other rules a read shares with a change.
            default -> refused(facts, where);
        };
    }

    private static String unservable(FileResult facts, String side) {
        if ("needle".equals(facts.argument())) {
            return "this request asks for a search this " + side + " cannot run: a search needs"
                    + " something to look for, and '" + facts.detail() + "' would match every"
                    + " line there is";
        }
        String head = "this request asks for a window this " + side + " cannot serve: ";
        if ("offset".equals(facts.argument())) {
            return head + "a window's offset is a line number and cannot be negative; got "
                    + facts.first();
        }
        return head + "a window must carry at least one line; got a limit of " + facts.limit();
    }

    private static String converted(FileResult facts) {
        return "path " + facts.path() + " is " + described(facts.format()) + ", which this"
                + " client reads, and this one could not be converted: ";
    }

    /**
     * Which of the image store's three refusals this was, as a sentence that
     * says what to do about it: convert it, shrink it, or tell whoever runs the
     * server. The status decides, and no arm reads the store's prose to work out
     * which it is — it carries that prose instead.
     */
    private static String imageRefused(FileResult facts) {
        String head = "path " + facts.path() + " is a " + facts.format() + " image, and ";
        int status = facts.status() == null ? 0 : facts.status();
        return switch (status) {
            case 415 -> head + "the server will not take that format: " + facts.detail()
                    + ". Both halves read one list -- " + ImageFormat.accepted() + " -- so this"
                    + " means this client and that server are different builds; convert the"
                    + " file to something they both take, or have the two matched.";
            case 413 -> head + "at " + facts.bytes() + " bytes it is over what the server will"
                    + " hold: " + facts.detail() + ". Shrink or crop it and read it again;"
                    + " nothing about this path or this workspace is wrong.";
            case 400 -> head + "the server has nowhere to put it: " + facts.detail()
                    + ". That is a deployment that cannot hold any image, so no other file and"
                    + " no other path gets a different answer; whoever runs the server fixes it.";
            default -> head + "the server refused to name it: " + facts.detail();
        };
    }

    /** A document's format as a sentence names it: "a PDF", "a png image". */
    private static String described(String format) {
        if (format == null) {
            return "a file of a kind these tools do not read";
        }
        for (ImageFormat image : ImageFormat.values()) {
            if (image.declared().equals(format)) {
                return "a " + format + " image";
            }
        }
        return "a " + format;
    }

    /**
     * Why the file side could not be asked at all: its root went, or it failed
     * while answering. What an outage reads as, to whoever reads the run's end.
     */
    public static String unavailable(FileResult facts) {
        Objects.requireNonNull(facts, "facts");
        String reason = facts.reason() == null ? "" : facts.reason();
        return switch (reason) {
            case FileResult.ROOT_GONE -> "the workspace " + facts.path() + " this session was"
                    + " set to is no longer there";
            case FileResult.ROOT_NOT_DIRECTORY -> "the workspace " + facts.path() + " this"
                    + " session was set to is no longer a directory";
            case FileResult.INTERNAL -> "this client failed while answering: " + facts.detail();
            default -> "the client could not answer the " + facts.op() + ", for a reason this"
                    + " server does not know (" + facts.kind() + (facts.reason() == null ? ""
                    : ", " + facts.reason()) + ")";
        };
    }

    /**
     * The one line a read of a picture hands over: named rather than read, the
     * id, and what the id is for. A client's picture was uploaded to be named;
     * this server's own was not copied at all, and the line says which.
     */
    public static String named(FileResult facts, boolean onClient) {
        Objects.requireNonNull(facts, "facts");
        String head = "The file " + facts.path() + " is a " + facts.format() + " image, so it"
                + " is named rather than read: ";
        String use = ". Hand that id to an agent that can see, as agent_run's 'images', and it"
                + " will be shown the picture -- you will not. ";
        return onClient
                ? head + "no tool here answers with a picture. It was uploaded to the server and"
                        + " its id is " + facts.image() + use + "The bytes were copied to get"
                        + " there, which is what a workspace on another machine costs: editing"
                        + " this file now does not change what that id resolves to."
                : head + "no tool in this server answers with a picture. Its id is "
                        + facts.image() + use + "Nothing was copied: the id points at this file,"
                        + " and it stops resolving if the file goes or if this project stops"
                        + " reaching it.";
    }

    /**
     * What a reply that is not an answer says, from its facts or — from a
     * client built before facts — its own words; null when it carries neither.
     * For a harness reader of the channel ({@code ChannelDefinitions}, {@code
     * ChannelHooks}) that reports a refusal rather than throwing one.
     */
    public static String said(FileReply reply) {
        Objects.requireNonNull(reply, "reply");
        FileResult facts = reply.result();
        if (facts == null) {
            return reply.sentence();
        }
        return FileResult.UNAVAILABLE.equals(facts.kind()) ? unavailable(facts)
                : refusal(facts, true);
    }

    private static String refused(FileResult facts, String where) {
        String path = facts.path();
        String op = facts.op();
        String reason = facts.reason() == null ? "" : facts.reason();
        return switch (reason) {
            case FileResult.EMPTY_OLD -> "an edit needs the text to replace, and it was empty;"
                    + " send the exact text, or send the whole file as content";
            case FileResult.MISSING -> missing(op, facts.argument());
            case FileResult.NO_WORKSPACE -> "no workspace is set for this session, so nothing on"
                    + " this machine is reachable; whoever is using this client sets one";
            case FileResult.NO_PATH -> "this request named no path";
            case FileResult.UNNAMEABLE -> "'" + path + "' is not a path this machine can even"
                    + " name: " + facts.detail();
            case FileResult.WORKSPACE_MOVED -> "path " + path + " was inside this session's"
                    + " workspace, but the workspace moved while this run was going and is now "
                    + roots(facts.roots()) + "; ask for your roots again rather than the paths you"
                    + " had";
            case FileResult.OUTSIDE -> "path " + path + " is outside this session's workspace,"
                    + " which is " + roots(facts.roots()) + "; ask for the roots you have rather"
                    + " than guessing at paths";
            case FileResult.HIDDEN -> "path " + path + " is inside this session's workspace,"
                    + " which is " + roots(facts.roots()) + ", but hidden: a name in it starts"
                    + " with '.', and no file tool reaches a hidden path";
            case FileResult.DIRECTORY -> "path " + path + " is a directory" + where
                    + ", so it cannot be " + done(op) + " as a file";
            case FileResult.LINK -> "path " + path + " is a link" + where + ", so it cannot be "
                    + done(op) + " as a file; name the file itself";
            case FileResult.NOT_REGULAR -> "path " + path + " is not a regular file" + where
                    + ", so it cannot be " + done(op);
            case FileResult.TOO_LARGE -> "path " + path + " is " + facts.bytes() + " bytes" + where
                    + ", and an edit loads no more than " + facts.limit() + " at once; send the"
                    + " whole file as content";
            case FileResult.EXISTS -> "path " + path + " already exists" + where + ", and this"
                    + " write may only create a file; read it before replacing it";
            case FileResult.DESTINATION_EXISTS -> "path " + facts.to() + " already exists"
                    + where + ", and a move never replaces a file; delete it first or choose"
                    + " another destination";
            case FileResult.FAILED -> FileRequest.MOVE.equals(op) && facts.to() != null
                    ? "path " + path + " could not be moved to " + facts.to() + where + ": "
                            + facts.detail()
                    : "path " + path + " could not be " + done(op) + where + ": "
                            + facts.detail();
            default -> unknown(facts, where);
        };
    }

    private static String missing(String op, String argument) {
        if ("replacing".equals(argument)) {
            return "an edit needs the text to replace; nothing was sent";
        }
        if ("to".equals(argument)) {
            return "a move needs the path to move the file to; nothing was sent";
        }
        if ("content".equals(argument)) {
            return FileRequest.EDIT.equals(op)
                    ? "an edit needs the text to put in its place; nothing was sent"
                    : "a write needs the content to write; nothing was sent";
        }
        return "the " + op + " needs its " + argument + "; nothing was sent";
    }

    /**
     * A kind or a reason this build does not know — a file side built after it.
     * Said as unknown rather than read as something known, and never blank: a
     * blank tool result reads to a model as a tool that does not work.
     */
    private static String unknown(FileResult facts, String where) {
        return "the " + facts.op() + (facts.path() == null ? "" : " of " + facts.path())
                + (CHANGES.contains(facts.op()) ? " was not made" : " was refused") + where
                + ", for a reason this server does not know (" + facts.kind()
                + (facts.reason() == null ? "" : ", " + facts.reason()) + ")";
    }

    private static String done(String op) {
        if (op == null) {
            return "changed";
        }
        return switch (op) {
            case FileRequest.EDIT -> "edited";
            case FileRequest.WRITE -> "written";
            case FileRequest.DELETE -> "deleted";
            case FileRequest.MOVE -> "moved";
            default -> "changed";
        };
    }

    private static String roots(List<String> roots) {
        return roots == null || roots.isEmpty() ? "empty" : String.join(", ", roots);
    }

    // --- what is nearest a miss -------------------------------------------------------

    /**
     * What to add to "not in the file": why, when that can be told, and the lines
     * to copy from. Empty when nothing in the file resembles {@code old}.
     */
    private static String nearest(FileResult facts) {
        String near = facts.near() == null ? "" : facts.near();
        switch (near) {
            case FileResult.EMPTY_FILE:
                return "The file is empty.";
            case FileResult.LOOKALIKE: {
                List<String> named = new ArrayList<>();
                for (FileResult.Difference difference : facts.differences() == null
                        ? List.<FileResult.Difference>of() : facts.differences()) {
                    named.add(describe(difference.sent()) + " where the file has "
                            + describe(difference.there()));
                }
                return "`old` has " + listed(named, named.size()) + ". The file's text there:\n"
                        + block(facts.excerpt());
            }
            case FileResult.WHITESPACE:
                return "`old` differs from the file only in whitespace/indentation. The file's"
                        + " text there:\n" + block(facts.excerpt());
            default:
                break;
        }
        StringBuilder out = new StringBuilder();
        List<Integer> foreign = facts.foreign() == null ? List.of() : facts.foreign();
        if (!foreign.isEmpty()) {
            List<String> named = new ArrayList<>();
            for (int cp : foreign) {
                int ascii = Replacement.lookalikeOf(cp);
                named.add(describe(cp) + (ascii < 0 ? "" : ", which looks like " + describe(ascii)));
            }
            int total = facts.count() == null ? named.size() : Math.max(facts.count(), named.size());
            out.append("`old` has ").append(listed(named, total))
                    .append(total == 1 ? ", which is" : "; each is").append(" nowhere in the file.");
        }
        if (FileResult.CLOSEST.equals(near) && facts.excerpt() != null) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("The closest lines in the file now are:\n").append(block(facts.excerpt()));
        }
        return out.toString();
    }

    // --- the shared shape -----------------------------------------------------------

    /**
     * The excerpt's lines under the bracketed line saying which they are — the
     * whole file said as such when it is every line, uncut.
     */
    static String block(Excerpt excerpt) {
        int from = excerpt.from();
        int to = excerpt.to();
        List<String> lines = excerpt.lines();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (excerpt.gap() != null && i == excerpt.gap()) {
                int skippedFrom = from + excerpt.gap();
                int skippedTo = skippedFrom + excerpt.omitted() - 1;
                body.append("\n[… ").append(excerpt.omitted()).append(" lines not shown: ")
                        .append(skippedFrom).append(" to ").append(skippedTo).append(" …]");
            }
            body.append('\n').append(lines.get(i));
            if (excerpt.clipped().contains(i)) {
                body.append(CUT);
            }
        }
        boolean elided = excerpt.gap() != null;
        boolean cut = !excerpt.clipped().isEmpty();
        StringBuilder header = new StringBuilder(excerpt.whole()
                ? WHOLE + ", " + range(from, to)
                : "[" + capitalised(range(from, to)));
        header.append(" of ").append(excerpt.total()).append(", counting from 0 as offset does");
        if (elided) {
            header.append("; the middle is left out where marked");
        }
        if (cut) {
            header.append("; a line ending").append(CUT).append(" is longer than ")
                    .append(Replacement.MAX_SHOWN_LINE_CHARS)
                    .append(" characters and is shown only that far — read that line at its own"
                            + " offset");
        }
        return header.append(".]").append(body).toString();
    }

    private static String range(int from, int to) {
        return from == to ? "line " + from : "lines " + from + " to " + to;
    }

    private static String capitalised(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // --- characters -----------------------------------------------------------------

    /**
     * A printable ASCII character quoted; any other by number and its Unicode
     * name — this server's {@link Character#getName}, which is why no side that
     * holds a file keeps a table of names.
     */
    static String describe(int cp) {
        if (cp >= 0x20 && cp < 0x7F) {
            return "'" + (char) cp + "'";
        }
        String name = Character.isValidCodePoint(cp) ? Character.getName(cp) : null;
        return String.format("U+%04X", cp) + (name == null ? "" : " " + name);
    }

    /**
     * At most {@link Replacement#MAX_LISTED}, then how many more: a pasted
     * paragraph of curly quotes is one fact.
     *
     * @param total how many there are, of which {@code items} are the first
     */
    private static String listed(List<String> items, int total) {
        int most = Replacement.MAX_LISTED;
        List<String> kept = items.size() > most ? items.subList(0, most) : items;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                out.append(i == kept.size() - 1 && total <= most ? ", and " : ", ");
            }
            out.append(kept.get(i));
        }
        if (total > most) {
            out.append(", and ").append(total - most).append(" more");
        }
        return out.toString();
    }
}
