package io.aeyer.plowshare.server.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HooksChainTest {

    private static final HookContext CONTEXT = new HookContext(
            "aristoxenus", true, Set.of("memory_write", "file_write"), "ledger", "cnv_1",
            HookContext.SERVER);

    private static HookRecord record(String hook, Stage stage, String decision) {
        return new HookRecord(hook, null, Tier.HARNESS, stage, null, decision, null, null, null, 0);
    }

    @Test
    void none_changes_nothing_and_records_nothing() {
        assertEquals(PromptPre.NOTHING, Hooks.NONE.promptPre(CONTEXT, "hello"));
        assertEquals(PromptPost.untouched("hi"), Hooks.NONE.promptPost(CONTEXT, "hi", List.of()));
        assertEquals(ToolPre.allowed("{}"), Hooks.NONE.toolPre(CONTEXT, "file_write", "{}"));
        assertEquals(ToolPost.untouched("ok"), Hooks.NONE.toolPost(CONTEXT, "file_write", "{}", "ok"));
    }

    @Test
    void additions_from_every_layer_arrive_in_layer_order() {
        Hooks first = new Hooks() {
            @Override
            public PromptPre promptPre(HookContext context, String utterance) {
                return new PromptPre(List.of(new Addition("a", "one", Mode.VOLATILE)),
                        List.of(record("a", Stage.PROMPT_PRE, HookRecord.ADD)));
            }
        };
        Hooks second = new Hooks() {
            @Override
            public PromptPre promptPre(HookContext context, String utterance) {
                return new PromptPre(List.of(new Addition("b", "two", Mode.DURABLE)),
                        List.of(record("b", Stage.PROMPT_PRE, HookRecord.ADD)));
            }
        };

        PromptPre both = Hooks.chain(first, second).promptPre(CONTEXT, "hello");

        assertEquals(List.of("one", "two"), both.additions().stream().map(Addition::text).toList());
        assertEquals(List.of("a", "b"), both.records().stream().map(HookRecord::hook).toList());
    }

    @Test
    void a_rewrite_is_what_the_next_layer_sees_and_a_deny_stops_the_chain() {
        List<String> seen = new ArrayList<>();
        Hooks rewrite = new Hooks() {
            @Override
            public ToolPre toolPre(HookContext context, String tool, String arguments) {
                return new ToolPre("{\"path\":\"b\"}", null,
                        List.of(record("rewrite", Stage.TOOL_PRE, HookRecord.REWRITE)));
            }
        };
        Hooks deny = new Hooks() {
            @Override
            public ToolPre toolPre(HookContext context, String tool, String arguments) {
                seen.add(arguments);
                return new ToolPre(arguments, "no",
                        List.of(record("deny", Stage.TOOL_PRE, HookRecord.DENY)));
            }
        };
        Hooks never = new Hooks() {
            @Override
            public ToolPre toolPre(HookContext context, String tool, String arguments) {
                seen.add("never");
                return ToolPre.allowed(arguments);
            }
        };

        ToolPre result = Hooks.chain(rewrite, deny, never).toolPre(CONTEXT, "file_write", "{\"path\":\"a\"}");

        assertTrue(result.isDenied());
        assertEquals("no", result.denied());
        assertEquals(List.of("{\"path\":\"b\"}"), seen, "the deny layer saw the rewrite; the third never ran");
        assertEquals(List.of("rewrite", "deny"), result.records().stream().map(HookRecord::hook).toList());
    }

    @Test
    void a_redaction_is_what_the_next_layer_sees() {
        Hooks scrub = new Hooks() {
            @Override
            public ToolPost toolPost(HookContext context, String tool, String arguments, String result) {
                return new ToolPost(result.replace("secret", "[redacted]"),
                        List.of(record("scrub", Stage.TOOL_POST, HookRecord.REDACT)));
            }
        };
        Hooks shout = new Hooks() {
            @Override
            public PromptPost promptPost(HookContext context, String reply, List<String> asked) {
                return new PromptPost(reply.toUpperCase(), List.of());
            }
        };

        assertEquals("a [redacted] b",
                Hooks.chain(scrub, Hooks.NONE).toolPost(CONTEXT, "file_read", "{}", "a secret b").result());
        assertEquals("HI", Hooks.chain(Hooks.NONE, shout).promptPost(CONTEXT, "hi", List.of()).reply());
        assertFalse(ToolPre.allowed("{}").isDenied());
    }

    private static final HookContext LOG = HookContext.forLog("submission", "scribe", false,
            "ledger", "cnv_1");

    private static Hooks layer(String name, String added, String closed, String note, String sent) {
        return new Hooks() {
            @Override
            public LogOpen logOpen(HookContext context, LogOpening opening) {
                return new LogOpen(List.of(added), List.of(record(name, Stage.LOG_OPEN, HookRecord.ADD)));
            }

            @Override
            public Notified logClose(HookContext context, LogClosing closing) {
                return new Notified(List.of(new Notified.Notice(name, closed)),
                        List.of(record(name, Stage.LOG_CLOSE, HookRecord.NOTIFY)));
            }

            @Override
            public DeliveryPre deliveryPre(HookContext context, Handover handover) {
                return new DeliveryPre(List.of(note),
                        List.of(record(name, Stage.DELIVERY_PRE, HookRecord.NOTE)));
            }

            @Override
            public Notified deliveryPost(HookContext context, Handover handover) {
                return new Notified(List.of(new Notified.Notice(name, sent)),
                        List.of(record(name, Stage.DELIVERY_POST, HookRecord.NOTIFY)));
            }
        };
    }

    /** Spec 2026-09-28-hooks-reach-the-log §4: notes and additions concatenate, notifies are collected. */
    @Test
    void log_stages_concatenate_in_layer_order() {
        Hooks both = Hooks.chain(layer("a", "one", "closed", "checked", "sent"),
                layer("b", "two", "ended", "twice", "delivered"));
        Handover inbox = new Handover(Handover.INBOX, "the answer");

        LogOpen opened = both.logOpen(LOG, new LogOpening(null, "enzo"));
        Notified closed = both.logClose(LOG, new LogClosing("ANSWERED", 1));
        DeliveryPre pre = both.deliveryPre(LOG, inbox);
        Notified post = both.deliveryPost(LOG, inbox);

        assertEquals("one\n\ntwo", opened.joined());
        assertEquals(List.of("a", "b"), opened.records().stream().map(HookRecord::hook).toList());
        assertEquals(List.of("closed", "ended"),
                closed.notices().stream().map(Notified.Notice::text).toList());
        assertEquals("the answer\n\nchecked\n\ntwice", pre.applyTo("the answer"));
        assertEquals(List.of("sent", "delivered"),
                post.notices().stream().map(Notified.Notice::text).toList());
    }

    @Test
    void none_does_nothing_at_any_log_stage() {
        Handover inbox = new Handover(Handover.INBOX, "t");

        assertEquals(LogOpen.NOTHING, Hooks.NONE.logOpen(LOG, new LogOpening(null, null)));
        assertEquals(Notified.NOTHING, Hooks.NONE.logClose(LOG, new LogClosing("ANSWERED", 0)));
        assertEquals("t", Hooks.NONE.deliveryPre(LOG, inbox).applyTo("t"));
        assertEquals(Notified.NOTHING, Hooks.NONE.deliveryPost(LOG, inbox));
    }

    private static final StageStart STARTED = new StageStart(new StageShown("code", "code", 1, 3),
            false, 2);

    @Test
    void a_gate_s_notes_concatenate_and_its_first_denial_ends_the_chain() {
        List<String> reached = new ArrayList<>();
        Hooks noting = new Hooks() {
            @Override
            public Gate stagePre(HookContext context, StageStart start) {
                reached.add("noting");
                return new Gate(null, List.of("mind the tests"),
                        List.of(record("noting", Stage.STAGE_PRE, HookRecord.NOTE)));
            }
        };
        Hooks denying = new Hooks() {
            @Override
            public Gate stagePre(HookContext context, StageStart start) {
                reached.add("denying");
                return new Gate("'freeze': no starts on Friday", List.of(),
                        List.of(record("freeze", Stage.STAGE_PRE, HookRecord.DENY)));
            }
        };
        Hooks after = new Hooks() {
            @Override
            public Gate stagePre(HookContext context, StageStart start) {
                reached.add("after");
                return Gate.NOTHING;
            }
        };

        Gate gate = Hooks.chain(noting, denying, after).stagePre(LOG, STARTED);

        assertEquals("'freeze': no starts on Friday", gate.denied());
        assertEquals(List.of("mind the tests"), gate.notes());
        assertEquals(List.of("noting", "freeze"), gate.records().stream().map(HookRecord::hook).toList());
        assertEquals(List.of("noting", "denying"), reached, "a denial ends the chain");
    }

    /**
     * Spec 2026-09-28-hooks-reach-the-log §4, amended 2026-09-29: fold.post's keeps and notices
     * concatenate in layer order, and each keep still names its hook, for the cap to name.
     */
    @Test
    void keeps_concatenate_and_approval_and_fold_notices_are_collected() {
        Hooks first = new Hooks() {
            @Override
            public FoldPost foldPost(HookContext context, Summarised summarised,
                    Deadline deadline) {
                return new FoldPost(List.of(new FoldPost.Kept("a", null, Tier.HARNESS,
                        "skill a@1 was loaded")), List.of(),
                        List.of(record("a", Stage.FOLD_POST, HookRecord.KEEP)));
            }

            @Override
            public Notified approvalPost(HookContext context, ApprovalAnswer answer) {
                return new Notified(List.of(new Notified.Notice("a", answer.decision())),
                        List.of(record("a", Stage.APPROVAL_POST, HookRecord.NOTIFY)));
            }
        };
        Hooks second = new Hooks() {
            @Override
            public FoldPost foldPost(HookContext context, Summarised summarised,
                    Deadline deadline) {
                return new FoldPost(List.of(new FoldPost.Kept("b", "20-b.js", Tier.PROJECT,
                        "skill b@2 was loaded")),
                        List.of(new Notified.Notice("b", "folded " + summarised.through())),
                        List.of(record("b", Stage.FOLD_POST, HookRecord.KEEP),
                                record("b", Stage.FOLD_POST, HookRecord.NOTIFY)));
            }
        };
        Hooks both = Hooks.chain(first, second);

        FoldPost folded = both.foldPost(LOG, new Summarised(4, 12, 900, "they agreed"),
                Deadline.after(Duration.ofSeconds(2)));

        assertEquals(List.of("skill a@1 was loaded", "skill b@2 was loaded"),
                folded.kept().stream().map(FoldPost.Kept::text).toList());
        assertEquals(List.of("a", "b"), folded.kept().stream().map(FoldPost.Kept::hook).toList());
        assertEquals(List.of("a", "b", "b"),
                folded.records().stream().map(HookRecord::hook).toList());
        assertEquals(List.of(new Notified.Notice("b", "folded 4")), folded.notices());
        assertEquals(List.of(new Notified.Notice("a", ApprovalAnswer.ALLOW)),
                both.approvalPost(LOG, new ApprovalAnswer("apr_1", ApprovalAnswer.ALLOW, "once")).notices());
    }

    @Test
    void none_passes_every_gate_keeps_nothing_and_notifies_nobody() {
        Approving asking = new Approving(List.of("pytest"), "/repo", null, true,
                List.of("once", "conversation", "project"));
        StageDone done = new StageDone(new StageShown("code", "code", 1, 3), "built it", null);

        assertEquals(Gate.NOTHING, Hooks.NONE.stagePre(LOG, STARTED));
        assertEquals(Gate.NOTHING, Hooks.NONE.stagePost(LOG, done));
        assertEquals(Gate.NOTHING, Hooks.NONE.approvalPre(LOG, asking));
        assertEquals(Notified.NOTHING,
                Hooks.NONE.approvalPost(LOG, new ApprovalAnswer("apr_1", ApprovalAnswer.DENY, null)));
        assertEquals(FoldPost.NOTHING, Hooks.NONE.foldPost(LOG,
                new Summarised(1, 2, 3, "they agreed"), Deadline.after(Duration.ofSeconds(2))));
        assertEquals(Gate.NOTHING, Hooks.chain(Hooks.NONE, Hooks.NONE).stagePre(LOG, STARTED));
    }

    /**
     * Spec 2026-09-28-hooks-reach-the-log decision 4, amended 2026-09-29: the harness layer and
     * the project layer share the one deadline the stage started with, so the second layer runs
     * with what the first left rather than with a limit of its own.
     */
    @Test
    void every_layer_of_fold_post_shares_the_one_deadline_it_was_given() {
        List<Deadline> seen = new ArrayList<>();
        Hooks layer = new Hooks() {
            @Override
            public FoldPost foldPost(HookContext context, Summarised summarised,
                    Deadline deadline) {
                seen.add(deadline);
                return FoldPost.NOTHING;
            }
        };
        Deadline deadline = Deadline.after(Duration.ofSeconds(2));

        Hooks.chain(layer, layer).foldPost(LOG, new Summarised(1, 2, 3, "they agreed"), deadline);

        assertEquals(2, seen.size());
        assertTrue(seen.stream().allMatch(each -> each == deadline), seen.toString());
    }

    @Test
    void a_deadline_counts_down_and_stops_at_zero() {
        Deadline passed = new Deadline(System.nanoTime() - 1_000, Duration.ofSeconds(2));

        assertEquals(Duration.ZERO, passed.remaining());
        Duration left = Deadline.after(Duration.ofSeconds(2)).remaining();
        assertTrue(left.compareTo(Duration.ofSeconds(2)) <= 0 && left.compareTo(Duration.ZERO) > 0,
                left.toString());
    }

    @Test
    void a_gate_s_notes_follow_the_text_or_stand_alone_when_there_is_none() {
        Gate noted = new Gate(null, List.of("one", "two"), List.of());

        assertEquals("why\n\none\n\ntwo", noted.applyTo("why"));
        assertEquals("one\n\ntwo", noted.applyTo(null));
        assertEquals("why", Gate.NOTHING.applyTo("why"));
        assertEquals(null, Gate.NOTHING.applyTo(null));
        assertFalse(noted.isDenied());
    }

    // --- an allow is bound to the arguments it judged (spec 2026-09-30-local-hooks-are-served) ---

    private static Hooks allowing() {
        return new Hooks() {
            @Override
            public ToolPre toolPre(HookContext context, String tool, String arguments) {
                return new ToolPre(arguments, null, List.of(), true);
            }
        };
    }

    private static Hooks rewritingTo(String to) {
        return new Hooks() {
            @Override
            public ToolPre toolPre(HookContext context, String tool, String arguments) {
                return ToolPre.allowed(to);
            }
        };
    }

    @Test
    void a_later_layer_s_rewrite_voids_an_earlier_allow_unless_it_or_a_later_one_allows_again() {
        String make = "{\"command\":[\"make\"]}";
        String rm = "{\"command\":[\"rm\"]}";

        assertFalse(Hooks.chain(allowing(), rewritingTo(rm)).toolPre(CONTEXT, "run", make)
                .explicitlyAllowed(), "the allow judged make");
        assertTrue(Hooks.chain(allowing(), rewritingTo(rm), allowing()).toolPre(CONTEXT, "run", make)
                .explicitlyAllowed());
        assertTrue(Hooks.chain(rewritingTo(rm), allowing()).toolPre(CONTEXT, "run", make)
                .explicitlyAllowed());
        assertTrue(Hooks.chain(allowing(), rewritingTo(make)).toolPre(CONTEXT, "run", make)
                .explicitlyAllowed(), "a rewrite to the same arguments changes nothing it judged");
        // A rewrite away and back is still a rewrite after the allow: it judged neither.
        assertFalse(Hooks.chain(allowing(), rewritingTo(rm), rewritingTo(make))
                .toolPre(CONTEXT, "run", make).explicitlyAllowed());
    }

    @Test
    void an_allow_given_for_other_arguments_than_the_final_ones_is_no_allow() {
        ToolPre stale = new ToolPre("{\"command\":[\"rm\"]}", null, List.of(), null,
                "{\"command\":[\"make\"]}");

        assertFalse(stale.explicitlyAllowed());
        assertFalse(Hooks.chain(new Hooks() {
            @Override
            public ToolPre toolPre(HookContext context, String tool, String arguments) {
                return stale;
            }
        }).toolPre(CONTEXT, "run", "{\"command\":[\"make\"]}").explicitlyAllowed());
    }
}
