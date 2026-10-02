package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** The shared contract table, which the TUI's runner runs too. */
class CommandRunnerTest {

    @TempDir
    Path dir;

    @TestFactory
    List<DynamicTest> every_case_in_the_shared_table() throws Exception {
        JsonNode cases;
        try (InputStream in = CommandRunnerTest.class.getResourceAsStream("commands.json")) {
            cases = new ObjectMapper().readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                List<String> argv = strings(c.get("argv"));
                List<String> inherit = c.has("inherit") ? strings(c.get("inherit")) : List.of("PATH");
                Map<String, String> host = new LinkedHashMap<>(Map.of("PATH", System.getenv("PATH")));
                host.putAll(map(c.get("host")));
                CommandRunner.Command command = new CommandRunner.Command(argv, dir, map(c.get("env")),
                        inherit, Duration.ofMillis(c.has("timeoutMillis") ? c.get("timeoutMillis").asLong() : 10_000),
                        c.has("outputBytes") ? c.get("outputBytes").asLong() : 1024 * 1024,
                        // stdinBytes: that many x's, for a bound too long to write out.
                        c.has("stdinBytes") ? "x".repeat(c.get("stdinBytes").asInt())
                                : c.has("stdin") ? c.get("stdin").asText() : null);
                JsonNode expect = c.get("expect");
                if (expect.has("refused")) {
                    assertThrows(CommandRunner.Refused.class, () -> CommandRunner.run(command, host, () -> false));
                    return;
                }
                CommandRunner.Outcome outcome = CommandRunner.run(command, host, () -> false);
                if (expect.has("exitCode")) {
                    if (expect.get("exitCode").isNull()) {
                        assertNull(outcome.exitCode());
                    } else {
                        assertEquals(expect.get("exitCode").asInt(), outcome.exitCode(), outcome.toString());
                    }
                }
                if (expect.has("timedOut")) {
                    assertEquals(expect.get("timedOut").asBoolean(), outcome.timedOut());
                }
                if (expect.has("stdout")) {
                    assertEquals(expect.get("stdout").asText(), outcome.stdout());
                }
                if (expect.has("stderr")) {
                    assertEquals(expect.get("stderr").asText(), outcome.stderr());
                }
                if (expect.has("stdoutLength")) {
                    assertEquals(expect.get("stdoutLength").asInt(), outcome.stdout().length());
                }
                if (expect.has("stdoutCut")) {
                    assertEquals(expect.get("stdoutCut").asLong(), outcome.stdoutCut());
                }
                if (expect.has("stdoutEndsWith")) {
                    assertTrue(outcome.stdout().endsWith(expect.get("stdoutEndsWith").asText()));
                }
                if (expect.has("maxMillis")) {
                    assertTrue(outcome.millis() < expect.get("maxMillis").asLong(), outcome.toString());
                }
                if (expect.has("minMillis")) {
                    assertTrue(outcome.millis() >= expect.get("minMillis").asLong(), outcome.toString());
                }
                // runs-for (spec 2026-10-01 §1): the case's deadline read as how long it had to run.
                if (expect.has("ranFor")) {
                    assertEquals(expect.get("ranFor").asBoolean(),
                            CommandRunner.ranFor(outcome, command.timeout()), outcome.toString());
                }
            }));
        }
        return tests;
    }

    @Test
    void a_cancel_kills_the_command_and_says_it_was_cancelled() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // the test ends either way
            }
            cancelled.set(true);
        });
        CommandRunner.Outcome outcome = CommandRunner.run(new CommandRunner.Command(
                List.of("sh", "-c", "sleep 30"), dir, Map.of(), List.of("PATH"), Duration.ofMinutes(1),
                1024), Map.of("PATH", System.getenv("PATH")), cancelled::get);

        assertTrue(outcome.cancelled());
        assertNull(outcome.exitCode());
        assertTrue(outcome.millis() < 8000);
    }

    @Test
    void a_working_directory_that_is_not_one_is_refused() {
        assertThrows(CommandRunner.Refused.class, () -> CommandRunner.run(new CommandRunner.Command(
                List.of("true"), dir.resolve("absent"), Map.of(), List.of("PATH"), Duration.ofSeconds(5),
                1024), Map.of("PATH", System.getenv("PATH")), () -> false));
    }

    @Test
    void a_program_is_looked_up_on_the_commands_path_and_not_this_processs() {
        CommandRunner.Refused refused = assertThrows(CommandRunner.Refused.class,
                () -> CommandRunner.run(new CommandRunner.Command(List.of("sh", "-c", "true"), dir,
                        Map.of(), List.of(), Duration.ofSeconds(5), 1024),
                        Map.of("PATH", System.getenv("PATH")), () -> false));
        assertTrue(refused.getMessage().contains("PATH"), refused.getMessage());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> map(JsonNode object) {
        Map<String, String> out = new LinkedHashMap<>();
        if (object != null) {
            object.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }
}
