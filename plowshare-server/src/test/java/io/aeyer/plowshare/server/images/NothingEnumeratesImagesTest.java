package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.ClasspathDefinitions;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.server.agents.BoundTools;
import io.aeyer.plowshare.server.agents.Environments;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Nothing an agent can reach will tell it what images exist.
 *
 * <h2>What this holds up, and why an absence is worth a test</h2>
 *
 * <p>On 2026-09-08 the owner decided that {@code agent_run} may pass on an image
 * id its caller was <em>told</em> and not only one it was <em>shown</em> — see
 * §6 of {@code implementation rationale}
 * and {@code AgentRunTool}'s javadoc, which hold the argument. The safety of
 * that decision is one claim: <b>naming is reachability-equivalent to being
 * shown, because an agent can only ever use an id somebody handed it.</b>
 *
 * <p>That claim rests on three things. An id is 128 bits of a content hash, so
 * it cannot be guessed. Resolution is scoped by a {@code Home} that arrives as a
 * parameter of {@code AgentTool.run}, so a model cannot name another tier —
 * {@code DelegationTest.an_id_in_another_project_is_not_reachable_from_this_run}
 * pins that one. And <b>nothing agent-facing enumerates images</b>, which is the
 * one that is an absence: there is no code to point a test at, only code that
 * must not appear. The day a listing arrives, an agent could <em>discover</em>
 * ids rather than only use ones it was given, and the equivalence above is gone
 * — quietly, with every other test still green. This file is what makes that day
 * loud.
 *
 * <h2>Agent-facing, and that qualifier is the whole of the scope</h2>
 *
 * <p><b>A server-side index is expected and is not forbidden here.</b> Retention
 * has to find what to sweep; an operator's console may well grow a gallery
 * served from something that walks the tier. What must not exist is a way for an
 * <em>agent</em> to reach one, which is why the two surfaces below are the tool
 * set and the HTTP routes rather than {@code ImageStore}'s own shape. {@code
 * ImageStore} answers {@code store}, {@code find} and {@code dataUri} today and
 * this file deliberately does not pin that: a listing method added for retention
 * would be a correct change, and a test that reddened for it would be a test
 * people learn to edit rather than read.
 *
 * <p>The console is not a third surface for the same reason it is not a separate
 * risk: it reaches this server over HTTP, so a gallery is a route, and a route
 * is what the second test below enumerates.
 */
class NothingEnumeratesImagesTest {

