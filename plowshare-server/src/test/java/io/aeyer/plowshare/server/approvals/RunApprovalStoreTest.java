package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.ProjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Questions, answers and matching, over V50. Spec 2026-09-15, asking a person, §3. */
@Testcontainers
class RunApprovalStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final List<String> TEST = List.of("./gradlew", "test", "--tests", "Foo");

    private static JdbcTemplate jdbc;

    @TempDir
    Path tmp;

    private RunApprovalStore store;
    private long project;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void fresh() throws IOException {
        jdbc.execute("TRUNCATE TABLE run_approvals, digests, digest_children, digest_memories, digest_spans,"
                + " digest_revisions, memory_provenance, projects CASCADE");
        Path real = tmp.toRealPath();
        Path repo = Files.createDirectory(real.resolve("repo"));
        Path server = Files.createDirectory(real.resolve("srv"));
        ProjectStore projects = new ProjectStore(jdbc, Files.writeString(server.resolve("plowshare.yml"), "x"),
                Files.createDirectory(server.resolve("profiles")), server.resolve("console-token"),
                server.resolve("exports"), server.resolve("data"));
        projects.define("payments", repo, List.of());
        project = projects.id("payments");
        store = new RunApprovalStore(jdbc, () -> Instant.parse("2026-09-15T09:00:00Z"));
    }

    private RunApproval asked(String conversation, String side, List<String> argv) {
        return store.ask(project, conversation, conversation, "coder", side, argv, "/repo", null);
    }

    @Test
    void a_question_is_open_until_it_is_answered_and_is_answered_at_most_once() {
        RunApproval question = asked("cnv_1", "local", TEST);

        assertEquals(List.of(question.id()), store.open("cnv_1").stream().map(RunApproval::id).toList());
        assertTrue(store.deny(question.id(), "enzo"));
        assertFalse(store.allow(question.id(), RunApproval.ONCE, null, "enzo"),
                "an answered question takes no second answer");
        assertTrue(store.open("cnv_1").isEmpty());
        assertEquals(RunApproval.DENIED, store.find(question.id()).orElseThrow().state());
    }

    @Test
    void an_account_s_open_questions_are_listed_wherever_they_were_raised_and_no_one_else_s() {
        RunApproval underAConductor = store.ask(project, "cnv_conductor", "cnv_coder", "enzo",
                "code_implementation", "local", TEST, "/repo", null);
        RunApproval inAnEvent = store.ask(project, "cnv_event", "cnv_event", "enzo", "coder",
                "server", TEST, "/repo", null);
        store.ask(project, "cnv_hers", "cnv_hers", "mara", "coder", "local", TEST, "/repo", null);
        RunApproval answered = store.ask(project, "cnv_done", "cnv_done", "enzo", "coder", "local",
                TEST, "/repo", null);
        store.deny(answered.id(), "enzo");

        assertEquals(java.util.Set.of(underAConductor.id(), inAnEvent.id()),
                store.openFor("enzo").stream().map(RunApproval::id)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void an_unattended_question_pins_its_account_until_delivery() {
        RunApproval question = store.ask(project, "cnv_event", "cnv_child", "enzo", "coder",
                "server", TEST, "/repo", null);

        assertEquals("enzo", question.handle());
        assertEquals(List.of(question.id()), store.undelivered("cnv_event").stream()
                .map(RunApproval::id).toList());
        store.delivered(question.id());
        assertTrue(store.undelivered("cnv_event").isEmpty());
        assertTrue(store.find(question.id()).orElseThrow().deliveredAt() != null);
    }

    /**
     * A command given input is approved with that input: the question shows it, and only a call
     * given the same input is let through — the input to a program that reads its code from
     * stdin is the code.
     */
    @Test
    void an_approval_asked_with_input_covers_only_that_input() {
        List<String> reads = List.of("./gradlew", "-");
        RunApproval once = store.ask(project, "cnv_1", "cnv_1", "coder", "local", reads, "/repo",
                RunApproval.withInput(null, "first\n"));
        store.allow(once.id(), RunApproval.ONCE, null, "enzo");
        RunApproval always = store.ask(project, "cnv_2", "cnv_2", "coder", "local", reads, "/repo",
                RunApproval.withInput("'allowlist': reads", "second"));
        store.allow(always.id(), RunApproval.CONVERSATION, null, "enzo");

        assertTrue(store.consume(project, "cnv_1", "local", reads, "/repo", "other\n").isEmpty());
        assertTrue(store.consume(project, "cnv_1", "local", reads, "/repo").isEmpty(),
                "no input is other input");
        assertTrue(store.consume(project, "cnv_1", "local", reads, "/repo", "first\n").isPresent());
        assertEquals(RunApproval.USED, store.find(once.id()).orElseThrow().state());

        assertTrue(store.consume(project, "cnv_2", "local", reads, "/repo", "second").isPresent());
        assertTrue(store.consume(project, "cnv_2", "local", reads, "/repo", "second").isPresent());
        assertTrue(store.consume(project, "cnv_2", "local", reads, "/repo", "second ").isEmpty());
    }

    @Test
    void an_approval_asked_with_no_input_covers_no_input_only() {
        store.allow(asked("cnv_1", "local", TEST).id(), RunApproval.CONVERSATION, null, "enzo");

        assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo", null).isPresent());
        assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo", "y\n").isEmpty());
    }

    @Test
    void a_project_approval_covers_its_prefix_whatever_the_input() {
        store.allow(asked("cnv_1", "local", TEST).id(), RunApproval.PROJECT, TEST, "enzo");

        assertTrue(store.consume(project, "cnv_9", "local", TEST, "/repo", "anything").isPresent());
    }

    @Test
    void the_input_shown_is_cut_and_carries_its_length_and_digest() {
        String shown = RunApproval.input("a\n".repeat(100));

        assertTrue(shown.startsWith("given input: `a\\na\\n"), shown);
        assertTrue(shown.contains("…` (200 bytes, sha256 "), shown);
        assertTrue(shown.length() < 140, shown);
        assertEquals(null, RunApproval.input(null));
        assertEquals("why — " + RunApproval.input("x"), RunApproval.withInput("why", "x"));
        assertEquals(RunApproval.input("x"), RunApproval.withInput(null, "x"));
        assertEquals("why", RunApproval.withInput("why", null));
    }

    @Test
    void a_once_approval_lets_the_call_run_one_time() {
        RunApproval question = asked("cnv_1", "local", TEST);
        store.allow(question.id(), RunApproval.ONCE, null, "enzo");

        assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isPresent());
        assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isEmpty());
        assertEquals(RunApproval.USED, store.find(question.id()).orElseThrow().state());
    }

    @Test
    void a_conversation_approval_covers_the_exact_call_in_that_conversation_only() {
        store.allow(asked("cnv_1", "local", TEST).id(), RunApproval.CONVERSATION, null, "enzo");

        assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isPresent());
        assertTrue(store.consume(project, "cnv_1", "local", TEST, "/repo").isPresent());
        assertTrue(store.consume(project, "cnv_2", "local", TEST, "/repo").isEmpty());
        assertTrue(store.consume(project, "cnv_1", "local", TEST, "/elsewhere").isEmpty());
        assertTrue(store.consume(project, "cnv_1", "local", List.of("./gradlew", "publish"), "/repo").isEmpty());
    }

    @Test
    void a_project_approval_covers_its_prefix_anywhere_on_its_side_and_can_be_revoked() {
        RunApproval question = asked("cnv_1", "local", TEST);
        store.allow(question.id(), RunApproval.PROJECT, List.of("./gradlew", "test"), "enzo");

        assertTrue(store.consume(project, "cnv_9", "local", List.of("./gradlew", "test"), "/other").isPresent());
        assertTrue(store.consume(project, "cnv_9", "server", List.of("./gradlew", "test"), "/repo").isEmpty(),
                "an approval for a laptop never covers the server");
        assertTrue(store.consume(project, "cnv_9", "local", List.of("./gradlew", "testFixturesJar"), "/repo")
                .isEmpty(), "whole arguments, never substrings");
        assertEquals(1, store.standing(project).size());

        assertTrue(store.revoke(question.id()));
        assertTrue(store.consume(project, "cnv_9", "local", List.of("./gradlew", "test"), "/repo").isEmpty());
        assertTrue(store.standing(project).isEmpty());
        assertFalse(store.revoke(question.id()));
    }

    @Test
    void covers_compares_whole_leading_arguments() {
        assertTrue(RunApprovalStore.covers(List.of("git"), List.of("git", "status")));
        assertTrue(RunApprovalStore.covers(List.of("git", "status"), List.of("git", "status")));
        assertFalse(RunApprovalStore.covers(List.of(), List.of("git")));
        assertFalse(RunApprovalStore.covers(null, List.of("git")));
        assertFalse(RunApprovalStore.covers(List.of("git", "status", "-s"), List.of("git", "status")));
        assertFalse(RunApprovalStore.covers(List.of("gi"), List.of("git")));
    }

    @Test
    void the_default_prefix_is_the_program_and_its_first_argument() {
        assertEquals(List.of("./gradlew", "test"), asked("cnv_1", "local", TEST).defaultPrefix());
        assertEquals(List.of("ls"), asked("cnv_1", "local", List.of("ls")).defaultPrefix());
    }

    @Test
    void a_question_and_the_answer_that_won_are_each_told_once() {
        List<String> told = new ArrayList<>();
        store.useEvents(new ApprovalEvents() {
            @Override
            public void asked(RunApproval approval) {
                told.add("asked " + approval.state());
            }

            @Override
            public void answered(RunApproval approval) {
                told.add("answered " + approval.state() + " by " + approval.answeredBy());
            }
        });

        RunApproval question = asked("cnv_1", "local", TEST);
        assertTrue(store.deny(question.id(), "enzo"));
        assertFalse(store.allow(question.id(), RunApproval.ONCE, null, "enzo"));

        assertEquals(List.of("asked asked", "answered denied by enzo"), told);
    }

    /**
     * V68: every answer that settles a question — each decision, a denial, a withdrawal — is told
     * by id, so the person's inbox notice about it can leave; an answer that lost, and the judge's
     * allowing that nobody was asked, are not.
     */
    @Test
    void every_answer_that_settles_a_question_is_told_so_its_notice_can_leave() {
        List<String> settled = new ArrayList<>();
        store.whenSettled(settled::add);
        RunApproval once = asked("cnv_1", "local", TEST);
        RunApproval conversation = asked("cnv_1", "local", TEST);
        RunApproval standing = asked("cnv_1", "local", TEST);
        RunApproval denied = asked("cnv_1", "local", TEST);
        RunApproval superseded = asked("cnv_1", "local", TEST);
        RunApproval ended = asked("cnv_1", "local", TEST);

        assertTrue(store.allow(once.id(), RunApproval.ONCE, null, "enzo"));
        assertTrue(store.allow(conversation.id(), RunApproval.CONVERSATION, null, "enzo"));
        assertTrue(store.allow(standing.id(), RunApproval.PROJECT, List.of("./gradlew"), "enzo"));
        assertTrue(store.deny(denied.id(), "enzo"));
        assertTrue(store.deny(superseded.id(), RunApproval.SUPERSEDED));
        assertTrue(store.deny(ended.id(), RunApproval.RUN_ENDED));
        assertFalse(store.deny(once.id(), "enzo"), "already answered");
        store.allowedBy(project, "cnv_1", "cnv_1", "enzo", "coder", "local", TEST, null, "/repo",
                null, RunApproval.JUDGE, "a test run");

        assertEquals(List.of(once.id(), conversation.id(), standing.id(), denied.id(),
                superseded.id(), ended.id()), settled);
    }

    @Test
    void a_settle_that_throws_costs_the_answer_nothing() {
        store.whenSettled(id -> {
            throw new IllegalStateException("the inbox is on fire");
        });
        RunApproval question = asked("cnv_1", "local", TEST);

        assertTrue(store.deny(question.id(), "enzo"));
        assertEquals(RunApproval.DENIED, store.find(question.id()).orElseThrow().state());
    }

    @Test
    void a_listener_that_throws_costs_the_question_nothing() {
        store.useEvents(new ApprovalEvents() {
            @Override
            public void asked(RunApproval approval) {
                throw new IllegalStateException("the record is on fire");
            }
        });

        RunApproval question = asked("cnv_1", "local", TEST);

        assertEquals(RunApproval.ASKED, store.find(question.id()).orElseThrow().state());
    }
    /**
     * V67: one approval for a whole acceptance set. Its commands are kept in order; its own argv
     * is empty, so no run tool call ever matches — and so never spends — it, whatever it is
     * answered with; and one answer answers the set.
     */
    @Test
    void a_set_is_one_question_its_commands_kept_and_no_run_tool_call_spends_it() {
        List<List<String>> set = List.of(TEST, List.of("./gradlew", "run"));
        List<RunApproval> told = new ArrayList<>();
        store.useEvents(new ApprovalEvents() {
            @Override public void asked(RunApproval approval) {
                told.add(approval);
            }
        });

        RunApproval question = store.askSet(project, "cnv_conductor", "cnv_conductor", "enzo",
                "implement_specification", "local", set, "/repo", "the set's reason", "unsure");

        assertTrue(question.isSet());
        assertEquals(set, question.commands());
        assertEquals(set, question.covered());
        assertEquals(List.of(), question.argv());
        assertEquals("unsure", question.judged());
        assertEquals(RunApproval.ASKED, question.state());
        assertEquals(List.of(question.id()), told.stream().map(RunApproval::id).toList());
        assertTrue(store.allow(question.id(), RunApproval.ONCE, null, "enzo"));
        for (List<String> each : set) {
            assertTrue(store.consume(project, "cnv_conductor", "local", each, "/repo").isEmpty());
        }
        assertEquals(RunApproval.ALLOWED, store.find(question.id()).orElseThrow().state(),
                "still allowed: the set's every command runs under it");
    }

    /** V67: what the command judge allowed is written allowed, told as an answer, not a question. */
    @Test
    void the_judge_s_allowing_is_an_answer_nobody_was_asked() {
        List<RunApproval> asked = new ArrayList<>();
        List<RunApproval> answered = new ArrayList<>();
        store.useEvents(new ApprovalEvents() {
            @Override public void asked(RunApproval approval) {
                asked.add(approval);
            }
            @Override public void answered(RunApproval approval) {
                answered.add(approval);
            }
        });

        RunApproval check = store.allowedBy(project, "cnv_conductor", "cnv_conductor", "enzo",
                "code_implementation", "local", TEST, null, "/repo", "the check's reason",
                RunApproval.JUDGE, "runs the project's tests");
        RunApproval set = store.allowedBy(project, "cnv_conductor", "cnv_conductor", "enzo",
                "implement_specification", "local", List.of(), List.of(TEST), "/repo", "reason",
                RunApproval.JUDGE, "tests");

        assertEquals(List.of(), asked, "nobody was asked");
        assertEquals(List.of(check.id(), set.id()), answered.stream().map(RunApproval::id)
                .toList());
        assertEquals(RunApproval.ALLOWED, check.state());
        assertEquals(RunApproval.JUDGE, check.answeredBy());
        assertEquals("runs the project's tests", check.judged());
        assertTrue(check.answeredAt() != null);
        assertEquals(TEST, check.argv());
        assertFalse(check.isSet());
        assertTrue(set.isSet());
        assertTrue(store.open("cnv_conductor").isEmpty(), "nothing waits on the person");
    }

    @Test
    void a_set_with_no_commands_is_refused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.askSet(project, "cnv_1", "cnv_1", null, "a", "local", List.of(),
                        "/repo", "r", null));
    }
}
