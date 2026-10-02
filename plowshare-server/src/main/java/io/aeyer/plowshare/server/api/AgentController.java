package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.DefineAgentRequest;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionAlreadyExistsException;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.DefinitionWriter;
import io.aeyer.plowshare.server.agents.Definitions;
import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Limits;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import io.aeyer.plowshare.server.requests.RequestedProjectId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The job surface: start a run, ask how it is going, ask how it ended, stop it,
 * and see what this process is holding — and, beside it, the catalogue of what
 * can be started at all.
 *
 * <p><b>{@link #agents} is the one verb here that is not about a run</b>, and it
 * is here rather than in a controller of its own because it is the same
 * resource: an agent is what {@code POST /v1/agents/&#123;name&#125;/runs} names
 * in its path, and a listing of the names that path accepts belongs beside the
 * path that accepts them. That is the same rule the conversation surface follows
 * when it keeps its own reads with its own writes; what it refuses is putting
 * <em>another</em> resource's lifecycle here.
 *
 * <p>The verbs the spec names, and the surfaces that have no agent behind them —
 * {@code POST /v1/curate}, because a curator pass is many runs orchestrated in
 * Java rather than one declared agent, and there is deliberately no {@code
 * curator.md} for {@code agent_run} to name. It is a job in every other way, so
 * it is submitted through the same {@link JobStore} and polled and cancelled
 * through the same endpoints as any other.
 *
 * <p><b>And a listing, which this file used to argue against.</b> A {@code GET
 * /v1/jobs} shipped here briefly and was removed as an endpoint with no caller:
 * no spec named it, no plan listed it, no client tool called it, and it existed
 * because {@code JobStore.jobs()} happened to be public. That was the same
 * speculation this slice kept deleting — the JSON-protocol transport, the
 * degradation seam, {@code curator.md} — and the rule it left behind was <b>add
 * one when something wants it</b>. Something does: the console has a live job
 * view, and that view cannot be built from the event stream, because {@code
 * EventChannelHandler} drops on overflow by design. The rule has not changed;
 * its terms have. {@code ProjectController} records the same turn for the same
 * reason.
 *
 * <p><b>Jobs live in memory and hold lifecycle only.</b> No reaping, no boot
 * sweep, no heartbeat to tell a crash from a slow run, because nothing durable
 * is left holding a lie. A poll on an unknown id says so, which is honest:
 * after a restart the run really is gone, and a curator pass is repeatable.
 * What a run <em>produced</em> is in Postgres — memories in the archive,
 * proposals in the queue — and that is what survives.
 *
 * <p><b>{@link #agents} and {@link #run} resolve through {@link
 * DefinitionResolver} rather than a bare {@link AgentRegistry}</b>, and that is
 * new rather than incidental: {@code DefinitionResolver.forCaller} answers per
 * caller, not per server, so a project's own {@code agents/} and {@code bots/}
 * are in the set both doors check a name against. {@link #run} reaches
 * further, into a connected client's own {@code .plowshare/}, because it
 * already takes a {@code session} for routing the run itself; {@link #agents}
 * deliberately does not take one — see its own javadoc for why a listing
 * naming an arbitrary session would be a different capability than scoping by
 * project, not a corollary of it. {@link DefinitionResolver#forCaller} never
 * answers {@code null} — the boot set, built from the classpath seed at
 * minimum, is always there — so this controller no longer has an "no registry
 * at all" state to hand off to {@code RequestedAgent}: a deployment that
 * serves nothing is one whose resolved registry happens to hold no agents,
 * not one with no registry.
 */
@RestController
public class AgentController {

    private final JobStore jobs;
    private final DefinitionResolver resolver;
    private final ProjectStore projects;
    private final Callers callers;
    private final Runs runs;
    private final Pictures pictures;
    private final Passes passes;
    private final Limits limits;
    private final Definitions definitions;

    /**
     * <b>The six services are injected and no longer built here.</b> Each of
     * {@link Callers}, {@link Runs}, {@link Pictures}, {@link Passes}, {@link
     * Limits} and {@link Definitions} carries {@code @Service} and is
     * component-scanned, so a constructor that built its own copy gave this
     * server two instances of every one: the scanned bean a frame handler
     * injects, and a private copy this controller called.
     *
     * <p>That is harmless only while all six are stateless. The day any one
     * gains a cache, a counter, a metric or an AOP proxy — Spring's {@code
     * Transactional} and {@code Cacheable} both work by proxying the bean the
     * container made, never the one {@code new} made — the two surfaces would
     * answer from different objects with nothing failing, which is the exact
     * failure class this migration exists to close. One decision means one
     * instance of the thing that holds it.
     *
     * <p>{@code curator}, {@code props}, {@code writer}, {@code images} and
     * {@code turns} are gone from this signature: they were here only to build
     * the services, and what a surface takes is the service and not the store.
     * {@code jobs}, {@code resolver} and {@code projects} stay because
     * handlers still read them directly — the job listing, the cancellation
     * and the lookup behind them over {@link JobStore}, and the agent
     * catalogue over the other two, that being the one handler here whose
     * decision has not come down into a service of its own yet.
     */
    public AgentController(
            JobStore jobs,
            DefinitionResolver resolver,
            ProjectStore projects,
            Callers callers,
            Runs runs,
            Pictures pictures,
            Passes passes,
            Limits limits,
            Definitions definitions) {
        this.jobs = jobs;
        this.resolver = resolver;
        this.projects = projects;
        this.callers = callers;
        this.runs = runs;
        this.pictures = pictures;
        this.passes = passes;
        this.limits = limits;
        this.definitions = definitions;
    }

    /**
     * Start one declared agent on one task, and answer with the id at once.
     *
     * <p>An unknown agent is a 400 naming the agents that exist rather than a
     * 404, because the caller is a model choosing from a list and the list is
     * the correction. A 404 would be true and useless.
     *
     * <p><b>{@code session} is optional and is not validated against the
     * registry.</b> A body naming a session nothing has attached to submits and
     * runs: the run simply reaches no client machine, which is what a run with
     * no session does too. Refusing an unattached id would make a client race
     * its own socket — the run may be submitted while the file channel is still
     * opening, and the seam is re-asked on every routing call precisely so that
     * a client connecting mid-run changes what the run can see.
     *
     * <p><b>{@code conversation} is what makes this an utterance</b>, and the
     * only endpoint a turn needs: a turn <em>is</em> a job, so it is started
     * here, polled at {@code GET /v1/jobs/&#123;id&#125;} and stopped at {@code
     * POST /v1/jobs/&#123;id&#125;/cancel} like any other. What changes is the
     * two things {@link Turn} decides — the allowance it spends and the home it
     * runs in.
     *
     * <p><b>And so a body carrying both a conversation and a project is
     * refused.</b> Two answers to one question, where taking either silently is
     * how a person comes to believe a run reached a project it never touched.
     * The refusal is a 400 because the caller can correct it by sending one of
     * them.
     *
     * <p><b>The agent a turn names is judged against the conversation's own
     * project, not the global boot set.</b> {@code request.project()} is
     * exactly the field just refused when a conversation is also named, so it
     * cannot be what builds the {@link DefinitionResolver.Caller} either —
     * {@code Callers.callerForConversation} asks {@link Turn#homeOf} instead,
     * which is the same home {@link Turn#speak} would run the turn in. Without
     * this, an agent defined only in that project's own {@code agents/} or {@code
     * bots/} could never be named in a turn: the unknown-agent refusal would
     * list the boot set while the turn itself, had one gone through, would
     * have run in the project the conversation was actually opened in.
     *
     * <p><b>None of the above is decided here any more.</b> Every sentence of
     * it is {@link Runs#start}'s, including the order — which is the half that
     * cannot be read off a refusal and the half a second surface would
     * otherwise have to guess. This handler binds a body, names the fields and
     * hands them over. Turning the ids a run names into bytes is {@link
     * Pictures}' now as well — it used to be a private method here, on the
     * grounds that its 404 had no type below {@code api} to be said in, and
     * {@link io.aeyer.plowshare.server.faults.NotFoundFault} is that type.
     */
    @PostMapping("/v1/agents/{name}/runs")
    public ResponseEntity<StartedJob> run(
            @PathVariable String name, @RequestBody RunAgentRequest request,
            @RequestAttribute(name = AuthFilter.HANDLE_ATTRIBUTE, required = false) String handle) {

        // Read the body into the fields the decision is made from, and hand
        // the decision over. The ordering, the two refusals and the branch
        // between a submission and an utterance are all Runs.start's, so that
        // a frame reaching the same capability calls one method and gets all
        // of it rather than restating any of it.
        //
        // Who speaks is the account AuthFilter signed this request in as, the
        // same handle agent.run reads off its socket -- absent when auth is off
        // or the credential names no account, which is a person nobody named.
        Runs.Started started = runs.start(
                new Runs.Ask(name, request.task(), request.project(), request.session(),
                        request.conversation(), request.maxTurns(), request.noTurnCap(),
                        request.images(), Speaker.person(handle), handle, request.newConversation()),
                pictures);
        return ResponseEntity.accepted().body(new StartedJob(started.id(), started.agent(), null, started.conversation()));
    }

    /**
     * Start a curator pass over one project.
     *
     * <p>The budget is the whole pass's, across every ruling in it, and it is
     * shared by reference down the tree exactly as a delegating agent's is.
     * Given by the caller or taken from configuration; a pass that spends it
     * stops, says so, and keeps everything it already did.
     *
     * <p><b>Which number that is when the caller names none is not decided
     * here any more.</b> It is {@link Passes#start}'s, with the refusal that
     * there is no global pass and the order between the two — see that class's
     * own javadoc for why a default nothing refuses is the most dangerous
     * thing a second surface could re-derive.
     */
    @PostMapping("/v1/curate")
    public ResponseEntity<StartedJob> curate(@RequestBody CurateRequest request) {
        Passes.Started started = passes.start(request.project(), request.maxModelCalls());
        return ResponseEntity.accepted().body(
                new StartedJob(started.id(), started.agent()));
    }

    /**
     * {@code POST /v1/agents} — write one definition to disk, and answer with
     * what it resolves to.
     *
     * <h2>An operator surface reached through no different a door than any
     * other {@code /v1} route</h2>
     *
     * <p>{@link DefinitionWriter}'s own class javadoc is explicit that nothing
     * in its package hands an agent a reference to it, and this method changes
     * none of that: it is a {@code POST} under {@code /v1}, gated by the same
     * bearer token every other route in this class already is, and reached by
     * no tool a running agent is ever handed. <b>The resolved project id is
     * trusted as given</b> rather than checked against a caller identity — a
     * previous task established that this server enforces nothing narrower
     * than the one operator token over all of {@code /v1} ({@code GET
     * /v1/projects} already lists every project there is), so there is no
     * ownership model here to invent and refuse against. What is still
     * enforced, inside {@link DefinitionWriter#write} itself before a byte is
     * written, is that a write cannot reach a tier it forbids — a project may
     * not define a name {@code AgentsConfig.REQUIRED} reserves, among the rest
     * of that method's own ladder.
     *
     * <h2>None of what follows is decided here any more</h2>
     *
     * <p>Every sentence of it is {@link Definitions#define}'s: that {@code
     * project} is a name and {@code RequestedProjectId.forWrite} is where it
     * becomes the surrogate id the writer takes, refusing a name this server
     * holds no row for rather than folding it into the global tier; that a
     * body omitting {@code name} or {@code text} is a {@code 400} before the
     * writer is ever called, because the writer's own {@code
     * Objects.requireNonNull} would be an opaque {@code 500}; that {@code
     * text} is measured against {@code DefinitionWriter.MAX_DEFINITION_BYTES},
     * which is the writer's ceiling now and so holds for every caller of it
     * rather than for this one; that the resolver's cache is dropped only once
     * a write has landed; and <b>the order all of that is reached in</b>,
     * which is the half no status can show and the half a second surface would
     * otherwise have to guess. This handler binds a body, names the fields,
     * hands them over, and renders what comes back.
     *
     * <h2>Two statuses and one shape, rendered from what came back</h2>
     *
     * <p>{@code 201} for a name that did not exist and {@code 200} for one
     * that did and was replaced because the caller asked for that — the
     * standard reading of a {@code POST} that created against one that
     * overwrote at the caller's own request, and exactly the bit {@link
     * DefinitionWriter.Written#disposition()} already carries. A name that
     * exists and was <b>not</b> asked to be replaced is {@code 409}, and
     * {@link DefinitionAlreadyExistsException} is a row in {@code Faults}
     * answering exactly that — <b>not a catch in this method</b>. Everything
     * else {@link DefinitionWriter#write} refuses — a name that is not a bare
     * filename stem, a file that would not parse, a grant naming a tool this
     * runtime does not bind, a name a project may not take, {@code text} over
     * the ceiling — raises {@link
     * io.aeyer.plowshare.server.faults.CallerFault}, which is another such
     * row. So this method catches nothing, which is the same answer a frame
     * handler gets from the same service without any of it being restated
     * twice. {@code 400} is still the answer for every one of those, because
     * the caller can correct the body and resend it, which is not true of a
     * 409: a body that will never load fails identically on every resend,
     * where one that merely collided with an existing name succeeds the
     * instant {@code overwrite} is added. Merging the two into one status
     * would erase exactly the bit a retrying caller needs.
     *
     * <p><b>An {@link java.io.UncheckedIOException} from the write itself is
     * left to fall through to {@link ApiExceptionHandler}'s catch-all, and
     * that is a decision and not an omission.</b> A disk that cannot be
     * written to is not the caller's mistake to correct by resending anything
     * — it is exactly what that handler's own closing sentence means, "a fault
     * in the server, not in the request" — so {@code 500} is the right answer
     * and this method adds no special case for it.
     *
     * <h2>The body, and the one case that has no view to show</h2>
     *
     * <p>A {@link DefinedAgent}: the freshly resolved {@link AgentView},
     * judged by the identical {@code DefinitionChecks} {@link
     * DefinitionWriter} already applied to the same candidate (see {@code
     * AgentsConfig.definitionChecks}'s own javadoc for why the two cannot
     * disagree), beside {@link DefinedAgent#restartRequired}. <b>A write to
     * the global tier has no such view</b>: {@link DefinitionResolver}'s own
     * top javadoc is explicit that the boot set is never rebuilt, only project
     * tiers are, so a definition written to {@code global/bots/} lands on disk
     * correctly and is not visible to this running process until it restarts.
     * {@link Definitions.Defined} carries either a resolved definition or the
     * reason there is none, never both and never neither, so this method
     * cannot render a stale view for that case by forgetting to branch — there
     * is nothing there to render. See {@link DefinedAgent}'s own javadoc for
     * why the fact is a boolean field and not a status code or a sentence.
     */
    @PostMapping("/v1/agents")
    public ResponseEntity<DefinedAgent> define(@RequestBody DefineAgentRequest request) {
        // Read the body into the fields the decision is made from, and hand
        // the decision over. The ordering, the four refusals, the ceiling, the
        // invalidation and the global tier's whole branch are all
        // Definitions.define's, so that a frame reaching the same capability
        // calls one method and gets all of it rather than restating any of it.
        Definitions.Defined defined = definitions.define(new Definitions.Ask(
                request.project(), request.name(), request.text(), request.overwrite()));

        DefinitionWriter.Disposition disposition = defined.written().disposition();
        HttpStatus status = disposition == DefinitionWriter.Disposition.CREATED
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        AgentView view = defined.restartRequired()
                ? AgentView.disabled(defined.name(), defined.restartReason())
                : AgentView.of(defined.definition(), defined.withheld());
        return ResponseEntity.status(status)
                .body(new DefinedAgent(view, defined.restartRequired()));
    }

    /**
     * {@code GET /v1/agents} — what can be run, and what each one may do.
     *
     * <p>The console's agent picker, and the one screen on this server that is a
     * list of choices rather than a list of things that happened. <b>It carries
     * no system prompt</b>; {@link AgentView} owns that decision and the test
     * that holds it.
     *
     * <p><b>Only the exported ones.</b> This is a list of choices, and every
     * entry on it has to be a choice that works: a private agent in the picker
     * is a row that answers 400 when it is clicked. {@code exported} gates this
     * listing and {@code POST /v1/agents/&#123;name&#125;/runs} together, from
     * the same field, so the two cannot come to disagree about what is
     * offerable — which is the failure a filter written only here would allow.
     *
     * <p>Ordered by {@link AgentRegistry#exportedNames()}, which answers a
     * sorted set. A picker that reordered itself between loads would make a
     * person hunt for the entry they clicked last time, and the ordering is the
     * registry's rather than this method's so that the messages naming the
     * agents that exist and the list offering them cannot disagree.
     *
     * <p><b>A disabled agent is on this list, marked {@code served: false} with
     * its reason, and is never merely absent.</b> That is what the disable rule
     * costs and what pays for it: an agent that used to stop the boot now stops
     * nothing, so the one screen a person looks at has to say so. {@code
     * exported} deliberately does not filter these — a file that could not be
     * parsed has no {@code exported} to read, and filtering would make the least
     * readable definitions the ones that vanish.
     *
     * <p><b>A deployment that serves no agents answers an empty list and not a
     * refusal</b>, which is the class javadoc's promise kept on the endpoint
     * where it is easiest to break. {@link #run} answers a 400 for an agent that
     * does not exist because naming one is a caller's mistake it can correct;
     * asking what this server runs is not a mistake, and a console met with a
     * refusal on its first screen would report the deployment broken when it is
     * merely empty.
     *
     * <p><b>Scoped by project, and global is a scope of its own.</b> {@code
     * project} is the same field {@link #run} already reads off a request
     * body, taken here as a query parameter because a {@code GET} has no body
     * to carry it in. It resolves through {@link DefinitionResolver#forCaller}
     * exactly as a run does, so <b>the listing answers with the server-side
     * resolved set for that project</b> — its own {@code agents/} and {@code
     * bots/}, layered over the global tier and the shipped seed — because a
     * listing that answered from the boot set alone would answer a
     * different, narrower question under the same name.
     *
     * <p><b>A connected client's own {@code .plowshare/} is not in that set,
     * and that is a real gap and not a rounding error.</b> {@link #run}
     * reaches it, because a submission carries {@code session}; this door
     * deliberately does not, for the reason the next paragraph gives — see
     * this class's own javadoc for the same asymmetry stated once, between
     * the two doors, rather than repeated here. The consequence has to be
     * named plainly: a bot defined only in a client's own machine is
     * runnable — {@code POST /v1/agents/&#123;name&#125;/runs} still resolves
     * it by name, through that session — and it will never appear in this
     * listing, because this door cannot identify which client is asking
     * without the ownership model it deliberately does not have. "What can
     * be run" at the top of this method means what the server side can see;
     * a client-local bot is a real, nameable exception to it.
     *
     * <p><b>There is deliberately no {@code session} parameter here, though
     * {@link #run} takes one.</b> A run's session only ever changes which
     * machine <em>that one job</em> reaches, and naming somebody else's live
     * session costs the caller nothing more than a run that fails to land
     * anywhere useful. A listing is a read, and the same parameter here would
     * let any caller holding the one operator token enumerate <em>another
     * live session's</em> local {@code .plowshare/} — its agent and bot names,
     * tools and scopes — over a socket that session opened and a directory
     * this server never touches on its own account. That is a new capability
     * this endpoint would be handing out, not a corollary of scoping by
     * project, so the caller is given {@code Caller(projectId, null)} always:
     * exactly {@link DefinitionResolver.Caller}'s "the console's ordinary
     * case", every time. A console that wants its <em>own</em> session's local
     * definitions reflected in what it offers has no way to ask for that from
     * this door — {@code session} on {@link #run} is unaffected, since a
     * run's own machine was never a fact this listing needed to expose.
     */
    @GetMapping("/v1/agents")
    public ResponseEntity<List<AgentView>> agents(
            @RequestParam(required = false) String project) {
        DefinitionResolver.Caller caller = new DefinitionResolver.Caller(
                RequestedProjectId.forListing(projects, project), null);
        return ResponseEntity.ok(
                AgentRows.of(resolver.forCaller(caller), callers, resolver.defaultBot(caller)));
    }

    /**
     * {@code GET /v1/jobs} — every job this process is holding.
     *
     * <p><b>This is what the console's live job view reconciles against</b>, and
     * that is why it exists rather than the console keeping its own list from the
     * event stream. {@code EventChannelHandler} publishes into a bounded queue and
     * drops on overflow by design — the stream is droppable and not durable — so a
     * console that treated events as a log would render a job whose events were
     * dropped as a job that did nothing. What a run came to is here.
     *
     * <p><b>Scoped to what {@link JobStore} holds, which is this process's jobs
     * and not history.</b> Nothing reaps them, so a finished run stays listed
     * until the process ends and then is gone entirely — the asymmetry the class
     * javadoc above argues for, and the one thing a console reading this screen
     * has to understand about it. A restart is not an empty server; it is a server
     * that has forgotten, and what those runs <em>produced</em> is in Postgres.
     *
     * <p>Ordered by {@link JobStore#jobs()}, newest last, and neither filtered nor
     * paged. A limit here would be a number invented rather than measured, and the
     * bound on this list is the one thing above: a process lives as long as it
     * lives, and every job it started is in the answer.
     */
    @GetMapping("/v1/jobs")
    public ResponseEntity<List<JobView>> jobs() {
        return ResponseEntity.ok(jobs.jobsFor(io.aeyer.plowshare.server.information.InformationCaller.account()).stream().map(JobView::of).toList());
    }

    /**
     * How a run is going, and how it ended once it has.
     *
     * <p>One endpoint for both questions. {@code agent_poll} and {@code
     * agent_result} are two renderings of this, not two states of the server:
     * a job that has finished carries its outcome from the moment it finishes,
     * and there is nothing a second call could learn.
     */
    @GetMapping("/v1/jobs/{id}")
    public ResponseEntity<JobView> job(@PathVariable String id) {
        return ResponseEntity.ok(JobView.of(find(id)));
    }

    /**
     * Ask a run to stop at its next turn boundary.
     *
     * <p>Answers with the job rather than with a bare acknowledgement, and the
     * job still reads {@code RUNNING}: a cancel is a request the loop honours
     * between turns, not a kill, and a caller shown {@code DONE} here would be
     * shown a state that is not yet true. {@code cancelRequested} is what says
     * the request landed.
     *
     * <p>Cancelling a finished job is not an error. It changes nothing, and
     * refusing it would make a caller race the run it is trying to stop.
     */
    @PostMapping("/v1/jobs/{id}/cancel")
    public ResponseEntity<JobView> cancel(@PathVariable String id) {
        Job job = find(id);
        jobs.cancel(id);
        return ResponseEntity.ok(JobView.of(job));
    }

    /**
     * {@code POST /v1/jobs/{id}/limits} — move a running job's ceilings without
     * restarting it.
     *
     * <h2>The interaction this exists for</h2>
     *
     * <p><b>"This run is nearly out — give it twenty more"</b>, in place of the
     * run stopping, the question being retyped and the whole thing being paid
     * for a second time. The run reads both bounds at every turn boundary and
     * the budget at every model call, so what this writes is picked up by the
     * run that is already going: there is nothing to restart and nothing to
     * resume.
     *
     * <p><b>It is beside {@link #cancel} because it is the same kind of verb</b>
     * — something a person does to a run while it is running — and it answers
     * with the job for the same reason that one does: what a caller wants next
     * is the state they have just changed.
     *
     * <h2>What it refuses, and the one that is not obvious</h2>
     *
     * <p>A body naming nothing is refused: it would answer 200 having done
     * nothing, which reads to whoever sent it as a limit that was moved.
     *
     * <p><b>A budget cannot be lowered below what has already been spent.</b>
     * {@code Budget.changeTo} permits it and defines what it means — the tree
     * stops at its next boundary — and this door still refuses it, for a reason
     * that is about the archive rather than about the run: a conversation's row
     * carries {@code budget_total} and {@code budget_spent} with {@code
     * conversations_spent_within_budget} between them, so a total below the
     * spending is a turn whose write-back Postgres would refuse, and the
     * conversation would lose the record of what it spent. <b>The verb for
     * stopping a run is {@link #cancel}</b>, which stops it at the same boundary
     * and leaves the accounting true, and the message says so.
     *
     * <p>A job that has finished is refused rather than silently accepted, which
     * is the opposite of what {@link #cancel} does with one — and the difference
     * is what the caller is asking for. Cancelling a finished run is asking for
     * a state it is already in; raising the ceiling on one is asking for
     * something that cannot happen, and answering 200 would say it had.
     *
     * <p><b>A job that is not one agent's run has no limits here to move.</b> A
     * curator pass builds its own budget and shares it across a whole pass;
     * {@code JobStore} never sees it, so there is nothing on the handle to move
     * and the refusal says which kind of job it is rather than pretending.
     */
    @PostMapping("/v1/jobs/{id}/limits")
    public ResponseEntity<JobView> limits(
            @PathVariable String id, @RequestBody AdjustLimitsRequest request) {

        // Every sentence above is decided by Limits.move, including the order
        // the two mutations happen in -- which is the half that cannot be read
        // off a refusal, because a body whose budget is refused and whose cap
        // was applied anyway answers with a refusal either way.
        jobs.getFor(id,io.aeyer.plowshare.server.information.InformationCaller.account());
        Job job = limits.move(
                id, request.maxTurns(), request.noTurnCap(), request.maxModelCalls());
        return ResponseEntity.ok(JobView.of(job));
    }

    /**
     * {@code id}, and nothing else — the 404 that used to be decided here is
     * now decided by {@link JobStore#get}, which is the method that knows.
     *
     * <p><b>The blocker was a missing type, and it was invented.</b> This
     * method used to catch {@link JobStore#get}'s {@code
     * IllegalArgumentException} and restate it as a {@link NotFoundException},
     * on the ruling that moving the decision down would mean either dragging an
     * {@code api} exception type into {@code agents/} or inventing a {@code
     * faults}-package "not found" type for one caller. {@link
     * io.aeyer.plowshare.server.faults.NotFoundFault} is that type, and the
     * caller that justified it is not this one: it is the frame handler that
     * will call {@link JobStore#get} without passing through any controller,
     * and would have been answered 500 for a misspelled id.
     *
     * <p>What is left is a rename of {@code jobs.get}, kept because three
     * handlers read better for it and because the name says which store is
     * being asked. The status is unchanged: {@code Faults} maps {@code
     * NotFoundFault} to the same {@code Code.NOT_FOUND} it maps {@link
     * NotFoundException} to, and {@code ApiExceptionHandler} shapes both
     * through one {@code toResponse}.
     */
    private Job find(String id) {
        return jobs.getFor(id,io.aeyer.plowshare.server.information.InformationCaller.account());
    }
}
