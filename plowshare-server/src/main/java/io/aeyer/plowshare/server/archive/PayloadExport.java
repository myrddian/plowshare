package io.aeyer.plowshare.server.archive;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * Where an ejected payload is written before it is taken out of the row, in a
 * format that does not need Plowshare to read.
 *
 * <h2>Server-rooted, said rather than discovered</h2>
 *
 * <p><b>An export lands on the machine this server runs on.</b> Under presence
 * the server may hold no user disk at all — the person's files are on their own
 * box, reached through the file channel — so there is no "their disk" to write
 * to, and an export that tried to would either fail on a headless deployment or
 * quietly write to whichever machine happened to be attached. <b>That sentence
 * is where the data directory came from</b>: it is the argument for the server
 * owning files at all, discovered here, for a different feature, before anything
 * had a place to put them.
 *
 * <p>So the directory is no longer a key of this feature's own. It is {@code
 * DataLayout}'s {@code projects/&lt;project-id&gt;/exports/}, with {@code
 * plowshare.conversations.retention.export-directory} demoted to the override —
 * Maven's shape, one root and a known tree, rather than three keys naming three
 * unrelated directories. {@link ExportDirectories} is the seam that answers
 * which, and carries the argument for why this class does not resolve it itself.
 *
 * <h2>Keyed by the project's id, and it had to learn one</h2>
 *
 * <p>An export is identified by its root conversation and has always been. What
 * it did not know was <em>whose</em> conversation that was, so the tree was flat
 * and "what is in here" could only be answered by reading every manifest in it.
 * Under {@code projects/&lt;id&gt;/} the question is a directory listing.
 *
 * <p><b>The id and never the name.</b> {@code ProjectStore.rename} exists, and a
 * directory named for a name is a directory a rename orphans — with nothing
 * failing, which is the worst way for it to happen. The manifest goes on
 * carrying the <em>name</em> on every line, because a person reading the file
 * wants the word they typed, and the two do not have to agree: the name in the
 * line is what the project was called when the payload was ejected.
 *
 * <p>The consequence an operator has to know, and it is stated here rather than
 * left to be found: <b>if the server's disk is not backed up, neither is the
 * export.</b> Retention moves a liability out of the database and onto a
 * filesystem; it does not make it somebody else's.
 *
 * <h2>The format is open, and that is a requirement rather than a preference</h2>
 *
 * <p>An export nobody can open without the system that wrote it is not an
 * export. The whole point of the {@code turn} policy — never automatic, export
 * explicit — is that it serves the person who wants to keep everything, and
 * "everything" has to survive this server being uninstalled. So:
 *
 * <pre>
 *   &lt;data-dir&gt;/projects/&lt;project-id&gt;/exports/&lt;root conversation id&gt;/
 *       manifest.jsonl
 *       payloads/&lt;conversation id&gt;/&lt;ordinal&gt;.txt
 * </pre>
 *
 * <p><b>The payloads are plain files holding exactly the bytes the tool
 * returned</b>, one per result, UTF-8, nothing wrapped around them. {@code cat}
 * works; a text editor works; {@code grep} across the directory works. A single
 * archive file holding every payload JSON-escaped inside it would have been
 * smaller and would have needed a program to get one file body back out, which
 * is the failure this is written against.
 *
 * <p><b>The manifest is JSON Lines</b> — one JSON object per line, no enclosing
 * array — because that is the open format for "a list of records that may be
 * long": it streams, {@code head} shows the first ten, {@code wc -l} counts
 * them, {@code jq} filters them, every language reads it, and a truncated file
 * is still valid up to its last newline. A single JSON document would have to be
 * read whole to be read at all.
 *
 * <p><b>It is not inherited from anywhere else in this codebase.</b> The memory
 * archive's file format is the archive's, and the retention design says in as
 * many words that this one is not borrowed from it — a log and a memory archive
 * are different systems, and the export of one is not the format of the other.
 *
 * <h2>One directory per tree</h2>
 *
 * <p>Named for the root conversation, with a subdirectory per conversation
 * inside it, because the tree is the unit a lifecycle acts on: a person who
 * archives a conversation and later goes looking for what it did wants the
 * delegated children's file reads in the same place as their own. The manifest
 * is the tree's and names the conversation on every line, so which run did what
 * survives the flattening.
 */
public final class PayloadExport {

    /** What the manifest is called inside a tree's directory. */
    static final String MANIFEST = "manifest.jsonl";

    /**
     * An export policy that keeps nothing.
     *
     * <p>Not a null {@link PayloadExport} at the call site: a sweep that had to
     * ask "is there an exporter?" before every payload would be a null check
     * somebody eventually forgets on the one path where forgetting means the
     * bytes are gone with no file. This one is asked the same question and
     * answers "nowhere", and {@code result_read} then tells a model this
     * deployment keeps no export rather than naming an empty place.
     */
    public static final PayloadExport NONE = new PayloadExport(null);

    private final ExportDirectories where;

    /**
     * @param where which directory a conversation's tree belongs in, or {@code
     *     null} for a deployment that keeps none. Nothing is created here: a
     *     server that never ejects anything should not leave an empty directory
     *     behind to explain, and {@link #write} makes what it needs when it
     *     needs it
     */
    public PayloadExport(ExportDirectories where) {
        this.where = where;
    }

    /** Whether anything is written at all. */
    public boolean keepsAnything() {
        return where != null;
    }

