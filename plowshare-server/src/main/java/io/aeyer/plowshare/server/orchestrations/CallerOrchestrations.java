package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools.Actions;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools.Offer;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Builds and authorises the tools an orchestration's caller holds. */
public final class CallerOrchestrations {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOT_OWNED =
            "No orchestration with that id is owned by this account.";
    private static final String NOT_A_CHILD = "That orchestration is not one this run started; a"
            + " conductor answers, reads and cancels only the runs it started itself.";
    /** Package-visible so {@code CallerOrchestrationsTest} can pin the exact sentence. */
    static final String ROOT_CANNOT_WAIT = "A root caller cannot wait for an orchestration:"
            + " nothing will resume this conversation when the run reports. It is started in the"
            + " background regardless of 'wait'; follow it with orchestration_status.";

    /** Package-visible for the same reason. Told only when {@code wait} was asked for and lost —
     *  see {@link Orchestrations.Started#waiting()}. */
    static final String CHILD_ALREADY_ENDED_BEFORE_THE_WAIT = "wait was requested, but the child"
            + " had already ended before the wait could take: read it with orchestration_status.";

    private final OrchestrationResolver resolver;
    private final Callers callers;
    private final Orchestrations orchestrations;
    private final OrchestrationCancel cancel;
    private final OrchestrationStore store;
    private final TodoLists todos;