    /** By path and not through the classpath: both source sets publish an {@code
     *  agents} directory, so {@code getResource("/agents")} resolves to the test
     *  fixtures. {@code InterlocutorDefinitionTest} measured that and owns the
     *  note. */
    /**
     * The tool names an agent may hold — pinned, so that a listing tool cannot
     * arrive quietly.
     *
     * <p><b>Asserted from both sides, because "agent-facing" has two of them.</b>
     * A tool is reachable by an agent when this boot binds the name <em>and</em>
     * a definition declares it: the bound set is what an operator's own agent
     * file may name, and the declared set is what the agents this repository
     * ships are actually offered. They are the same eighteen names today, and
     * that is a fact about this directory rather than a rule — a tool bound and
     * declared by nobody would be legal and would still belong in this list,
     * because the next definition written could name it.
     *
     * <p><b>{@code BoundTools} is a hand-assembled set and this is not a
     * loophole.</b> {@code
     * AgentsConfigTest.the_known_tool_set_the_tests_assemble_is_the_set_the_boot_binds}
     * asserts it equals {@code JobRuntime.knownTools()} on a real boot, so a
     * tool registered in {@code AgentsConfig} and left out of that set is
     * already red over there; updating it — the one mechanical step that closes
     * that failure — is what brings the change here, in front of this argument.
     *
     * <p>The list is spelled out rather than filtered for names that look like
     * listings. A guard that asked whether a name contained "image" would pass
     * a {@code gallery}, a {@code project_figures}, or an {@code image_reader}
     * that grew an id-listing argument; a set that has to be edited by hand
     * cannot be got round by naming.
     *
     * <p><b>{@code search} and {@code fetch} were added by the fetch slice, and
     * the equivalence survives them.</b> Neither can answer which images exist:
     * {@code search} dials a registered provider and returns hits from the open
     * web, and {@code fetch} retrieves a URL as prose. {@code fetch} is the one
     * worth arguing, because it accepts any address and can therefore dial this
     * server's own port. It still cannot enumerate: every {@code /v1/} route is
     * gated by {@code AuthFilter} on a prefix rule, the token file is refused to
     * an agent by {@code FileAccess}'s dot-prefixed-component predicate and by
     * {@code ProjectStore.mandatoryExclusions} besides, and {@code fetch} issues
     * a {@code GET} while the only route that may name an image is {@code POST
     * /v1/images}. The half of this tripwire below — over published routes — is
     * what actually holds that line, and it is why the loopback reach recorded
     * as cost 6 of the fetch design does not cost this rule anything.
     *
     * <p><b>{@code run} was added by the run slice, and the equivalence does NOT
     * survive it where it is enabled.</b> A command on the server side runs as
     * this server's OS user and can list the data directory, images included —
     * {@code CommandRunner} says plainly that nothing isolates it. <b>Declaring
     * {@code run} is not what opens that, and this test does not forbid it</b>: a
     * definition naming a tool is not permission to use it. What holds the line is
     * the permission flag, the environment's server-side mode, which is {@code off}
     * unless an operator writes otherwise — {@link
     * #run_reaches_nothing_until_an_operator_opens_the_server_side} holds that. An
     * operator who opens the server side has given its agents the data directory;
     * the run spec's §8 records isolation as the slice that closes this.
     *
     * <p>{@code get_date} reads the clock only; it reaches no image store or filesystem and
     * cannot enumerate images.
     *
     * <p><b>{@code conversation_trajectory} reveals only ids already written into
     * the selected conversation.</b> It reads {@code EntryStore.pageOfLog}; it
     * never asks {@code ImageStore}, walks a directory or lists a tier. An image
     * id in a tool call's arguments is one an agent had already been given, so
     * showing that persisted event again does not let Daedalus discover which
     * images exist and does not move §6's reachability boundary.
     *
     * <p>The archive read adapters use conversation/memory records in the run's
     * home; {@code conversation_chat} projects the same persisted entries as
     * the trajectory reader. The corpus adapters return document paragraphs,
     * outlines and citation coordinates. None reads {@code ImageStore} or lists
     * image files, so these reads preserve the same reachability boundary.
     */
    @Test
    void the_tools_an_agent_may_hold_are_these_and_none_of_them_lists_images() {
        List<String> allowed = List.of(
                "agent_run", "conversation_chat", "conversation_context", "conversation_list", "conversation_search", "conversation_trajectory", "document_ask", "document_citations", "document_list", "document_outline", "document_rank", "document_retrieve",
                "document_search",
                "fetch",
                "file_delete", "file_edit", "file_glob", "file_grep", "file_move", "file_read",
                "file_roots", "file_stat", "get_date", "information_read", "information_write",
                "memory_index", "memory_navigate", "memory_read", "memory_recall", "memory_write",
                "result_list", "result_read",
                "run", "search", "todo_read", "todo_write");
        String why = "a tool has been added to what an agent may reach. If it can answer with"
                + " WHICH images exist, agent_run's permissive id rule stops being"
                + " reachability-equivalent to the conservative one -- read §6 of"
                + " implementation rationale before"
                + " editing this list";

        assertEquals(allowed, List.copyOf(BoundTools.boundByThisServer()), why);

        Set<String> declared = new TreeSet<>();
        for (AgentDefinition shipped
                : AgentRegistry.read(new ClasspathDefinitions(), BoundTools.boundByThisServer(),
                        Set.of()).enabled().values()) {
            declared.addAll(shipped.tools());
        }
        // run is behind a permission flag rather than a declaration, so whether a shipped agent
        // names it yet does not move this rule; every other tool must be declared by one.
        assertEquals(allowed.stream().filter(tool -> !tool.equals("run") && !tool.equals("information_write")).toList(),
                declared.stream().filter(tool -> !tool.equals("run") && !tool.equals("information_write")).toList(), why);
    }

