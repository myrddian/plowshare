package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.FileWords;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.hooks.HookFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * A session's {@code .plowshare/hooks/}, read over its file channel so the server can snapshot a
 * log's local hooks (spec 2026-09-30-local-hooks-are-served decisions 1, 2 and 8).
 *
 * <p>{@link ChannelDefinitions#list}'s shape: one glob, each file paged to its end, every request
 * marked, every bound this server's own. <b>Two differences, both from decision 8.</b> A bound
 * crossed refuses the whole set, never one file, because a partial set would run some gates and
 * silently not others. And an answer outside {@code <root>/.plowshare/hooks/<one name>} refuses the
 * whole set too: {@code ChannelDefinitions} skips such a file, but a hook skipped is a gate missing.
 * The listing's count, shape and names are all judged before a single file is read, so a refusal
 * names its real cause and costs no reads.
 *
 * <p><b>Never throws.</b> Every failure is a {@link Served} with a reason, which {@code
 * PinnedLocalHooks} writes into the log as a {@code HOOK} entry (decision 7). Any text in that
 * reason that the session chose is {@linkplain #clip clipped} first.
 *
 * <p><b>One deadline for the whole read</b>, {@link #DEADLINE}: this runs inside the door that
 * opens the log, and a per-request deadline times 32 files would hold that door for minutes.
 */
public final class ChannelHooks {

    /**
     * The whole read's time, the channel's own per-request number. {@code
     * hooks.script.LocalHookSets.MISS_BELIEVED} is twice this, and must move with it.
     */
    static final Duration DEADLINE = Duration.ofSeconds(30);

    /** The most characters of the session's own text a reason carries (decision 8). */
    static final int MAX_QUOTED = 200;

    private static final String DIRECTORY = ChannelDefinitions.DEFAULT_DIRECTORY;
    private static final String HOOKS = "hooks";

    /**
     * What a session served: the hook files in name order, or why there are none.
     *
     * @param unreadable why nothing was served, or {@code null} when the read went well (an empty
     *     directory, or none, is {@link #NONE})
     */
    public record Served(List<HookFile> files, String unreadable) {

        public static final Served NONE = new Served(List.of(), null);

        public Served {
            files = List.copyOf(files);
        }

        static Served refused(String why) {
            return new Served(List.of(), why);
        }

        public boolean failed() {
            return unreadable != null;
        }
    }

    /** A reason the whole set is refused, carried out of the read to {@link #read}. */
    private static final class Refusal extends RuntimeException {
        Refusal(String why) {
            super(why, null, false, false);
        }
    }

    /** One listed hit that passed the shape check, and the name it serves under. */
    private record Hit(String path, String name) {
    }

    /** One file assembled to its end, and its size as the bounds count it. */
    private record Whole(String text, long bytes) {
    }

    private final SessionChannel channel;
    private final String sessionId;
    private final LongSupplier nanoTime;

    public ChannelHooks(SessionChannel channel, String sessionId) {
        this(channel, sessionId, System::nanoTime);
    }

    /** {@code nanoTime} is the clock {@link #DEADLINE} is measured on; a test's own. */
    ChannelHooks(SessionChannel channel, String sessionId, LongSupplier nanoTime) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Read the set once; never throws. Each call is its own read, with its own deadline. */
    public Served read() {
        try {
            return new Reading(nanoTime.getAsLong() + DEADLINE.toNanos()).served();
        } catch (Refusal refused) {
            return Served.refused(refused.getMessage());
        } catch (RuntimeException failed) {
            return Served.refused("the session '" + clip(sessionId) + "' could not serve its"
                    + " .plowshare/hooks/: " + clip(failed.getMessage()));
        }
    }

    /**
     * The session's text as a reason may quote it: control, format and separator characters
     * escaped as {@code \}{@code uXXXX}, so no ANSI sequence, NUL or bidi override reaches a {@code
     * HOOK} entry, and cut to {@link #MAX_QUOTED} characters with an ellipsis (decision 8: the
     * sender picks every byte of it).
     */
    static String clip(String untrusted) {
        if (untrusted == null) {
            return "(nothing)";
        }
        StringBuilder safe = new StringBuilder(Math.min(untrusted.length(), MAX_QUOTED) + 8);
        int i = 0;
        while (i < untrusted.length()) {
            if (safe.length() >= MAX_QUOTED) {
                return safe.append('…').toString();
            }
            // By code point, so a pair is never split and a lone surrogate is escaped.
            int point = untrusted.codePointAt(i);
            i += Character.charCount(point);
            int type = Character.getType(point);
            if (Character.isISOControl(point) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE) {
                safe.append(String.format("\\u%04x", point));
            } else {
                safe.appendCodePoint(point);
            }
        }
        return safe.toString();
    }

    /**
     * One read's state: its deadline and the roots it asked for, which live and die with the one
     * {@link #read} call that made them, so two reads never share an answer or a clock.
     */
    private final class Reading {

        private final long deadline;
        private List<Path> roots;

        Reading(long deadline) {
            this.deadline = deadline;
        }

        Served served() {
            FileReply listing = ask(FileRequest.glob(id(), DIRECTORY + "/" + HOOKS + "/*"));
            if (listing.paths() == null) {
                throw new Refusal("the session answered its .plowshare/hooks/ listing without a list");
            }
            List<String> listed = new ArrayList<>();
            for (String hit : listing.paths()) {
                if (hit != null && HookFile.isHookName(lastSegment(hit))) {
                    listed.add(hit);
                }
            }
            if (listed.isEmpty()) {
                return Served.NONE;
            }
            // Count, shape and names, all before a single read (decision 8).
            if (listed.size() > HookFile.MAX_FILES) {
                throw new Refusal(".plowshare/hooks/ holds " + listed.size() + " hook files, and at"
                        + " most " + HookFile.MAX_FILES + " are read for one log; none were");
            }
            List<Hit> hits = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (String hit : listed) {
                String name = contained(hit);
                if (!names.add(name)) {
                    throw new Refusal("the session served two hook files named '" + clip(name)
                            + "', and a name must name one file; none were read");
                }
                hits.add(new Hit(hit, name));
            }
            List<HookFile> files = new ArrayList<>();
            long total = 0;
            for (Hit hit : hits) {
                Whole whole = readWhole(hit);
                // Counted as the per-file bound counts: each line with its newline.
                total += whole.bytes();
                if (total > HookFile.MAX_SET_BYTES) {
                    throw new Refusal("the hook files under .plowshare/hooks/ come to more than "
                            + HookFile.MAX_SET_BYTES + " bytes together, the most read for one"
                            + " log; none were");
                }
                files.add(new HookFile(hit.name(), whole.text()));
            }
            return new Served(HookFile.ordered(files), null);
        }

        /**
         * The file's name, when {@code hit} is exactly {@code <root>/.plowshare/hooks/<one name>}
         * for a root the session named, or {@code .plowshare/hooks/<one name>}; otherwise the set
         * is refused. {@code ChannelDefinitions.isContained}'s rules: the string checked is the
         * string sent, every segment is a segment everywhere, and an absolute hit is anchored on
         * the session's own roots.
         */
        private String contained(String hit) {
            Path parsed;
            try {
                parsed = Path.of(hit);
            } catch (RuntimeException unusable) {
                throw outside(hit);
            }
            if (!parsed.normalize().toString().equals(hit)) {
                throw outside(hit);
            }
            for (Path segment : parsed) {
                if (!ChannelDefinitions.isSafeSegment(segment.toString())) {
                    throw outside(hit);
                }
            }
            boolean shaped;
            if (parsed.isAbsolute()) {
                shaped = false;
                for (Path root : clientRoots()) {
                    Path inside = root.resolve(DIRECTORY).resolve(HOOKS);
                    if (parsed.getNameCount() == inside.getNameCount() + 1
                            && parsed.startsWith(inside)) {
                        shaped = true;
                        break;
                    }
                }
            } else {
                shaped = parsed.getNameCount() == 3
                        && DIRECTORY.equals(parsed.getName(0).toString())
                        && HOOKS.equals(parsed.getName(1).toString());
            }
            if (!shaped) {
                throw outside(hit);
            }
            return parsed.getFileName().toString();
        }

        /**
         * The session's roots, asked once per read. An odd answer (no list, or a root that is not
         * absolute and normalised) anchors nothing; a refused or failed one refuses the set.
         */
        private List<Path> clientRoots() {
            if (roots != null) {
                return roots;
            }
            List<Path> fetched = new ArrayList<>();
            FileReply reply = ask(FileRequest.roots(id()));
            if (reply.paths() != null) {
                for (String named : reply.paths()) {
                    try {
                        Path root = Path.of(named);
                        if (root.isAbsolute() && root.normalize().equals(root)) {
                            fetched.add(root);
                        }
                    } catch (RuntimeException unusable) {
                        // A root this server cannot use as an anchor anchors nothing.
                    }
                }
            }
            roots = List.copyOf(fetched);
            return roots;
        }

        /**
         * One file paged to its end, bounded by {@link HookFile#MAX_FILE_BYTES} as each line
         * arrives ({@code ChannelDefinitions.readWhole}'s checks: the total may not move, a page
         * may not overshoot its window, an empty page may not claim more).
         *
         * <p>Every line is counted with its newline, the last one included, so a file that ends in
         * a newline is bounded at exactly {@link HookFile#MAX_FILE_BYTES} bytes and one with no
         * final newline at 262,143. The set bound sums the same count.
         */
        private Whole readWhole(Hit hit) {
            List<String> lines = new ArrayList<>();
            long bytes = 0;
            int offset = 0;
            long expectedTotal = -1;
            String unassembled = clip(hit.name()) + " could not be assembled from the session's"
                    + " answers, so none of its hooks were read";
            while (true) {
                Window window = Window.of(offset, Window.MAX_WINDOW_LINES);
                Span span = ask(FileRequest.read(id(), hit.path(), window)).span();
                if (span == null || !isPageable(span.stoppedBy())
                        || span.lines().size() > window.limit()
                        || (expectedTotal >= 0 && span.totalLines() != expectedTotal)
                        || (span.lines().isEmpty() && span.more())) {
                    throw new Refusal(unassembled);
                }
                expectedTotal = span.totalLines();
                for (String line : span.lines()) {
                    bytes += line == null ? 1 : line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    if (bytes > HookFile.MAX_FILE_BYTES) {
                        throw new Refusal(clip(hit.name()) + " is larger than "
                                + HookFile.MAX_FILE_BYTES + " bytes, the most read for one hook"
                                + " file; none were read");
                    }
                    lines.add(line == null ? "" : line);
                }
                if (lines.size() > expectedTotal) {
                    throw new Refusal(unassembled);
                }
                if (!span.more()) {
                    return new Whole(String.join("\n", lines), bytes);
                }
                offset += span.lines().size();
                if (offset >= expectedTotal) {
                    throw new Refusal(unassembled);
                }
            }
        }

        /** One exchange, marked, within what is left of {@link #DEADLINE}. */
        private FileReply ask(FileRequest request) {
            long left = deadline - nanoTime.getAsLong();
            if (left <= 0) {
                throw new Refusal("the session took longer than " + DEADLINE.toSeconds()
                        + " s to serve its .plowshare/hooks/, so none were read");
            }
            FileReply reply = channel.ask(sessionId, request.forHooks(), Duration.ofNanos(left));
            if (reply == null || !request.id().equals(reply.id())) {
                throw new Refusal("the session answered a different request");
            }
            if (FileReply.REFUSED.equals(reply.outcome())) {
                String said = FileWords.said(reply);
                throw new Refusal("the session refused to serve its .plowshare/hooks/: "
                        + (said == null ? "it gave no reason" : clip(said)));
            }
            if (!FileReply.OK.equals(reply.outcome())) {
                String said = FileWords.said(reply);
                throw new Refusal("the session could not serve its .plowshare/hooks/: "
                        + (said == null ? "it gave no reason" : clip(said)));
            }
            return reply;
        }
    }

    private static Refusal outside(String hit) {
        return new Refusal("the session listed '" + clip(hit) + "', which is not a file directly"
                + " under .plowshare/hooks/, so none of its hooks were read");
    }

    private static boolean isPageable(String stoppedBy) {
        return Span.LINES.equals(stoppedBy) || Span.BYTES.equals(stoppedBy)
                || Span.END.equals(stoppedBy);
    }

    private static String lastSegment(String hit) {
        return hit.substring(hit.lastIndexOf('/') + 1);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