    public CallerOrchestrations(OrchestrationResolver resolver, Callers callers,
            Orchestrations orchestrations, OrchestrationCancel cancel, OrchestrationStore store,
            TodoLists todos) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.orchestrations = Objects.requireNonNull(orchestrations, "orchestrations");
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        this.store = Objects.requireNonNull(store, "store");
        this.todos = Objects.requireNonNull(todos, "todos");
    }

    /** Extras for this caller, or none when its definition grants no orchestration. */
    public RunExtras.Extras forRun(RunExtras.Context context) {
        if (context.definition() == null || context.definition().orchestrations().isEmpty()) {
            return RunExtras.Extras.NONE;
        }
        Map<String, OrchestrationDefinition> granted = granted(context.definition(),
                context.home(), context.conversationId(), context.sessionId());
        List<Offer> offers = granted.values().stream()
                .map(definition -> new Offer(definition.name(), definition.description(),
                        definition.stages().stream().map(OrchestrationDefinition.Stage::id).toList()))
                .toList();
        Actions actions = actions(context, granted);
        // context.end(): this caller's tools trip the run's own TurnEnd -- the one JobRuntime made
        // before asking any provider (spec 2026-09-27 §2) -- rather than a null the loop would
        // have to fall back and mint a second, unreachable one for.
        return new RunExtras.Extras(
                CallerOrchestrationTools.forRun(offers, actions), context.end(), false);
    }

    /** The orchestrations this definition is granted and can reach from here, in grant order. */
    public Map<String, OrchestrationDefinition> granted(AgentDefinition definition, Home home,
            String conversation, String session) {
        DefinitionResolver.Caller caller = conversation == null
                ? callers.callerFor(home.project(), session)
                : callers.callerForConversation(conversation, session);
        Map<String, OrchestrationDefinition> reachable = resolver.forCaller(caller);
        Map<String, OrchestrationDefinition> granted = new LinkedHashMap<>();
        for (String name : new LinkedHashSet<>(definition.orchestrations())) {
            OrchestrationDefinition found = reachable.get(name);
            if (found != null) {
                granted.put(name, found);
            }
        }
        return granted;
    }

    private Actions actions(RunExtras.Context context,
            Map<String, OrchestrationDefinition> definitions) {
        return new Actions() {
            @Override
            public String start(String name, String request, String suppliedContext,
                    boolean wait) {
                OrchestrationDefinition definition = definitions.get(name);
                if (definition == null) {
                    return "This run is not granted the orchestration '" + name + "'.";
                }
                Optional<OrchestrationRecord> conductor = conductorRun(context);
                if (conductor.isPresent()) {
                    Orchestrations.Started started = orchestrations.startNested(
                            conductor.get().id(), definition, request, suppliedContext, wait);
                    if (started.refusal() != null) {
                        return started.refusal();
                    }
                    // wait was asked for but did not take: the child was already over by the time
                    // it would have, so nothing is going to wake this conductor — Started.waiting
                    // is what actually happened, not what wait asked for, and this is the one
                    // place that reads it.
                    String note = wait && !started.waiting()
                            ? CHILD_ALREADY_ENDED_BEFORE_THE_WAIT : null;
                    return renderHandle(started.run(), definition, note);
                }
                OrchestrationRecord run = orchestrations.start(new Orchestrations.Start(definition,
                        context.home(), request, suppliedContext, context.conversationId(),
                        context.definition().name(), context.callerHandle(), context.sessionId(),
                        null, 0));
                return renderHandle(run, definition, ROOT_CANNOT_WAIT);
            }

            @Override
            public String answer(String id, String answer) {
                if (owned(id, context).isEmpty()) {
                    return refusedFor(context);
                }
                // A question only the person may answer (spec 2026-09-28: a run that stopped
                // making progress) is refused to every model here, with its text, so the model
                // tells the person rather than deciding for them. Asked again after a lost answer:
                // the run may have gone stuck between this caller's read and its answer, which
                // the store refuses inside its own lock.
                Optional<String> personOnly = orchestrations.personOnlyQuestion(id);
                boolean conductor = conductorRun(context).isPresent();
                if (personOnly.isPresent()) {
                    return Utterances.onlyThePerson(personOnly.get(), conductor);
                }
                if (!orchestrations.answerAsModel(id, answer, context.definition().name())) {
                    // Or somebody answered first — the person, whose copy of a cap question
                    // went out beside this model's (spec 2026-09-29 §2) — and saying who and
                    // what leaves the model nothing to guess or retry.
                    return orchestrations.personOnlyQuestion(id)
                            .map(question -> Utterances.onlyThePerson(question, conductor))
                            .or(() -> orchestrations.answeredAlready(id))
                            .orElse("Orchestration " + id + " is not waiting for an answer;"
                                    + " nothing changed.");
                }
                return "Answered orchestration " + id + ". Its conductor will continue.";
            }

            @Override
            public String choose(String id, JsonNode choices, String note) {
                if (owned(id, context).isEmpty()) {
                    return refusedFor(context);
                }
                // THE SAME PERSON-ONLY GATE AS WORDS: a stuck or verifier question is refused to
                // every model here, with its text, before its options are even read.
                Optional<String> personOnly = orchestrations.personOnlyQuestion(id);
                boolean conductor = conductorRun(context).isPresent();
                if (personOnly.isPresent()) {
                    return Utterances.onlyThePerson(personOnly.get(), conductor);
                }
                return switch (orchestrations.answerChosen(id, choices, note,
                        context.definition().name(), false)) {
                    case Orchestrations.Chosen.Answered answered ->
                            "Answered orchestration " + id + ". Its conductor will continue.";
                    case Orchestrations.Chosen.Refused refused ->
                            refused.why() + " Nothing was answered.";
                    case Orchestrations.Chosen.Lost lost -> orchestrations.personOnlyQuestion(id)
                            .map(question -> Utterances.onlyThePerson(question, conductor))
                            .or(() -> orchestrations.answeredAlready(id))
                            .orElse("Orchestration " + id + " is not waiting for an answer;"
                                    + " nothing changed.");
                };
            }

            @Override
            public String status(String id) {
                Optional<OrchestrationRecord> found = owned(id, context);
                if (found.isEmpty()) {
                    return refusedFor(context);
                }
                OrchestrationRecord run = found.get();
                String rendered = render(run);
                // RULE 1 (spec 2026-09-27 §2): checking on work this caller started while it is
                // still going ends the caller's turn. Measured twice — a conductor (2026-09-25) and
                // the bot Aristoxenus (2026-09-27) each polled a run they had started and then
                // cancelled it as stuck. The result reaches the caller on its own: a conductor is
                // woken by its child's report, a bot or agent is spoken to by Delivery.
                // "to you", not "here": Delivery speaks into a caller conversation only when it is
                // a TURN one, and a delegation's or an event's caller gets the account's inbox.
                // Softly: an approval, a question or a finish in the same batch is something
                // somebody has to see, and takes the ending instead (TurnEnd#requestSoftly).
                boolean inFlight = run.state() == OrchestrationState.RUNNING
                        || run.state() == OrchestrationState.WAITING;
                boolean mine = context.conversationId() != null
                        && context.conversationId().equals(run.callerConversation());
                if (inFlight && mine && context.end() != null
                        && context.end().requestSoftly(Outcome.Ending.ANSWERED, "`" + run.id()
                                + "` (`" + run.definitionName() + "`) is working; its result will"
                                + " be delivered to you when it finishes.")) {
                    return rendered + "\n\nThis run is working. Its result is delivered to you when"
                            + " it finishes — your turn ends here.";
                }
                return rendered;
            }

            @Override
            public String cancel(String id) {
                Optional<OrchestrationRecord> found = owned(id, context);
                if (found.isEmpty()) {
                    return refusedFor(context);
                }
                OrchestrationRecord target = found.get();
                // RULE 2 (spec 2026-09-27 §3): a model never stops running work. Both measured
                // cancellations were of runs working normally, judged stuck by the caller that
                // started them. A stall is the person's news (StallSweep), and /cancel is theirs.
                if (target.state() == OrchestrationState.RUNNING) {
                    return onlyThePersonStops(id);
                }
                // Nor one asking the person whether it goes on (spec 2026-09-28): the model may not
                // answer that question, so it may not settle it the other way either.
                if (asksThePerson(target)) {
                    return onlyThePersonStopsAsking(target);
                }
                // Nor one with such a run below it, which the cancel's cascade would end.
                Optional<String> stuckBelow = orchestrations.stuckBelow(id);
                if (stuckBelow.isPresent()) {
                    return onlyThePersonStopsAbove(id, stuckBelow.get());
                }
                // The guarded doors: the read above is a step before the stop, and a person's
                // answer landing between them puts the run back to running. The stop itself
                // refuses a running row, and the re-read says which way it lost.
                boolean cancelled = conductorRun(context).isPresent()
                        ? cancel.cancelOwnChild(id, context.definition().name())
                        : cancel.cancelUnlessRunning(id, context.definition().name());
                if (!cancelled) {
                    Optional<OrchestrationRecord> now = store.find(id);
                    if (now.filter(CallerOrchestrations::asksThePerson).isPresent()) {
                        return onlyThePersonStopsAsking(now.get());
                    }
                    Optional<String> below = orchestrations.stuckBelow(id);
                    if (below.isPresent()) {
                        return onlyThePersonStopsAbove(id, below.get());
                    }
                    boolean runningAgain = now
                            .filter(row -> row.state() == OrchestrationState.RUNNING).isPresent();
                    return runningAgain ? onlyThePersonStops(id)
                            : "Orchestration " + id + " has already ended; nothing changed.";
                }
                return "Cancelled orchestration " + id + ".";
            }
        };
    }

    /** Whether a run is asking a question only the person may answer ({@link
     *  Orchestrations#PERSON_ONLY}). */
    private static boolean asksThePerson(OrchestrationRecord run) {
        return run.state() == OrchestrationState.ASKING
                && Orchestrations.personOnly(run.pendingCap());
    }

    /** A model's cancel of a run asking the person a question only they may answer, refused — the
     *  store's model stop refuses it too, inside its own WHERE, for a run that asked since the
     *  read. */
    private static String onlyThePersonStopsAsking(OrchestrationRecord run) {
        String what = switch (run.pendingCap()) {
            case Orchestrations.UNCOVERED -> "asks the person whether its acceptance commands stand";
            case Orchestrations.CHECK_FAILURES ->
                    "asks the person whether it goes on after its check kept failing";
            case Orchestrations.CONCERNS ->
                    "asks the person about its acceptance checker's concerns";
            case Orchestrations.PRODUCT_CHECK -> "asks the person to check the product";
            case Orchestrations.INSTALL -> "asks the person whether to install a draft";
            default -> "asks about being stuck";
        };
        return "Only the person can stop " + run.id() + " while it " + what + ": /cancel "
                + run.id() + ".";
    }

    /** A model's cancel of a run that has a run asking about being stuck below it, refused: the
     *  cancel would cascade to it. */
    private static String onlyThePersonStopsAbove(String id, String stuck) {
        return "Only the person can stop " + id + " while " + stuck + " asks about being stuck:"
                + " /cancel " + id + ".";
    }

    /** Rule 2's refusal: the person's command named, and nothing changed. */
    private static String onlyThePersonStops(String id) {
        return "`" + id + "` is working. Only the person can stop a running run, with `/cancel "
                + id + "`.";
    }

    /**
     * The run {@code id} names, if this caller may touch it: for a conductor, only a run it started
     * itself; for anyone else, any run on its account.
     *
     * <p><b>A conductor is held to its own children.</b> It read and cancelled any run on the
     * account — a sibling phase, another tree, a run the person started by hand — because the
     * account was the only test. The runs a conductor starts are the only ones it is told about,
     * and so the only ones it has any business answering or stopping.
     */
    private Optional<OrchestrationRecord> owned(String id, RunExtras.Context context) {
        String handle = ownerOf(context);
        if (handle == null) {
            return Optional.empty();
        }
        Optional<OrchestrationRecord> conductor = conductorRun(context);
        return store.find(id)
                .filter(run -> handle.equals(run.callerHandle()))
                .filter(run -> conductor.isEmpty()
                        || conductor.get().id().equals(run.parent()));
    }

    /** What a refused {@link #owned} says: a conductor is told why, anyone else what it always was. */
    private String refusedFor(RunExtras.Context context) {
        return conductorRun(context).isPresent() ? NOT_A_CHILD : NOT_OWNED;
    }

    /**
     * The account this caller acts for: its own handle, or — for a conductor's turn, which has
     * none — the account written on the run whose conductor conversation this is.
     *
     * <p><b>A conductor turn has no handle of its own.</b> {@code Turn.speakToConductor} submits
     * none, and {@code JobRuntime}'s fallback resolves one from the client session, which a
     * conductor's turn passes only while the tree's original session is still live — Decision 2.
     * So the ordinary background case has {@code callerHandle() == null}, and a parent told to
     * "answer it with orchestration_answer" would read {@code NOT_OWNED} about its own child:
     * spec §5 and §8's question-climbing flow would be unreachable. The account is on the row the
     * whole time, which is where the child's own {@code caller_handle} came from, so it is read
     * from there. Ownership is still the same test — the run asked about must carry this account —
     * and a row of another account's is refused exactly as before.
     *
     * <p>A terminal row is read too: the account that owns a tree does not change when the run
     * ends, and {@code startNested} is what refuses a conductor whose own run is over.
     */
    private String ownerOf(RunExtras.Context context) {
        if (context.callerHandle() != null) {
            return context.callerHandle();
        }
        return conductorRun(context).map(OrchestrationRecord::callerHandle).orElse(null);
    }

    /**
     * The run whose conductor conversation is this caller's own, if any — the same lookup {@code
     * OrchestrationsConfig.runExtras} already makes to decide a conductor's own tools, repeated
     * here rather than shared with it: that one pays it on every turn a conductor takes, and this
     * one pays it only when an orchestration tool is actually called.
     *
     * <p><b>A terminal run is not filtered out.</b> A conductor conversation belongs to its run
     * whatever state the row is in, and a start from one whose run has ended must reach {@code
     * startNested} to be refused there: taking the root door instead would insert a run with no
     * parent, a dead {@code ORCHESTRATION} caller conversation and usually no handle — an
     * invisible run that spends its whole budget where no cascade, no frame and no ending can
     * reach it.
     */
    private Optional<OrchestrationRecord> conductorRun(RunExtras.Context context) {
        if (context.conversationId() == null) {
            return Optional.empty();
        }
        return store.byConductorConversation(context.conversationId());
    }

    /**
     * The handle a caller reads back from a start: the id and stage list, and — when there is one
     * — a note alongside them. {@code note} is {@link #ROOT_CANNOT_WAIT} for every root start
     * (nothing will ever resume a root's own conversation, whatever {@code wait} asked for),
     * {@link #CHILD_ALREADY_ENDED_BEFORE_THE_WAIT} for a nested start whose {@code wait} was
     * asked for but lost the race, and {@code null} otherwise.
     */
    private String renderHandle(OrchestrationRecord run, OrchestrationDefinition definition,
            String note) {
        ObjectNode result = JSON.createObjectNode();
        result.put("id", run.id());
        ArrayNode stages = result.putArray("stages");
        definition.stages().forEach(stage -> stages.add(stage.id()));
        if (note != null) {
            result.put("note", note);
        }
        return result.toString();
    }

    private String render(OrchestrationRecord run) {
        ObjectNode out = JSON.createObjectNode();
        out.put("id", run.id());
        out.put("definition", run.definitionName());
        put(out, "project", run.project());
        put(out, "parent", run.parent());
        out.put("depth", run.depth());
        out.put("state", run.state().wire());
        put(out, "pending_cap", run.pendingCap());
        put(out, "waiting_for", run.waitingFor());
        put(out, "result", run.result());
        put(out, "failure", run.failure());
        out.put("returns_used", run.returnsUsed());
        out.put("max_returns", run.maxReturns());
        out.put("nudges", run.nudges());
        out.put("restarts", run.restarts());
        put(out, "created_at", run.createdAt());
        put(out, "ended_at", run.endedAt());

        ArrayNode items = out.putArray("todos");
        for (TodoItem item : todos.list(run.conductorConversation())) {
            ObjectNode node = items.addObject();
            node.put("id", item.id());
            put(node, "parent", item.parent());
            node.put("position", item.position());
            node.put("text", item.text());
            node.put("status", item.status().wire());
            put(node, "summary", item.summary());
            node.put("locked", item.locked());
            put(node, "stage_id", item.stageId());
        }

        ArrayNode messages = out.putArray("messages");
        for (OrchestrationMessage message : store.messages(run.id())) {
            ObjectNode node = messages.addObject();
            node.put("id", message.id());
            node.put("kind", message.kind().wire());
            node.put("text", message.text());
            node.put("author", message.author());
            put(node, "created_at", message.createdAt());
            put(node, "delivered_at", message.deliveredAt());
            put(node, "cap_kind", message.capKind());
        }

        ArrayNode children = out.putArray("children");
        for (OrchestrationRecord child : store.children(run.id())) {
            ObjectNode node = children.addObject();
            node.put("id", child.id());
            node.put("state", child.state().wire());
        }
        return out.toString();
    }

    private static void put(ObjectNode node, String name, String value) {
        if (value == null) node.putNull(name); else node.put(name, value);
    }

    private static void put(ObjectNode node, String name, Instant value) {
        if (value == null) node.putNull(name); else node.put(name, value.toString());
    }
}
