package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The Studio's four tools as a model reads them — each a door onto a port that owns the work. */
class StudioToolsTest {

    private static final Home HOME = Home.of("story");

    private static final class FakePort implements StudioTools.Port {
        final List<String> calls = new ArrayList<>();
        StudioTools.Installing installing = new StudioTools.Installing.Asked("Install triage?");
        String notADraft;

        @Override public Optional<String> notADraft(String run, String path) {
            calls.add("notADraft " + path);
            return Optional.ofNullable(notADraft);
        }

        @Override public String catalog(String run) { calls.add("catalog " + run); return "the catalog"; }
        @Override public String read(String run, String name) { calls.add("read " + name); return "the source"; }
        @Override public String validate(String run, String path, String text) {
            calls.add("validate " + path + " " + text);
            return "The loader accepts this draft.";
        }
        @Override public StudioTools.Installing install(String run, String path, String text) {
            calls.add("install " + path + " " + text);
            return installing;
        }
    }

    /** Reads one file, as the run's Commands.Port does; refuses any other. Implement every
     *  abstract method {@code Commands.Port} declares (read it first); all but {@code read} throw. */
    private static Commands.Port files(Map<String, String> texts) {
        return new Commands.Port() {
            @Override public Commands.Placed place(Home home, Path cwd, List<String> argv) { throw new UnsupportedOperationException(); }
            @Override public Commands.Verdict judge(Commands.Placed placed) { throw new UnsupportedOperationException(); }
            @Override public io.aeyer.plowshare.protocol.CommandRunner.Outcome run(Commands.Placed placed) { throw new UnsupportedOperationException(); }
            @Override public String read(Home home, String relative) {
                String text = texts.get(relative);
                if (text == null) {
                    throw new WorkspaceRefusedException("no such file: " + relative);
                }
                return text;
            }
        };
    }

    private static AgentTool tool(String name, FakePort port, TurnEnd end, Commands.Port commands) {
        return StudioTools.forRun(port, "orc_1", end, commands, HOME, List.of(name)).get(0);
    }

    @Test
    void validate_reads_the_draft_through_the_run_and_answers_the_port_s_report() {
        FakePort port = new FakePort();
        AgentTool validate = tool(StudioTools.VALIDATE_NAME, port, new TurnEnd(),
                files(Map.of("docs/o/triage.md", "---draft---")));

        String said = validate.run("{\"path\": \"docs/o/triage.md\"}", HOME);

        assertEquals("The loader accepts this draft.", said);
        assertEquals(List.of("notADraft docs/o/triage.md", "validate docs/o/triage.md ---draft---"),
                port.calls);
    }

    @Test
    void a_draft_that_cannot_be_read_is_said_and_the_port_is_not_asked() {
        FakePort port = new FakePort();
        AgentTool validate = tool(StudioTools.VALIDATE_NAME, port, new TurnEnd(), files(Map.of()));

        String said = validate.run("{\"path\": \"docs/o/missing.md\"}", HOME);

        assertEquals("docs/o/missing.md could not be read: no such file: docs/o/missing.md", said);
        assertEquals(List.of("notADraft docs/o/missing.md"), port.calls, "only the path is checked");
    }

    /** Final review 4: a path the Studio would refuse is refused before anything reads it. */
    @Test
    void a_path_that_is_not_a_draft_is_refused_before_it_is_read() {
        List<String> read = new ArrayList<>();
        Commands.Port reading = new Commands.Port() {
            @Override public Commands.Placed place(Home home, Path cwd, List<String> argv) { throw new UnsupportedOperationException(); }
            @Override public Commands.Verdict judge(Commands.Placed placed) { throw new UnsupportedOperationException(); }
            @Override public io.aeyer.plowshare.protocol.CommandRunner.Outcome run(Commands.Placed placed) { throw new UnsupportedOperationException(); }
            @Override public String read(Home home, String relative) {
                read.add(relative);
                return "secret";
            }
        };
        for (String name : List.of(StudioTools.VALIDATE_NAME, StudioTools.INSTALL_NAME)) {
            FakePort port = new FakePort();
            port.notADraft = ".env is not a draft in this run's artifacts directory";
            TurnEnd end = new TurnEnd();

            String said = tool(name, port, end, reading).run("{\"path\": \".env\"}", HOME);

            assertEquals(".env is not a draft in this run's artifacts directory", said);
            assertEquals(List.of("notADraft .env"), port.calls);
            assertTrue(end.requested().isEmpty());
        }
        assertEquals(List.of(), read, "nothing was read");
    }

    @Test
    void install_asked_ends_the_turn_awaiting_and_refused_does_not() {
        FakePort port = new FakePort();
        TurnEnd end = new TurnEnd();
        AgentTool install = tool(StudioTools.INSTALL_NAME, port, end,
                files(Map.of("docs/o/triage.md", "---draft---")));

        String asked = install.run("{\"path\": \"docs/o/triage.md\"}", HOME);

        assertTrue(asked.startsWith("Asked the person whether to install it."), asked);
        assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
        assertEquals("Install triage?", end.requested().orElseThrow().text());

        port.installing = new StudioTools.Installing.Refused("the draft does not load");
        TurnEnd second = new TurnEnd();
        String refused = tool(StudioTools.INSTALL_NAME, port, second,
                files(Map.of("docs/o/triage.md", "x"))).run("{\"path\": \"docs/o/triage.md\"}", HOME);
        assertEquals("the draft does not load", refused);
        assertTrue(second.requested().isEmpty());
    }

    @Test
    void catalog_and_read_go_straight_to_the_port() {
        FakePort port = new FakePort();

        assertEquals("the catalog", tool(StudioTools.CATALOG_NAME, port, new TurnEnd(), null)
                .run("{}", HOME));
        assertEquals("the source", tool(StudioTools.READ_NAME, port, new TurnEnd(), null)
                .run("{\"name\": \"code_implementation\"}", HOME));
    }

    @Test
    void only_the_declared_tools_are_built() {
        List<AgentTool> tools = StudioTools.forRun(new FakePort(), "orc_1", new TurnEnd(), null, HOME,
                List.of("file_read", StudioTools.CATALOG_NAME));

        assertEquals(List.of(StudioTools.CATALOG_NAME),
                tools.stream().map(tool -> tool.schema().name()).toList());
    }
}
