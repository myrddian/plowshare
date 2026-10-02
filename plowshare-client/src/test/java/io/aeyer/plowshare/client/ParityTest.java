package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.client.cli.Commands;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The two front ends offer the same capabilities, and where they differ they say
 * why.
 *
 * <h2>What this can check that an assembly cannot</h2>
 *
 * <p>{@link Capabilities} is enforced where each surface is built: {@link
 * PlowshareClient#tools} refuses a tool nothing declares, and {@code
 * cli.Commands} refuses a command nothing declares. Both of those are one-way —
 * they see what was offered and cannot see what was left out. This class is the
 * other direction, and it is the direction the parity rule is actually about: a
 * capability declared and then not built, and an entry that quietly omits one
 * side.
 *
 * <h2>Against the real surfaces</h2>
 *
 * <p>The registry here is the one {@code PlowshareClient.main} serves and the
 * command list is the one a person types at, not a fixture listing what somebody
 * believed they contained. A test that named the tools itself would pass on the
 * day a tool was deleted.
 *
 * <p>Nothing here reaches a server: {@link HttpServerClient} parses its base URL
 * at construction and opens nothing, and {@link ClientPresence} dials nothing
 * until something asks it to root.
 */
class ParityTest {

    /** A client pointed at a port nothing is listening on. Every tool here is
     *  registered rather than called, so it is never asked for anything. */
    private final ServerClient server = new HttpServerClient("http://127.0.0.1:1", null);

    private ToolRegistry registry() {
        return PlowshareClient.tools(server, new ClientPresence(server, null));
    }

    /**
     * Every tool the MCP surface serves is declared.
     *
     * <p>Also true at assembly, which is the point: this asserts that {@link
     * PlowshareClient#tools} really does the check rather than that the check
     * would pass if it ran. A registry built without it would still satisfy the
     * other tests in this class.
     */
    @Test
    void the_tool_surface_is_the_declared_one() {
        List<String> registered = registry().tools().stream().map(ToolRegistry.Tool::name).toList();

        assertEquals(sorted(Capabilities.tools()), sorted(registered),
                "the MCP surface and what Capabilities declares have come apart");
    }

    /**
     * Every command the terminal offers is declared, and every declared command
     * is one the terminal offers.
     *
     * <p>The second half is what no assembly can see. {@code Commands} refuses a
     * verb nothing declares, so the first half is true before this runs; a
     * capability declared here and never built is invisible to it, and reads —
     * to anyone consulting the declaration to answer "can a person do this?" —
     * as a capability that exists.
     */
    @Test
    void the_command_surface_is_the_declared_one() {
        assertEquals(sorted(Capabilities.commands()), sorted(Commands.offered()),
                "the terminal and what Capabilities declares have come apart");
    }

    /**
     * A capability with only one side declared says why, in prose.
     *
     * <p>The whole rule, and the only one of these tests that is about the
     * declaration rather than about the code under it. "The same capabilities,
     * and where they differ it says why" — so an entry with no tool or no command
     * is legal and an entry that is silent about it is not.
     */
    @Test
    void a_capability_only_one_front_end_has_says_why() {
        List<String> silent = new ArrayList<>();
        for (Capabilities.Capability capability : Capabilities.ALL) {
            boolean lopsided = capability.tools().isEmpty() || capability.commands().isEmpty();
            boolean says = capability.note() != null && !capability.note().isBlank();
            if (lopsided && !says) {
                silent.add(capability.what());
            }
        }
        assertTrue(silent.isEmpty(),
                "these are offered by one front end and not the other, with no reason given, so"
                        + " nothing distinguishes them from drift: " + silent);
    }

    /** Nothing is declared twice, in either spelling: two entries claiming one
     *  tool would let a capability be dropped without any count changing. */
    @Test
    void no_tool_and_no_command_is_declared_by_two_capabilities() {
        List<String> tools = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        for (Capabilities.Capability capability : Capabilities.ALL) {
            tools.addAll(capability.tools());
            commands.addAll(capability.commands());
        }
        assertEquals(tools.size(), Set.copyOf(tools).size(), "a tool is declared twice: " + tools);
        assertEquals(commands.size(), Set.copyOf(commands).size(),
                "a command is declared twice: " + commands);
    }

