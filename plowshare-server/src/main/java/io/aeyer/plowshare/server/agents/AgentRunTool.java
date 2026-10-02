package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.images.ImageRefusedException;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.images.ImageVanishedException;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * {@code agent_run} — one agent handing work to an agent it declared.
 *
 * <p>This is what makes Plowshare's orchestration <em>data</em> rather than
 * code: an agent's file names its callees, and the runtime executes whatever
 * graph a directory of files describes. Anchor wires three agents in Java in a
 * fixed order and needs none of this.
 *
 * <h2>Bound to one run, and built by the runtime for that run</h2>
 *
 * <p>Unlike every other {@link AgentTool}, one instance of this does <b>not</b>
 * serve every job. It carries the caller's definition, the tree's {@link Budget}
 * and the run's cancellation flag, none of which are parameters of {@link
 * AgentTool#run}, so {@link JobRuntime} builds one per run inside the run.
 *
 * <p><b>That is a plan correction and not a preference.</b> The plan had this
 * registered in the runtime's shared tool list; a shared instance has nothing to
 * read the calling agent's {@code calls()} off, no budget to pass down, and no
 * cancellation flag — so it would have needed a thread-local, or a wider
 * dispatch signature every other tool would carry and ignore. Per-run
 * construction is the one shape that keeps all three explicit, immutable, and
 * impossible to read from the wrong run.
 *
 * <h2>The schema says what each callee is for, and the callee's file owns those
 * words</h2>
 *
 * <p>The description names each callee and then quotes that agent's own {@code
 * description:}, whitespace-flattened onto one line. <b>An earlier version of
 * this paragraph argued the other way</b> — that what an agent's helpers are for
 * belongs in the calling agent's prompt — and the measurement against it is that
 * a caller's prompt is a second copy of a fact the callee's file already owns,
 * free to drift from it, while the sentence a delegating model actually reads at
 * the moment it picks a callee was the bare name. One owner per fact puts it
 * here, at the decision point, generated from the file that defines the agent
 * being chosen.
 *
 * <p><b>Construction still cannot fail on a bad graph</b>, which is the property
 * the old shape got by looking nothing up. It is kept by looking up through
 * {@link AgentRegistry#find} rather than {@code get}: a callee this process does
 * not serve contributes its name and no description, and the model's own call to
 * it is refused later as a tool result it can read. The failure a throw here
 * would produce is the one worth avoiding — a run with no turns and no calls,
 * which {@code JobStore}'s last-resort catch reports as {@code 0, 0}: the honest
 * count and a useless one.
 *
 * <p><b>That miss used to be reachable only from a definition built by hand, and
 * is now an ordinary state.</b> Every registry checked every declared callee
 * over its whole set, so a declared callee was always served; under the disable
 * rule a callee can be defined, read, refused for its own reasons and left out
 * of the set while its caller keeps running with an inert grant. A callee that
 * no file defines <em>at all</em> is a different thing and never reaches here:
 * the item rule takes that name out of the caller's {@code calls:} at load. {@code calls:} is a
 * grant and not a dependency, so the edge fails at call time rather than at load
 * — and {@link #answer} is where it fails, with a sentence saying the agent is
 * not being served rather than a report that the tool is broken. {@code
 * a_declared_callee_this_process_does_not_serve_is_a_tool_result} pins it.
 *
 * <h2>The declared list is enforced here as well as at load</h2>
 *
 * <p>{@code AgentRegistry} refuses a cyclic graph at boot and takes an
 * unknown callee out of the list that names it, and that load-time work is the
 * real guard — it is why there is no depth counter
 * anywhere in this runtime. It is not the whole guard: <b>a model can name any
 * string it likes</b>, and the name it sends has to be checked against this
 * caller's list every time. The refusal is a tool result naming who this agent
 * may call, not an exception, because the model can correct it on its next turn
 * and the turn that produced the wrong name was already paid for.
 *
 * <p>An agent this process does not serve and an agent this caller may not reach
 * get the same sentence, which is {@code JobRuntime.offeredTo}'s rule for a
 * withheld tool arriving one level up: from where the model stands those are the
 * same fact.
 *
 * <h2>The budget is the tree's, and the child's own number is not consulted</h2>
 *
 * <p>The parent's {@link Budget} object is passed down by reference, so a
 * child's spending is spending the parent no longer has. The child's own {@code
 * max-model-calls} is <b>not</b> read, and it is worth saying why rather than
 * leaving it to look like an omission. Taking the smaller of the two would need
 * a second counter that decrements both, and the runaway it would guard against
 * is already bounded: the turn loop checks {@code definition.maxTurns()} every
 * turn and spends at most one call per turn, so a child cannot make more calls
 * than its own file allows turns. Two numbers governing one thing would also
 * mean an operator raising the tree's budget sees no effect and cannot tell
 * which cap bit. A definition's {@code max-model-calls} is what a job started on
 * its own behalf is given; see {@code JobStore.submit}.
 *
 * <h2>What comes back, and what ends the parent</h2>
 *
 * <p>A child's {@link Outcome} is not a string. An ending that is not {@link
 * Outcome.Ending#ANSWERED} already carries a sentence <em>the runtime</em> wrote
 * naming the ending and the tools the child called, and that sentence travels up
 * unchanged behind a prefix that says the same thing again — a run that stopped
 * must never read to its parent as a run that decided.
 *
 * <p>An approval question is also not merely a tool result. A child ending
 * {@link Outcome.Ending#AWAITING} requests the same ending on its parent's
 * {@link TurnEnd}; one parent at a time carries the question to the root turn.
 *
 * <p>One failure ending is not a tool result at all. A child that ended {@link
 * Outcome.Ending#UNAVAILABLE} or {@link Outcome.Ending#SUB_AGENT_FAILED} could
 * not reach something it depends on, and that is the same class of failure
 * {@code JobRuntime.dependencyFailure} refuses to render as a tool result one
 * level down: handing it to the parent as prose would leave it answering around
 * a dead endpoint out of its own head, and spending the rest of the tree's
 * budget rediscovering that the endpoint is still dead. So it throws {@link
 * SubAgentFailed} and the parent ends {@link Outcome.Ending#SUB_AGENT_FAILED},
 * carrying the child's own detail. That is where that constant, declared and
 * unused since Task 4, earns its place.
 *
 * <p>{@link Outcome.Ending#TURN_CAP}, {@link Outcome.Ending#CALL_BUDGET} and
 * {@link Outcome.Ending#CANCELLED} are tool results and need no special case.
 * The last two are the tree's own condition, so the parent meets them at its
 * next boundary check without spending another call — the budget is already
 * empty, the flag is already set.
 *
 * <h2>A child's grants are its own, and it may not hold one its caller lacks</h2>
 *
 * <p><b>The rule this class deferred is now enforced, and not here.</b> An
 * earlier version of this paragraph said the intersection of caller and callee
 * grants would have to be computed before {@code runtime.run} below, the moment
 * a tool could touch a filesystem. It is not, and this is better: {@code calls:}
 * makes the graph static, so {@code AgentRegistry} checks every edge of it once,
 * at load, and a set that would allow the escalation fails the boot instead of
 * refusing a delegation halfway through a job somebody is waiting on. A runtime
 * intersection would also be a second reading of the same file, reachable only
 * down a path somebody has to remember to test.
 *
 * <p><b>What is still true is that a child may hold {@code tools} its caller
 * lacks</b>, and that is deliberate rather than the same hole in another hat. A
 * file tool reaches nothing on its own: what it may touch is resolved from the
 * grants its provider was built with, and {@code LocalProvider} is built with a
 * list of grants and nothing else that could widen them — so a callee holding
 * {@code file_read} and no grant reads no file. That closes the filesystem half
 * without a rule about {@code tools}, <em>on the condition that a job's provider
 * is built from that job's own definition</em>, which is the wiring this slice
 * leaves to its last task and the reason the condition is written down rather
 * than assumed. {@code boss.md} declaring {@code tools: [agent_run]} and
 * reaching {@code probe_read}'s effects through {@code helper} stays legal
 * meanwhile, and stays bounded by the tier it was always bounded by.
 *
 * <h2>The tier is not an argument</h2>
 *
 * <p>The schema has no {@code project} field. The child runs against the {@code
 * home} the parent was given, which arrives as a parameter of {@link #run}: a
 * tool must not let an agent name its own tier, and delegation must not become
 * the way around that.
 *
 * <h2>A picture is passed by reference, and the harness is what turns the
 * reference into bytes</h2>
 *
 * <p><b>{@code images} is a list of ids and not an inheritance.</b> The design
 * this was built against considered having a delegated run silently inherit
 * whatever its caller was shown, and the reason that is wrong is that {@code
 * agent_run} creates a <em>job</em>: a call into an asynchronous service passes
 * a reference, and the harness — never the agent — holds the bytes behind it. So
 * a caller writes down which picture it means, the same way it writes down which
 * agent and which task it means, and a delegation that means no picture says
 * nothing and gets exactly the request it got before this argument existed.
 *
 * <h2>An id it was told, and not only one it was shown — decided 2026-09-08</h2>
 *
 * <p><b>An id resolves against the pictures this run is looking at first, and
 * then against this run's own tier.</b> That is the reverse of the rule this
 * class shipped with, under which an id a caller merely knew was refused and
 * only a picture already on its own opening message could be handed on.
 *
 * <p><b>The old rule was never a containment argument, and this is not a
 * containment budget being spent.</b> The paragraph two headings up already says
 * what an id is: a 128-bit content-addressed token conferring exactly one power,
 * <em>cause a vision-capable agent to be shown this picture</em>. Nothing in
 * this server turns an id into bytes an agent can read, and an unguessable id in
 * an agent's hands grants nothing its holder did not already have. That was true
 * when the conservative rule was chosen and it is what makes the permissive one
 * safe.
 *
 * <p><b>It was chosen on cost and it is reversed on reach.</b> The cost was
 * real: resolution was a map lookup, and this reading puts a second uid→bytes
 * site in a server that had exactly one — {@code Pictures.pictures} is the
 * first. What the cost argument missed is that <em>every ingress except a
 * submit-time attachment delivers an id as text</em>: in a tool result, in a
 * document record, in a task somebody wrote. So "only what it was shown" was not
 * a principle about what an agent may reach. It was coupling to one ingress, and
 * the narrowest one.
 *
 * <p><b>The evidence is a shipped edge that could not fire.</b> {@code
 * interlocutor} declares {@code calls: [image_reader]} and runs on a text model.
 * It can never be shown a picture, so under the old rule there was no id it
 * could ever legally pass and the edge was provably dead — which the file said
 * about itself in as many words. It is live now.
 *
 * <h2>What holds a named id in, now that one is allowed</h2>
 *
 * <p><b>The tier is not the model's to choose.</b> {@link #store} is asked with
 * the {@code home} that arrives as a <em>parameter</em> of {@link #run} — this
 * run's own, never anything in the arguments a model sent — so an id belonging
 * to another project resolves to nothing here. It is the rule of "the tier is
 * not an argument" one heading up, applied to the picture rather than to the
 * child.
 *
 * <p><b>And nothing enumerates images.</b> An id is 128 bits of a content hash,
 * so it cannot be guessed; {@code ImageStore} answers {@code store}, {@code
 * find} and {@code dataUri} and lists nothing; {@code ImageController} has only
 * a {@code @PostMapping}. That is what makes naming reachability-equivalent to
 * being shown — an agent may use an id it was handed and has no way to discover
 * one it was not. The day an agent-facing listing arrives, that equivalence is
 * gone, which is why the absence is asserted by {@code
 * NothingEnumeratesImagesTest} rather than hoped for in a comment.
 *
 * <p><b>{@link #shown} is still consulted first, and not as an optimisation.</b>
 * It is free — the parts are already built — and it is the <em>only</em> path
 * for a picture that never entered the store. The ephemeral class of image,
 * bytes handed to a job rather than uploaded to a project, has no tier to be
 * found in; see §3 of {@code
 * implementation rationale}, which is
 * also where §6 records this decision.
 */
public final class AgentRunTool implements AgentTool {

    /** The name the model calls and the job log records. Spelled once, in {@link
     *  AgentRegistry}, because the registry validates against it at boot. */
    public static final String NAME = AgentRegistry.AGENT_RUN;

    private static final String EXAMPLE =
            "{\"agent\": \"promotion_judge\", \"task\": \"is mem_000042 worth promoting?\"}";

    /**
     * The set a delegation resolves against, and it is <b>the boot set</b>.
     *
     * <p>{@code JobRuntime.offeredTo} builds this tool from the registry the
     * runtime was wired with, which is the one {@code AgentsConfig} validated
     * at boot — seed plus {@code global/} — and never a caller's resolved set.
     * That is not a subtlety to leave implicit, because it has a consequence a
     * definition cannot see from where it is written: <b>a project-tier agent
     * whose {@code calls:} names another project-tier agent passes validation
     * and then finds no callee here.</b> {@code DefinitionResolver} validates
     * that edge against the merged graph and is right to; the tool holding the
     * boot set is what it fails against at run time.
     *
     * <p>Closing it needs a project id at {@code offeredTo}, which has none —
     * a run carries a {@code Home}, and a {@code Home} carries a project
     * <em>name</em> while {@code DefinitionResolver.Caller} takes a surrogate
     * <em>id</em>, so the wiring would have to reach the database from inside
     * the turn loop. That is a change to what a run knows about itself and not
     * a change to this tool. Until it is made, the refusal in {@link #answer}
     * must say so rather than assert a reason that is false for this path.
     */
    private final AgentRegistry agents;
    private final JobRuntime runtime;
    private final AgentDefinition caller;
    private final Budget budget;
    private final BooleanSupplier cancelled;
    /** The session that asked for the tree, or null. Nullable and carried
     *  rather than resolved: see {@link #answer}. */
    private final String sessionId;
    /**
     * The <em>caller's</em> log, and the only thing this class uses it for is to
     * ask it for a child's.
     *
     * <p>{@link Transcript#delegate} is the whole of the interaction: nothing
     * here reads the parent's history, records into it or measures anything
     * against it. A child that wrote into its caller's log is precisely what
     * delegation exists not to do, and holding the parent's transcript without
     * that being possible is what {@code delegate} returning a <em>different</em>
     * transcript buys.
     */
    private final Transcript transcript;
    private final Set<String> callable;
    /**
     * The pictures this run was shown, by id, in the order it was shown them.
     *
     * <p><b>The first of the two ways an id becomes a picture here, and the free
     * one:</b> these parts are already built, so a caller handing on what it is
     * looking at costs a map lookup and reads nothing. It is also the only way
     * that works for a picture with no tier behind it — see the class javadoc.
     *
     * <p>Ordered, so that a refusal listing what the caller <em>was</em> shown
     * names them in the order the opening message named them.
     */
    private final Map<String, Content.Image> shown;
    /**
     * The tier's images, for an id this run was told rather than shown.
     *
     * <p><b>The second way, and it is scoped by a {@code Home} this class never
     * holds.</b> The store is asked inside {@link #run}, with the {@code home}
     * that method is handed, so there is no field here a model's arguments could
     * reach and no tier but the run's own. {@link ImageStore#NONE} for a
     * deployment that keeps no data directory, which answers "no such image" to
     * everything rather than making this a null somebody has to check.
     */
    private final ImageStore store;
    private final TurnEnd end;
    private final String callerHandle;
    private final ToolSchema schema;

    /**
     * How the delegate this tool last started came back, or {@code null} when its last call
     * started none — refused before any child ran. Read by {@link JobRuntime} straight after
     * {@link #run} returns, for the record's tool line: the ending is the call's outcome, and the
     * rendered result does not carry it. A plain field: one run's tool, called on that run's own
     * thread, one call at a time.
     */
    private Outcome.Ending returned;

    /**
     * The id of the model's call being answered, set by the runtime just before {@link #run} and
     * cleared by it -- the same side channel {@link #returned} uses the other way. Null for a
     * delegation no model asked for ({@link #runDeclared}).
     */
    private String callId;
    private boolean scripted;
    AgentRunTool scripted() { scripted = true; return this; }

    void calledAs(String id) {
        this.callId = id;
    }

    /**
     * The tool as one run may use it.
     *
     * <p>Package-private: {@link JobRuntime} is the only thing that knows a
     * run's budget and cancellation flag, so it is the only thing that can build
     * one of these honestly. A second door taking those on trust from elsewhere
     * would be a door onto a different tree's budget.
     *
     * @param cancelled the <em>parent's</em> flag, handed to the child run. A
     *     parent blocked inside this tool cannot reach its own boundary check,
     *     so the child is the only place in the tree that is asked; see {@link
     *     #run}.
     * @param transcript the caller's log, which this asks for a child's. {@link
     *     Transcript#NONE} for a caller keeping none, whose children keep none
     *     either — a child of nothing is nothing
     * @param images the pictures the caller was shown, already resolved to
     *     parts. Never null; empty is every run in this repository that is not
     *     looking at something, and is the ordinary state of a caller that hands
     *     on an id it was told about instead. See {@link #shown}
     * @param store where an id the caller was <em>not</em> shown is looked up,
     *     or {@link ImageStore#NONE} for a deployment holding no images. Never
     *     null, and never asked without a {@code Home} from {@link #run}. See
     *     {@link #store}
     */
    AgentRunTool(
            AgentRegistry agents,
            JobRuntime runtime,
            AgentDefinition caller,
            Budget budget,
            BooleanSupplier cancelled,
            String sessionId,
            Transcript transcript,
            List<Content.Image> images,
            ImageStore store) {
        this(agents, runtime, caller, budget, cancelled, sessionId, transcript, images, store, null,
                null);
    }

    AgentRunTool(
            AgentRegistry agents,
            JobRuntime runtime,
            AgentDefinition caller,
            Budget budget,
            BooleanSupplier cancelled,
            String sessionId,
            Transcript transcript,
            List<Content.Image> images,
            ImageStore store,
            TurnEnd end) {
        this(agents, runtime, caller, budget, cancelled, sessionId, transcript, images, store, end,
                null);
    }

    AgentRunTool(
            AgentRegistry agents,
            JobRuntime runtime,
            AgentDefinition caller,
            Budget budget,
            BooleanSupplier cancelled,
            String sessionId,
            Transcript transcript,
            List<Content.Image> images,
            ImageStore store,
            TurnEnd end,
            String callerHandle) {
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.caller = Objects.requireNonNull(caller, "caller");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        // Not requireNonNull: a tree submitted with no session is the ordinary
        // case — a curator pass, a scheduled tick — and the whole point of the
        // parameter is that it may be absent.
        this.sessionId = sessionId;
        // Sorted, and a set: the model is shown one order every time and cannot
        // read a preference into it, and a file naming the same callee twice
        // does not say it twice. Static helpers only from here on — the build
        // lints this-escape as an error.
        this.callable = Collections.unmodifiableSet(new TreeSet<>(caller.calls()));
        // Insertion-ordered and NOT sorted, which is the opposite of the line
        // above and for the opposite reason. `callable` is sorted so a model
        // cannot read a preference into an order nobody chose; these have an
        // order somebody did choose -- the order they were attached in, which is
        // the order `JobRuntime.named` says them in -- and re-sorting would make
        // this tool's refusals disagree with the message that taught the model
        // the ids.
        Map<String, Content.Image> byId = new LinkedHashMap<>();
        for (Content.Image image : Objects.requireNonNull(images, "images")) {
            byId.put(image.uid(), image);
        }
        this.shown = Collections.unmodifiableMap(byId);
        this.store = Objects.requireNonNull(store, "store");
        this.end = end;
        this.callerHandle = callerHandle;
        this.schema = new ToolSchema(NAME, describe(agents, this.callable), parameters());
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
        // Outside the try: a null here is the runtime's bug and not the model's,
        // and the never-throw rule is a rule about a caller's mistakes. See
        // AgentTool.
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(home, "home");
        returned = null;
        // Read and cleared here, before anything below can throw or return, so a stale id can
        // never leak into a later call on this same tool. See calledAs and callId.
        String opening = callId;
        callId = null;
        try {
            return answer(argumentsJson, home, opening);
        } catch (BadArguments unusable) {
            // Only BadArguments, and that is load-bearing: it is a nested type
            // of ToolArguments, so nothing outside this package can be thrown
            // into this clause, and SubAgentFailed below travels straight
            // through to the turn loop.
            return unusable.getMessage();
        }
    }

    private String answer(String argumentsJson, Home home, String openedBy) {
        JsonNode args = ToolArguments.parse(argumentsJson, NAME, EXAMPLE);
        String wanted = ToolArguments.requireText(args, "agent", NAME,
                "the name of an agent you may call");
        String task = ToolArguments.requireText(args, "task", NAME,
                "what that agent should do, in plain language");
        // Read here rather than after the callee is resolved, so that a shape
        // mistake -- a bare string where an array belongs -- is answered as a
        // shape mistake whichever agent was named. Which PICTURES those ids
        // stand for is a different question and cannot be asked yet: it depends
        // on the callee, which is not known until three checks below.
        List<String> named = ToolArguments.optionalTexts(args, "images",
                sent -> new BadArguments(NAME + " needs 'images' to be a list of image ids, like"
                        + " [\"img_" + "0".repeat(32) + "\"], or left out altogether. It was "
                        + MemoryTools.oneLine(sent.toString()) + "."));

        return answer(wanted, task, named, home, openedBy);
    }

    /** How the delegate the last {@link #run} started came back, or {@code null} when it started
     *  none. See {@link #returned}. */
    Outcome.Ending returned() {
        return returned;
    }

    /** Runs a harness-mandated child through the same checked, logged delegation path. */
    String runDeclared(String wanted, String task, Home home) {
        Objects.requireNonNull(wanted, "wanted");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(home, "home");
        return answer(wanted, task, List.of(), home, null);
    }

    private String answer(String wanted, String task, List<String> named, Home home,
            String openedBy) {

        if (!callable.contains(wanted)) {
            return refuse(wanted);
        }

        // Looked up after the membership check, so a name the model invented is
        // never a reason to consult the registry.
        //
        // A CALLEE THAT IS DECLARED AND NOT SERVED IS NOW AN ORDINARY CASE, and
        // it did not used to be. Every registry ran the unknown-callee check over
        // its whole set, so a declared callee was always present and this shape
        // arrived only from a definition built by hand. Under the disable rule a
        // callee can be defined, refused for its own reasons and left out of the
        // set while its caller keeps running — which is what "calls: is a grant,
        // so the edge fails at call time" means once it is code.
        //
        // Refused HERE rather than left to get()'s IllegalArgumentException,
        // which JobRuntime's general clause would wrap in "this is a fault in
        // the tool rather than in what you sent it". That is exactly backwards:
        // nothing is wrong with agent_run, and nothing the model sends can fix
        // it, so the sentence has to say the agent is not there and let the
        // model do the work another way.
        AgentDefinition callee = agents.find(wanted).orElse(null);
        if (callee == null) {
            // THREE WAYS TO GET HERE AND THE SENTENCE NAMES ALL THREE, because
            // it used to name two and was therefore false on the third. The
            // third is not exotic: a project-tier agent calling another
            // project-tier agent takes it every time, since this tool holds the
            // boot set and neither definition is in it -- see the javadoc on
            // `agents`. A message asserting "read and refused, or no such
            // agent" sends that operator to look for a fault in a file that is
            // fine.
            return "the agent '" + wanted + "' is one '" + caller.name() + "' may call, but it is"
                    + " not in the set this run can delegate into: its definition was read and"
                    + " refused, or it is defined only in a project's own agents/ or bots/"
                    + " directory or in a client's .plowshare/ -- delegation resolves against the"
                    + " set this server"
                    + " booted with, which is the shipped definitions and global/ -- or this"
                    + " process defines no such agent anywhere. Nothing you send can start it. Do"
                    + " the work yourself, or use one of " + callable;
        }

        // THE THREE REFUSALS, IN THE ORDER A CALLER WOULD WANT THEM ANSWERED.
        // Each is a tool result and not an exception, for the reason the
        // undeclared-callee refusal above is one: the model can correct any of
        // them on its next turn, and the turn that produced the mistake was
        // already paid for.
        //
        // Each has its OWN SENTENCE, and that is not tidiness. These are three
        // different facts about three different things -- the callee, the string,
        // and this tier -- and a model told "that picture cannot be sent" for all
        // three would have no way to tell "ask a different agent" from "you
        // mistyped an id" from "no such picture here". One sentence for three
        // states is one state to anybody reading it.
        List<Content.Image> pictures = new ArrayList<>(named.size());
        for (String id : named) {
            // FIRST, because it is the only one that is about the callee rather
            // than about the id, and because it is true of every id in the list
            // at once: naming which id a blind agent cannot be shown would be
            // answering the smaller question.
            if (!callee.vision()) {
                return refuseBlind(wanted);
            }
            if (!ImageStore.isUid(id)) {
                return "'" + MemoryTools.oneLine(id) + "' is not an image id, so there is no"
                        + " picture to send. An id is 'img_' and 32 hexadecimal characters, and"
                        + " it is written down wherever you were given it. " + heldHere();
            }
            // SHOWN FIRST, AND THE ORDER IS THE DESIGN RATHER THAN THE FASTER
            // ARM. These parts are already built, so this reads nothing -- and
            // it is the only arm that can answer for a picture that was never
            // stored, which is what an image handed to a job rather than
            // uploaded to a project is. The store cannot stand in for it.
            Content.Image picture = shown.get(id);
            if (picture == null) {
                // THE TIER, AND THE TIER IS `home` -- a parameter of run(), not
                // a field and not an argument the model sent. That is the whole
                // of the containment: an agent may name an id, and cannot name
                // whose it is.
                //
                // TWO MORE ANSWERS SINCE A FILE CAN BE NAMED WITHOUT BEING
                // COPIED, and they are caught apart rather than together. An id
                // named out of a project's own file resolves by re-reading that
                // file, so it has two ways to fail that an uploaded picture has
                // not -- the file is gone, or this project may no longer read it
                // -- and the design note gives them three sentences precisely so
                // that neither is said in the other's words. A run told
                // "refused" goes looking for a permission to fix; a run told
                // "gone" stops looking at all. One clause for both would be one
                // state to anybody reading it, which is the mistake the three
                // refusals above already avoid.
                try {
                    picture = fromStore(home, id);
                } catch (ImageVanishedException gone) {
                    return gone.getMessage() + ". " + heldHere();
                } catch (ImageRefusedException refused) {
                    return refused.getMessage() + ". " + heldHere();
                }
            }
            if (picture == null) {
                return "this " + tier(home) + " has no image " + id + ", so there is nothing to"
                        + " hand on. An image belongs to the tier it was uploaded to, and a run"
                        + " reaches its own and no other. " + heldHere();
            }
            pictures.add(picture);
        }

        // The child's own conversation, parented to the caller's, opened before
        // the run so that the run writes into it from its first message.
        //
        // WHAT THIS FIXES, because it is the whole point of the tool and was
        // silently missing: a delegated child used to run on Transcript.NONE. It
        // wrote no entries, so a `code_reviewer` run left no trace of what it
        // did -- the caller's log held this call and the result and nothing in
        // between. It measured no prompt, so it could not compact at all and a
        // child that read several large files accumulated until the endpoint
        // refused it. It stored no results, so `result_read` meant nothing
        // there. And it had no log to be resumed from.
        //
        // A SEPARATE CONVERSATION AND NOT ROWS IN THE CALLER'S. The caller must
        // not hold the callee's noise -- that is the whole justification for
        // delegation -- and a separate conversation makes it structural: a
        // separate log, a separate projection, and no filter anywhere that could
        // be got wrong. `delegate` never throws, so a child that could not be
        // given a log runs without one, which is exactly what every child did
        // before this line existed.
        Transcript child = transcript.delegate(callee, home, openedBy);
        // The record's delegation lines (spec 2026-09-28 §2), on the caller's conversation: the
        // record follows the caller's run, and the child's own lines are its conversation's.
        // child.conversationId() names this specific delegation's own conversation (spec
        // 2026-09-29 §1a, fixed after review): two delegations to the same callee, live at once
        // in this run, each open a distinct one, and the facts footer reads that one back.
        runtime.activity().delegated(transcript.conversationId(), caller.name(), wanted, task,
                child.conversationId());

        // The full form, called directly and on this thread. Not
        // JobStore.submit: that builds a fresh Budget from the child's own
        // max-model-calls and the sharing this whole class rests on would be
        // lost. This thread is the parent's virtual thread, so blocking here is
        // free and the parent holds no lane slot while it waits — which is the
        // deadlock the design exists to avoid, and what
        // a_parent_blocked_on_a_child_holds_no_lane_slot pins.
        //
        // The parent's own session goes down with it. A child is work the same
        // client asked for, reached through one more hop, so a child built for
        // no session would be a run that silently cannot see the machine the
        // job was submitted from — a smaller capability arriving by accident
        // rather than by anybody's decision. The grants are the half that is
        // NOT inherited, and for the reason above: they are the callee's own,
        // checked edge by edge at load.
        //
        // THE BUDGET IS STILL THE PARENT'S OBJECT and the child's row holds no
        // copy of it. That is what stops a delegated run being a second
        // allowance of the same size that double-counts the first time anything
        // sums the column; ConversationStore.log refuses to write one.
        //
        // THE PICTURES GO DOWN AS PARTS, RESOLVED BEFORE THE CHILD IS STARTED,
        // which is the whole of what `images:` does: an id this run is looking
        // at travels as the very object the caller's own opening message
        // carries, and an id it was merely told about was read out of this
        // run's own tier a few lines above. Either way what crosses is bytes
        // already in hand, never an id for the child to resolve -- the child is
        // shown a picture, exactly as a submission shows one. An empty list is
        // every delegation made before this argument existed and reaches the
        // model as the identical request.
        //
        // TurnCap.from(callee) is spelled here because the full form makes its
        // caller name a cap, and this is the same value the shorter overload
        // used to fill in: the child is capped at its own file's max-turns and
        // never at a ceiling the parent was given. See JobRuntime's cap javadoc,
        // which argues why that is the opposite of what the budget does.
        //
        // RULE 6 (spec 2026-09-29 §3): a conductor's delegation carries the run's real paths
        // below the task, from the harness and not the model. Measured 2026-09-28: a
        // code_reviewer handed none built one from the root's folder name and the phase's id,
        // and read a directory that did not exist. The child is given, and its log closes on,
        // what it was handed; the record's `delegated` line above keeps the task as written.
        //
        // ABOVE the task, the harness's facts for this callee (measured 2026-09-30,
        // orc_3190C667F18B8E57): a reviewer that runs nothing is handed the run's check as the
        // harness last ran it, so what it says about a test passing is weighed against that and
        // not against the conductor's retelling. First, so it reads as the frame the task is
        // given in, and it is read from the store: nothing in the task can change it.
        String framed = runtime.taskFacts(transcript.conversationId(), callee)
                .map(facts -> facts + "\n\n" + task).orElse(task);
        String handed = runtime.handoffNote(transcript.conversationId())
                .map(note -> framed + "\n\n" + note).orElse(framed);
        Outcome outcome = callerHandle == null
                ? runtime.run(callee, handed, home, budget, cancelled, sessionId,
                        JobWatch.UNWATCHED, child, TurnCap.from(callee), pictures)
                : runtime.run(callee, handed, home, budget, cancelled, sessionId,
                        JobWatch.UNWATCHED, child, TurnCap.from(callee), pictures, callerHandle);

        // Before the propagation check, so that a child whose failure kills the
        // caller still has its own record closed: the ending, the closing
        // answer, and the turn row naming which agent answered. A run whose
        // failure travels is exactly the one somebody will read back afterwards.
        child.closed(handed, outcome);
        returned = outcome.ending();
        // Told here, beside the close and before the propagation check, so a child whose failure
        // ends the caller is still told coming back -- with the ending that ended it. Not for a
        // child waiting on a person: it has not come back, and when it is resumed and ends,
        // Orchestrations.delegateEnded tells its return -- once per delegation, never an
        // "awaiting" return and then the real one. The approval it waits on is its own line.
        if (outcome.ending() != Outcome.Ending.AWAITING) {
            runtime.activity().delegateReturned(transcript.conversationId(), caller.name(), wanted,
                    outcome);
        }

        if (outcome.ending() == Outcome.Ending.AWAITING && end != null) {
            end.request(Outcome.Ending.AWAITING, outcome.text());
        }

        if (propagates(outcome.ending())) {
            throw new SubAgentFailed(wanted, outcome);
        }
        // A RETURNED DELEGATION IS PROGRESS for a caller that conducts an orchestration. Measured
        // 2026-09-28, orc_3187D648AC346812: a conductor with no children did an hour of work
        // through coder and code_reviewer between three prose endings, and was failed `stuck`
        // because only a child's start reset its count. Not for an AWAITING child: nothing has
        // come back yet, and its resumed answer is counted where it lands (Orchestrations).
        if (outcome.ending() != Outcome.Ending.AWAITING) {
            runtime.delegationReturned(transcript.conversationId());
        }
        // 1a (spec 2026-09-29): the conductor reads what the delegate did, from the record,
        // beside what it said. Outside a tree there is no record and no footer.
        // child.conversationId(), not transcript's own: delegationFacts reads back the tool
        // lines of exactly this delegation, and only the child's own conversation names it when
        // another delegation to the same callee is live at once (fixed after review).
        if (scripted) {
            if (!outcome.answered()) throw new ScriptedDelegateStopped(outcome);
            return outcome.text();
        }
        String rendered = render(wanted, pictures, outcome);
        String facts = runtime.activity().delegationFacts(child.conversationId(), wanted);
        return facts == null ? rendered : rendered + "\n\n" + facts;
    }

    /**
     * The answer to a callee that was handed a picture and cannot see one.
     *
     * <p><b>The check {@code AgentsConfig.requireModelSees} cannot make.</b> That
     * one runs at boot and asks whether an agent <em>declaring</em> {@code
     * vision: true} names a model some pool says can see. It has nothing to say
     * about this: a callee that never declared vision is a perfectly good agent,
     * correctly configured, being handed something it has no use for. {@code
     * Pictures.pictures} refuses the identical thing at the other door, for
     * a person, and this is the same refusal for an agent — without it, the boot
     * check is a check on a claim nobody has to make.
     *
     * <p>The sentence says what a silent send would look like, because that is
     * the failure being prevented and it is invisible from every side: a model
     * that cannot see answers that it saw nothing, which reads as the model's
     * limitation rather than as this delegation being misaddressed.
     */
    private String refuseBlind(String wanted) {
        return "the agent '" + wanted + "' has not declared 'vision: true', so it cannot be"
                + " shown a picture. A model that cannot see answers that it saw nothing, which"
                + " would read as that agent's limitation rather than as this call being"
                + " misaddressed. Hand it the task without 'images', or give the picture to an"
                + " agent that declares it can see.";
    }

    /**
     * One id, resolved against this run's own tier, or {@code null} for a tier
     * that holds no such image.
     *
     * <p><b>{@link ImageStore#find} before {@link ImageStore#dataUri}, and the
     * split is the same one {@code Pictures.pictures} makes</b> for the same
     * reason: a tier that does not hold the id is an ordinary answer this method
     * turns into a sentence, and {@code dataUri} raises for it. What is left
     * raising is the one state a refusal would be wrong about — a record whose
     * bytes are gone, which is somebody having edited the directory and is loud
     * on purpose.
     *
     * <p>The {@code home} is a parameter and never a field: see {@link #store}.
     *
     * <p><b>Two exceptions travel out of here rather than becoming null</b>, and
     * both are about a picture this server named in a project's own file and
     * never copied. {@code ImageVanishedException} is the file having gone;
     * {@code ImageRefusedException} is the project no longer reaching it. Null
     * would collapse either into "this tier has no image", which is wrong in
     * opposite directions: the id was real, and one of the two states is
     * something an operator can undo.
     */
    private Content.Image fromStore(Home home, String id) {
        return store.find(home, id).isEmpty()
                ? null
                : new Content.Image(id, store.dataUri(home, id));
    }

    /** How a refusal says which tier it looked in. {@code Pictures}'
     *  wording, so that a person reading a 404 and a model reading a tool result
     *  are told the same thing about the same fact. */
    private static String tier(Home home) {
        return home.isGlobal() ? "server's global tier" : "project '" + home.project() + "'";
    }

    /**
     * What this run is looking at, as a sentence the two id refusals both end
     * with.
     *
     * <p>Shared because it is the same fact — <em>these</em> are the pictures in
     * front of you — and a model that mistyped an id and a model that named one
     * this tier has not got both need somewhere to go next. It is no longer the
     * whole of what may be passed on, since an id this run was told about
     * resolves too; it stays because it is the half this run can state, and a
     * model that misread an id it was handed recovers by looking at what it
     * actually has.
     *
     * <p><b>Which is why the empty arm no longer says there is nothing to pass
     * on.</b> It used to, and under the old rule that was the truth. A run shown
     * no picture can still name one its tier holds, so the sentence says what an
     * id has to be instead of closing a door that is open.
     */
    private String heldHere() {
        return shown.isEmpty()
                ? "You are not being shown any picture yourself, so an id here has to name one"
                        + " this tier already holds."
                : "The pictures you are being shown are " + shown.keySet() + ".";
    }

    /**
     * Whether a child's ending is the parent's death rather than its reading.
     *
     * <p>The endings that mean <em>this run cannot go on</em>. {@link
     * Outcome.Ending#SUB_AGENT_FAILED} is in the list so that a failure two
     * levels down reaches the top rather than stopping at the first parent,
     * which would report a grandchild's dead endpoint as an ordinary sub-agent
     * that had nothing to say.
     *
     * <p><b>{@link Outcome.Ending#STUCK} is deliberately not in it</b>, and it
     * is the newest constant to have been asked. A child that repeated itself
     * did not fail: it reached no answer, which is what {@link
     * Outcome.Ending#TURN_CAP} and {@link Outcome.Ending#CALL_BUDGET} also mean,
     * and the parent is told so in a tool result and decides what to do about
     * it. Nothing about the child's loop stops the parent working — the endings
     * in the list below are conditions of the <em>tree</em>, and a stuck child
     * is a condition of one agent.
     *
     * <p><b>{@link Outcome.Ending#CALL_FAILURES} is not in it either</b>, for
     * STUCK's reason: a child that kept writing its calls as text is a
     * condition of one agent, and the parent reads it in a tool result ({@code
     * a_child_that_kept_writing_calls_as_text_is_reported_and_not_propagated}).
     * So {@link SubAgentFailed#sentence()} never meets it.
     *
     * <p><b>{@link Outcome.Ending#SESSION_GONE} is in it for a reason the other
     * two do not have: parent and child are the same session.</b> A child that
     * lost the client's file channel lost the parent's too — one job, one
     * session id — so a parent that carried on would call file tools down a
     * channel already known to be dead, once per turn, until its budget ran out.
     * Measured while this list was still two constants long: {@code
     * a_child_whose_session_went_away_says_so_rather_than_naming_an_endpoint}
     * ended {@code ANSWERED} and nothing else in the suite noticed. <b>A list of
     * enum constants is not a compile check</b>, which is why a set that grows
     * has to come back here.
     */
    public static boolean propagates(Outcome.Ending ending) {
        return ending == Outcome.Ending.UNAVAILABLE
                || ending == Outcome.Ending.SUB_AGENT_FAILED
                || ending == Outcome.Ending.SESSION_GONE;
    }

    /**
     * A child's outcome as something the model reads.
     *
     * <p>The prefix carries the distinction the whole {@link Outcome} type
     * exists for. For an answer it says so and the child's own words follow; for
     * anything else it says the child did not reach one, and what follows is the
     * sentence the <em>runtime</em> wrote — never {@code Completion.content()},
     * which for those endings the child never put in its text at all.
     *
     * <p>{@link Outcome#detail()} is appended only for an answer, and only when
     * it is not empty. That is the one case a caller cannot get from the ending:
     * a model cut off on {@code finish_reason: "length"} decided to stop and so
     * ends {@code ANSWERED}, with a half-sentence for text. Dropping the note
     * here would hand the parent a truncated answer as a whole one — this rule's
     * mirror image, one level up. For every other ending the detail is already
     * in the sentence or is an operator's diagnostic, and those endings are
     * either rendered here in full or do not reach this method at all.
     *
     * <h2>The result names the picture it is about</h2>
     *
     * <p><b>An answer about a picture is not self-describing and every other
     * kind of answer here is.</b> A delegation's task travels in the tool call
     * the model can read back; a picture does not travel in anything a reader
     * can see. Without the id, "a red square" is a sentence about nothing that
     * anyone — the calling model on its next turn, a person reading the entry, an
     * operator reading a transcript six weeks later — can attach to a subject.
     * The id is the one handle that exists, and this is the one place it can be
     * written down: {@code Outcome} is the child's and knows nothing about what
     * it was shown.
     *
     * <p>It is on both branches, not just the answer. A child that ran out of
     * turns looking at a picture is a fact about <em>that</em> picture, and it is
     * the reading somebody will use to decide whether to send it again.
     *
     * <p><b>A delegation with no pictures renders byte for byte what it
     * rendered before this argument existed</b>, which is what keeps every
     * existing assertion in the suite about a tool result meaning what it meant.
     */
    static final class ScriptedDelegateStopped extends RuntimeException {
        final Outcome outcome;
        ScriptedDelegateStopped(Outcome outcome) { super(outcome.text()); this.outcome = outcome; }
    }

    public static String render(String agent, List<Content.Image> shown, Outcome outcome) {
        String who = "the agent '" + agent + "'" + about(shown);
        if (!outcome.answered()) {
            return who + " did not reach an answer. " + outcome.text();
        }
        String answered = who + " answered:\n" + outcome.text();
        return outcome.detail().isBlank() ? answered : answered + "\n\nNote: " + outcome.detail();
    }

    /** Which pictures a child was shown, as a clause, or nothing at all when it
     *  was shown none. In the order they were passed, which is the order the
     *  child was shown them. */
    private static String about(List<Content.Image> shown) {
        if (shown.isEmpty()) {
            return "";
        }
        return ", shown " + shown.stream().map(Content.Image::uid)
                .collect(java.util.stream.Collectors.joining(", ")) + ",";
    }

    /**
     * The answer to an agent this caller may not reach.
     *
     * <p>{@code oneLine} on the name for the reason {@code
     * JobRuntime.noSuchTool} gives: not a forgery defence — this becomes the
     * content of a {@code tool} message, and a JSON string value has no way out
     * of its own field — but an invented name full of newlines should not turn
     * one sentence into twenty.
     */
    private String refuse(String wanted) {
        // Rendered as the set, brackets and all, so this sentence and {@code
        // JobRuntime.noSuchTool}'s — the same refusal one level down — read the
        // same way to a model that meets both in one run.
        String may = callable.isEmpty()
                ? "It may call no other agent."
                : "The agents it may call are " + callable + ".";
        return "the agent '" + caller.name() + "' may not call '" + MemoryTools.oneLine(wanted)
                + "'. " + may;
    }

    /**
     * The sentence the model chooses a callee from.
     *
     * <p>The opening is the same for every caller; what follows is one line per
     * callee, in the sorted order {@link #callable} fixes, so a model cannot read
     * a preference into the order and two renderings of one agent are the same
     * bytes.
     */
    private static String describe(AgentRegistry agents, Set<String> callable) {
        String opening = "Hand a task to another agent and wait for what it says. The agent"
                + " runs on its own, with its own tools, and does not see this conversation —"
                + " so the task has to stand by itself. Its answer, or the reason it did not"
                + " reach one, comes back as this tool's result.";
        if (callable.isEmpty()) {
            return opening + " This agent may call no other agent.";
        }
        StringBuilder out = new StringBuilder(opening).append("\n\nThe agents you may call:");
        for (String name : callable) {
            out.append("\n- ").append(name).append(summarise(agents, name));
        }
        return out.toString();
    }

    /**
     * One callee's own {@code description:}, as one line.
     *
     * <p>Flattened because an agent file writes that key as a YAML block and
     * keeps it to a readable width, and the line breaks it wraps at are the
     * file's rather than anything this list means by them.
     *
     * <p>{@link AgentRegistry#find} and not {@code get} — see the class javadoc:
     * a callee this process does not serve leaves its name here with nothing
     * after it, rather than ending a run before it has a turn to report.
     */
    private static String summarise(AgentRegistry agents, String name) {
        String description = agents.find(name)
                .map(AgentDefinition::description)
                .orElse("")
                .replaceAll("\\s+", " ")
                .strip();
        return description.isEmpty() ? "" : ": " + description;
    }

    private static Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent", ToolArguments.string(
                "Which agent to hand the task to, by name, from the list in this tool's"
                        + " description."));
        properties.put("task", ToolArguments.string(
                "What that agent should do, in plain language, complete enough to act on"
                        + " without seeing this conversation."));
        // OPTIONAL, and last, so that the two required arguments read first and a
        // delegation with nothing to look at is the shape it always was. The
        // description is the same for a run holding no picture at all -- see
        // JobRuntime.offeredTo, which argues why a schema that appeared and
        // disappeared with an attachment would be a tool no model could learn.
        properties.put("images", ToolArguments.strings(
                "Optional. Ids of pictures to show that agent: ones you are being shown, or"
                        + " ones you were told the id of. The picture has to belong to the"
                        + " project this run is in, and the agent has to be one that can"
                        + " see."));
        return ToolArguments.object(properties, List.of("agent", "task"));
    }

    /**
     * The sentence a run that could not go on because its sub-agent stopped is ended with —
     * {@link SubAgentFailed#sentence}'s, and public so the orchestration engine can end a run the
     * same way when a sub-agent it resumed after an approval stops the same way, spec 2026-09-26
     * §4. One sentence in one place, so the two routes to that ending cannot read differently.
     *
     * @param outcome the sub-agent's own, whose ending {@link #propagates}
     */
    public static String stoppedSentence(String agent, Outcome outcome) {
        String why = outcome.ending() == Outcome.Ending.SESSION_GONE
                ? "the client session it was working in went away"
                : "something it depends on could not be reached";
        return "This run could not go on: the sub-agent '" + agent + "' stopped because "
                + why + ".";
    }

    /**
     * A child that could not reach what it depends on, on its way to ending its
     * parent.
     *
     * <p>An exception rather than a return value because a tool has one way to
     * answer and this is not an answer — the same reason {@link BadArguments} is
     * one. Its visibility is the guard: nothing outside this package can throw
     * one, so the clause {@code JobRuntime} catches it in cannot widen by
     * accident into swallowing an unrelated failure.
     */
    static final class SubAgentFailed extends RuntimeException {

        private final transient String agent;
        private final transient Outcome outcome;

        SubAgentFailed(String agent, Outcome outcome) {
            super("the sub-agent '" + agent + "' ended " + outcome.ending());
            this.agent = agent;
            this.outcome = outcome;
        }

        /**
         * The sentence the parent's outcome reads with. Distinct from every
         * other ending's, because two endings sharing a sentence would be one
         * ending to anybody reading the result.
         *
         * <p><b>And distinct from itself, across the endings it stands for.</b>
         * The parent's ending is {@code SUB_AGENT_FAILED} whichever way the
         * child died — deliberately, since the thing to look at is one agent
         * further down rather than this run's own dependencies — so <b>one level
         * up</b> the words are the only place the difference can survive. One
         * sentence for both would tell an operator whose user closed a laptop to
         * go and check whether an endpoint is up.
         *
         * <p><b>Two levels up they do not, and the first version of this
         * paragraph said "to the top" as though they did.</b> At depth two the
         * middle agent's own outcome is {@code SUB_AGENT_FAILED}, so the ternary
         * below takes its else arm and the top-level sentence says "something it
         * depends on could not be reached" about a closed laptop — the exact
         * reading this arm exists to prevent, one level further out. {@code
         * a_session_two_levels_down_reaches_the_top_in_its_detail_and_not_its_sentence}
         * says so rather than leaving it to be discovered.
         *
         * <p><b>Not keyed on the chain, and the reason is that nothing is lost
         * where it matters.</b> {@link #detail} chains, so the top reads {@code
         * middle: helper: SessionGoneException: …} and an operator has the fact
         * one line down — exactly as they already do for a dead endpoint two
         * levels down, which is the shape this class shipped with. Keying the
         * ternary on the root cause means threading a root ending through every
         * {@code SubAgentFailed}, because a middle's {@link Outcome} says only
         * that its own child failed; matching on the detail string instead is
         * the locale-and-wording guess this project forbids.
         */
        String sentence() {
            return stoppedSentence(agent, outcome);
        }

        /**
         * The child's own detail, under this child's name.
         *
         * <p>Chained rather than replaced, so a failure two levels down arrives
         * as {@code middle: helper: LlmException: …} and an operator can see
         * which agent actually met the dead endpoint. The child's detail is
         * never blank for any of the three propagating endings — {@code
         * JobRuntime.describe} falls back to the exception's type name, and this
         * method supplies the other — so there is no empty-detail branch here to
         * test. (It read "either propagating ending" while there were two.)
         *
         * <p><b>This chain is also the only thing that carries a session that
         * went away past depth one</b>, for the reason {@link #sentence} sets
         * out: the sentence generalises at the first level and stops.
         */
        String detail() {
            return agent + ": " + outcome.detail();
        }
    }
}
