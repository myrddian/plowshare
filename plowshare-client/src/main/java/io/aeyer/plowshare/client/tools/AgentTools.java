package io.aeyer.plowshare.client.tools;

import static io.aeyer.plowshare.client.tools.MemoryTools.oneLine;
import static io.aeyer.plowshare.client.tools.Schemas.object;
import static io.aeyer.plowshare.client.tools.Schemas.string;

import io.aeyer.plowshare.client.ClientPresence;
import io.aeyer.plowshare.client.ServerClient;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The job surface and the promotion queue, as MCP tools.
 *
 * <p>Four verbs for a run — start it, ask how it is going, ask how it ended,
 * stop it — plus the two a person needs to answer the queue, plus the one that
 * starts a curator pass. Nothing here decides anything; every one is a thin
 * rendering of one HTTP call, because the judgements all live on the server
 * where the archive and the model are.
 *
 * <h2>A truncated run is never dressed as an answer</h2>
 *
 * <p>The rule this whole surface exists to keep. Excalibur returned a model's
 * own deliberation as an answer when a run ran out of turns, and a failed tool
 * call came back looking like a considered reply. So {@code agent_result} reads
 * {@code answered} <em>before</em> it renders {@code text}, and a run that
 * stopped gets a sentence saying which way it stopped — never the text under a
 * heading that implies it decided. The ending arrives as a string and this
 * renderer does not enumerate them, so a server that grows one reaches a reader
 * without a change here; it said "which of the five ways" until the server had
 * six.
 *
 * <h2>A job is a handle on a run, not a container for its output</h2>
 *
 * <p>Concluded work goes where output lives: memories in the archive,
 * proposals in the queue, as the agent produces them. Jobs live in the server's
 * memory and are gone after a restart, deliberately — nothing durable is left
 * holding a lie about a run that is not running. A poll on an unknown id says
 * so, which is honest, and a curator pass is repeatable.
 *
 * <h2>Every line at column zero is one this renderer wrote</h2>
 *
 * <p>The same rule as {@code MemoryTools}, and it matters here for the same
 * reason and one more: an outcome's {@code text} is a <em>model's</em> answer,
 * and a run's {@code detail} carries an exception message. Both reach column
 * zero, both go through {@link MemoryTools#oneLine} or {@link
 * MemoryTools#quote}.
 */
public final class AgentTools {

    static final String NO_PROPOSALS = "(nothing waiting)";

    private final ServerClient server;

    /** What this process roots, or null for a surface with no session behind it.
     *
     *  <p>Nullable rather than always present, and the two are different clients
     *  rather than one client half-configured: {@code cli.Plowshare} holds its
     *  own {@link io.aeyer.plowshare.client.SessionClient} and never registers
     *  these tools, and a test of what this renders has nothing to serve. */
    private final ClientPresence presence;

    /** A surface with no machine behind it: every run it starts reaches the
     *  filesystems the server itself can see, and nothing else. */
    public AgentTools(ServerClient server) {
        this(server, null);
    }

    /**
     * @param presence what this process roots, and therefore which machine a run
     *     started here reaches. See {@link #run}
     */
    public AgentTools(ServerClient server, ClientPresence presence) {
        this.server = server;
        this.presence = presence;
    }

    public void registerOn(ToolRegistry registry) {
        registry.register("agent_run", RUN_DESCRIPTION, runSchema(), this::run);
        registry.register("agent_poll", POLL_DESCRIPTION, jobSchema(), this::poll);
        registry.register("agent_result", RESULT_DESCRIPTION, jobSchema(), this::result);
        registry.register("agent_cancel", CANCEL_DESCRIPTION, jobSchema(), this::cancel);
        registry.register("memory_curate", CURATE_DESCRIPTION, curateSchema(), this::curate);
        registry.register(
                "memory_proposals", PROPOSALS_DESCRIPTION, proposalsSchema(), this::proposals);
        registry.register("memory_resolve", RESOLVE_DESCRIPTION, resolveSchema(), this::resolve);
    }

    // --- the tools -----------------------------------------------------------------

    /**
     * Start a run, on whatever machine this process is serving.
     *
     * <p><b>The session is this client's own, and what it decides is narrower
     * than it looks.</b> Server-side, {@code AgentsConfig.runProviders} routes a
     * <em>named project</em> to whichever presence roots it and says in as many
     * words that {@code sessionId} "is not consulted at all on that path" — so
     * what makes a project run reach this machine is the file channel {@link
     * ClientPresence} opened, not the id on this submission. The id decides the
     * <b>global tier</b>, which has no place to root: global is the absence of a
     * project, so the only machine such a run can mean is the one it came from,
     * and passing none is what used to make every global run from here reach the
     * server's own disks.
     *
     * <p>Both halves are therefore needed and neither substitutes for the other:
     * the channel roots the project, and this id is what a placeless run has to
     * go on. A process nobody has asked to root anything passes null and gets the
     * old behaviour — a smaller capability rather than an empty one.
     *
     * <p>The refusal in between is {@code SessionClient}'s and is reached rather
     * than restated: a client rooting a project whose channel has gone does not
     * start the run at all, because the server accepts a session id it has never
     * seen attached and would execute against its own disks reporting nothing
     * amiss.
     */
    public Object run(Map<String, Object> args) {
        String agent = required(args, "agent");
        String task = required(args, "task");
        String project = project(args);
        String session = presence == null ? null : presence.sessionForRun(agent);

        ServerClient.StartedJob started = ask(() -> server.run(agent, task, project,
                session,
                // No conversation, and this one IS a decision rather than an
                // absence. agent_run starts a run on its own behalf, and a tool
                // that could put a turn into somebody's conversation would be
                // spending an allowance a person set for their own utterances, on
                // one they did not make.
                null));
        return "Started " + oneLine(started.id()) + ", running '" + oneLine(started.agent())
                + "' over the " + tier(project) + " archive.\n\nIt is running now and this call"
                + " did not wait for it. Ask agent_poll whether it has finished, then"
                + " agent_result for how it ended. Jobs live in the server's memory: a restart"
                + " loses the handle, though anything the run wrote to the archive or the queue"
                + " survives.";
    }

    public Object curate(Map<String, Object> args) {
        String project = requiredProject(args);
        Integer budget = integer(args, "max_model_calls");

        ServerClient.StartedJob started = ask(() -> server.curate(project, budget));
        return "Started " + oneLine(started.id()) + ", a curator pass over the project '"
                + oneLine(project) + "'.\n\nIt lists that project's memories, drops the ones"
                + " already ruled on and the ones global holds word for word, and puts each"
                + " survivor to a judge. Confident rulings are applied; the rest are filed for"
                + " a person to answer with memory_proposals and memory_resolve. Poll it with"
                + " agent_poll and read it with agent_result.";
    }

    public Object poll(Map<String, Object> args) {
        ServerClient.JobStatus job = ask(() -> server.job(required(args, "job_id")));

        if (job.outcome() != null) {
            return heading(job) + " has finished. Read how it ended with agent_result.";
        }
        String cancelling = job.cancelRequested()
                ? " It has been asked to stop and will do so at its next turn boundary."
                : "";
        return heading(job) + " is still running." + cancelling
                + " Nothing has been concluded yet; poll again in a moment.";
    }

    /**
     * How the run ended, with the ending read before the text.
     *
     * <p>The order is the whole design. An {@code ANSWERED} run's text is its
     * answer; every other ending's text is an account of a run that stopped, and
     * rendering the two the same way is exactly the failure this project exists
     * to avoid.
     */
    public Object result(Map<String, Object> args) {
        ServerClient.JobStatus job = ask(() -> server.job(required(args, "job_id")));

        if (job.outcome() == null) {
            return heading(job) + " has not finished. There is no result to read yet — poll it"
                    + " with agent_poll.";
        }
        ServerClient.RunOutcome outcome = job.outcome();
        StringBuilder out = new StringBuilder(heading(job));
        out.append(outcome.answered() ? " answered." : " stopped without answering.");
        // "steps" and not "turns", which is the one word of this sentence that
        // changed and the reason it did: a step is one model call plus the tool
        // results it asked for, and a turn is one thing a person said. This
        // result is read by a foreign harness's model, so the noun is the
        // server's own — see `Outcome` — rather than this renderer's.
        out.append(" It took ").append(outcome.steps())
                .append(outcome.steps() == 1 ? " step and " : " steps and ")
                .append(outcome.modelCalls())
                .append(outcome.modelCalls() == 1 ? " model call." : " model calls.");
        out.append("\n\nHow it ended: ").append(oneLine(outcome.ending())).append('\n');
        if (!outcome.answered()) {
            out.append("This is NOT an answer. The run stopped, and what follows is an account"
                    + " of what it managed to do — not a conclusion it reached.\n");
        }
        if (outcome.detail() != null && !outcome.detail().isBlank()) {
            out.append("Detail: ").append(oneLine(outcome.detail())).append('\n');
        }
        out.append('\n').append(MemoryTools.quote(
                outcome.text() == null || outcome.text().isBlank()
                        ? "(the run produced no text)"
                        : outcome.text()));
        return out.toString();
    }

    public Object cancel(Map<String, Object> args) {
        ServerClient.JobStatus job = ask(() -> server.cancelJob(required(args, "job_id")));

        if (job.outcome() != null) {
            return heading(job) + " had already finished, so there was nothing to stop. Read how"
                    + " it ended with agent_result.";
        }
        return heading(job) + " has been asked to stop, and will do so at its next turn"
                + " boundary rather than immediately — a turn already in flight is paid for"
                + " either way. It still reads as running until it gets there. Whatever it"
                + " already wrote to the archive or the queue stands.";
    }

    public Object proposals(Map<String, Object> args) {
        String project = project(args);
        List<ServerClient.ProposalRow> waiting = ask(() -> server.proposals(project));

        if (waiting.isEmpty()) {
            return NO_PROPOSALS + " — nothing in the " + tier(project) + " archive is waiting on"
                    + " a decision.";
        }
        // new StringBuilder(int) is the CAPACITY constructor, not a content one,
        // so `new StringBuilder(waiting.size())` silently dropped the count and
        // opened every listing with " proposals waiting on…". Found by
        // a_queue_with_several_proposals_counts_them_in_the_plural, which is the
        // only test that reads the first word of this string.
        StringBuilder out = new StringBuilder()
                .append(waiting.size())
                .append(waiting.size() == 1 ? " proposal waiting" : " proposals waiting")
                .append(" on the " + tier(project) + " archive. Each one asks whether a memory"
                        + " should be copied into the global archive, which every project"
                        + " reads.\n");
        for (ServerClient.ProposalRow row : waiting) {
            out.append('\n').append(oneLine(row.id())).append("  ")
                    .append(oneLine(row.action())).append("  ").append(oneLine(row.memoryId()))
                    .append("\n    why: ").append(oneLine(row.reason()))
                    .append("\n    asked: ").append(row.createdAt())
                    // Null is rendered rather than skipped. A row filed before
                    // the server had a proposed_by column has no name to give,
                    // and a line that simply stopped after the instant would
                    // read as one asked by nobody rather than one whose asker
                    // was never recorded — the same collapse this field exists
                    // to undo one level down.
                    .append(" by ").append(row.proposedBy() == null
                            ? "somebody this queue did not record"
                            : oneLine(row.proposedBy()))
                    .append('\n');
        }
        out.append("\nRead the memory itself with memory_read before deciding — the reason above"
                + " is one sentence, written by whoever the line above names. Settle one with"
                + " memory_resolve.");
        return out.toString();
    }

    public Object resolve(Map<String, Object> args) {
        String id = required(args, "proposal_id");
        String decision = required(args, "decision").trim().toLowerCase(Locale.ROOT);
        boolean accept = switch (decision) {
            case "accept" -> true;
            case "reject" -> false;
            default -> throw new IllegalArgumentException(
                    "'decision' must be \"accept\" or \"reject\", not \"" + oneLine(decision)
                            + "\". Accepting promotes the memory into the global archive;"
                            + " rejecting records that it belongs where it is, which is what"
                            + " stops it being proposed again.");
        };
        String reason = optional(args, "reason");
        String by = required(args, "by");

        ServerClient.Resolution settled = ask(() -> server.resolve(id, accept, reason, by));
        if (!accept) {
            return oneLine(settled.proposal().id()) + " is rejected. "
                    + oneLine(settled.proposal().memoryId()) + " stays where it is, and it will"
                    + " not be proposed again: a rejection is remembered, which is what stops a"
                    + " later pass asking the same question.";
        }
        StringBuilder out = new StringBuilder(oneLine(settled.proposal().id()))
                .append(" is accepted. ").append(oneLine(settled.proposal().memoryId()))
                .append(" was promoted as ").append(oneLine(settled.promotedId()))
                .append(", which every project now reads; the project's own record is retired"
                        + " and points at the new one.");
        if (!settled.demoted().isEmpty()) {
            out.append("\n\nThe global index was full, so these fell out of it: ")
                    .append(String.join(", ", settled.demoted().stream()
                            .map(MemoryTools::oneLine).toList()))
                    .append(". They are cold, not deleted: still readable by id and still found"
                            + " by recall — falling out of the index is not deletion.");
        }
        return out.toString();
    }

    // --- descriptions ----------------------------------------------------------------

    static final String RUN_DESCRIPTION = """
            Start one of this server's agents on a task, and get back a job id \
            straight away. The run happens on the server; this call does not \
            wait for it.

            Agents are files an operator deploys, so which ones exist is a \
            property of this deployment — if you name one that is not there, \
            the answer lists the ones that are.

            `project` fixes which archive the run reads, and the agent cannot \
            widen it: an agent runs against the tier its job was started for. \
            Omit it for the memories that hold everywhere.

            Poll with agent_poll and read the ending with agent_result. Jobs \
            live in the server's memory: a restart loses the handle, though \
            anything the run wrote to the archive or the queue survives.""";

    static final String POLL_DESCRIPTION = """
            Ask whether a run has finished. Fast and free — no model runs.

            Says running or finished, and nothing about what it concluded: read \
            that with agent_result once it has finished. An id this server does \
            not know is an error rather than "not finished", because after a \
            restart the run really is gone and waiting for it would be waiting \
            forever.""";

    static final String RESULT_DESCRIPTION = """
            Read how a run ended and what it produced.

            It tells you FIRST whether the run answered or stopped, and the \
            difference is the whole point: a run that ran out of turns, spent \
            its budget, was cancelled, or could not reach something it needed \
            has text describing what it managed — not a conclusion. Only an \
            answered run's text is an answer.

            What a run concluded and wrote down is in the archive and the \
            proposal queue, not here: read those with memory_read and \
            memory_proposals.""";

    static final String CANCEL_DESCRIPTION = """
            Ask a run to stop.

            It stops at its next turn boundary rather than immediately — a turn \
            already in flight is paid for either way — so the job still reads as \
            running for a moment afterwards. Whatever it already wrote to the \
            archive or the queue stands; cancelling does not undo anything.""";

    static final String CURATE_DESCRIPTION = """
            Review one project's memories and decide which of them hold for \
            every project, not just this one. Starts a job and returns its id; \
            the pass runs on the server.

            It skips anything already ruled on and anything the global archive \
            already holds word for word, then puts each survivor to a judge. \
            Confident rulings are applied at once — the memory is copied into \
            the global archive and the project's record retired — and everything \
            the judge is unsure about is filed for a person, which you can read \
            with memory_proposals.

            This costs model calls, roughly two per memory considered. \
            `max_model_calls` bounds the whole pass; omit it for the server's \
            configured budget. A pass that runs out says so and keeps \
            everything it already did.""";

    static final String PROPOSALS_DESCRIPTION = """
            List the promotions waiting on a decision: memories a curator thinks \
            may belong in the global archive but would not settle by itself. \
            Fast and free — no model runs.

            Pass `project` for one project's queue; omit it for the global one. \
            Settled proposals are not listed — a rejection is remembered so the \
            curator stops asking, not so you keep seeing it.

            The reason on each is one sentence written by whoever asked. Read \
            the memory itself with memory_read before deciding.""";

    static final String RESOLVE_DESCRIPTION = """
            Settle one waiting proposal, once.

            `decision` is "accept" or "reject". Accepting copies the memory into \
            the global archive, which every project reads, and retires the \
            project's own record with a link forward to the new one. Rejecting \
            records that the claim belongs where it is — and that record is the \
            point: it is what stops the same memory being proposed again next \
            week, and the week after.

            Neither is reversible and a settled proposal is never re-opened, so \
            read the memory with memory_read first. Say who you are in `by`; it \
            is what tells your decision from the curator's own on a row read \
            months later.

            If the memory has been superseded or invalidated since the proposal \
            was filed, accepting is refused rather than applied: a promoted \
            memory that had stopped being true would be read by every project.""";

    // --- schemas -----------------------------------------------------------------------

    private static Map<String, Object> runSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent", string("Which agent to run, by name."));
        properties.put("task", string("What it is being asked to do, in plain language."));
        properties.put("project", string(
                "The archive it reads from. Omit for the memories that hold everywhere."));
        return object(properties, List.of("agent", "task"));
    }

    private static Map<String, Object> jobSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("job_id", string("The job id, as agent_run returned it."));
        return object(properties, List.of("job_id"));
    }

    private static Map<String, Object> curateSchema() {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("type", "integer");
        budget.put("description", "How many model calls the whole pass may spend. Omit for the"
                + " server's configured budget. Roughly two calls per memory considered.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", string("The project whose memories to review."));
        properties.put("max_model_calls", budget);
        return object(properties, List.of("project"));
    }

    private static Map<String, Object> proposalsSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project", string(
                "Whose queue to list. Omit for the global one."));
        return object(properties, List.of());
    }

    private static Map<String, Object> resolveSchema() {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "string");
        decision.put("enum", List.of("accept", "reject"));
        decision.put("description", "\"accept\" promotes the memory into the global archive;"
                + " \"reject\" records that it belongs where it is.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("proposal_id", string("The proposal, as memory_proposals showed it."));
        properties.put("decision", decision);
        properties.put("reason", string(
                "Why. Recorded on the proposal, and it is what a later reader sees."));
        properties.put("by", string("Who is deciding — you."));
        return object(properties, List.of("proposal_id", "decision", "by"));
    }


    // --- rendering and arguments ---------------------------------------------------

    private static String heading(ServerClient.JobStatus job) {
        return oneLine(job.id()) + " ('" + oneLine(job.agent()) + "')";
    }

    private static String tier(String project) {
        return project == null ? "global" : "project '" + oneLine(project) + "'";
    }

    private static String required(Map<String, Object> args, String name) {
        String value = optional(args, name);
        if (value == null) {
            throw new IllegalArgumentException("'" + name + "' is required and must not be empty");
        }
        return value;
    }

    private static String optional(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        // isBlank, deliberately, where MemoryTools.required uses isEmpty and
        // documents that choice. A whitespace-only job_id is refused here and
        // would reach the server there. This is the better rule for an id the
        // server looks up by equality; it is recorded rather than silently
        // divergent because the two files are copies and a reader comparing
        // them must not conclude one of us is a typo.
        return text.isBlank() ? null : text;
    }

    /**
     * A project a caller may leave out entirely, but may not send empty.
     *
     * <p>{@code MemoryTools} draws the same line and for the same reason: an
     * empty string is what an unset field or a stray default sends, and reading
     * it as "global" would silently widen the tier a run or a listing reaches.
     */
    private static String project(Map<String, Object> args) {
        Object value = args.get("project");
        if (value == null) {
            return null;
        }
        String text = value.toString();
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "'project' was sent empty. Omit it entirely for the global archive — an"
                            + " empty project is not the global tier.");
        }
        return text;
    }

    /** Curating needs one: there is no global pass, because promotion is what
     *  puts a project's memory into global and a pass over global would have
     *  nowhere to promote to. */
    private static String requiredProject(Map<String, Object> args) {
        String project = project(args);
        if (project == null) {
            throw new IllegalArgumentException(
                    "'project' is required. There is no global curator pass: promotion is what"
                            + " puts a project's memory into the global archive, so a pass over"
                            + " global would have nowhere to promote to.");
        }
        return project;
    }

    private static Integer integer(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "'" + name + "' should be a whole number, not " + value);
        }
    }

    private <T> T ask(Call<T> call) {
        try {
            return call.get();
        } catch (IOException unreachable) {
            throw new MemoryTools.ServerUnreachableException(
                    "could not reach the Plowshare server at " + server.baseUrl() + " — "
                            + describe(unreachable)
                            + ". This says nothing about the run: the server was never asked."
                            + " Check the server is running, then try again.",
                    unreachable);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.toString() : message;
    }

    @FunctionalInterface
    private interface Call<T> {
        T get() throws IOException;
    }
}