    /** Every entry is about something. A blank {@code what} would leave a failed
     *  check naming nothing a reader could act on. */
    @Test
    void every_capability_says_what_it_is() {
        for (Capabilities.Capability capability : Capabilities.ALL) {
            assertFalse(capability.what() == null || capability.what().isBlank(),
                    "a capability with no description: " + capability);
        }
    }

    /**
     * The fourth column, and the whole point of this task -- and, on its own,
     * <b>a decorative test rather than one that can fail</b>. {@link
     * Capabilities.Capability}'s compact constructor already refuses to build an
     * entry with empty {@code agents} and a blank {@code agentNote}, so every
     * object that exists inside {@code Capabilities.ALL} necessarily satisfies
     * the assertion below the moment the class finishes loading -- a record's
     * fields cannot change afterward, and a JVM that reached this line already
     * ran that check on all forty entries without throwing. There is no
     * sequence of edits to {@code Capabilities.ALL} that fails this test without
     * also failing to compile the module (the field would not exist) or
     * throwing at class-initialisation (the constructor would refuse it) --
     * both of which stop the build before any test runs, this one included.
     *
     * <p>What survives is documentation value: this spells out the actual rule
     * ("empty agents needs a reason"), not the emptier one the original wording
     * checked ({@code agents() != null}, which {@code List.copyOf} guarantees
     * unconditionally and which is why a review of this task flagged it as a
     * tautology that would still pass with the constructor's check deleted
     * entirely). {@link #a_capability_with_no_agent_and_no_reason_refuses_to_construct}
     * below is the test that actually pins the enforcement: it builds a {@code
     * Capability} outside of {@code ALL}, so nothing has validated it yet, and
     * it is the one that goes red if the constructor's refusal is weakened or
     * removed.
     */
    @Test
    void every_capability_declares_its_agent_surface_or_says_why_it_has_none() {
        for (Capabilities.Capability capability : Capabilities.ALL) {
            boolean namesAgents = !capability.agents().isEmpty();
            boolean saysWhyNot = capability.agentNote() != null && !capability.agentNote().isBlank();
            assertTrue(namesAgents || saysWhyNot,
                    capability.what() + " must name the agents that reach it, or declare an "
                            + "agent gap with a reason — an undeclared gap is the defect this "
                            + "column exists to make visible");
        }
    }

    /**
     * Against the real register, not a constant this test also sets: it reads
     * {@code Capabilities.ALL} itself, so a capability whose {@code tools}
     * happens to name {@code search} or {@code fetch} without naming
     * {@code interlocutor} in {@code agents} fails this — the same shape of
     * mistake that let {@code search} ship reaching no agent in the first
     * place. {@code fetch} now has its own entry, granted to {@code
     * interlocutor} the same way {@code search} was, so this test checks both
     * lines today rather than only {@code search}'s — the day one entry
     * arrived this method was already written to check it and did not
     * change.
     */
    @Test
    void search_and_fetch_name_the_interlocutor() {
        assertTrue(Capabilities.ALL.stream()
                .filter(c -> c.tools().contains("search") || c.tools().contains("fetch"))
                .allMatch(c -> c.agents().contains("interlocutor")));
    }

    /**
     * The enforcement itself, isolated from the forty real entries that
     * happen to satisfy it. Every test above would keep passing if the
     * constructor's check were deleted and every entry in {@code ALL} still
     * carried a reason -- this is the one that fails if the refusal itself
     * breaks, the same way {@code a_capability_only_one_front_end_has_says_why}
     * is the only test that would catch {@code consoleNote}'s twin check going
     * missing.
     */
    @Test
    void a_capability_with_no_agent_and_no_reason_refuses_to_construct() {
        assertThrows(IllegalArgumentException.class, () -> new Capabilities.Capability(
                "a capability made up for this test", List.of(), List.of("some_tool"),
                List.of("some command"), List.of("some-screen"), null, null, null,
                List.of("some.frame"), null));
    }

