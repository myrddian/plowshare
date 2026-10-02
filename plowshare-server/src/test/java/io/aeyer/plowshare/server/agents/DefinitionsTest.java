package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing one definition and answering with what it resolves to: the whole
 * decision, below the surface that asked for it.
 *
 * <p>{@code AgentDefinitionApiTest} still measures the same statuses and
 * bodies through HTTP, and deliberately — what that file proves is that
 * nothing a caller sees moved when the decision did. This file proves the
 * decision, and above all <b>the one branch that changes the shape of the
 * answer rather than its status</b>.
 *
 * <p><b>The global-tier tests are the point of this class.</b> A write with no
 * project does not consult {@link DefinitionResolver} at all: the boot set is
 * read once and never rebuilt, so resolving would answer with what this
 * process already believed and not with what was just written. There is no
 * status to compare and no exception to catch — a surface that resolved
 * normally here would return stale data that looks current. {@code
 * a_global_write_is_never_resolved_and_says_a_restart_is_required} is the test
 * that fails when it does.
 */
class DefinitionsTest {

    /** {@code DefinitionWriterTest}'s own fixture, reused for its reason: the
     *  smallest set every shipped {@code REQUIRED} agent parses against. */
    private static final Set<String> TOOLS = Set.of("memory_recall", "memory_read", "agent_run");

    private DefinitionResolver resolver;
    private ProjectStore projects;
    private DataLayout layout;
    private Definitions definitions;

