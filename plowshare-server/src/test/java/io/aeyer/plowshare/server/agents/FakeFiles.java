package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionGoneException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link SessionChannel} test double for {@link ChannelDefinitionsTest} and
 * {@link DefinitionResolverTest} — a fake {@code ask}, not a socket, exactly as
 * {@link ChannelDefinitions}'s own javadoc says a channel integration test does
 * not belong in this suite.
 *
 * <p><b>Public, and its two builders with it, for one caller in another
 * package</b>: {@code AgentControllerTest} needs a resolution that actually
 * carries a client tier, to prove a refusal on the run path does not enumerate
 * it. A second hand-rolled channel over there would be a second reading of the
 * wire contract to keep in step with this one — and the paging rules below are
 * exactly the sort of thing a casual second fake gets wrong in a way that makes
 * a green test meaningless.
 *
 * <h2>What a listing answers with</h2>
 *
 * <p>{@link #withListing} registers filenames under one directory, and {@code
 * ask} answers a {@link FileRequest#GLOB} whose pattern starts with that
 * directory with each filename joined onto it — {@code
 * ".plowshare/bots/mine.md"} for {@code withListing(".plowshare/bots",
 * List.of("mine.md"))}. {@link #withRawListing} is the same registration
 * without the joining, for a test that needs to hand back an exact string —
 * an absolute path, one that opens with {@code ..}, or anything else a
 * concatenation of {@code directory + "/" + name} cannot spell.
 *
 * <h2>What a read answers with</h2>
 *
 * <p>{@link #withFile} hands the whole content to {@link Window#cut}, exactly
 * as a real client does — {@code RemoteProvider}'s own javadoc says neither
 * side invents a range, and this fake does not either. For content small
 * enough to fit one window that is indistinguishable from a canned single
 * reply.
 *
 * <h2>What a roots request answers with</h2>
 *
 * <p>Empty unless {@link #withRoots} says otherwise, which is the honest
 * default for a fake whose listings are spelled relative: {@code
 * ChannelDefinitions} anchors an ABSOLUTE hit on the roots its session
 * declares, so a fake declaring none is a client that can see nothing and no
 * absolute hit is contained. {@link #withRoots} is what a test uses to be the
 * client a production client actually is — one whose glob answers with
 * root-prefixed absolute paths, because {@code ClientEnforcer.glob} walks
 * {@code FileAccess.roots()} and those are canonicalised absolute.
 *
 * <p>{@link #withPagedFile} is the one that is not. It is keyed by the
 * <em>requested</em> offset rather than call order, and on purpose: a fake
 * that just returns its next canned page regardless of what was asked would
 * pass for any advancing arithmetic on the caller's side, including a wrong
 * one. Keying by offset means a caller that computes the wrong next window
 * asks for an offset nothing was registered for, and gets an {@code
 * IllegalStateException} rather than silently the next page in line —
 * {@code ChannelDefinitions.read} catches that like any other channel
 * failure and contributes nothing, which is what turns a broken offset
 * calculation into a failing assertion on the assembled text rather than a
 * green test that never looked.
 */
public final class FakeFiles implements SessionChannel {

    private final Map<String, List<String>> listings = new LinkedHashMap<>();
    private final Map<String, List<String>> files = new LinkedHashMap<>();
    private final Map<String, Map<Integer, Span>> pagedByOffset = new LinkedHashMap<>();
    private final Map<String, Endless> endless = new LinkedHashMap<>();
    private List<String> roots = List.of();
    private boolean closed;

    /** What this session answers {@link FileRequest#ROOTS} with — see this
     *  class's own javadoc for why the default is empty. */
    FakeFiles withRoots(List<String> declared) {
        this.roots = List.copyOf(declared);
        return this;
    }

    public FakeFiles withListing(String directory, List<String> filenames) {
        List<String> hits = filenames.stream().map(name -> directory + "/" + name).toList();
        listings.merge(directory, hits, FakeFiles::concat);
        return this;
    }

    /** {@link #withListing} without joining {@code directory} onto each entry
     *  — {@code rawHits} are returned exactly as given. */
    FakeFiles withRawListing(String directory, List<String> rawHits) {
        listings.merge(directory, List.copyOf(rawHits), FakeFiles::concat);
        return this;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> merged = new ArrayList<>(a);
        merged.addAll(b);
        return merged;
    }

    public FakeFiles withFile(String path, String content) {
        files.put(path, content.lines().toList());
        return this;
    }

    /** Canned {@link Span}s, one per offset a caller might request when
     *  paging {@code path} — see this class's own javadoc for why keying by
     *  the requested offset, rather than by call order, is the point. */
    FakeFiles withPagedFile(String path, Map<Integer, Span> byRequestedOffset) {
        pagedByOffset.put(path, Map.copyOf(byRequestedOffset));
        return this;
    }

    /**
     * A file that never ends: every window answered with a full page, {@code
     * more=true}, and one unchanging {@code totalLines} the caller cannot
     * disprove.
     *
     * <p><b>Not canned pages, because the point is that there is no last
     * one.</b> A map of offsets has a largest key, and a caller that is not
     * bounded merely walks off the end of it and gets the {@code
     * IllegalStateException} {@link #withPagedFile} raises — which {@code
     * ChannelDefinitions} swallows into "contributes nothing", the very answer
     * a bounded caller gives. A test written that way passes whether or not the
     * bound exists.
     *
     * <p>So this generates instead, and trips an {@link AssertionError} — an
     * {@code Error}, deliberately, so it travels straight out through {@code
     * ChannelDefinitions}' {@code RuntimeException} catches and fails the test
     * rather than becoming an empty list — as soon as it has been asked more
     * times than a bounded caller could possibly ask.
     *
     * @param claimedTotal what every page reports as the file's length. {@code
     *     Integer.MAX_VALUE} is the attack: it is the largest number the wire
     *     can carry and it makes every total-derived check unreachable
     * @param stopAfter the most reads a caller with a real ceiling would make
     */
    FakeFiles withEndlessFile(String path, int linesPerPage, int claimedTotal, int stopAfter) {
        endless.put(path, new Endless(linesPerPage, claimedTotal, stopAfter, new int[] {0}));
        return this;
    }

    private record Endless(int linesPerPage, int claimedTotal, int stopAfter, int[] asked) {
    }

    FakeFiles thatIsClosed() {
        closed = true;
        return this;
    }

    @Override
    public FileReply ask(String session, FileRequest request) {
        if (closed) {
            throw new SessionGoneException("session '" + session + "' is not connected");
        }
        if (FileRequest.ROOTS.equals(request.op())) {
            return FileReply.listed(request.id(), roots);
        }
        if (FileRequest.GLOB.equals(request.op())) {
            return glob(request);
        }
        if (FileRequest.READ.equals(request.op())) {
            return read(request);
        }
        throw new IllegalStateException("FakeFiles does not support op '" + request.op() + "'");
    }

    private FileReply glob(FileRequest request) {
        for (Map.Entry<String, List<String>> entry : listings.entrySet()) {
            if (request.pattern() != null && request.pattern().startsWith(entry.getKey() + "/")) {
                return FileReply.listed(request.id(), entry.getValue());
            }
        }
        // No directory registered for this pattern -- a real client answers
        // "searched, and there was nothing" rather than a refusal, exactly as
        // LocalProvider.glob does for a root with no matching tree under it.
        return FileReply.listed(request.id(), List.of());
    }

    private FileReply read(FileRequest request) {
        Endless forever = endless.get(request.path());
        if (forever != null) {
            forever.asked()[0]++;
            if (forever.asked()[0] > forever.stopAfter()) {
                throw new AssertionError("'" + request.path() + "' was read "
                        + forever.asked()[0] + " times, and a caller bounded by a ceiling of"
                        + " its own would have stopped after " + forever.stopAfter()
                        + ": the only bound left is the one the peer picked");
            }
            List<String> page = new ArrayList<>(forever.linesPerPage());
            for (int i = 0; i < forever.linesPerPage(); i++) {
                page.add("x");
            }
            return FileReply.answered(request.id(), new Span(page, request.window().offset(),
                    forever.claimedTotal(), true, Span.LINES));
        }
        Map<Integer, Span> pages = pagedByOffset.get(request.path());
        if (pages != null) {
            int offset = request.window().offset();
            Span span = pages.get(offset);
            if (span == null) {
                throw new IllegalStateException("test bug or a broken caller: FakeFiles was asked"
                        + " to read '" + request.path() + "' at offset " + offset
                        + ", and only " + pages.keySet() + " are configured");
            }
            return FileReply.answered(request.id(), span);
        }
        List<String> lines = files.get(request.path());
        if (lines == null) {
            // As every real client words it (ClientEnforcer, the TUI's enforcer): the opening
            // ChannelDefinitions.environmentFileRead tells an absent file from a failed read by.
            return FileReply.refused(request.id(), "there is no file at " + request.path()
                    + " on this machine");
        }
        return FileReply.answered(request.id(), request.window().cut(lines));
    }
}
