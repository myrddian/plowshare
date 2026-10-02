package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.FileReply;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.hooks.HookFile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Spec 2026-09-30-local-hooks-are-served decisions 2, 7 and 8, over a scripted session. */
class ChannelHooksTest {

    private static final String DIR = ".plowshare/hooks";
    private static final String GUARD = "export default { name: 'guard', stages: {} }";

    private final List<FileRequest> asked = Collections.synchronizedList(new ArrayList<>());

    private SessionChannel recording(SessionChannel files) {
        return (session, request) -> {
            asked.add(request);
            return files.ask(session, request);
        };
    }

    @Test
    void it_reads_every_ts_and_js_file_in_name_order_under_its_own_mark() {
        FakeFiles files = new FakeFiles()
                .withListing(DIR, List.of("20-b.js", "10-a.ts", "README.md", ".draft.ts"))
                .withFile(DIR + "/10-a.ts", GUARD)
                .withFile(DIR + "/20-b.js", "export default {}\nconst x = 1");

        ChannelHooks.Served served = new ChannelHooks(recording(files), "tui-1").read();

        assertFalse(served.failed(), served.unreadable());
        assertEquals(List.of(new HookFile("10-a.ts", GUARD),
                new HookFile("20-b.js", "export default {}\nconst x = 1")), served.files());
        assertTrue(asked.stream().allMatch(request -> FileRequest.HOOKS.equals(request.purpose())),
                asked.toString());
        assertEquals(List.of(DIR + "/*"), asked.stream()
                .filter(request -> FileRequest.GLOB.equals(request.op()))
                .map(FileRequest::pattern).toList());
        assertFalse(asked.stream().anyMatch(request -> String.valueOf(request.path()).endsWith("README.md")),
                "a file that is not a hook is not read");
    }

    @Test
    void a_session_without_the_directory_has_no_hooks_and_nothing_failed() {
        assertEquals(ChannelHooks.Served.NONE, new ChannelHooks(new FakeFiles(), "tui-1").read());
    }

    @Test
    void more_than_32_files_refuses_the_whole_set_before_a_single_read() {
        List<String> names = IntStream.rangeClosed(1, 33)
                .mapToObj(i -> String.format("%02d.ts", i)).toList();
        FakeFiles files = new FakeFiles().withListing(DIR, names);
        names.forEach(name -> files.withFile(DIR + "/" + name, GUARD));

        ChannelHooks.Served served = new ChannelHooks(recording(files), "tui-1").read();

        assertTrue(served.failed());
        assertTrue(served.unreadable().contains("32"), served.unreadable());
        assertEquals(List.of(), served.files());
        assertFalse(asked.stream().anyMatch(request -> FileRequest.READ.equals(request.op())));
    }

    @Test
    void one_file_over_256_KiB_refuses_the_whole_set() {
        String big = ("x".repeat(1023) + "\n").repeat(257);
        FakeFiles files = new FakeFiles().withListing(DIR, List.of("10-a.ts", "20-big.ts"))
                .withFile(DIR + "/10-a.ts", GUARD).withFile(DIR + "/20-big.ts", big);

        ChannelHooks.Served served = new ChannelHooks(files, "tui-1").read();

        assertTrue(served.failed());
        assertTrue(served.unreadable().contains("262144"), served.unreadable());
        assertEquals(List.of(), served.files(), "a partial set would run some gates and not others");
    }

    @Test
    void a_set_over_1_MiB_refuses_the_whole_set() {
        String large = ("x".repeat(1023) + "\n").repeat(250);
        List<String> names = List.of("1.ts", "2.ts", "3.ts", "4.ts", "5.ts");
        FakeFiles files = new FakeFiles().withListing(DIR, names);
        names.forEach(name -> files.withFile(DIR + "/" + name, large));

        ChannelHooks.Served served = new ChannelHooks(files, "tui-1").read();

        assertTrue(served.failed());
        assertTrue(served.unreadable().contains("1048576"), served.unreadable());
    }

    @Test
    void a_session_that_has_gone_serves_nothing_and_says_so() {
        ChannelHooks.Served served = new ChannelHooks(new FakeFiles().thatIsClosed(), "tui-1").read();

        assertTrue(served.failed());
        assertTrue(served.unreadable().contains("not connected"), served.unreadable());
    }

