package io.aeyer.plowshare.server.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.hooks.Step;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StuckBriefTest {

    private static final ZonedDateTime SUNDAY =
            ZonedDateTime.parse("2026-09-13T10:15:00+10:00[Australia/Melbourne]");

    private static final String WHY = "the last 12 steps only read or searched; nothing was written, run"
            + " or handed on";

    private static final java.util.Set<String> TOOLS = java.util.Set.of("search", "fetch");

    @Test
    void the_brief_opens_with_why_the_harness_is_asking() {
        String brief = StuckBrief.render(WHY, "q", SUNDAY, TOOLS, List.of(new Step(1, null,
                List.of(new ToolCall("c1", "search", "{}")), List.of("r"), null)), 300, 300, 800);

        assertTrue(brief.startsWith("Why the harness is asking: " + WHY + ".\n\nThe question: q\n\n"), brief);
    }

    @Test
    void the_brief_holds_the_question_the_date_with_its_weekday_each_step_and_the_latest_thinking() {
        String brief = StuckBrief.render(WHY, "why did the US Fed hike rates today?", SUNDAY, TOOLS, List.of(
                new Step(1, "openai/gpt-oss-120b",
                        List.of(new ToolCall("c1", "search", "{\"query\":\"Fed hike September 13 2026\"}")),
                        List.of("Allstate Insurance Agents in Miami, FL"), "the user means today"),
                new Step(2, "openai/gpt-oss-120b",
                        List.of(new ToolCall("c2", "search", "{\"query\":\"\\\"September 13, 2026\\\" Fed\"}")),
                        List.of("x".repeat(1_000)), "still today, September 13 2026")), 300, 300, 800);

        assertTrue(brief.contains("why did the US Fed hike rates today?"));
        assertTrue(brief.contains("Sunday 13 September 2026"));
        assertTrue(brief.contains("1. search {\"query\":\"Fed hike September 13 2026\"}"));
        assertTrue(brief.contains("Allstate Insurance Agents in Miami, FL"));
        assertTrue(brief.contains("x".repeat(300) + "…"));
        assertFalse(brief.contains("x".repeat(301)));
        assertTrue(brief.contains("still today, September 13 2026"));
        assertFalse(brief.contains("the user means today"), "only the latest thinking");
    }

    @Test
    void the_brief_names_the_tools_the_model_may_call_in_order_after_the_date() {
        String brief = StuckBrief.render(WHY, "q", SUNDAY, TOOLS, List.of(new Step(1, null,
                List.of(new ToolCall("c1", "search", "{}")), List.of("r"), null)), 300, 300, 800);

        assertTrue(brief.contains("(Australia/Melbourne)\n\nThe tools it may call, and no others: fetch, search\n\n"
                + "Steps so far"), brief);
    }

    @Test
    void a_run_offered_no_tools_is_said_to_have_none() {
        String brief = StuckBrief.render(WHY, "q", SUNDAY, java.util.Set.of(), List.of(new Step(1, null,
                List.of(new ToolCall("c1", "search", "{}")), List.of("r"), null)), 300, 300, 800);

        assertTrue(brief.contains("\n\nThe tools it may call: none.\n\n"), brief);
    }

    @Test
    void a_run_with_no_thinking_says_nothing_about_thinking() {
        String brief = StuckBrief.render(WHY, "q", SUNDAY, TOOLS, List.of(new Step(1, null,
                List.of(new ToolCall("c1", "fetch", "{}")), List.of("r"), null)), 300, 300, 800);

        assertFalse(brief.contains("thinking"));
        assertEquals(brief.strip(), brief);
    }

    @Test
    void a_long_multi_line_argument_is_cut_and_kept_on_its_calls_line() {
        String content = ("line of a whole file\n").repeat(1_000);
        String brief = StuckBrief.render(WHY, "write it", SUNDAY, TOOLS, List.of(new Step(1, null,
                List.of(new ToolCall("c1", "write_file", "{\"content\":\"" + content + "\"}")),
                List.of("ok"), null)), 40, 300, 800);

        String call = brief.lines().filter(line -> line.startsWith("1. ")).findFirst().orElseThrow();
        assertEquals("1. write_file " + ("{\"content\":\"" + content).substring(0, 40).replace('\n', ' ')
                + "…", call);
        assertTrue(brief.contains("   → ok"), "the result still follows on the next line");
        assertFalse(brief.contains("line of a whole file line of a whole file line of a whole file"));
    }
}