    @BeforeEach
    void setUp(@TempDir Path data) throws Exception {
        layout = new DataLayout(data).initialise();
        resolver = mock(DefinitionResolver.class);
        projects = mock(ProjectStore.class);
        Callers callers = new Callers(resolver, projects, mock(Turn.class), org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
        definitions = new Definitions(writerOver(layout), resolver, projects, callers);
    }

    // --- the branch that changes the shape of the answer -------------------------

    /**
     * The most dangerous line in this file's subject, pinned twice over: the
     * answer says a restart is required, and {@link
     * DefinitionResolver#forCaller} is never asked.
     *
     * <p>The second half is the one no body could show. A surface that wrote
     * the file and then resolved normally would get a current-looking view of
     * whatever this process read at boot — which does not include what was
     * just written, and never will until it restarts.
     */
    @Test
    void a_global_write_is_never_resolved_and_says_a_restart_is_required() throws Exception {
        Definitions.Defined defined = definitions.define(
                new Definitions.Ask(null, "helper", definition("helper", "hi"), false));

        assertTrue(defined.restartRequired());
        assertNull(defined.definition(),
                "a global write has nothing resolved to report, and saying so in the shape is"
                        + " what keeps a surface from rendering one");
        assertTrue(defined.restartReason().contains("the boot set is never rebuilt"),
                defined.restartReason());
        assertTrue(defined.restartReason().contains(
                layout.botsFor(null).resolve("helper.md").toString()), defined.restartReason());
        verify(resolver, never()).forCaller(any());
        assertTrue(Files.exists(layout.botsFor(null).resolve("helper.md")),
                "the bytes land even though nothing serves them yet");
    }

    /** The contrast that proves the test above measures something: a project
     *  write does resolve, live, and reports no restart. */
    @Test
    void a_project_write_is_resolved_live_and_reports_no_restart() throws Exception {
        when(projects.id("payments")).thenReturn(7L);
        when(resolver.forCaller(any()))
                .thenReturn(new AgentRegistry(Map.of("helper", agent("helper"))));

        Definitions.Defined defined = definitions.define(
                new Definitions.Ask("payments", "helper", definition("helper", "hi"), false));

        assertFalse(defined.restartRequired());
        assertNull(defined.restartReason());
        assertNotNull(defined.definition());
        assertEquals("helper", defined.definition().name());
        assertEquals(List.of(), defined.withheld());
        verify(resolver).forCaller(new DefinitionResolver.Caller(7L, null));
    }

    /** The cache is dropped on the way out of a write that landed, for the
     *  tier it landed in — a same-length replacement inside one filesystem
     *  tick is invisible to the resolver's own stamp. */
    @Test
    void a_write_that_landed_invalidates_the_tier_it_landed_in() throws Exception {
        when(projects.id("payments")).thenReturn(7L);
        when(resolver.forCaller(any()))
                .thenReturn(new AgentRegistry(Map.of("helper", agent("helper"))));

        definitions.define(
                new Definitions.Ask("payments", "helper", definition("helper", "hi"), false));

        verify(resolver).invalidate(7L);
    }

    /** Nothing changed on disk, so there is nothing for the cache to have gone
     *  stale about. */
    @Test
    void a_refused_write_invalidates_nothing() throws Exception {
        when(projects.id("payments")).thenReturn(7L);
        Files.createDirectories(layout.botsFor(7L));
        Files.writeString(layout.botsFor(7L).resolve("helper.md"), definition("helper", "old"));

        assertThrows(DefinitionAlreadyExistsException.class, () -> definitions.define(
                new Definitions.Ask("payments", "helper", definition("helper", "hi"), false)));

        verify(resolver, never()).invalidate(any());
    }

    // --- the order the body is read in -------------------------------------------

    /**
     * The project name is resolved before either field is read, so a body that
     * names a project this server does not know is told that and not that it
     * forgot a field.
     */
    @Test
    void an_unknown_project_is_named_before_a_missing_name_is() {
        when(projects.id("ghost")).thenReturn(null);

        CallerFault refused = assertThrows(CallerFault.class, () -> definitions.define(
                new Definitions.Ask("ghost", null, null, false)));

        assertTrue(refused.getMessage().contains("no project called 'ghost'"),
                refused.getMessage());
    }

    /** And {@code name} before {@code text}, sent as one body missing both. */
    @Test
    void a_missing_name_is_named_before_a_missing_text_is() {
        CallerFault refused = assertThrows(CallerFault.class,
                () -> definitions.define(new Definitions.Ask(null, null, null, false)));

        assertTrue(refused.getMessage().contains("'name' is required to define an agent"),
                refused.getMessage());
    }

    /** Neither field reaches the writer, and neither reaches the filesystem. */
    @Test
    void a_body_missing_text_is_refused_and_nothing_is_written() {
        CallerFault refused = assertThrows(CallerFault.class,
                () -> definitions.define(new Definitions.Ask(null, "helper", null, false)));

        assertTrue(refused.getMessage().contains("'text' is required to define an agent"),
                refused.getMessage());
        assertFalse(Files.exists(layout.botsFor(null).resolve("helper.md")));
    }

    // --- the shape itself --------------------------------------------------------

    /**
     * The invariant that makes the dangerous branch unrenderable wrongly: a
     * result carries a resolved definition or a reason a restart is needed,
     * and never both or neither. A surface cannot accidentally show a stale
     * view for a global write because there is no view in the result to show.
     */
    @Test
    void a_result_cannot_carry_both_a_definition_and_a_restart_reason() {
        DefinitionWriter.Written written = new DefinitionWriter.Written(
                layout.botsFor(null).resolve("helper.md"), "somewhere",
                DefinitionWriter.Disposition.CREATED);

        assertThrows(IllegalArgumentException.class, () -> new Definitions.Defined(
                "helper", written, agent("helper"), List.of(), "and a reason"));
        assertThrows(IllegalArgumentException.class, () -> new Definitions.Defined(
                "helper", written, null, List.of(), null));
    }

    /**
     * And nothing is withheld from an agent that was never resolved.
     *
     * <p>{@code withheld} is {@code Callers.withheldFrom} read out of the
     * registry the definition came from, and the restart branch has no
     * registry to ask — which is why {@code define} passes {@code List.of()}
     * there. The javadoc said so and the constructor permitted the opposite:
     * a result claiming both that this process serves nothing under the name
     * and that something was taken away from what it serves. Both halves of
     * the type's own rule are enforced now rather than one enforced and one
     * documented.
     */
    @Test
    void a_result_that_needs_a_restart_cannot_also_say_what_was_withheld() {
        DefinitionWriter.Written written = new DefinitionWriter.Written(
                layout.botsFor(null).resolve("helper.md"), "somewhere",
                DefinitionWriter.Disposition.CREATED);

        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> new Definitions.Defined(
                        "helper", written, null, List.of("helper -> other: no such agent"),
                        "a restart is needed"));

        assertTrue(refused.getMessage().contains("nothing can have been withheld"),
                refused.getMessage());
    }

    // --- fixtures ----------------------------------------------------------------

    private static DefinitionWriter writerOver(DataLayout layout) {
        AgentRegistry bootSet = new AgentRegistry(
                AgentRegistry.read(new ClasspathDefinitions(), TOOLS, AgentsConfig.REQUIRED));
        return new DefinitionWriter(
                bootSet, layout, TOOLS, AgentsConfig.REQUIRED, DefinitionChecks.NONE);
    }

    private static String definition(String name, String body) {
        return "---\nname: " + name + "\ndescription: d\nmodel: m\nmax-turns: 1\n"
                + "max-model-calls: 1\nexported: true\n---\n" + body + "\n";
    }

    private static AgentDefinition agent(String name) {
        return new AgentDefinition(
                name, "a fixture", "fast", List.of(), List.of(), List.of(), 2, 4,
                "You do one thing.", true, true);
    }
}