    @Test
    void a_session_that_refuses_serves_nothing_and_says_why() {
        SessionChannel refusing = (session, request) -> FileReply.refused(request.id(), "not today");

        ChannelHooks.Served served = new ChannelHooks(refusing, "tui-1").read();

        assertTrue(served.failed());
        assertTrue(served.unreadable().contains("not today"), served.unreadable());
    }

    @Test
    void an_answer_outside_the_hooks_directory_refuses_the_whole_set() {
        for (String escaping : List.of(".plowshare/hooks/../secret.ts", ".plowshare/hooks/deep/x.ts",
                ".plowshare/agents/x.ts", ".plowshare/hooks/a\\..\\..\\x.ts")) {
            FakeFiles files = new FakeFiles().withListing(DIR, List.of("10-a.ts"))
                    .withRawListing(DIR, List.of(escaping))
                    .withFile(DIR + "/10-a.ts", GUARD).withFile(escaping, GUARD);

            ChannelHooks.Served served = new ChannelHooks(files, "tui-1").read();

            assertTrue(served.failed(), escaping);
            assertEquals(List.of(), served.files(), escaping);
        }
    }

    @Test
    void an_absolute_answer_is_read_only_under_a_root_the_session_named() {
        FakeFiles inside = new FakeFiles().withRoots(List.of("/work/repo"))
                .withRawListing(DIR, List.of("/work/repo/.plowshare/hooks/10-a.ts"))
                .withFile("/work/repo/.plowshare/hooks/10-a.ts", GUARD);
        FakeFiles outside = new FakeFiles().withRoots(List.of("/work/repo"))
                .withRawListing(DIR, List.of("/work/other/.plowshare/hooks/10-a.ts"))
                .withFile("/work/other/.plowshare/hooks/10-a.ts", GUARD);

        assertEquals(List.of(new HookFile("10-a.ts", GUARD)),
                new ChannelHooks(inside, "tui-1").read().files());
        assertTrue(new ChannelHooks(outside, "tui-1").read().failed());
    }

    @Test
    void two_files_with_one_name_refuse_the_whole_set() {
        FakeFiles twice = new FakeFiles().withRoots(List.of("/a", "/b"))
                .withRawListing(DIR, List.of("/a/.plowshare/hooks/10-a.ts", "/b/.plowshare/hooks/10-a.ts"))
                .withFile("/a/.plowshare/hooks/10-a.ts", GUARD)
                .withFile("/b/.plowshare/hooks/10-a.ts", GUARD);

        ChannelHooks.Served served = new ChannelHooks(twice, "tui-1").read();

        assertTrue(served.failed());
        assertTrue(served.unreadable().contains("10-a.ts"), served.unreadable());
    }

    @Test
    void a_duplicate_name_is_refused_for_what_it_is_before_a_single_read() {
        String big = ("x".repeat(1023) + "\n").repeat(257);
        FakeFiles twice = new FakeFiles().withRoots(List.of("/a", "/b"))
                .withRawListing(DIR, List.of("/a/.plowshare/hooks/10-a.ts", "/b/.plowshare/hooks/10-a.ts"))
                .withFile("/a/.plowshare/hooks/10-a.ts", big)
                .withFile("/b/.plowshare/hooks/10-a.ts", GUARD);

        ChannelHooks.Served served = new ChannelHooks(recording(twice), "tui-1").read();

        assertTrue(served.unreadable().contains("two hook files named '10-a.ts'"), served.unreadable());
        assertFalse(asked.stream().anyMatch(request -> FileRequest.READ.equals(request.op())),
                "the first file's size is not the cause, and was never read");
    }

    @Test
    void an_out_of_shape_hit_is_refused_before_a_single_read() {
        FakeFiles files = new FakeFiles().withListing(DIR, List.of("10-a.ts"))
                .withRawListing(DIR, List.of(".plowshare/hooks/deep/x.ts"))
                .withFile(DIR + "/10-a.ts", GUARD);

        ChannelHooks.Served served = new ChannelHooks(recording(files), "tui-1").read();

        assertTrue(served.unreadable().contains("not a file directly under"), served.unreadable());
        assertFalse(asked.stream().anyMatch(request -> FileRequest.READ.equals(request.op())));
    }