    /**
     * The fifth column's enforcement, isolated the same way the fourth's is
     * above and for the same reason: every entry in {@code ALL} already carries
     * a frame list or a frame note, so this is the only test that goes red if
     * the compact constructor's refusal is weakened or deleted.
     *
     * <p>Spec §3.7 is what it holds: an endpoint with no frame equivalent has to
     * <em>show</em> as a declared gap. The migration this column exists for said
     * "not yet" on nearly every line while it ran, which was the correct answer
     * and was only ever worth anything because the alternative — saying nothing
     * at all — is impossible.
     */
    @Test
    void a_capability_with_no_frame_and_no_reason_refuses_to_construct() {
        assertThrows(IllegalArgumentException.class, () -> new Capabilities.Capability(
                "a capability made up for this test", List.of(), List.of("some_tool"),
                List.of("some command"), List.of("some-screen"), null, null,
                "some agent reason", List.of(), null));
    }

    /**
     * The other half of the same rule, and the half a naive reading of this
     * column gets wrong. <b>A frame note beside a non-empty frame list is
     * legitimate</b>: {@code frames} is per-capability and an endpoint is not, so
     * a capability whose endpoints are split — some answered by the socket and
     * one deliberately not — has both a list and something left to say about it.
     * Two entries are in exactly that shape, and a "note XOR frames" rule would
     * reject both while catching nothing.
     *
     * <p>What is <em>not</em> legitimate is narrower and is a real mistake
     * somebody could make once per controller: leaving the stock deferred reason
     * on an entry that has just been given frames. {@link Capabilities#frameGap}
     * says in so many words that <em>no</em> frame type answers this, so beside
     * a list of the frame types that answer it, it is a sentence the register
     * contradicts on the same line. Six tasks each added frames to entries that
     * carried it; this is what would have caught the one that forgot.
     */
    @Test
    void a_capability_that_names_frames_may_not_also_call_them_a_gap() {
        assertThrows(IllegalArgumentException.class, () -> new Capabilities.Capability(
                "a capability made up for this test", List.of(), List.of("some_tool"),
                List.of("some command"), List.of("some-screen"), null, null,
                "some agent reason", List.of("some.frame"), Capabilities.frameGap()));
    }

    /**
     * A note beside frames is accepted when it says something the frame list
     * does not, which is the case the rule above must not break. Held over the
     * real register rather than a fixture: both entries in this shape are
     * partial on purpose — one names two of its three endpoints' types and rules
     * the third permanently operational, the other names a conversation's read
     * half and points at the entry where speaking into one is declared.
     */
    @Test
    void the_entries_that_carry_both_a_frame_list_and_a_note_are_kept() {
        List<String> both = new ArrayList<>();
        for (Capabilities.Capability capability : Capabilities.ALL) {
            if (!capability.frames().isEmpty() && capability.frameNote() != null) {
                both.add(capability.what());
            }
        }
        assertEquals(
                List.of("speak into a conversation",
                        "manage which search providers this server will ask"),
                both,
                "a partially framed capability is the one shape that carries both, and it is"
                        + " legitimate: " + both);
    }

    /**
     * <b>The application gap is empty, and this is the line that says so.</b>
     * {@link Capabilities#frameGap} is the stock "not yet", and after the breadth
     * plan no entry uses it: every capability either names the frame types that
     * answer it or argues a decision in its own words.
     *
     * <p>So this is a tripwire rather than a description. An endpoint added
     * later with no frame handler is free to be deferred — {@code frameGap()} is
     * still the honest thing to write for one — but writing it fails here, which
     * is the point: §4.3's risk is a strangler stalling half-done, and the
     * mitigation is that reopening the gap costs one deliberate edit to the list
     * in {@code Capabilities}' own javadoc rather than none at all.
     */
    @Test
    void nothing_is_left_deferred_on_the_socket() {
        List<String> deferred = new ArrayList<>();
        for (Capabilities.Capability capability : Capabilities.ALL) {
            if (Capabilities.frameGap().equals(capability.frameNote())) {
                deferred.add(capability.what());
            }
        }
        assertEquals(List.of(), deferred,
                "every application endpoint has a frame equivalent, so \"not yet\" is no longer"
                        + " an answer any entry gives. If one of these really is deferred rather"
                        + " than decided, say so in the remaining-HTTP-only list in"
                        + " Capabilities' class javadoc and change this test with it: "
                        + deferred);
    }