    /**
     * The permission flag {@code run}'s place in the list above rests on: with no environment
     * file, and with one that says nothing about the server, the server side runs nothing.
     */
    @Test
    void run_reaches_nothing_until_an_operator_opens_the_server_side() {
        assertEquals(EnvironmentFile.OFF, EnvironmentFile.Side.DEFAULT.mode());
        assertEquals(EnvironmentFile.OFF, Environments.NONE.resolve("payments", null).server().mode());
        assertEquals(EnvironmentFile.OFF, new Environments(name -> 7L, id -> Path.of("/no/such/environment.yml"),
                null).resolve("payments", null).server().mode());
        assertEquals(EnvironmentFile.OFF, EnvironmentFile.Side.DEFAULT.with(
                EnvironmentFile.parse("local:\n  mode: open\n").server()).mode(),
                "a file that opens only the laptop leaves the server off");
    }

    /**
     * The only route in this server that names an image is the upload, and it
     * takes bytes rather than answering with any.
     *
     * <p><b>The enumeration this catches is the one that would not look like a
     * mistake.</b> {@code GET /v1/images} to list a project's pictures, or a
     * gallery route for a console, is an ordinary thing to want and is exactly
     * what would let an agent — through anything that can read a route, or
     * through a person pasting a result into a task — come by ids it was never
     * given. {@code ImageController}'s javadoc already says there is no {@code
     * GET}; this is that sentence with a way to fail.
     *
     * <p>Enumerated the way {@code AuthFilterTest.mappedPaths} does and for its
     * reasons: {@code @RestController} is meta-annotated {@code @Controller} so
     * one filter finds both spellings, and {@code MethodIntrospector} is the
     * same call the dispatcher makes, so this sees a handler exactly when Spring
     * would map it — including one declared on a superclass or without {@code
     * public}. Nothing is started and no request is made.
     */
    @Test
    void the_only_route_that_names_an_image_is_the_upload() throws ClassNotFoundException {
        assertEquals(Set.of("POST /v1/images"), mappingsNaming("image"),
                "a route about images has appeared beside the upload. A way to ask this server"
                        + " WHICH images exist is a way to discover ids rather than be given"
                        + " them, which is the assumption agent_run's id rule rests on -- read"
                        + " §6 of implementation rationale"
                        + "2026-09-08-how-a-picture-gets-in-design.md first");
    }

    /**
     * Every {@code METHOD /path} this application publishes whose path contains
     * {@code word}, class-level prefixes included.
     *
     * <p>A mapping declaring no method is reported as {@code ANY}, which is what
     * a bare {@code @RequestMapping} really is and is not a shape to wave
     * through: a route that answers every verb answers {@code GET} too.
     */
    private static Set<String> mappingsNaming(String word) throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<String> found = new LinkedHashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("io.aeyer.plowshare")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping onClass =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = onClass == null || onClass.path().length == 0
                    ? new String[] {""}
                    : onClass.path();
            Map<Method, RequestMapping> mappings = MethodIntrospector.selectMethods(
                    controller,
                    (MethodIntrospector.MetadataLookup<RequestMapping>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(
                                    method, RequestMapping.class));
            for (RequestMapping mapping : mappings.values()) {
                for (String prefix : prefixes) {
                    for (String path : mapping.path()) {
                        String whole = prefix + path;
                        if (whole.toLowerCase(java.util.Locale.ROOT).contains(word)) {
                            found.add(verbs(mapping) + " " + whole);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static String verbs(RequestMapping mapping) {
        if (mapping.method().length == 0) {
            return "ANY";
        }
        StringBuilder out = new StringBuilder();
        for (RequestMethod method : mapping.method()) {
            out.append(out.isEmpty() ? "" : "|").append(method.name());
        }
        return out.toString();
    }
}
