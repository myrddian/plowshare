package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoardToolsTest {

    /** Records every call; refuses pass/close when told to. */
    private static final class FakeSeat implements BoardTools.Seat {
        final List<String> calls = new ArrayList<>();
        String refuse;

        @Override public String read() {
            calls.add("read");
            return "1 new";
        }

        @Override public String read(String topic) {
            calls.add("read " + topic); return "read";
        }
        @Override public String requestTopic(String title, String why) {
            calls.add("request " + title + " " + why); return "requested";
        }
        @Override public String decide(String request, boolean approve, String reason) {
            calls.add("decide " + request + " " + approve + " " + reason); return "decided";
        }

        @Override public String post(String body, String replyTo, List<String> mentions,
                boolean alert) {
            calls.add("post " + body + " " + replyTo + " " + mentions + " " + alert);
            return "posted";
        }

        @Override public String document(String title, String body, String replyTo) {
            calls.add("document " + title + " " + body + " " + replyTo);
            return "documented";
        }

        @Override public Optional<String> pass(String reason) {
            calls.add("pass " + reason);
            return Optional.ofNullable(refuse);
        }

        @Override public Optional<String> close(String resolution, List<String> cites) {
            calls.add("close " + resolution + " " + cites);
            return Optional.ofNullable(refuse);
        }
    }

    private static AgentTool named(List<AgentTool> tools, String name) {
        return tools.stream().filter(t -> t.schema().name().equals(name)).findFirst()
                .orElseThrow();
    }

    @Test
    void a_member_gets_read_post_document_and_pass_and_the_opener_close_instead_of_pass() {
        FakeSeat seat = new FakeSeat();
        assertEquals(List.of("board_read", "board_post", "board_document", "board_pass", "board_request_topic"),
                BoardTools.forMember(seat, new TurnEnd()).stream()
                        .map(t -> t.schema().name()).toList());
        assertEquals(List.of("board_read", "board_post", "board_document", "board_close", "board_decide"),
                BoardTools.forOpenerSeat(seat, new TurnEnd()).stream()
                        .map(t -> t.schema().name()).toList());
    }

    @Test
    void post_reads_its_arguments_and_optional_ones_default() {
        FakeSeat seat = new FakeSeat();
        AgentTool post = named(BoardTools.forMember(seat, new TurnEnd()), "board_post");
        assertEquals("posted", post.run("{\"body\": \"hi\", \"reply_to\": \"bdm_1\","
                + " \"mentions\": [\"critic\"], \"alert\": true}", Home.global()));
        assertEquals("posted", post.run("{\"body\": \"plain\"}", Home.global()));
        assertEquals(List.of("post hi bdm_1 [critic] true", "post plain null [] false"),
                seat.calls);
    }

    @Test
    void a_missing_body_is_refused_as_a_sentence_not_thrown() {
        AgentTool post = named(BoardTools.forMember(new FakeSeat(), new TurnEnd()), "board_post");
        String refused = post.run("{}", Home.global());
        assertTrue(refused.contains("body"), refused);
    }

    @Test
    void pass_ends_the_turn_and_a_refused_pass_does_not() {
        FakeSeat seat = new FakeSeat();
        TurnEnd end = new TurnEnd();
        named(BoardTools.forMember(seat, end), "board_pass").run("{\"reason\": \"nothing\"}",
                Home.global());
        assertTrue(end.requested().isPresent());
        FakeSeat refusing = new FakeSeat();
        refusing.refuse = "the topic is closed";
        TurnEnd kept = new TurnEnd();
        assertEquals("the topic is closed", named(BoardTools.forMember(refusing, kept),
                "board_pass").run("{\"reason\": \"x\"}", Home.global()));
        assertTrue(kept.requested().isEmpty());
    }

    @Test
    void close_ends_the_turn_with_its_cites() {
        FakeSeat seat = new FakeSeat();
        TurnEnd end = new TurnEnd();
        named(BoardTools.forOpenerSeat(seat, end), "board_close").run(
                "{\"resolution\": \"done\", \"cites\": [\"bdm_1\"]}", Home.global());
        assertEquals(List.of("close done [bdm_1]"), seat.calls);
        assertTrue(end.requested().isPresent());
    }

    @Test
    void open_passes_an_optional_budget() {
        List<String> calls = new ArrayList<>();
        AgentTool open = BoardTools.open((title, label, body, budget) -> {
            calls.add(title + "|" + label + "|" + body + "|" + budget);
            return "opened";
        });
        open.run("{\"title\": \"sync\", \"label\": \"BAD SPEC\", \"body\": \"what?\"}",
                Home.global());
        open.run("{\"title\": \"t\", \"label\": \"l\", \"body\": \"b\", \"budget\": 30}",
                Home.global());
        assertEquals(List.of("sync|BAD SPEC|what?|null", "t|l|b|30"), calls);
    }

    @Test
    void an_invalid_budget_is_refused_without_opening_a_topic() {
        var calls = new ArrayList<String>();
        var open = BoardTools.open((title, label, body, budget) -> { calls.add(title); return "opened"; });
        for (String budget : List.of("-1", "0", "1", "1.5", "\"many\"")) {
            String refused = open.run("{\"title\":\"t\",\"label\":\"l\",\"body\":\"b\",\"budget\":" + budget + "}", Home.global());
            assertTrue(refused.contains("budget"), refused);
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void a_refused_close_does_not_end_the_turn_and_bad_read_json_is_refused() {
        FakeSeat seat = new FakeSeat();
        seat.refuse = "the topic is closed";
        TurnEnd end = new TurnEnd();
        assertEquals(seat.refuse, named(BoardTools.forOpenerSeat(seat, end), "board_close")
                .run("{\"resolution\":\"done\"}", Home.global()));
        assertTrue(end.requested().isEmpty());
        assertTrue(named(BoardTools.forMember(seat, new TurnEnd()), "board_read")
                .run("not JSON", Home.global()).contains("board_read"));
    }
    @Test void request_decide_and_ancestor_read_parse_required_fields_without_ending_the_wake() {
        FakeSeat seat = new FakeSeat();
        TurnEnd end = new TurnEnd();
        named(BoardTools.forMember(seat, end), "board_request_topic").run(
                "{\"title\":\"Child\",\"why\":\"Research\"}", Home.global());
        named(BoardTools.forOpenerSeat(seat, end), "board_decide").run(
                "{\"request\":\"bdm_1\",\"approve\":false,\"reason\":\"Keep here\"}", Home.global());
        named(BoardTools.forMember(seat, end), "board_read").run("{\"topic\":\"bdt_root\"}", Home.global());
        assertEquals(List.of("request Child Research", "decide bdm_1 false Keep here", "read bdt_root"), seat.calls);
        assertTrue(named(BoardTools.forOpenerSeat(seat, end), "board_decide")
                .run("{\"request\":\"bdm_1\",\"reason\":\"No flag\"}", Home.global()).contains("approve"));
        assertEquals(3, seat.calls.size());
    }

}