    /**
     * Write one payload out, and append its line to the tree's manifest.
     *
     * <p><b>The file first, the manifest second, and the row is nulled by the
     * caller third.</b> Each step is only taken once the one before it is on
     * disk, so every way this can be interrupted leaves a state a re-run
     * repairs: a file with no manifest line is rewritten and re-listed, and a
     * file with a manifest line whose row still holds the content is rewritten
     * over itself. The order that would lose data — null the row, then write —
     * is the one this method makes impossible by returning the path only after
     * the bytes are there.
     *
     * @param rootId the root of the tree, which names the directory
     * @param conversation the conversation this result was recorded in
     * @param payload the row, with its content still in it
     * @param at when it is being ejected, for the manifest line
     * @return where the bytes were written, as a path string to keep on the row,
     *     or {@code null} for {@link #NONE}
     * @throws UncheckedIOException if the export cannot be written. <b>Raised
     *     and not swallowed</b>: the caller's next act is to remove the payload
     *     from the database, and an exporter that reported success it did not
     *     have would turn a full disk into deleted file bodies
     */
    public String write(
            String rootId, ConversationRecord conversation, EntryRecord payload, Instant at) {
        if (where == null) {
            return null;
        }
        Objects.requireNonNull(payload.content(), "an ejected payload is written before it goes");
        // THE PROJECT IS ASKED FOR BEFORE ANY BYTES MOVE. An unresolvable home
        // raises here, with the row still holding its content, which is this
        // method's whole ordering rule one step earlier: the caller's next act
        // is to null the row, so anything that can fail has to fail before the
        // file exists rather than after.
        Path tree = where.forProject(conversation.home()).resolve(named(rootId));
        Path file = tree.resolve("payloads").resolve(named(payload.conversationId()))
                .resolve("%04d.txt".formatted(payload.ordinal()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, payload.content(), StandardCharsets.UTF_8);
            // The path RELATIVE TO THE TREE'S DIRECTORY, so the manifest and
            // the files it names travel together: an operator who moves or
            // copies the directory has an archive whose lines still resolve.
            // An absolute path would be a manifest that is only correct on the
            // machine and in the location it was written.
            Files.writeString(tree.resolve(MANIFEST),
                    line(rootId, conversation, payload, at, tree.relativize(file)),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(
                    "the export for conversation " + payload.conversationId() + " entry "
                            + payload.ordinal() + " could not be written to " + file
                            + ", so its payload has been left in the database", unwritable);
        }
        return file.toString();
    }

    /**
     * One manifest line: everything about the call except the bytes, which are
     * in the file it names.
     *
     * <p><b>Built by hand and not by an {@code ObjectMapper}.</b> Every value
     * here is a string, an integer or an instant this server owns, the shape is
     * eight fields that will be read by {@code jq} more often than by any
     * program, and a mapper would put a Jackson version between an operator's
     * archive and their ability to read it. What it does borrow from Jackson is
     * the one part that is genuinely easy to get wrong — {@link
     * JsonStringEncoder}, which is what makes a tool name a model invented, with
     * a quotation mark or a newline in it, come out as valid JSON.
     *
     * <p>The fields are what a person tracing one file back to what produced it
     * needs: which conversation and which tree, which run's agent, which project,
     * where in the log, which call, the handle a model would have redeemed, how
     * large it was, when it happened, and when it went.
     */
    private static String line(
            String rootId, ConversationRecord conversation, EntryRecord payload, Instant at,
            Path file) {
        StringBuilder out = new StringBuilder("{");
        text(out, "conversation", payload.conversationId());
        text(out, "root", rootId);
        text(out, "origin", conversation.origin().wireName());
        text(out, "agent", conversation.agent());
        text(out, "project", conversation.home().project());
        number(out, "ordinal", payload.ordinal());
        number(out, "turn", payload.turnOrdinal());
        text(out, "tool_call_id", payload.toolCallId());
        text(out, "handle", payload.handle() == null ? null : payload.handle().toString());
        number(out, "chars", payload.content().length());
        text(out, "recorded_at", payload.recordedAt() == null ? null
                : payload.recordedAt().toString());
        text(out, "ejected_at", at.toString());
        text(out, "file", file.toString().replace('\\', '/'));
        out.setCharAt(out.length() - 1, '}');
        return out.append('\n').toString();
    }

    private static void text(StringBuilder out, String field, String value) {
        out.append('"').append(field).append("\":");
        if (value == null) {
            out.append("null,");
            return;
        }
        out.append('"').append(new String(JsonStringEncoder.getInstance().quoteAsString(value)))
                .append("\",");
    }

    private static void number(StringBuilder out, String field, long value) {
        out.append('"').append(field).append("\":").append(value).append(',');
    }

    /**
     * A conversation id as a directory name, or a refusal.
     *
     * <p>Ids are minted by {@code MemoryIds} and are hexadecimal under a fixed
     * prefix, so nothing this server writes can fail this. What it stops is the
     * row a psql session or a later migration could leave: an id holding a slash
     * or a {@code ..} would put a person's file body outside the export
     * directory, which is the one mistake here that reaches the rest of the
     * disk. {@code Workspace} makes the same check about the same class of
     * mistake for the same reason.
     */
    private static String named(String id) {
        for (int at = 0; at < id.length(); at++) {
            char each = id.charAt(at);
            boolean ordinary = (each >= 'a' && each <= 'z') || (each >= 'A' && each <= 'Z')
                    || (each >= '0' && each <= '9') || each == '_' || each == '-';
            if (!ordinary) {
                throw new IllegalArgumentException(
                        "conversation id '" + id + "' cannot name a directory: an export writes"
                                + " one directory per conversation, and an id carrying anything"
                                + " but letters, digits, '_' and '-' would put a file somewhere"
                                + " this export does not reach");
            }
        }
        return id;
    }
}