    /**
     * Documentation value, like its {@code agents} twin above: the constructor
     * has already run on every entry by the time this line executes. It spells
     * out the actual rule rather than a weaker one a reader might mistake for it.
     */
    @Test
    void every_capability_declares_its_frame_surface_or_says_why_it_has_none() {
        for (Capabilities.Capability capability : Capabilities.ALL) {
            boolean namesFrames = !capability.frames().isEmpty();
            boolean saysWhyNot =
                    capability.frameNote() != null && !capability.frameNote().isBlank();
            assertTrue(namesFrames || saysWhyNot,
                    capability.what() + " must name the frame types that answer it, or declare a"
                            + " socket gap with a reason — §3.7's whole point is that \"not yet\""
                            + " becomes a list somebody can read rather than a feeling");
        }
    }

    /** Nothing is declared twice here either, for {@code tools}' own reason: two
     *  entries claiming one frame type would let a capability be dropped without
     *  any count changing. */
    @Test
    void no_frame_type_is_declared_by_two_capabilities() {
        List<String> frames = new ArrayList<>();
        for (Capabilities.Capability capability : Capabilities.ALL) {
            frames.addAll(capability.frames());
        }
        assertEquals(frames.size(), Set.copyOf(frames).size(),
                "a frame type is declared twice: " + frames);
    }

    /**
     * The assembly-side half, which is the one that fails a boot. It is the same
     * one-way check {@code toolsAreDeclared} is: a routed type nothing declares
     * is refused here, and a declared type nothing routes is this class's
     * business rather than an assembly's.
     */
    @Test
    void a_routed_frame_type_nothing_declares_is_refused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Capabilities.framesAreDeclared(List.of("conversation.invented")));

        assertTrue(refused.getMessage().contains("conversation.invented"),
                "the refusal names the type: " + refused.getMessage());
    }

    /**
     * Real routed types really are declared, so the check above is not passing
     * by being empty. Two are named literally rather than the whole set being
     * handed back to the method that produced it, which would assert nothing:
     * these were the two pilots the surface started from, and the register now
     * names fifty-two. The count is not pinned here — {@code FrameRouterTest}
     * holds every type this server knows against this register, which is the
     * check that moves when the surface does.
     */
    @Test
    void routed_frame_types_are_declared() {
        Capabilities.framesAreDeclared(List.of("project.define", "conversation.turns"));

        assertTrue(
                Capabilities.frames().containsAll(List.of("project.define", "conversation.turns")),
                "declared: " + Capabilities.frames());
    }

    /**
     * The trap this column had to be built around. {@code ws.FrameTypes.REFUSED}
     * is response-only — the channel answers under it when a frame was too
     * malformed to have a type at all — so it is never routed and no client can
     * send it. Requiring it to be declared would fail the boot over a capability
     * that does not exist.
     */
    @Test
    void the_response_only_refusal_type_is_never_required_to_be_declared() {
        Capabilities.framesAreDeclared(List.of(Capabilities.RESPONSE_ONLY_REFUSED));

        assertFalse(Capabilities.frames().contains(Capabilities.RESPONSE_ONLY_REFUSED),
                "nothing declares it, which is the point: it is not a capability");
    }

    private static Set<String> sorted(Iterable<String> names) {
        Set<String> ordered = new TreeSet<>();
        names.forEach(ordered::add);
        return ordered;
    }
}