    /** A channel that honours only the three-argument ask, on a clock the test moves. */
    private static final class Timed implements SessionChannel {
        final long[] now = {1_000L};
        final List<Duration> handed = new ArrayList<>();
        private final SessionChannel files;
        private final Duration eachAskTakes;

        Timed(SessionChannel files, Duration eachAskTakes) {
            this.files = files;
            this.eachAskTakes = eachAskTakes;
        }

        @Override
        public FileReply ask(String session, FileRequest request) {
            throw new AssertionError("every ask must carry what is left of the deadline");
        }

        @Override
        public FileReply ask(String session, FileRequest request, Duration deadline) {
            handed.add(deadline);
            now[0] += eachAskTakes.toNanos();
            return files.ask(session, request);
        }
    }

    @Test
    void every_ask_is_handed_what_is_left_of_one_30_s_deadline() {
        FakeFiles files = new FakeFiles().withListing(DIR, List.of("10-a.ts", "20-b.ts", "30-c.ts"))
                .withFile(DIR + "/10-a.ts", GUARD).withFile(DIR + "/20-b.ts", GUARD)
                .withFile(DIR + "/30-c.ts", GUARD);
        Timed timed = new Timed(files, Duration.ofSeconds(2));

        ChannelHooks.Served served = new ChannelHooks(timed, "tui-1", () -> timed.now[0]).read();

        assertFalse(served.failed(), served.unreadable());
        assertEquals(4, timed.handed.size(), "one glob, three reads: " + timed.handed);
        assertEquals(ChannelHooks.DEADLINE, timed.handed.get(0));
        for (int i = 0; i < timed.handed.size(); i++) {
            Duration left = timed.handed.get(i);
            assertTrue(left.isPositive() && left.compareTo(ChannelHooks.DEADLINE) <= 0, left.toString());
            if (i > 0) {
                assertTrue(left.compareTo(timed.handed.get(i - 1)) < 0, timed.handed.toString());
            }
        }
    }

    @Test
    void a_channel_that_stalls_past_the_deadline_is_refused_never_thrown() {
        FakeFiles files = new FakeFiles().withListing(DIR, List.of("10-a.ts", "20-b.ts"))
                .withFile(DIR + "/10-a.ts", GUARD).withFile(DIR + "/20-b.ts", GUARD);
        Timed stalling = new Timed(files, Duration.ofSeconds(31));

        ChannelHooks.Served served = assertDoesNotThrow(
                () -> new ChannelHooks(stalling, "tui-1", () -> stalling.now[0]).read());

        assertTrue(served.unreadable().contains("longer than 30 s"), served.unreadable());
        assertEquals(1, stalling.handed.size(), "nothing is asked once the deadline has passed");

        SessionChannel timingOut = (session, request) -> {
            throw new WorkspaceUnavailableException("the session did not answer within 30 s");
        };
        ChannelHooks.Served timedOut = assertDoesNotThrow(() -> new ChannelHooks(timingOut, "tui-1").read());
        assertTrue(timedOut.unreadable().contains("did not answer"), timedOut.unreadable());
    }

