package io.aeyer.plowshare.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a Plowshare client is, declared once, for all three front ends to be held
 * to.
 *
 * <h2>The rule this exists to keep</h2>
 *
 * <p>{@code cli.Plowshare} is Plowshare's own harness — its analogue of Hermes or
 * DSH running on a local machine — and {@link PlowshareClient} is the adapter that
 * lets somebody else's harness do the same things. So:
 *
 * <blockquote>Anything one of them can do and the other cannot is a thing
 * Plowshare is only fully usable through one front end.</blockquote>
 *
 * <p>It is <b>"the same capabilities, and where they differ it says why"</b> and
 * not flat equality. A stated exception is a decision; an unstated one is drift.
 *
 * <h2>Why a declaration, rather than remembering</h2>
 *
 * <p>The parity design named the root cause: each main <em>hand-assembles</em>
 * what it offers. {@code PlowshareClient.main} registers six tool families;
 * {@code cli.Plowshare} parses flags and dispatches a table. Nothing anywhere
 * said what the set was, so parity was a coincidence that had to be
 * re-established by hand every time either side grew — which is the same shape as
 * the console's {@code wire.ts} drifting from {@code JobView}: two things that
 * must agree with nothing holding them together.
 *
 * <p>This is the thing that holds them together, and it holds them at
 * <b>assembly time rather than in a test</b>. {@link #toolsAreDeclared} runs in
 * {@link PlowshareClient#tools} and {@link #commandsAreDeclared} runs in {@code
 * cli.Commands}' static initialiser, so a tool or a verb added without a line
 * here fails on the first thing that builds either surface — every test in the
 * module included. {@code ParityTest} pins the other direction, which no
 * assembly can see: a capability declared here that neither side actually
 * offers, and an entry that omits one side without saying why.
 *
 * <h2>There is a third front end, and it is not a peer of the other two</h2>
 *
 * <p>This class knew about two surfaces while there were three. {@code
 * plowshare-console} is a browser tab served by the same server, reading the
 * same {@code /v1}, and it was invisible here — so <em>log search shipped on two
 * surfaces of three and nothing recorded the gap</em>. {@link
 * Capability#screens} is what records it, and three things about that list are
 * deliberately unlike the other two.
 *
 * <p><b>1. A screen is a place, not a verb, so the list repeats.</b> {@code
 * memory_index} exists to offer one capability and {@code plowshare memory index}
 * exists to offer one capability; the console's {@code memory} screen offers four
 * and its {@code trajectory} screen offers three. So {@code screens}
 * is a many-to-many mapping where the other two are nearly one-to-one, and it is
 * <b>deliberately out of {@code ParityTest}'s "nothing is declared twice"
 * rule</b> — a rule that is load-bearing for a tool name and would be simply
 * false about a screen.
 *
 * <p><b>2. Nothing in Java assembles the console, so there is no {@code
 * screensAreDeclared}.</b> The two checks above run where a surface is built;
 * the console's surface is built by TypeScript in a module with no Java in it,
 * and a Java list of screen names is exactly the fixture {@code ParityTest}'s
 * own javadoc warns about — one that "would pass on the day a tool was deleted".
 * <b>The check therefore lives on the side that can see the surface</b>:
 * {@code plowshare-console/src/screens/parity.test.ts} reads this file, takes
 * every name inside an {@link #inConsole} call, and holds it against the
 * console's own {@code VIEWS} in both directions. That is the same discipline —
 * against the real surface and never against a second list — applied from the
 * only module where the real surface is.
 *
 * <p><b>3. A console gap gets its own field.</b> {@link Capability#note} answers
 * "why does one of the two mains not have this"; {@link Capability#consoleNote}
 * answers "why can a person not do this in the browser". Those are different
 * arguments about different absences — "a tool cannot spend somebody's allowance
 * on an utterance they did not make" and "a browser tab has no filesystem to
 * lend" are not the same sentence — and folding them into one string means
 * neither can be read off. {@code consoleNote} is <b>required exactly when
 * {@code screens} is empty</b>, and unlike the other rule it is enforced in
 * {@link Capability}'s own constructor rather than in a test: the constructor
 * runs when {@link #ALL} initialises, which is the first thing either main does,
 * and it is the only place left that runs at all. The tool/command rule stays in
 * {@code ParityTest} precisely so that it goes on being a test that can fail.
 *
 * <h2>What the third surface turned up the moment it was named</h2>
 *
 * <p><b>Three capabilities exist only in the console</b>, and the register had
 * no way to say so because it had no third column: putting a document into the
 * corpus, invalidating a memory, and re-opening settled promotion proposals.
 * None of the three has an MCP tool or a CLI verb — {@code POST /v1/documents},
 * {@code POST /v1/memories/&#123;id&#125;/invalidate} and {@code POST
 * /v1/proposals/reconsider} were reachable by {@code curl} and by nothing else
 * until the console grew screens for them. They are entries here now, each with
 * a {@link Capability#note} saying why, which is the inversion of the parity
 * rule the register was written for: not "one main has it and the other does
 * not", but "neither main has it".
 *
 * <p>That they were invisible is structural rather than careless. The two
 * assembly checks are one-way — they see what a surface offered and cannot see
 * what nothing offered — and a capability with no entry at all is not lopsided,
 * not undeclared, and not counted. {@code ParityTest} would have caught a
 * declared-and-unbuilt entry; nothing catches an endpoint no front end reaches,
 * and nothing here can, because this class does not read the server's routes.
 * <b>That is the next thing worth building</b> and it is not this slice.
 *
 * <p><b>The fourth surface turned out to be that thing, sideways.</b> It still
 * does not read the server's routes — but a frame type must be declared against
 * an entry, and an endpoint that reached no front end had no entry to be
 * declared against, so each one had to be found and written down before its
 * frame could be. <b>Eleven entries below were added for exactly that reason</b>
 * and would otherwise not exist: forty when the socket column was cut, fifty-one
 * when the breadth plan closed. Among them are {@code GET /v1/agents}, {@code
 * GET /v1/projects}, a conversation's lifecycle, {@code POST
 * /v1/memories/reembed} and the four operator routes nothing had ever named —
 * the image upload, the retention sweep, the buffer purge and the provider
 * registry. A register that was three columns wide and complete was, when a
 * fourth column asked a question of every endpoint, twenty per cent short.
 *
 * <p>It is not a check and should not be mistaken for one. It found eleven
 * because the breadth plan walked every controller once; nothing here would
 * notice the twelfth, and an endpoint added tomorrow with neither a front end
 * nor a frame is invisible again on the same day. The census that would catch
 * it is still unbuilt, and is still worth building.
 *
 * <h2>There is now a fourth <em>surface</em> as well, and the application half
 * of it is finished</h2>
 *
 * <p>Plowshare's application surface was HTTP. A WebSocket frame surface was
 * built beside it — additive, HTTP untouched — and the overlap it was designed
 * to survive turned out to be short. <b>Fifty-two frame types answer fifty-two
 * of the fifty-five endpoints in the server's {@code api/} package</b>, which is
 * every application endpoint except two binary uploads and one operational
 * route. <b>A fifty-third answers no endpoint at all</b> — {@code
 * conversation.latest}, declared under "speak into a conversation", where the
 * reason a listing could not answer it is written out. That is the asymmetry
 * running the other way for the first time, and the register holds it in the
 * same column rather than leaving the two surfaces to be read as a mirror.
 *
 * <p>{@link Capability#frames} is what made leaving that half-finished safe
 * rather than merely survivable while it ran, and spec §3.7 is the whole
 * argument: <b>"a frame type with no declaration fails the build, and an
 * endpoint with no frame equivalent shows as a declared gap."</b> The two halves
 * fail in different places on purpose, and §3.7 says which: the first is {@code
 * FrameRouterTest}'s, because no Java in the server's main source set can see
 * this class; the second is {@link Capability}'s own compact constructor, and so
 * fails the boot.
 *
 * <p><b>The column's value was never that it would fill up.</b> It is that the
 * emptiness was enumerated while it lasted, so a reader could count the
 * remaining gap instead of estimating it. What the column holds now is the list
 * below, which is the same instrument pointed at what is left.
 *
 * <h2>Everything a socket-only client still cannot do</h2>
 *
 * <p>Eleven endpoints, and they are written here rather than remembered because
 * §4.3 says why: <em>"a strangler's real risk is stalling half-done. The
 * mitigation is §3.7: the remaining gap is a declared list rather than a
 * feeling."</em> <b>Every one of the eleven is a decision. Not one of them is a
 * "not yet"</b> — {@link #frameGap()} is the stock sentence for a deferral and
 * no entry below gives it any more, which {@code ParityTest} holds so that
 * reopening the gap costs a deliberate edit to this list.
 *
 * <p><b>Two binary uploads, permanently.</b> {@code POST /v1/documents} takes a
 * multipart PDF and {@code POST /v1/images} takes multipart bytes whose format
 * is sniffed from the bytes. A text frame could carry either as base64, and
 * neither will: nothing on this server has a precedent for it — {@code
 * FileChannelHandler} is a {@code TextWebSocketHandler} that has only ever
 * exchanged JSON — and the event channel's write queue is bounded and sized for
 * small frames. Two endpoints do not justify inventing a binary frame shape.
 * <b>The cost is stated and reversible</b>: a socket-only client cannot upload
 * and keeps one HTTP call for it. Their two entries below carry the reasoning.
 *
 * <p><b>Three operational routes, permanently.</b> {@code POST
 * /v1/search/providers}, {@code GET /v1/config} and {@code PUT
 * /v1/config/&#123;key&#125;}. §4.1 does not narrow HTTP to nothing — it narrows
 * it to "health, auth, and what ops genuinely needs", and these are what that
 * sentence is about. {@code PUT /v1/config/&#123;key&#125;} takes a raw {@code
 * text/plain} body, which is the shape of a thing meant to be reached with
 * {@code curl} by somebody holding a shell on the box; provider registration is
 * the one route that makes this server dial a URL the caller supplied and report
 * what came back. Both of the entries these land on argue them at length.
 *
 * <p><b>Six auth endpoints, out by construction rather than by ruling.</b>
 * {@code POST /v1/auth}, {@code /v1/auth/refresh}, {@code /v1/auth/login},
 * {@code /v1/auth/ticket}, {@code /v1/auth/password} and {@code GET
 * /v1/auth/session}. §2 and §5: auth happens before the socket exists, and
 * {@code POST /v1/auth/ticket} mints the very credential the socket opens with.
 * A frame equivalent would have to be sent down a connection that could not have
 * been established without it. <b>These are the one part of the remaining
 * surface with no entry in this register at all</b>, and that is correct rather
 * than an omission: signing in is not a capability a Plowshare client offers, it
 * is how a client comes to be able to offer any of them. It is written here so
 * that the absence reads as reasoned to the next person who counts the routes
 * and finds eleven where this list would otherwise claim five.
 *
 * <p><b>A decision and a "not yet" are different facts and are written
 * differently.</b> A deferral is one migration repeated, so it is the stock
 * {@link #frameGap()} sentence; a ruling is argued in the entry's own words, and
 * the reader's test is whether the sentence would still be true after the
 * migration finished. That test is why the list above survives this task: every
 * sentence in it is still true.
 *
 * <p><b>Where the enforcement lives, and why it is not where the other two
 * are.</b> {@link #toolsAreDeclared} and {@link #commandsAreDeclared} are called
 * from {@link PlowshareClient#tools} and {@code cli.Commands}' static
 * initialiser — both in this module, which is what lets them run at assembly
 * time. The frame surface is assembled in {@code plowshare-server}, whose {@code
 * src/main} deliberately cannot see this module at all: the server takes {@code
 * plowshare-client} as a test dependency only, so that the compiler goes on
 * enforcing that the server knows nothing about MCP. {@link
 * #framesAreDeclared(Collection)} therefore cannot be called from {@code
 * ws.FrameRoutingConfig}, and buying that call would mean putting the MCP SDK on
 * the server's runtime class path. So it lands where {@link Capability#screens}'
 * check landed, for the identical reason: <b>on the side that can see the
 * surface</b>. That is {@code FrameRouterTest} in the server's test source set,
 * the one place in this repository where the real routing table and this
 * register can be in one JVM — the console's arrangement repeated, not the tool
 * surface's.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It carries no descriptions, no schemas and no help text. A tool description
 * is read by a model, a command's usage line is read by a person at a terminal,
 * and a screen is read by a person looking at it; the three are written
 * differently on purpose — {@code Commands} says why. What is shared between the
 * surfaces is the <em>capability</em>, which is the only thing that has to be
 * the same.
 */
public final class Capabilities {

    /**
     * One thing a Plowshare client can do, and how each front end offers it.
     *
     * <p><b>A fourth column, added after the fact.</b> {@code agents} answers a
     * question the other three cannot: not "can a person at a terminal, a model
     * over MCP, or a person in a browser do this", but "can one of
     * <em>Plowshare's own agents</em> do this". Those are genuinely different
     * surfaces — {@code plowshare-server/src/main/resources/agents/*.md}'s {@code
     * tools:} lines are a fourth registry nothing above named, which is exactly
     * how {@code search} shipped on the MCP tool surface, the CLI and passed
     * every check this class runs, and reached no shipped agent: nothing
     * anywhere could say so, because nothing recorded what "reaches an agent"
     * even meant. This field is that record.
     *
     * @param what the capability in a person's words, for the message a failed
     *     check prints. Not a tool name, not a command and not a screen: those
     *     are the three spellings, and this is the thing they spell
     * @param agents the agents whose {@code tools:} list names a tool this
     *     capability offers — ground truth read from the agent definitions
     *     themselves, not asserted here. Empty for a capability no shipped agent
     *     reaches, which the great majority of these are
     * @param tools the MCP tools that offer it, or empty for a capability MCP
     *     does not have. More than one where the surfaces genuinely divide
     *     differently — a model choosing between two narrow tools and a person
     *     reading one command's output are not the same shape
     * @param commands the CLI commands that offer it, as the words a person
     *     types after {@code plowshare}, or empty for a capability the CLI does
     *     not have
     * @param screens the web console's views that offer it, spelled as the words
     *     in its nav — the names in {@code shell.ts}'s {@code VIEWS} — or empty
     *     for a capability the console does not have. <b>Repeats across
     *     entries by design</b>: one screen is a place where several capabilities
     *     are offered, which is what makes it unlike the other two lists
     * @param note why one of the two <em>mains</em> is empty. <b>Required
     *     exactly when one is</b>, and that is the whole of the original rule: an
     *     omission with a reason is compliance, and an omission without one is
     *     the drift this class exists to catch. Also carries the reason when
     *     <em>neither</em> main has it, which is what a console-only capability
     *     looks like from here
     * @param consoleNote why {@code screens} is empty. <b>Required exactly when
     *     it is</b>, checked below rather than in {@code ParityTest}, for the
     *     reason this class's header gives: the tool/command rule has a test
     *     that can fail and this one has nothing else that runs
     * @param agentNote why {@code agents} is empty. <b>Required exactly when it
     *     is</b>, enforced the same way and for the same reason as {@code
     *     consoleNote} — there is no fifth registry that can see this class's
     *     own omissions, so the check has to live where the value is built. Most
     *     entries carry one of two stock reasons rather than a bespoke one:
     *     unlike a {@code consoleNote}, which argues a specific decision about a
     *     specific screen, an empty {@code agents} list is — for everything this
     *     task did not itself decide — one of two open questions repeated, not a
     *     different argument each time. {@link #agentGap()} marks a real MCP
     *     tool no agent's {@code tools:} line names yet, a live policy question;
     *     {@link #agentGapNoTool()} marks a capability with no MCP tool at all,
     *     which no agent could reach regardless of policy. The two are written
     *     as distinct strings, not one shared constant, precisely so a reader —
     *     or a future test — can tell which kind of gap a given entry has
     *     without cross-referencing {@code implementation rationale} §17.5's prose, which
     *     can drift out of sync with the code the way §17.1 briefly did
     * @param frames the WebSocket frame types that answer this capability,
     *     spelled as the dotted discriminators a frame's {@code type} carries
     *     and as {@code ws.FrameTypes} names them. Empty for a capability the
     *     socket surface does not reach, which — now that the breadth plan has
     *     run — is four of the entries below and each of them says why
     * @param frameNote why {@code frames} is empty. <b>Required exactly when it
     *     is</b>, enforced in this record's own constructor for {@code
     *     consoleNote}'s and {@code agentNote}'s reason: nothing else runs. The
     *     stock reason is {@link #frameGap()}, "not yet", which was the honest
     *     answer for an endpoint the breadth plan had not reached — and the
     *     column exists so that "not yet" was an enumerated list rather than a
     *     feeling. <b>A decided absence is written out in full instead</b>, at
     *     the call site, because a permanent ruling and an unfinished migration
     *     are different facts about the same emptiness and a reader has to be
     *     able to tell which one they are looking at. Three rulings are on file
     *     and two of them land on entries here: binary upload stays on HTTP
     *     (twice, once per upload), and the operational routes stay curl-shaped
     *     (twice as well — {@code /v1/config}'s entry, and the provider
     *     registry's, which is the partial case below). The third — that auth
     *     gets no frames, because auth happens before the socket exists and
     *     mints the credential the socket opens with — has no entry to land on,
     *     which is itself worth knowing and is why this class's header writes it
     *     out
     *     <p><b>Not required to be empty for the note to be present.</b> A
     *     capability is not an endpoint: where one capability's endpoints split,
     *     some answered by the socket and one ruled to stay on HTTP, the entry
     *     carries both a frame list and a note saying what is still missing. Two
     *     entries do. The constructor refuses only the combination that cannot
     *     be true — {@link #frameGap()} beside a non-empty list, a line calling
     *     its own frame types a gap
     */
    public record Capability(
            String what, List<String> agents, List<String> tools, List<String> commands,
            List<String> screens, String note, String consoleNote, String agentNote,
            List<String> frames, String frameNote) {

        public Capability {
            agents = List.copyOf(agents);
            tools = List.copyOf(tools);
            commands = List.copyOf(commands);
            screens = List.copyOf(screens);
            frames = List.copyOf(frames);
            if (screens.isEmpty() && (consoleNote == null || consoleNote.isBlank())) {
                // Thrown from the constructor and therefore from ALL's
                // initialiser, which is the first thing either main touches. It
                // is not in ParityTest because that test would then be the only
                // thing holding a rule the console's own suite cannot see
                // either -- and because a rule enforced where the value is built
                // fails on whoever added the entry.
                throw new IllegalArgumentException(
                        "the capability '" + what + "' names no console screen and says nothing"
                                + " about why. A person at the web console cannot do this, and"
                                + " nothing distinguishes that decision from a screen nobody got"
                                + " round to. Add the view that offers it -- the word in the"
                                + " console's nav -- or a consoleNote saying why a browser tab"
                                + " does not get this.");
            }
            if (agents.isEmpty() && (agentNote == null || agentNote.isBlank())) {
                // Same enforcement, same reason, for the column this task
                // added: an agent gap that is declared is a decision (or, for
                // most entries here, an acknowledged open question); one that
                // is not declared is indistinguishable from an oversight, which
                // is the exact failure mode that let `search` ship reaching no
                // agent in the first place.
                throw new IllegalArgumentException(
                        "the capability '" + what + "' names no agent and says nothing about why."
                                + " No agent's tools: line in"
                                + " plowshare-server/src/main/resources/agents/*.md names a tool"
                                + " this capability offers, and nothing distinguishes that from a"
                                + " gap nobody noticed. Add the agents that reach it, or an"
                                + " agentNote saying why none does -- agentGap() is the stock"
                                + " reason when a tool exists and nobody has decided which agent"
                                + " should get it, same as it was for search; agentGapNoTool() is"
                                + " the stock reason when there is no tool at all for any agent to"
                                + " declare.");
            }
            if (frames.isEmpty() && (frameNote == null || frameNote.isBlank())) {
                // The third check in this constructor and the same argument a
                // third time, because the column it guards is the one whose
                // emptiness was expected: while the breadth plan ran, the socket
                // answered a handful of endpoints out of fifty-odd, so almost
                // every entry here was a gap -- and a gap nobody wrote down is
                // indistinguishable from a migration that quietly stalled. Spec
                // 3.7 is exactly this -- "an endpoint with no frame equivalent
                // shows as a declared gap" -- and it only holds if saying
                // nothing is impossible. Enforced here rather than in a test for
                // the reason the two above give: a rule enforced where the value
                // is built fails on whoever added the entry.
                throw new IllegalArgumentException(
                        "the capability '" + what + "' names no frame type and says nothing about"
                                + " why. A client speaking the socket protocol cannot do this, and"
                                + " nothing distinguishes that from an endpoint the migration has"
                                + " not reached. Add the dotted types that answer it -- the"
                                + " spellings in ws.FrameTypes -- or a frameNote saying why the"
                                + " socket does not get this: frameGap() is the stock reason for"
                                + " 'not yet', which no entry gives any more, and a decided"
                                + " absence gets its reasoning written out instead so a reader"
                                + " can tell a ruling from a backlog.");
            }
            if (!frames.isEmpty() && frameGap().equals(frameNote)) {
                // The fourth check, and the one that makes the third express the
                // truth rather than a simplification of it. A note BESIDE frames
                // is legitimate and two entries rely on it: `frames` is
                // per-capability and an endpoint is not, so a capability whose
                // endpoints split -- some answered by the socket, one ruled to
                // stay on HTTP -- has both a list and something left to say
                // about it. A "note XOR frames" rule would reject those two and
                // catch nothing. What cannot be true is this narrower thing: the
                // stock reason says in so many words that NO frame type answers
                // this, so beside the frame types that answer it, it is a
                // sentence the same line contradicts. It is also the reachable
                // mistake -- six tasks each gave frames to entries that carried
                // it, and forgetting to clear one would have left the register
                // reading as a gap that was already closed.
                throw new IllegalArgumentException(
                        "the capability '" + what + "' names frame types and calls them a gap at"
                                + " the same time. frameGap() says no frame type answers this,"
                                + " and " + frames + " answer it. Drop the note if these types"
                                + " are the whole capability, or replace it with what is still"
                                + " HTTP-only here -- that is the shape a partially framed"
                                + " capability takes, and it is the only reason to carry both.");
            }
        }
    }

    /**
     * The console views that offer a capability.
     *
     * <p><b>A method and not a bare {@code List.of}</b>, and the reason is the
     * only enforcement this list has: {@code parity.test.ts} in the console reads
     * this file and takes every string literal inside a call to this method, so
     * the marker has to be one shape that a regular expression can find without
     * parsing Java. Writing {@code List.of("memory")} in one of these entries
     * would be a screen the console's suite cannot see, which is the same defect
     * as not declaring it.
     */
    private static List<String> inConsole(String... screens) {
        return List.of(screens);
    }

    /**
     * The reason most entries below with a real MCP tool leave {@code agents}
     * empty: a tool exists, and no shipped agent's {@code tools:} line names
     * it — the exact shape {@code search} was in before this slice decided it
     * for {@code interlocutor}. This is a live policy question, not unbuilt
     * plumbing, which is why {@link #agentGapNoTool()} exists as a distinct
     * reason rather than one gap swallowing both meanings.
     *
     * <p>Not shaped like {@link #consoleGap} — that helper takes a bespoke
     * {@code consoleNote} argument at every call site, because each one argues a
     * different decision about a different screen. An empty {@code agents} list
     * is not that: for every capability this task did not itself decide to
     * grant, the reason is the identical open question — "which agents
     * <em>should</em> reach this, if any" — repeated, not re-argued. Writing a
     * fresh paragraph at each call site that needs one would read as that many
     * considered decisions where there was one audit and one deferral. So it is
     * a constant, reused, and the actual reasoning — including the full list
     * this reason covers — is written once, in {@code implementation rationale} §17.5.
     *
     * <p>A capability whose gap <em>is</em> an argued decision — not this one,
     * but conceivably a future entry — gets its own {@code agentNote} string
     * passed directly to the constructor instead, the way an unusual {@code
     * consoleNote} already bypasses {@link #consoleGap} by calling {@link
     * Capability}'s own constructor. This method is the default, not the only
     * door.
     */
    private static String agentGap() {
        return "undecided -- a tool exists and no agent declares it, see TODO §17.5";
    }

    /**
     * The reason the handful of tool-less entries below leave {@code agents}
     * empty, and a genuinely different sentence from {@link #agentGap()}'s.
     * That one is an open policy question about a tool an agent could already
     * name; this one has no MCP tool for any agent's {@code tools:} line to
     * name in the first place, so no agent could reach the capability today
     * regardless of what anybody decided. Structural, not deferred — the
     * distinction the review that added this method found missing when both
     * kinds of gap carried the same string.
     */
    private static String agentGapNoTool() {
        return "no MCP tool exists for an agent to declare, see TODO §17.5";
    }

    /**
     * The stock reason for leaving {@code frames} empty because the socket
     * surface has not reached this capability's endpoint <em>yet</em>: a
     * migration backlog and not a ruling.
     *
     * <p><b>Nothing below calls it, and that emptiness is this column's whole
     * result.</b> It was the answer on nearly every line while the breadth plan
     * ran; the plan finished, and each of the four entries that still name no
     * frame type now argues a decision in its own words instead. The method
     * stays for two reasons that are both about the next person rather than
     * about this file today. It is the <em>name</em> of the deferred answer, so
     * an endpoint added tomorrow with no frame handler has one obvious,
     * honest thing to write — and the check below refuses it in the one place
     * it would be a lie, which needs the exact sentence to compare against.
     *
     * <p>A stock constant on {@link #agentGap()}'s precedent and for its
     * argument: a deferral repeated is one decision, not one per call site, so
     * the reasoning is written once — in {@code
     * implementation rationale}, the
     * plan that closed these one controller at a time.
     *
     * <p><b>An entry whose absence is decided does not use this.</b> It passes
     * its own {@code frameNote} to {@link Capability}'s constructor, the way an
     * unusual {@code consoleNote} bypasses {@link #consoleGap}, and the reader's
     * test is whether the sentence would still be true after the breadth plan
     * finished. Every entry below that names no frame type is in that shape.
     *
     * <p><b>Not a factory method like {@link #inConsole}</b>, and the difference
     * is worth stating because that method exists for a reason this column does
     * not share. {@code inConsole} is a marker a regular expression in another
     * module has to find in this file's <em>source text</em>, because {@code
     * parity.test.ts} cannot import Java. Nothing outside Java reads the frame
     * column: its checker is {@code FrameRouterTest}, which is Java, on a class
     * path that has this class on it, and it calls {@link #frames()} rather than
     * parsing anything. So the frame lists below are plain {@code List.of}
     * calls. A marker method would be a shape with no reader.
     *
     * <p>Package-private rather than private so that {@code ParityTest} can hold
     * the two rules about it — that no entry carries it, and that the
     * constructor refuses it beside a frame list — against the sentence itself
     * rather than against a second copy of the sentence.
     */
    static String frameGap() {
        return "not yet -- HTTP only; no frame type answers this, see the breadth plan";
    }

    /**
     * Offered by all three, and named agents reach it — or, for most entries,
     * none do yet, which {@link #agentGap()} records.
     *
     * <p><b>There was a frames-less overload of this and of {@link #consoleGap}
     * and they are gone</b>, which is a small thing that says the same as the
     * list in this class's header. Each hard-coded {@link #frameGap()}, so
     * declaring a capability the socket did not answer was the shorter of the
     * two spellings — correct while the migration ran and every entry was in
     * that state, and wrong the moment it finished. Deferring is now the longer
     * spelling: pass {@code frameGap()} to {@link Capability}'s own constructor
     * and say so out loud. Nothing stops it, and {@code ParityTest} makes it
     * fail the build until the header's list is edited to match.
     *
     * @param frames the dotted types that answer it, from {@code ws.FrameTypes}
     */
    private static Capability everywhere(String what, List<String> agents, String tool,
            String command, List<String> screens, List<String> frames) {
        return new Capability(what, agents, List.of(tool), List.of(command), screens, null, null,
                agents.isEmpty() ? agentGap() : null, frames, null);
    }

    /**
     * Offered by both mains and not by the console, which says why. Agent reach
     * is separate: named agents reach it, or {@link #agentGap()} records that
     * none does yet.
     *
     * @param frames the dotted types that answer it
     * @param frameNote what is still missing on the socket even so, or {@code
     *     null} when the frames listed are the whole capability. Every call site
     *     passes {@code null} today — the two entries that really do carry a
     *     note beside their frames are both written out with {@link
     *     Capability}'s own constructor, because both need something else it
     *     does not take either. The parameter stays because the shape is real
     *     and the next partial capability may well be a console gap
     */
    private static Capability consoleGap(
            String what, List<String> agents, String tool, String command, String consoleNote,
            List<String> frames, String frameNote) {
        return new Capability(what, agents, List.of(tool), List.of(command), List.of(), null,
                consoleNote, agents.isEmpty() ? agentGap() : null, frames, frameNote);
    }

    /**
     * Everything a Plowshare client can do, in the order the surfaces present
     * it: the archive, then a run, then a conversation, then the corpus, then a
     * project, then this client's own machine.
     */
    public static final List<Capability> ALL = List.of(
            new Capability("retain sources, evidence and reports and manage their lifecycle",List.of("librarian"),List.of("information"),List.of("information"),inConsole("information"),
                    "CLI exposes owner management; MCP offers reads, acquisition and draft evidence/reports. Sharing, finalisation and migration require explicit human controls.",
                    null,null,
                    io.aeyer.plowshare.protocol.frames.InformationOperations.frames(),null),

            everywhere("navigate memory digests", List.of("interlocutor", "farnsworth", "daedalus"),
                    "memory_navigate", "memory navigate", inConsole("memory"),
                    List.of("memory.navigate")),
            everywhere("build memory digests", List.of(),
                    "memory_digest", "memory digest", inConsole("memory"),
                    List.of("memory.digest")),
            everywhere("survey what is remembered", List.of(),
                    "memory_index", "memory index", inConsole("memory"),
                    List.of("memory.index")),
            everywhere("read memories in full",
                    List.of("code_reviewer", "interlocutor", "promotion_judge"),
                    "memory_read", "memory read", inConsole("memory"),
                    List.of("memory.read")),
            everywhere("ask the archive a question", List.of("code_reviewer", "interlocutor"),
                    "memory_recall", "memory recall", inConsole("memory"),
                    List.of("memory.recall")),
            consoleGap("record something worth remembering", List.of(), "memory_write", "memory write",
                    """
                    The console reads the archive and does not write to it, and \
                    memory.ts says why in its own words: the index is what a \
                    person surveys and a memory is what a run forms. A textarea \
                    in a browser would be a fourth author of claims that go into \
                    every prompt, with no run behind it to say what it was doing \
                    when it learned this. Not refused on principle -- the \
                    endpoint is there and a screen could take it -- but it is a \
                    decision about who writes to the archive rather than a \
                    screen nobody built.""",
                    List.of("memory.write"), null),
            consoleGap("review one project's memories for promotion", List.of(),
                    "memory_curate", "memory curate",
                    """
                    A curate pass is a job per candidate against a shared \
                    allowance, and the console can already watch it: the jobs \
                    screen lists the run and the proposals screen is where its \
                    output is settled. What is missing is the door that starts \
                    one. It is the smallest of these gaps and the most ordinary \
                    -- a button on the proposals screen -- and it is a gap \
                    rather than a decision.""",
                    List.of("agent.curate"), null),
            everywhere("see what is waiting on a promotion decision", List.of(),
                    "memory_proposals", "memory proposals", inConsole("proposals"),
                    List.of("proposal.list")),
            everywhere("settle one promotion", List.of(),
                    "memory_resolve", "memory resolve", inConsole("proposals"),
                    List.of("proposal.resolve")),
            new Capability(
                    "re-open promotions that were settled",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("proposals"),
                    """
                    POST /v1/proposals/reconsider is reachable from the console \
                    and from curl, and from neither main. It answers with both \
                    lists -- reopened and refused -- because a re-open can be \
                    refused when the row's waiting place has been taken since, \
                    and a count alone cannot tell a tier with nothing to re-open \
                    from one where every row was blocked. Rendering both is the \
                    reason it landed on a screen first. Neither front end has a \
                    verb for it, which is the drift this register could not \
                    record until it had a third column.""",
                    null,
                    agentGapNoTool(),
                    List.of("proposal.reconsider"),
                    null),
            everywhere("start one run", List.of("interlocutor"), "agent_run", "run",
                    inConsole("chat"), List.of("agent.run")),
            new Capability(
                    "see what this server can run",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("chat"),
                    """
                    GET /v1/agents has no tool and no verb, and this entry did \
                    not exist until a frame type needed declaring against it -- \
                    the same way the conversation lifecycle entry below came to \
                    exist, which is this column doing its job from an \
                    unexpected direction. The console's REPL reads it to fill \
                    its agent picker; neither main has a way to ask. For MCP \
                    that is closer to a decision than a gap -- a model is \
                    handed the names it may run in agent_run's own refusal, \
                    which is the correction it can act on -- and for the CLI it \
                    is an ordinary gap nobody has written.""",
                    null,
                    agentGapNoTool(),
                    List.of("agent.list"),
                    null),
            new Capability(
                    "write an agent definition",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    POST /v1/agents is an operator surface and reaches no front \
                    end at all -- no tool, no verb, no screen -- which makes it \
                    the one entry here that is empty on all three and is still \
                    not a gap. DefinitionWriter's own javadoc is explicit that \
                    nothing in its package hands an agent a reference to it, \
                    and the reason generalises past MCP: a door that writes a \
                    definition is a door that changes what every later run is \
                    allowed to do, which is infrastructure an operator sets and \
                    not something a running agent should reach. It answers over \
                    curl and it answers over the socket, because a socket-only \
                    operator tool is exactly the reader the frame surface \
                    exists for.""",
                    """
                    Same decision as the one above, applied to the browser. A \
                    screen that writes an agent definition is a textarea that \
                    changes what every later run in a project is allowed to do, \
                    with no review step and no undo -- and the console's own \
                    rule, stated on the memory screen, is that the archive and \
                    what runs against it are what a run forms rather than what \
                    a tab types. A person who should be writing definitions has \
                    the data directory in front of them.""",
                    agentGapNoTool(),
                    List.of("agent.define"),
                    null),
            new Capability(
                    "see every job this process is holding",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("jobs"),
                    """
                    GET /v1/jobs is the console's live job view and has neither \
                    a tool nor a verb. It is what a client reconciles against, \
                    because the event stream drops on overflow by design and a \
                    client that treated pushes as a log would render a job \
                    whose events were dropped as a job that did nothing. A CLI \
                    verb for it is a plain gap; a tool is closer to a decision, \
                    since agent_poll answers about the one job a model started \
                    and enumerating every job this process holds is a different \
                    question than the one a run has any business asking.""",
                    null,
                    agentGapNoTool(),
                    List.of("job.list"),
                    null),
            new Capability(
                    "how a run is going, and how it ended",
                    List.of(),
                    List.of("agent_poll", "agent_result"),
                    List.of("job status"),
                    inConsole("jobs"),
                    """
                    Two tools and one command, and the fold is deliberate. A model \
                    asking "has it finished" wants an answer it does not have to \
                    read a result out of, and a run that has not finished has no \
                    result — so on that side the two are separate calls with \
                    separate costs. A terminal prints what it knows the moment it \
                    is asked: a job that is running prints that it is running, and \
                    a finished one prints how it ended. Two verbs differing only in \
                    how much of one HTTP call they show would be a choice a person \
                    has to make before they have the information to make it. The \
                    console folds them further still: a screen shows both states \
                    at once because it is not answering a question, it is being \
                    looked at.""",
                    null,
                    agentGap(),
                    List.of("job.status"),
                    null),
            everywhere("stop a run", List.of(), "agent_cancel", "job cancel", inConsole("jobs"),
                    List.of("job.cancel")),
            new Capability(
                    "watch a model produce its answer",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    THE SECOND FRAME WITH NO ENDPOINT BEHIND IT, AND UNLIKE \
                    THE FIRST THERE COULD NOT BE ONE. conversation.latest \
                    answers no endpoint because nobody wrote that endpoint; \
                    job.stream answers none because a subscription is not a \
                    shape request-response can express. There is no GET that \
                    means "and keep telling me", and a poll is the thing \
                    streaming exists to replace. So this asymmetry is not a \
                    gap in HTTP waiting to be filled -- it is the socket doing \
                    the one thing it is for.""",
                    """
                    NOT OFFERED, AND THAT IS A DECISION ABOUT A DIFFERENT \
                    AUDIENCE RATHER THAN AN OVERSIGHT. Deltas arrive in their \
                    hundreds per model call -- 6 571 characters of reasoning \
                    against 1 965 of answer on one measured call -- where this \
                    console's handler expects four events a turn. Whether a \
                    browser should show a model thinking has not been asked. \
                    The console is untouched and keeps working, because a \
                    frame it has never heard of carries no kind and falls out \
                    of its if/else-if without being mentioned.""",
                    agentGapNoTool(),
                    List.of("job.stream"),
                    null),
            new Capability(
                    "follow a conversation's log as it grows",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    A SUBSCRIPTION, AND job.stream'S ASYMMETRY FOR job.stream'S \
                    REASON. conversation.follow asks the socket to say when the log \
                    of the conversation on screen grows -- conversation.appended, \
                    naming the conversation and its highest ordinal and carrying no \
                    content -- and there is no GET that means "and keep telling me". \
                    What was added is read with conversation.trajectory, after the \
                    last ordinal already shown.""",
                    """
                    NOT OFFERED. The console reads a conversation when a person opens \
                    it and keeps no live chat of one; a turn the harness starts there \
                    is seen on the next read.""",
                    agentGapNoTool(),
                    List.of("conversation.follow"),
                    null),
            new Capability(
                    "move a running job's ceilings",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("jobs"),
                    """
                    POST /v1/jobs/{id}/limits has no tool and no verb, and the \
                    absence on the MCP side is a decision rather than a gap: an \
                    agent that can raise its own turn cap or its own budget is \
                    an agent whose ceilings are not bounds, which is the same \
                    rule this register already applies to the live \
                    configuration one entry down. The CLI's absence is an \
                    ordinary gap. The console's jobs screen has it, because \
                    "this run is nearly out -- give it twenty more" is a thing \
                    a person says about a run they are watching.""",
                    null,
                    agentGapNoTool(),
                    List.of("job.limits"),
                    null),

            everywhere("see what conversations are open", List.of(),
                    "conversation_list", "conversation list", inConsole("chat"),
                    List.of("conversation.list")),
            consoleGap("find where something was said", List.of("interlocutor", "farnsworth", "daedalus"),
                    "conversation_search", "conversation search",
                    """
                    THE GAP THIS COLUMN WAS ADDED FOR. GET /v1/entries/search \
                    landed on the MCP surface and on the CLI in the same merge, \
                    from worktrees that could not see each other, and the console \
                    got neither -- so log search shipped on two surfaces of three \
                    with nothing in the build recording it. It is a gap and not a \
                    decision: the endpoint is tier-scoped, pages the way the \
                    trajectory screen already pages, and answers hits carrying \
                    the conversation each was said in. It is also the console's \
                    ONLY door onto a delegated child's id, since GET \
                    /v1/conversations lists roots of origin 'turn' and nothing \
                    enumerates a tree -- so the chat view's sidebar has to say \
                    the tree is unreachable, and draw each conversation's \
                    children slot empty, for as long as this stays empty.""",
                    List.of("conversation.search"), null),
            everywhere("read what the model is shown in a conversation", List.of(),
                    "conversation_chat", "conversation chat", inConsole("chat"),
                    List.of("conversation.chat")),
            everywhere("read everything a conversation recorded", List.of(),
                    "conversation_trajectory", "conversation trajectory",
                    inConsole("chat"),
                    // Two types, because this capability is two reads a console
                    // shows on one screen: the log itself, and -- per entry --
                    // what the turn that wrote it was actually shown.
                    // `trajectory.ts` draws both, and neither has a front end
                    // of its own.
                    List.of("conversation.trajectory", "conversation.projection")),
            everywhere("price a conversation's prompt", List.of(),
                    "conversation_context", "conversation context", inConsole("chat"),
                    List.of("conversation.context")),
            new Capability(
                    "speak into a conversation",
                    List.of(),
                    List.of(),
                    List.of("talk"),
                    inConsole("chat"),
                    """
                    MCP reads conversations and cannot say anything into one. \
                    AgentTools.run passes no conversation, deliberately: "a tool \
                    that could put a turn into somebody's conversation would be \
                    spending an allowance a person set for their own utterances, on \
                    one they did not make." That argument assumes the caller is not \
                    a person, and it dissolves against a harness somebody is \
                    driving — which is the Hermes case the parity design is written \
                    about. It was undecidable while nothing identified the caller \
                    and is decidable now that client_root_project_here exists, and \
                    it is deliberately NOT decided here: this slice gives the CLI \
                    the tool surface, and weakening that exception in passing is \
                    exactly what its own design note says not to do. See §4.3 of \
                    implementation rationale Note that the console has always had this and \
                    was never the case the exception was written about: the REPL \
                    is a person typing, which is the one thing nobody doubted.""",
                    null,
                    agentGapNoTool(),
                    List.of("conversation.open", "conversation.turns",
                            "conversation.compactions", "conversation.resume",
                            "conversation.latest"),
                    """
                    ALL OF IT EXCEPT THE UTTERANCE ITSELF, AND THAT ONE IS \
                    ANOTHER CONTROLLER'S -- AND IT IS NOW DECLARED THERE. The \
                    first four above are the whole of ConversationController's \
                    side of having a conversation: open one, read its turns \
                    back, read the seams it was folded at, and continue a run \
                    that stopped. A turn is actually spoken through POST \
                    /v1/agents/{name}/runs, which lives on AgentController and \
                    carries agent.run under "start one run" -- so the absence \
                    this note recorded is closed, and what is left here is the \
                    pointer to where the other half is declared. A socket-only \
                    client can hold the conversation AND put a turn into it, \
                    across two entries in this register rather than one. \
                    THE FIFTH, conversation.latest, IS THE FIRST TYPE IN THIS \
                    REGISTER WITH NO ENDPOINT BEHIND IT, and the asymmetry runs \
                    the other way for once: it answers which conversation an \
                    agent is still having in a tier, which is what talking to a \
                    bot continues. GET /v1/conversations answers a tier oldest \
                    first and unlimited -- the order the console's opening \
                    screen is built on -- so reading this out of that listing \
                    would mean reversing a shared order or handing a client a \
                    whole tier to take the last row of. The console needs \
                    neither: it opens a conversation from a list a person is \
                    looking at, and a terminal has no list to look at. \
                    FrameTypes.CONVERSATION_LATEST carries the argument; this \
                    is the register saying it out loud rather than a later \
                    parity sweep finding it."""),
            new Capability(
                    "put a conversation out of the way, or mark it to go",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("chat"),
                    """
                    PUT /v1/conversations/{id}/lifecycle has no tool and no \
                    verb, and this entry did not exist until a frame type \
                    needed declaring against it -- which is the column doing \
                    its job from an unexpected direction. The console's chat \
                    screen archives a conversation, puts it back, and marks one \
                    to be ejected; neither main has a way to. That is a gap \
                    rather than a decision for the CLI, whose REPL holds one \
                    conversation at a time and has nothing to tidy; it is \
                    closer to a decision for MCP, where a tool that could \
                    archive somebody's conversation is the same question \
                    memory_invalidate raises one resource over. Neither has \
                    been asked.""",
                    null,
                    agentGapNoTool(),
                    List.of("conversation.lifecycle"),
                    null),
            new Capability(
                    "put a memory beyond use, keeping it readable",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("memory"),
                    """
                    POST /v1/memories/{id}/invalidate has no tool and no verb. It \
                    is not deletion -- the memory stays readable and carries the \
                    reason it stopped being true, which is the whole point of \
                    keeping it -- and MemoryTools names memory_invalidate only as \
                    the Excalibur tool it took a default from, never registering \
                    one. So the reason a claim was retired is recorded on one \
                    surface of three. Which front end should get it is a real \
                    question rather than an oversight: an agent invalidating what \
                    another agent wrote is a different act from a person doing \
                    it, and neither main has been asked to decide.""",
                    null,
                    agentGapNoTool(),
                    List.of("memory.invalidate"),
                    null),
            new Capability(
                    "repair memories that have no vector",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    POST /v1/memories/reembed has no tool and no verb, and this \
                    entry did not exist until a frame type needed declaring \
                    against it -- the third time this column has found a \
                    capability nobody had written down. It is the repair for \
                    what `unsearchable` makes visible on the index and on a \
                    recall: a memory written while the embedding endpoint was \
                    down is complete except for its vector, and nothing else \
                    asks for one. For MCP that absence is closer to a decision \
                    than a gap -- one model call per unembedded memory is not a \
                    bill a recall should be able to run up by accident, which \
                    MemoryController's own javadoc argues -- and for the CLI it \
                    is an ordinary gap nobody has written.""",
                    """
                    Nothing renders it. The memory screen shows the \
                    `unsearchable` count that makes the fault visible, which is \
                    the odd half of this: an operator can see the archive needs \
                    repairing there and has to leave for a terminal to repair \
                    it. A button beside that count is the obvious screen and \
                    nobody has built it.""",
                    agentGapNoTool(),
                    List.of("memory.reembed"),
                    null),

            everywhere("search the document corpus", List.of("interlocutor", "librarian"),
                    "document_search", "document search", inConsole("documents"),
                    List.of("document.search")),
            consoleGap("read a passage with the whole paper around it", List.of(),
                    "document_retrieve", "document retrieve",
                    """
                    DECLARED WITH NO SCREEN, and the reason is that the console \
                    HAS this shape already and would have to choose between two \
                    renderings of one question. Its `documents` screen renders \
                    search hits, marks the paragraph id as the citation and the \
                    chunk id as explicitly not one; a retrieve is the same panel \
                    with four levels of summary stacked above every hit, and a \
                    reader looking at both would have to work out which of two \
                    lists of passages they were reading. That is a design \
                    question about one screen rather than a missing button, and \
                    the answer is more likely to be a mode on the existing panel \
                    than a second one. The two surfaces it does have are the two \
                    the parity rule is about.""",
                    List.of("document.retrieve"), null),
            consoleGap("see what documents the corpus holds", List.of("interlocutor", "librarian"),
                    "document_list", "document list",
                    """
                    DECLARED WITH NO SCREEN, and this is the smallest and most \
                    ordinary gap in this file. The `documents` screen shows an \
                    ingest form and a search panel and has never shown the \
                    corpus; there is no argument against a table of rows, only \
                    that nobody has written one. It is a gap rather than a \
                    decision, and it is the one entry here whose console note \
                    should stop being true soonest.

                    Recorded with it, because it is what makes the omission \
                    worth a line: until this landed NO front end could enumerate \
                    the corpus. `document ask` takes an id, `document \
                    citations` names the documents answers have drawn on, and \
                    `document search` names the ones a question happens to \
                    reach; none of the three answers what is here. A person who \
                    had uploaded a paper and forgotten its name had nowhere to \
                    look.

                    A socket-only client gets it from the first line, which is \
                    what document.list is for: every other type in that area \
                    takes an id or answers with passages, so without this one a \
                    client speaking only frames would have had the same nowhere \
                    to look.""",
                    List.of("document.list"), null),
            consoleGap("read one document's structure", List.of(),
                    "document_outline", "document show",
                    """
                    DECLARED WITH NO SCREEN, and it goes where the listing above \
                    goes -- an outline is what a row on that table expands into, \
                    so building it before the table would be a screen with no \
                    way into it. What it renders is titles and summaries and \
                    deliberately no passage text, which is the one thing that \
                    would make it a third reader of uploaded prose in the \
                    console; that is an argument for it being EASY rather than \
                    for it waiting, and the waiting is the listing's.""",
                    List.of("document.detail"), null),
            new Capability(
                    "read one chunk back by its id",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    NEITHER MAIN HAS THIS AND NEITHER SHOULD, which is a \
                    different sentence from the three above it and from the \
                    ingest entry below. GET /v1/documents/chunks/{id} exists, is \
                    tested, and is reachable by curl and by nothing else. Three \
                    reasons, and the first two are Anchor's own.

                    ANCHOR DOES NOT CALL IT EITHER. The route is in Anchor's \
                    SPEC, it is implemented, and there is no caller: not in its \
                    client SDK, not in its shell, not in its docs. A surface \
                    ported faithfully includes the fact that nothing used it.

                    IT IS THE READ ANCHOR'S OWN DESIGN SAYS IS UNNECESSARY. The \
                    retrieve beside it promises "one row per chunk with no \
                    follow-up reads", and this IS the follow-up read -- it \
                    answers with the same record, which is pinned by a test. A \
                    tool for it would be a second door onto an answer the caller \
                    was already handed.

                    A CHUNK ID IS THE ONE ID HERE THAT DOES NOT LAST. V18's \
                    identity rule preserves a PARAGRAPH's id across a re-ingest; \
                    DocumentStore.Passage says in as many words that a chunk id \
                    is "deliberately not a citation" because a chunk is an \
                    artefact of the chunker. So a tool keyed on one would invite \
                    a model to write down the id this corpus is least willing to \
                    honour, and every surface here already tells it to write \
                    down the paragraph instead.

                    What the route is for is a caller outside these two mains \
                    that holds a chunk id and comes back -- the machine-consumer \
                    case Anchor's SPEC section 1.1 describes -- and that caller \
                    is speaking HTTP by construction. If a reason to spend a \
                    tool on it appears, this note is what has to be answered.""",
                    """
                    The console has no more claim on it than the two mains do, \
                    and one fewer reason: a chunk id is not something a person \
                    at a browser tab is ever holding.""",
                    agentGapNoTool(),
                    List.of("document.chunk"),
                    null),
            consoleGap("find which documents are about a subject", List.of(),
                    "document_rank", "document rank",
                    """
                    DECLARED WITH NO SCREEN, and it goes with the listing and the \
                    outline above rather than with the search panel: it is an \
                    ordering of the corpus table nobody has built yet, not a \
                    second search box. Building it before that table would be a \
                    ranking of rows with nowhere to render them.

                    Recorded here because it is what V27 was written for. The \
                    column it reads holds one vector per document, and this is \
                    the read that answers "which paper should I be reading" \
                    without fanning out over every passage in the corpus -- \
                    which is the question a caller has to answer before \
                    document_ask, and could previously only guess at from a \
                    search hit.""",
                    List.of("document.rank"), null),
            new Capability(
                    "guess what one document says about a claim, with no model call",
                    List.of(),
                    List.of(),
                    List.of("document stance"),
                    List.of(),
                    """
                    NO MCP TOOL, AND THIS ONE IS A DECISION RATHER THAN A GAP. \
                    The number is two cosines subtracted: the claim and the \
                    string "not " + the claim, both compared against a \
                    one-sentence summary a model wrote about a paper nothing has \
                    read. Anchor's own type says twice that it is not a \
                    substitute for reading the paper, and nothing anywhere -- \
                    here or in Anchor -- checks that an embedding model puts a \
                    negation anywhere useful in cosine space.

                    THE MODEL-FACING SURFACE ALREADY ANSWERS THIS QUESTION \
                    PROPERLY. document_ask reads the document, quotes the words \
                    each claim rests on, and reports a quotation it could not \
                    find rather than dropping it. Putting a cheap unvalidated \
                    number beside it, with no rule for choosing between them, is \
                    a live change to what agents reach for -- and section 3.3 \
                    and stage 8 have both already refused smaller changes to a \
                    model-visible surface than this one, on the ground that no \
                    test in this suite can evaluate them.

                    THE CLI HAS IT BECAUSE A PERSON IS A DIFFERENT READER. The \
                    terminal prints the two numbers with the sentence that says \
                    what they were made of and which one to read first, and a \
                    person can hold a caveat beside a figure. That is not a \
                    claim that a model cannot; it is that nobody here can \
                    measure whether it does.

                    WHAT WOULD CHANGE IT: Anchor designed this as a pre-filter \
                    for corpora too large to deliberate on, and that scale would \
                    make the tool worth the risk. This corpus is papers somebody \
                    uploaded, document_rank narrows it, and document_ask answers \
                    it. The route is there and speaks HTTP for the machine \
                    consumer Anchor's SPEC section 1.1 describes.""",
                    """
                    The console has neither, and for the reason above plus one \
                    of its own: the `documents` screen has no per-document view \
                    to put it on, which is the same table the three entries \
                    above are waiting for.""",
                    agentGapNoTool(),
                    List.of("document.stance"),
                    null),
            consoleGap("ask one document a question and deliberate on the answer",
                    List.of("close_reader"), "document_ask", "document ask",
                    """
                    DECLARED WITH NO SCREEN, AND THE REASON IS THE ANSWER RATHER \
                    THAN THE BUTTON. Starting one is trivial on the `documents` \
                    screen -- it is a text field beside a row that is already \
                    there -- and the `jobs` screen already watches a running job \
                    and reads how it ended, which is how a curator pass is \
                    followed today. What has no shape yet is READING the answer: \
                    a deliberation's outcome is model prose over uploaded text, \
                    with a grounding block under it that quotes paragraphs \
                    verbatim and a failed-attribution block that quotes words the \
                    document does NOT contain. Rendering that is a third reader of \
                    uploaded text in the console, and the one where getting the \
                    quoting wrong is worst: the whole point of the failed block is \
                    that a reader can tell a checked quotation from an unchecked \
                    one, and a screen that ran them together would destroy exactly \
                    the signal this capability exists to produce.

                    So it waits behind the citations panel above, which is the \
                    same problem one size smaller and has to be solved first. The \
                    two surfaces it does have are the two the parity rule is \
                    about, so nothing here is reachable only through Plowshare's \
                    own front end.""",
                    List.of("document.ask"), null),
            consoleGap("read what answers took from the corpus", List.of(),
                    "document_citations", "document citations",
                    """
                    DECLARED WITH NO SCREEN, DELIBERATELY, and this entry is what \
                    stops that being a fourth invisible hole beside the three \
                    above. The console has a `documents` screen and this could \
                    have gone on it; what it would have been is a second reader \
                    of uploaded document text, with its own quoting to keep \
                    right, added in the same slice that first wrote the rows it \
                    would read -- and a citation listing is worth having a shape \
                    before it is worth having a panel. The two surfaces it does \
                    have are the two the parity rule is about, so nothing here is \
                    reachable only through Plowshare's own front end; what is \
                    missing is the one column that was added last and the one \
                    this register can see. The screen is the obvious next thing \
                    and it is an addition to `documents`, not a view of its \
                    own.""",
                    List.of("document.citations"), null),
            new Capability(
                    "put a document into the corpus",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("documents"),
                    """
                    Neither main ingests, and one of the two says why it never \
                    will: DocumentTools declines a document_ingest because "a \
                    document arrives as bytes somebody read off a disk", and \
                    Anchor's other ingest route -- handing the SERVER a path to \
                    read -- is the leash bypass the design declined. The CLI has \
                    no such argument and no verb either, which is the omission \
                    this entry records. Meanwhile the browser is the one place \
                    where a file picker is native, so the console is now the only \
                    surface that can put a document in at all. A capability whose \
                    sole holder is a front end nothing declared was, until this \
                    column existed, not visible from anywhere.""",
                    null,
                    agentGapNoTool(),
                    List.of(),
                    """
                    DECIDED, NOT DEFERRED -- and the distinction is the reason \
                    this column carries a note rather than a flag. POST \
                    /v1/documents is a multipart upload of a PDF somebody read \
                    off a disk, and it stays on HTTP: a text frame could carry \
                    those bytes as base64, but there is no precedent on this \
                    server for it -- FileChannelHandler is a TextWebSocketHandler \
                    that has only ever exchanged JSON -- and the event channel's \
                    write queue is bounded and sized for small frames. Two binary \
                    endpoints, this one and POST /v1/images, do not justify \
                    inventing a binary frame shape in the middle of a migration. \
                    The cost is stated: a socket-only client cannot upload and \
                    keeps one HTTP call for it, which is visible here, declared, \
                    and reversible the day a binary frame shape is worth having."""),

            new Capability(
                    "put a picture where a run can see it",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    NEITHER MAIN HAS A VERB FOR THIS AND BOTH REACH IT ANYWAY, \
                    which is a shape no other entry here has. POST /v1/images is \
                    the only thing in this server that mints an image UID, and \
                    what calls it is the file channel: SessionClient.uploads \
                    hands the bytes over when a run reads a picture out of the \
                    rooted workspace, under whichever project that session \
                    roots. So the capability is offered by both mains, by the \
                    machinery rather than by a command, and neither a tool nor a \
                    verb would add anything -- a person who wants a picture in a \
                    run puts it in the workspace. THERE IS NO TOOL ON PURPOSE: \
                    ImageController's own javadoc declines an image_store, \
                    because an agent that could create an image would need bytes \
                    to create it from, which is the byte-returning file tool the \
                    design rejected. Naming a UID needs none.""",
                    """
                    No screen, and the documents screen is the near miss worth \
                    naming: a browser is the one place a file picker is native, \
                    and the console uses it to put PDFs in the corpus. An image \
                    is not corpus -- nothing reads it back except the code that \
                    attaches it to a model call, and there is deliberately no \
                    GET, no listing and no gallery anywhere for one. A tab that \
                    uploaded images would be a producer with no consumer it \
                    could show.""",
                    """
                    No agent reaches this and none should. The producer is a \
                    person or a job and the consumer is an agent that is HANDED \
                    a UID -- that asymmetry is the design, not a gap in it, and \
                    it is the same rule that keeps project_lend off the tool \
                    surface: the agent names a thing, the server decides what \
                    that means.""",
                    List.of(),
                    """
                    DECIDED, NOT DEFERRED, and it is the second half of the \
                    ruling POST /v1/documents carries above. This is a multipart \
                    upload of raw bytes whose format is sniffed from the bytes \
                    themselves, and it stays on HTTP: a text frame could carry \
                    them as base64, but there is no precedent for that on this \
                    server -- FileChannelHandler is a TextWebSocketHandler that \
                    has only ever exchanged JSON -- and the event channel's \
                    write queue is bounded and sized for small frames, which a \
                    five-mebibyte picture is not. Two binary endpoints do not \
                    justify inventing a binary frame shape in the middle of a \
                    migration. The cost is stated and is smaller here than for \
                    documents: the clients that upload images are the ones \
                    already holding an HTTP session for the file channel."""),

            consoleGap("read a web page from outside this project, a window at a time",
                    List.of("interlocutor"), "fetch", "fetch",
                    """
                    DECLARED WITH NO SCREEN, on search's own entry below and for the \
                    identical reason: the caller reads content this server did not \
                    generate and does not archive, arriving over the open web rather \
                    than out of this deployment's own records. A `fetch` screen would \
                    face the same open question a `search` screen would -- whether a \
                    browser tab should render arbitrary fetched text at all -- and \
                    nobody has answered it for either. It is not the same gap as \
                    `document_retrieve` above, which the console declines for a design \
                    reason already decided; this is search's own undecided question, \
                    asked a second time because a second capability reaches the same \
                    kind of content the same way.""",
                    List.of("web.fetch"), null),

            consoleGap("search for information from outside this project", List.of("interlocutor"),
                    "search", "search",
                    """
                    DECLARED WITH NO SCREEN, AND DELIBERATELY SO RATHER THAN A GAP \
                    THIS COLUMN SHOULD CLOSE NEXT. Search's whole design is that \
                    the caller never learns which provider answered -- the design \
                    spec's own words, "no provider name reaches the model, ever" \
                    -- and a browser tab is a person's own eyes on the page, not a \
                    model reading a result field. A `search` screen would be the \
                    first console view answering a question with content this \
                    server did not generate and does not archive, which is a \
                    different kind of screen from every other one here: `memory` \
                    and `proposals` show this server's own records, and \
                    `documents` shows a paper somebody in this deployment chose to \
                    upload. There is no argument yet for whether a browser tab \
                    should get a search box at all, only that nobody has made \
                    one, and this note exists so that absence reads as unmade \
                    rather than unnoticed.""",
                    List.of("web.search"), null),

            new Capability(
                    "manage which search providers this server will ask",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    Operator work, and it reaches neither main. \
                    bin/plowshare-searxng is the whole of its client today: it \
                    registers a provider, reads the ladder out of /v1/config and \
                    appends to it. A tool would be the worst version of this -- \
                    whoever can add a provider can read every question this \
                    server is ever asked, including the ones interlocutor asks \
                    on a person's behalf -- and a CLI verb is an ordinary gap \
                    nobody has written, not a decision.""",
                    """
                    No screen, for the reason the config screen is the exception \
                    rather than the rule: a provider registration is a thing an \
                    operator does once per deployment from the box the server \
                    runs on, and the console has nowhere to put a probe that \
                    dials an arbitrary URL and reports what came back.""",
                    agentGapNoTool(),
                    List.of("provider.list", "provider.deregister"),
                    """
                    TWO OF THE THREE, AND THE THIRD IS DECIDED RATHER THAN \
                    DEFERRED. Listing and deregistering are ordinary reads and \
                    writes over a small table and they answer over the socket. \
                    POST /v1/search/providers does not, and will not: it is \
                    operational in the sense 4.1 of the socket design means -- \
                    the curl-shaped target of bin/plowshare-searxng, run by \
                    somebody holding a shell on the box -- and it is the one \
                    route that makes this server open a connection to a URL the \
                    caller supplied and report what came back, which is a probe \
                    for anything this process can route to. HTTP narrows to \
                    health, auth, and what ops genuinely needs; this is what \
                    that sentence is about. The cost if that is wrong is one \
                    call a socket-only operator tool cannot make, which is \
                    visible here and reversible."""),

            consoleGap("name a project's workspace", List.of(), "project_define", "project define",
                    """
                    The console's projects screen lists projects and moves a \
                    workspace; it does not create one. Defining names a project \
                    for the first time and a browser cannot check that the \
                    directory it is naming exists on the machine that will read \
                    it -- the same asymmetry client_root_project_here exists \
                    for. A screen for it would be honest only if it said that \
                    the path is taken on trust.""",
                    List.of("project.define"),
                    null),
            new Capability(
                    "see every project's leash",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("projects"),
                    """
                    GET /v1/projects has no tool and no verb, and this entry \
                    did not exist until a frame type needed declaring against \
                    it -- the third time this column has turned up a capability \
                    nothing else could see, after GET /v1/agents and the \
                    conversation lifecycle. The console's projects screen is \
                    what reads it. For MCP it is closer to a decision than a \
                    gap and ProjectController argues it at length: the answer \
                    is every project's workspace and every path fenced off \
                    around it, which is a map of this server's disk and of \
                    where its secrets are not, and an agent holding it would \
                    learn in one call what the six project verbs are withheld \
                    to stop it learning at all. For the CLI it is an ordinary \
                    gap nobody has written.""",
                    null,
                    agentGapNoTool(),
                    List.of("project.list"),
                    null),
            everywhere("point a project at a different directory", List.of(),
                    "project_workspace_set", "project workspace", inConsole("projects"),
                    List.of("project.workspace")),
            consoleGap("lend a project a further directory", List.of(), "project_lend", "project lend",
                    """
                    The console's projects screen renders `lent` -- it has to, or \
                    it would show a shorter leash than the one enforced -- and it \
                    offers no verb that changes it. The reason is the same \
                    asymmetry project_define's entry records and no stronger: a \
                    browser cannot check that the directory it is naming exists \
                    on the machine that will read it, and lending is the one \
                    project verb whose whole product is a directory. Unlike the \
                    exclusions, `lent` arrives complete, so a form posting it \
                    back would write nothing the server did not send -- this is \
                    a gap that could be closed honestly, and is open only \
                    because that screen's one verb is deliberate.""",
                    List.of("project.lend"), null),
            new Capability(
                    "sync a project's files with its machine as a union",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "Union sync is a terminal-client verb: it runs git on the machine that"
                            + " holds the files, which the console and the MCP client cannot.",
                    "Union sync is a terminal-client verb: it runs git on the machine that"
                            + " holds the files, which the console and the MCP client cannot.",
                    agentGapNoTool(),
                    List.of("union.status", "union.enable", "union.begin", "union.ready",
                            "union.abort", "union.disable", "union.hidden", "union.conflict.open",
                            "union.conflict.list", "union.conflict.resolve"),
                    null),
            consoleGap("stop lending a project a directory", List.of(), "project_unlend",
                    "project unlend",
                    """
                    Withheld from the console beside its other half, and it \
                    would be strange on its own: a screen that could take a lent \
                    directory back but not lend one would let a person undo \
                    something they had no way to do there.""",
                    List.of("project.unlend"), null),
            new Capability(
                    "rename a project, and its whole archive with it",
                    List.of(),
                    List.of("project_move"),
                    List.of("project rename"),
                    List.of(),
                    """
                    The same capability under two words, and each is right for its \
                    reader. On the tool side the family is project_define, \
                    project_workspace_set, project_lend, project_unlend, \
                    project_move and project_forget, and \
                    ProjectTools records why those names carry the load they do: a \
                    model reads nothing but the descriptions, and two of them could \
                    each answer "when a checkout moves", so each names the other. A \
                    person reading a usage block has all six in front of them at \
                    once and no such hazard — and for them "move" is the word for \
                    the directory, which is the one thing this verb does not \
                    touch.""",
                    """
                    A rename moves a project's whole archive with it, and the \
                    console has no undo and no confirmation step built for \
                    anything of that weight -- what it has is one screen that \
                    renders the leash as enforced. A gap, not a decision, and the \
                    thing it wants first is a way to say "this will move every \
                    memory, proposal and conversation filed under this name".""",
                    agentGap(),
                    List.of("project.move"),
                    null),
            consoleGap("drop a project's workspace", List.of(), "project_forget", "project forget",
                    """
                    Forgetting drops the workspace and leaves the archive, which \
                    is a distinction a screen has to draw before it offers the \
                    control -- somebody who reads "forget" as "delete" and is \
                    right would be catastrophically wrong here. The projects \
                    screen states the leash and does not yet state that.""",
                    List.of("project.forget"), null),

            new Capability(
                    "manage a project's members",
                    List.of(), List.of(), List.of(), List.of(),
                    "Frames only. The configured seeded admin adds or removes known accounts"
                            + " from a project; each answer reports its resulting member list.",
                    "no console control for membership management yet",
                    agentGapNoTool(),
                    List.of("project.member.add", "project.member.remove"),
                    null),

            new Capability(
                    "lend this machine's own files to a run",
                    List.of(),
                    List.of("client_root_project_here"),
                    List.of(),
                    List.of(),
                    """
                    Both surfaces do this and only one of them needs a verb for it. \
                    A terminal command IS the session: cli.Plowshare opens one, \
                    declares what it lends with --workspace and what it roots with \
                    --project, and gives both back when the command ends. The MCP \
                    process outlives every call it serves, so somebody has to be \
                    able to say — later, explicitly — what it should root, and that \
                    is what client_root_project_here is for. A CLI verb of the same \
                    name would have nothing to hold: the session it rooted would \
                    close as the command returned.""",
                    """
                    Structural, and the only entry here that will stay empty. A \
                    browser tab has no filesystem to lend: it can open a file a \
                    person picks, which is what the documents screen does, and it \
                    cannot serve line-based windows out of a directory on demand \
                    the way a file channel does. The console's runs read files \
                    through whichever client rooted the project, never through \
                    the tab.""",
                    agentGap(),
                    List.of(),
                    """
                    NOT AN ENDPOINT, SO NOT A GAP -- and it carried the stock \
                    "not yet -- HTTP only" until the breadth plan finished and \
                    made that sentence readable as the lie it was. This is the \
                    one entry in the register with no route behind it on either \
                    surface. Rooting is a claim this process makes about its own \
                    disk: ClientPresence.root opens a file channel and holds it, \
                    and what travels is the server ASKING this client for a \
                    window of a file it named. There is no request a socket \
                    client could send to do this and no reply it could be sent, \
                    because the direction is the other way round -- and the \
                    transport is already a WebSocket, just not this one. A frame \
                    type here would be a type with nothing to answer."""),

            new Capability(
                    "remove what retention policy has marked, and say what went",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    Operator work with no front end at all. This server has no \
                    scheduler -- deliberately, since a timer started from a bean \
                    would delete bytes with nobody watching and would fire in \
                    every test that stands up a real database -- so what it owes \
                    an operator is a verb their own cron can call, and POST \
                    /v1/retention/sweep is that verb. Neither main has it and \
                    neither obviously should: a sweep deletes conversation \
                    payloads, and a command that does that belongs in the hands \
                    of whoever runs the deployment rather than in a tool surface \
                    a model can reach.""",
                    """
                    No screen. A button that ejects a person's conversation \
                    history is the one control where "are you sure" is not \
                    enough -- the report says what went and the bytes are gone \
                    -- and the console has no notion of who is operating it.""",
                    """
                    No agent, and this is a decision rather than an open \
                    question: an agent that could run a retention sweep could \
                    delete the record of what it did.""",
                    List.of("retention.sweep"),
                    null),

            new Capability(
                    "reclaim the buffers behind search and fetch",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    """
                    The sweep above's sibling, and the same absence for a \
                    weaker reason: nothing a person loses here is theirs. A \
                    fetched page and a stored result set are derived data that \
                    can simply be fetched or searched again, so a purge is \
                    reclaiming disk rather than removing history. It has no tool \
                    and no verb because nobody has needed one from those two \
                    surfaces, which makes this an ordinary gap -- the cron entry \
                    beside the sweep's is what runs it today.""",
                    """
                    No screen, on the sweep's reasoning one notch down: an \
                    operator reclaiming disk is looking at the box, not at a \
                    browser tab, and the two numbers this answers mean nothing \
                    to anybody else.""",
                    agentGapNoTool(),
                    List.of("buffer.purge"),
                    null),

            new Capability(
                    "read and change the live configuration",
                    List.of(),
                    List.of(),
                    List.of(),
                    inConsole("config"),
                    """
                    Two absences, and this register's own distinction applies: \
                    one is a decision and the other is a gap, and one sentence \
                    for both would blur exactly the difference worth keeping.

                    THERE IS NO MCP TOOL, AND THERE SHOULD NOT BE ONE. \
                    RuntimeConfigController's own javadoc makes the case for the \
                    one live key today, and nothing about it weakens as more \
                    keys go live: "An agent that can raise its own ingest budget \
                    is an agent whose budget is not a bound -- it is a \
                    suggestion." A tool over PUT /v1/config/{key} would hand a \
                    model the one door that could widen, from inside a run, the \
                    ceiling that run is supposed to be stopped by. Infrastructure \
                    an operator set is the operator's to change and not the \
                    agent's, which is the same rule this register already applies \
                    to a run's own allowance.

                    THERE IS NO CLI VERB, AND THAT IS A GAP RATHER THAN A \
                    DECISION. Nothing in cli.Commands or PlowshareClient argues \
                    against a `plowshare config get` or `plowshare config set`; \
                    either would read and write exactly what this screen does, \
                    over the same two routes, and there is no case on file for \
                    withholding them. They are absent because this task built \
                    the console's door onto /v1/config and not the terminal's -- \
                    an ordinary gap, and saying so plainly is worth more than \
                    dressing it as a choice.""",
                    null,
                    agentGapNoTool(),
                    List.of(),
                    """
                    DECIDED, NOT DEFERRED, and for a reason the two absences \
                    above already rehearse. GET /v1/config and PUT \
                    /v1/config/{key} are operational, and §4.1 of the socket \
                    design says HTTP narrows to "health, auth, and what ops \
                    genuinely needs" rather than disappearing. These are what \
                    that sentence is about: PUT /v1/config/{key} takes a raw \
                    text/plain body, which is the shape of a thing meant to be \
                    reached with curl by somebody holding a shell on the box, \
                    and a frame equivalent would be a second door onto the \
                    ceiling a run is supposed to be stopped by -- the same \
                    argument the missing MCP tool above rests on, one layer \
                    out. POST /v1/search/providers is the third endpoint in \
                    this ruling, and it has an entry of its own now -- "manage \
                    which search providers this server will ask", added when \
                    its other two verbs got frames -- which is where the same \
                    reasoning is written for that route. It had none at all \
                    while nothing about it needed declaring, which is what the \
                    header above means by an endpoint no front end reaches \
                    being invisible here."""),
            new Capability(
                    "schedule when events fire",
                    List.of(), List.of(), List.of(), List.of(),
                    "Frames only. A schedule is a cron expression in a zone that emits an event"
                            + " name; the server fires it itself. Every verb but list needs a signed-in"
                            + " account, and only the defining account may redefine, pause or forget it."
                            + " schedule.read turns one sentence into a proposed schedule and trigger"
                            + " and saves nothing; the client confirms it and sends the two defines.",
                    "not yet a console screen: the console has INBOX and nothing that defines"
                            + " schedules, by decision (events spec §9)",
                    agentGapNoTool(),
                    List.of("schedule.define", "schedule.list", "schedule.pause", "schedule.forget",
                            "schedule.read"),
                    null),
            new Capability(
                    "subscribe an agent to an event",
                    List.of(), List.of(), List.of(), List.of(),
                    "Frames only. A trigger is the instructions an event runs, written by the"
                            + " signed-in account whose user-inbox receives the result; only that account"
                            + " may redefine, pause or forget it.",
                    "not yet a console screen, by decision (events spec §9)",
                    agentGapNoTool(),
                    List.of("trigger.define", "trigger.list", "trigger.pause", "trigger.forget"),
                    null),
            new Capability(
                    "raise an event and see what it started",
                    List.of(), List.of(), List.of(), List.of(),
                    "Frames only. event.fire is the same intake a tick uses, and needs a signed-in"
                            + " account.",
                    "not yet a console screen, by decision (events spec §9)",
                    agentGapNoTool(),
                    List.of("event.fire", "firing.list"),
                    null),
            new Capability(
                    "read what event-started runs left in your user-inbox",
                    List.of(), List.of(), List.of(), inConsole("inbox"),
                    "Frames only, per account. inbox.changed is pushed bare to every socket of the"
                            + " account when the count moves; it is not a request type.",
                    null,
                    "bots whose definition says announces-inbox get a server-side inbox_read"
                            + " tool and a notice; no MCP tool exists",
                    List.of("inbox.list", "inbox.read"),
                    null),
            new Capability(
                    "approve or deny a command a run asked a person about, and revoke a standing"
                            + " project approval",
                    List.of(), List.of(), List.of(), inConsole("chat", "projects"),
                    "Frames only; spec 2026-09-15, asking a person. The CLI and MCP show the question"
                            + " an AWAITING turn ended on and cannot answer it: --talk has no prompt for"
                            + " it and MCP cannot speak into a conversation (TODO §4.3).",
                    null,
                    "an agent never answers its own question; no tool exists, by design",
                    List.of("approval.list", "approval.answer", "approval.revoke"),
                    null),
            new Capability(
                    "read a conversation's todo list, and be told when it changes",
                    List.of(), List.of(), List.of(), List.of(),
                    "Frames only. todos.changed is pushed bare to the speaking session's account"
                            + " when a batch commits; it is not a request type. Changing the list is"
                            + " todo_write, a tool the run's own agent holds -- not a door this client"
                            + " opens, so neither an MCP tool nor a CLI verb answers it either.",
                    "not yet a console screen -- nothing in the console renders a conversation's"
                            + " todo list",
                    agentGapNoTool(),
                    List.of("todos.read"),
                    null),
            new Capability(
                    "start, see, answer and cancel the orchestrations an account started, list the"
                            + " orchestration definitions a project can reach, and read a"
                            + " project's caps and apply them to its live runs",
                    List.of(), List.of(), List.of(), List.of(),
                    "Frames, and GET /v1/orchestrations/{id}/record for a run tree's record;"
                            + " spec 2026-09-13, orchestrations §7, and spec 2026-09-28, the"
                            + " orchestration record; orchestration.start directly starts an authorized"
                            + " definition with a durable request ID, and orchestration.receipt recovers"
                            + " its acknowledgment without starting work again. The TypeScript CLI"
                            + " offers both; existing caller grants and project/account checks apply."
                            + " orchestration.caps is spec 2026-09-29"
                            + " §2, sent by the TUI's /cap after it writes the project's"
                            + " .plowshare/environment.yml. orchestration.changed and"
                            + " orchestration.recorded are pushes, not request types.",
                    "not yet a console screen -- orchestrations spec §10",
                    "an agent reaches orchestrations through its orchestrations: grant, which offers"
                            + " orchestrate_<name> and orchestration_answer, _status and _cancel",
                    List.of("orchestration.start", "orchestration.receipt",
                            "orchestration.definitions", "orchestration.list", "orchestration.status",
                            "orchestration.answer", "orchestration.cancel", "orchestration.record",
                            "orchestration.caps"),
                    null),
            new Capability(
                    "inspect an account's project board and swarm, and raise a root topic's budget",
                    List.of(), List.of(), List.of(), List.of(),
                    "board.topics pages the account's topics; board.messages reads whole messages, seats"
                            + " and decisions without advancing model read watermarks; swarm.status reads"
                            + " account queue entries and shared pool occupancy. The desktop Board and Swarm"
                            + " inspector refreshes these reads while open. Operator POST"
                            + " /v1/board/topics/{id}/topup and board.topup grant an absolute root ceiling.",
                    "Board and Swarm inspection is available in the desktop application.",
                    "A budget grant is an operator's decision; agents cannot grant their own research allowance.",
                    List.of("board.topics", "board.messages", "swarm.status", "board.topup"),
                    null),
            new Capability(
                    "read inference usage and costs, count a conversation's next projection, and subscribe to usage snapshots",
                    List.of(), List.of(), List.of(), inConsole("usage"),
                    "Authenticated read-only frames and scoped snapshot subscriptions. Desktop/console reference comparisons"
                            + " use dated public or custom rates without changing booked costs. TUI and TypeScript CLI provide"
                            + " lightweight reports; Java UsageSocket supports reports and reconnect. No metrics REST fallback.",
                    "Shared usage view, reference comparison, hierarchy and call audit over the existing socket.",
                    agentGapNoTool(),
                    List.of("usage.conversation", "usage.project", "usage.agent", "usage.run",
                            "usage.orchestration", "usage.models", "usage.pools", "usage.calls",
                            "usage.subscribe", "usage.unsubscribe", "conversation.context.count"),
                    null));

    private Capabilities() {}

    /** Every MCP tool this client declares, in declaration order. */
    public static Set<String> tools() {
        return spellings(Which.TOOLS);
    }

    /** Every CLI command this client declares, in declaration order. */
    public static Set<String> commands() {
        return spellings(Which.COMMANDS);
    }

    /**
     * Every console view this register names, in declaration order.
     *
     * <p><b>Nothing in Java calls this, and that is the finding rather than dead
     * code.</b> Its two siblings exist because each has an assembly that can be
     * held to it; there is no Java assembly of the console, so the reader of this
     * set is {@code parity.test.ts} in {@code plowshare-console}, which parses
     * the source of this file rather than calling anything. The accessor is here
     * so that the day something on this side can check the console — a build step
     * that reads {@code VIEWS}, say — it does not have to invent the union again.
     */
    public static Set<String> screens() {
        return spellings(Which.SCREENS);
    }

    /** Every frame type this register names, in declaration order. */
    public static Set<String> frames() {
        return spellings(Which.FRAMES);
    }

    /**
     * The one frame type that exists and is never a capability: {@code
     * ws.FrameTypes.REFUSED}, which a channel answers under when an inbound
     * frame was too malformed to have a {@code type} of its own at all.
     *
     * <p><b>Spelled out here rather than imported</b> because it cannot be
     * imported: {@code ws.FrameTypes} is in {@code plowshare-server}, which
     * depends on this module and not the other way round. {@code
     * FrameRouterTest} pins the two spellings against each other, so the
     * duplication is checked rather than hoped for.
     */
    public static final String RESPONSE_ONLY_REFUSED = "frame.refused";

    /**
     * Refuse a tool surface that offers something nothing here declares.
     *
     * <p>Called where the surface is assembled, so the failure lands on whoever
     * added the tool rather than on whoever next reads this file. It is one-way
     * on purpose: a tool declared here and not registered is a real defect too,
     * and it is {@code ParityTest}'s, because an assembly cannot see what it was
     * not asked to build.
     *
     * @throws IllegalStateException naming every undeclared tool
     */
    public static void toolsAreDeclared(Collection<String> registered) {
        check("MCP tool", registered, tools(),
                "Add it to Capabilities.ALL, with the CLI command and the console screen that"
                        + " offer the same capability — or with either side empty and a note"
                        + " saying why that surface does not get this.");
    }

    /**
     * Refuse a command surface that offers something nothing here declares.
     *
     * @throws IllegalStateException naming every undeclared command
     * @see #toolsAreDeclared
     */
    public static void commandsAreDeclared(Collection<String> handled) {
        check("CLI command", handled, commands(),
                "Add it to Capabilities.ALL, with the MCP tool and the console screen that offer"
                        + " the same capability — or with either side empty and a note saying why"
                        + " that surface does not get this.");
    }

    /**
     * Refuse a frame surface that answers a type nothing here declares — spec
     * §3.7's first half, and the reason the second half ("an endpoint with no
     * frame equivalent shows as a declared gap") is worth anything.
     *
     * <p>One-way, like its two siblings: a type this register declares and
     * nothing routes is a real defect too, and it belongs to the test that can
     * see the routing table rather than to this method.
     *
     * <h2>{@link #RESPONSE_ONLY_REFUSED} is excluded, deliberately</h2>
     *
     * <p>{@code ws.FrameTypes.REFUSED} is a type the server <em>answers</em>
     * under and never routes: a frame that was not JSON, or whose {@code type}
     * was missing, has no type to echo, and an {@code Envelope} cannot exist
     * without one — so the refusal needs a {@code type} of its own. No client
     * can send it and no capability is behind it. Requiring it to be declared
     * would fail the boot over a capability that does not exist, and the fix
     * somebody reached for under that pressure would be a fictional entry in
     * {@link #ALL} claiming the socket offers something it does not. Filtered
     * here rather than left to each caller, because a caller handing this method
     * "every type this server knows" — which is the stronger check, catching a
     * constant added and left unrouted — is exactly the caller that will have it
     * in hand.
     *
     * @param routed the frame types the surface answers; {@link
     *     #RESPONSE_ONLY_REFUSED} may be among them and is ignored
     * @throws IllegalStateException naming every undeclared frame type
     * @see #toolsAreDeclared
     */
    public static void framesAreDeclared(Collection<String> routed) {
        List<String> asked = new ArrayList<>();
        for (String type : routed) {
            if (!RESPONSE_ONLY_REFUSED.equals(type)) {
                asked.add(type);
            }
        }
        check("frame type", asked, frames(),
                "Add it to Capabilities.ALL, on the entry whose HTTP endpoint it answers — or, if"
                        + " it answers no capability a front end offers, that is itself the thing"
                        + " to say out loud, before the surface grows a type nothing here"
                        + " can see.");
    }

    private static void check(
            String kind, Collection<String> offered, Set<String> declared, String remedy) {

        List<String> undeclared = new ArrayList<>();
        for (String each : offered) {
            if (!declared.contains(each)) {
                undeclared.add(each);
            }
        }
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "undeclared " + kind + (undeclared.size() == 1 ? ": " : "s: ")
                            + String.join(", ", undeclared) + ". Nothing in Capabilities says"
                            + " what a Plowshare client offers here, so the three front ends have"
                            + " no way to be held to each other. " + remedy);
        }
    }

    /** Which spelling {@link #spellings} is collecting. */
    private enum Which { TOOLS, COMMANDS, SCREENS, FRAMES }

    private static Set<String> spellings(Which wanted) {
        Set<String> all = new LinkedHashSet<>();
        for (Capability capability : ALL) {
            all.addAll(switch (wanted) {
                case TOOLS -> capability.tools();
                case COMMANDS -> capability.commands();
                case SCREENS -> capability.screens();
                case FRAMES -> capability.frames();
            });
        }
        return all;
    }
}