    @Test
    void a_hostile_page_refuses_the_whole_set_and_nothing_is_thrown() {
        String path = DIR + "/10-a.ts";
        Map<String, Map<Integer, Span>> pages = new LinkedHashMap<>();
        pages.put("the total moves between pages", Map.of(
                0, new Span(List.of("a"), 0, 3, true, Span.LINES),
                1, new Span(List.of("b"), 1, 4, true, Span.LINES)));
        pages.put("a page longer than its window", Map.of(
                0, new Span(Collections.nCopies(2_001, "a"), 0, 2_001, false, Span.END)));
        pages.put("an empty page that claims more", Map.of(
                0, new Span(List.of(), 0, 5, true, Span.LINES)));
        pages.put("more lines than the total", Map.of(
                0, new Span(List.of("a", "b"), 0, 1, false, Span.END)));
        pages.put("more claimed at the total", Map.of(
                0, new Span(List.of("a"), 0, 1, true, Span.LINES)));
        pages.put("a stop this build does not know", Map.of(
                0, new Span(List.of("a"), 0, 1, false, "sideways")));

        Map<String, SessionChannel> cases = new LinkedHashMap<>();
        pages.forEach((name, byOffset) -> cases.put(name,
                new FakeFiles().withListing(DIR, List.of("10-a.ts")).withPagedFile(path, byOffset)));
        FakeFiles honest = new FakeFiles().withListing(DIR, List.of("10-a.ts")).withFile(path, GUARD);
        cases.put("a reply to a different request", (session, request) -> {
            FileReply real = honest.ask(session, request);
            return FileRequest.READ.equals(request.op())
                    ? FileReply.answered(UUID.randomUUID().toString(), real.span()) : real;
        });

        cases.forEach((name, channel) -> {
            ChannelHooks.Served served = assertDoesNotThrow(() -> new ChannelHooks(channel, "tui-1").read(), name);
            assertTrue(served.failed(), name);
            assertEquals(List.of(), served.files(), name);
            String expected = name.equals("a reply to a different request")
                    ? "answered a different request" : "could not be assembled";
            assertTrue(served.unreadable().contains(expected), name + ": " + served.unreadable());
        });
    }

    @Test
    void the_session_s_own_words_are_clipped_and_carry_no_control_characters() {
        String hostile = "\u001b[31mred\u0000‮" + "x".repeat(500);
        SessionChannel refusing = (session, request) -> FileReply.refused(request.id(), hostile);
        FakeFiles listing = new FakeFiles()
                .withRawListing(DIR, List.of(DIR + "/\u001b[2J\u0007" + "y".repeat(500) + ".ts"));

        for (ChannelHooks.Served served : List.of(new ChannelHooks(refusing, "tui-1").read(),
                new ChannelHooks(listing, "tui-1").read())) {
            String reason = served.unreadable();
            assertTrue(reason.contains("\\u001b") && reason.contains("…"), reason);
            assertTrue(reason.length() < 2 * ChannelHooks.MAX_QUOTED + 150, reason);
            assertTrue(reason.codePoints().noneMatch(point -> Character.isISOControl(point)
                    || Character.getType(point) == Character.FORMAT), reason);
        }
        assertEquals("a😀b", ChannelHooks.clip("a😀b"), "a pair is one character, not two escapes");
    }

    @Test
    void the_set_is_counted_as_each_file_is_one_newline_per_line() {
        String full = ("x".repeat(1023) + "\n").repeat(256);
        List<String> four = List.of("1.ts", "2.ts", "3.ts", "4.ts");
        FakeFiles atTheBound = new FakeFiles().withListing(DIR, four);
        four.forEach(name -> atTheBound.withFile(DIR + "/" + name, full));
        FakeFiles overIt = new FakeFiles().withListing(DIR, four).withListing(DIR, List.of("5.ts"))
                .withFile(DIR + "/5.ts", "a");
        four.forEach(name -> overIt.withFile(DIR + "/" + name, full));

        assertFalse(new ChannelHooks(atTheBound, "tui-1").read().failed(),
                "four files of 262144 bytes are exactly 1 MiB");
        ChannelHooks.Served over = new ChannelHooks(overIt, "tui-1").read();
        assertTrue(over.unreadable().contains("1048576"), String.valueOf(over.unreadable()));
    }

    @Test
    void a_file_with_no_final_newline_is_bounded_at_262143_bytes() {
        String lines = ("x".repeat(1023) + "\n").repeat(255);
        FakeFiles fits = new FakeFiles().withListing(DIR, List.of("10-a.ts"))
                .withFile(DIR + "/10-a.ts", lines + "x".repeat(1023));
        FakeFiles doesNot = new FakeFiles().withListing(DIR, List.of("10-a.ts"))
                .withFile(DIR + "/10-a.ts", lines + "x".repeat(1024));

        assertFalse(new ChannelHooks(fits, "tui-1").read().failed());
        assertTrue(new ChannelHooks(doesNot, "tui-1").read().unreadable().contains("262144"));
    }
}
