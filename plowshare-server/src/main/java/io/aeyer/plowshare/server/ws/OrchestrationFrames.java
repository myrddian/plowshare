package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.OrchestrationCancel;
import io.aeyer.plowshare.server.orchestrations.OrchestrationMessage;
import io.aeyer.plowshare.server.orchestrations.OrchestrationRecord;
import io.aeyer.plowshare.server.orchestrations.OrchestrationState;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore;
import io.aeyer.plowshare.server.orchestrations.Orchestrations;
import io.aeyer.plowshare.server.requests.RequestedProjectId;
import io.aeyer.plowshare.server.todos.TodoLists;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Orchestrations, from the caller's side of the wire: which definitions a project can reach, one
 * account's runs, one run's status, answering a question it asked, and cancelling it — spec
 * 2026-09-13, orchestrations §7.
 *
 * <h2>{@link OrchestrationResolver} arrives through an {@link ObjectProvider}</h2>
 *
 * <p>The bean is conditional — {@code OrchestrationsConfig} only builds one where the rest of the
 * orchestration engine is wired — so a deployment without it must still answer {@code
 * orchestration.definitions} rather than fail to boot for want of a collaborator this area could
 * simply do without. An absent resolver answers an empty list, on {@link AgentListHandler}'s own
 * promise that asking what a deployment offers is never a refusal.
 */
@Component
public class OrchestrationFrames implements FrameArea {

  private io.aeyer.plowshare.server.personal.PersonalSpaces personal;

  @org.springframework.beans.factory.annotation.Autowired
  public void usePersonalSpaces(io.aeyer.plowshare.server.personal.PersonalSpaces personal) {
    this.personal = personal;
  }

  private final OrchestrationStore store;
  private final Orchestrations orchestrations;
  private final OrchestrationCancel cancel;
  private final TodoLists todos;
  private final ObjectProvider<OrchestrationResolver> resolver;
  private final ProjectStore projects;

  public OrchestrationFrames(
      OrchestrationStore store,
      Orchestrations orchestrations,
      OrchestrationCancel cancel,
      TodoLists todos,
      ObjectProvider<OrchestrationResolver> resolver,
      ProjectStore projects) {
    this.store = Objects.requireNonNull(store, "store");
    this.orchestrations = Objects.requireNonNull(orchestrations, "orchestrations");
    this.cancel = Objects.requireNonNull(cancel, "cancel");
    this.todos = Objects.requireNonNull(todos, "todos");
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.projects = Objects.requireNonNull(projects, "projects");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.ORCHESTRATION_START, this::start,
        FrameTypes.ORCHESTRATION_RECEIPT, this::receipt,
        FrameTypes.ORCHESTRATION_DEFINITIONS, this::definitions,
        FrameTypes.ORCHESTRATION_LIST, this::list,
        FrameTypes.ORCHESTRATION_STATUS, this::status,
        FrameTypes.ORCHESTRATION_ANSWER, this::answer,
        FrameTypes.ORCHESTRATION_CANCEL, this::cancel);
  }

  // -- wire records ----------------------------------------------------------------------

  /** One stage of a definition, as a caller reads it — never evaluated, only shown. */
  public record StageView(String id, String doneWhen, List<String> mayReturnTo) {}

  /**
   * One orchestration a caller could start, or could not — {@code tier} and {@code stages} are null
   * and empty on a refused row, which carries only its name and why it is withheld.
   */
  public record DefinitionView(
      String name,
      String description,
      String tier,
      List<StageView> stages,
      List<String> triggers,
      boolean served,
      String withheld) {}

  /**
   * One run, as a caller reads it.
   *
   * <p><b>{@code stalledSince} is last and read from the store, not the record.</b> It is the stall
   * sweep's own mark rather than anything {@code OrchestrationRecord} carries, so it is filled the
   * same way for every run a caller reads — a listing, or one status — and never among the fields
   * {@link #viewOf} takes straight off the record above it.
   */
  public record RunView(
      String id,
      String definition,
      String tier,
      String project,
      String state,
      String pendingCap,
      String result,
      String failure,
      int returnsUsed,
      int maxReturns,
      int nudges,
      int restarts,
      String callerAgent,
      String callerConversation,
      String conductorConversation,
      String parent,
      int depth,
      String waitingFor,
      Instant createdAt,
      Instant endedAt,
      Instant stalledSince) {}

  /**
   * One child a run has started, as a caller reads it — just enough to point at it with {@code
   * orchestration_status}.
   */
  public record ChildView(String id, String state) {}

  /**
   * One question the conductor asked, or one answer it was given, as a caller reads it — {@code
   * structure} its options or choices (V70), or null for plain words.
   */
  public record MessageView(
      String id,
      String kind,
      String text,
      String author,
      Instant createdAt,
      Instant deliveredAt,
      String capKind,
      JsonNode structure) {}

  public record Definitions(List<DefinitionView> definitions) {}

  public record Listed(List<RunView> orchestrations) {}

  public record Status(
      RunView orchestration,
      List<TodoView> todos,
      List<MessageView> messages,
      List<ChildView> children) {}

  public record Answered(String id, String state) {}

  public record Cancelled(String id, String state) {}

  record ListBody(String project, String state, Integer limit) {}

  record IdBody(String id) {}

  record AnswerBody(String id, String answer, JsonNode choices) {}

  // -- handlers ----------------------------------------------------------------------------

  record StartBody(
      String agent,
      String definition,
      String request,
      String context,
      String project,
      String requestId) {}

  record ReceiptBody(String requestId) {}

  public record Started(String id, String state, String requestId) {}

  private io.aeyer.plowshare.server.agents.Callers callers;
  private ObjectProvider<io.aeyer.plowshare.server.orchestrations.CallerOrchestrations> grants;

  @org.springframework.beans.factory.annotation.Autowired
  public void useStartAuthority(
      io.aeyer.plowshare.server.agents.Callers callers,
      ObjectProvider<io.aeyer.plowshare.server.orchestrations.CallerOrchestrations> grants) {
    this.callers = callers;
    this.grants = grants;
  }

  private static java.util.UUID requestId(String value) {
    try {
      var id = java.util.UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new CallerFault("orchestration start/receipt needs requestId as a UUID");
    }
  }

  Outcome start(Map<String, Object> payload, Asking asking) {
    StartBody body = Payloads.as(payload, StartBody.class, FrameTypes.ORCHESTRATION_START);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_START);
    var key = requestId(body.requestId());
    for (String field : List.of("agent", "definition", "request")) {
      Payloads.required(payload, field, FrameTypes.ORCHESTRATION_START, "Nothing was started.");
    }
    var authority = grants == null ? null : grants.getIfAvailable();
    if (callers == null || authority == null) {
      throw new CallerFault("Orchestration starts are unavailable on this server");
    }
    var home =
        personal == null
            ? io.aeyer.plowshare.server.requests.RequestedHome.in(body.project())
            : personal.home(body.project(), handle);
    callers.requireSession(asking.sessionId(), handle);
    callers.requireProject(home.project(), handle);
    var caller = callers.callerFor(home.project(), asking.sessionId(), handle);
    var agent = callers.requireAgent(body.agent(), caller);
    var definition =
        authority.granted(agent, home, null, asking.sessionId(), handle).get(body.definition());
    if (definition == null) throw new CallerFault("This caller is not granted that orchestration");
    // Socket identity is authority, not caller-supplied data. A new connection can recover
    // the same key, while changing the agent/project/definition/input cannot reuse it.
    var identity =
        JSON.createObjectNode()
            .put("agent", body.agent())
            .put("definition", body.definition())
            .put("project", body.project())
            .put("request", body.request())
            .put("context", body.context());
    var run =
        orchestrations.start(
            new Orchestrations.Start(
                definition,
                home,
                body.request(),
                body.context(),
                null,
                agent.name(),
                handle,
                asking.sessionId(),
                null,
                0),
            key,
            identity.toString());
    return new Outcome(
        io.aeyer.plowshare.protocol.frames.Code.ACCEPTED,
        null,
        new Started(run.id(), run.state().wire(), key.toString()));
  }

  Outcome receipt(Map<String, Object> payload, Asking asking) {
    ReceiptBody body = Payloads.as(payload, ReceiptBody.class, FrameTypes.ORCHESTRATION_RECEIPT);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_RECEIPT);
    var key = requestId(body.requestId());
    var receipt =
        store
            .startReceipt(handle, key)
            .orElseThrow(
                () -> new CallerFault("No orchestration start receipt is owned by this account"));
    var run = owned(FrameTypes.ORCHESTRATION_RECEIPT, receipt.id(), handle);
    return Outcome.ok(new Started(run.id(), run.state().wire(), key.toString()));
  }

  Outcome definitions(Map<String, Object> payload, Asking asking) {
    Tiered body = Payloads.as(payload, Tiered.class, FrameTypes.ORCHESTRATION_DEFINITIONS);
    OrchestrationResolver r = resolver.getIfAvailable();
    if (r == null) {
      return Outcome.ok(new Definitions(List.of()));
    }
    DefinitionResolver.Caller caller =
        new DefinitionResolver.Caller(
            RequestedProjectId.forListing(projects, body.project()),
            asking.sessionId(),
            asking.handle());
    Map<String, DefinitionView> merged = new TreeMap<>();
    r.refusalsFor(caller)
        .forEach(
            (name, reason) ->
                merged.put(
                    name,
                    new DefinitionView(name, null, null, List.of(), List.of(), false, reason)));
    r.forCaller(caller).forEach((name, definition) -> merged.put(name, viewOf(definition)));
    return Outcome.ok(new Definitions(List.copyOf(merged.values())));
  }

  private io.aeyer.plowshare.server.information.InformationJobs inputs;

  @org.springframework.beans.factory.annotation.Autowired
  public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) {
    this.inputs = inputs;
  }

  Outcome list(Map<String, Object> payload, Asking asking) {
    ListBody body = Payloads.as(payload, ListBody.class, FrameTypes.ORCHESTRATION_LIST);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_LIST);
    int limit = body.limit() == null ? 50 : body.limit();
    if (limit < 1 || limit > 200) {
      throw new CallerFault("orchestration.list takes a limit from 1 to 200, not " + limit + ".");
    }
    OrchestrationState state = null;
    if (body.state() != null) {
      try {
        state = OrchestrationState.of(body.state());
      } catch (IllegalArgumentException unknown) {
        throw new CallerFault(
            "orchestration.list has an unknown state '"
                + body.state()
                + "'; it is one of running, asking, waiting, finished, failed, capped,"
                + " cancelled.");
      }
    }
    List<RunView> runs =
        store.byCaller(handle, body.project(), state, limit).stream()
            .filter(run -> inputs == null || inputs.logAllowed(run.id(), handle))
            .map(this::viewOf)
            .toList();
    return Outcome.ok(new Listed(runs));
  }

  Outcome status(Map<String, Object> payload, Asking asking) {
    IdBody body = Payloads.as(payload, IdBody.class, FrameTypes.ORCHESTRATION_STATUS);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_STATUS);
    OrchestrationRecord run = owned(FrameTypes.ORCHESTRATION_STATUS, body.id(), handle);
    List<TodoView> todoViews =
        todos.list(run.conductorConversation()).stream().map(TodoView::of).toList();
    List<MessageView> messages =
        store.messages(run.id()).stream().map(OrchestrationFrames::viewOf).toList();
    List<ChildView> children =
        store.children(run.id()).stream()
            .map(child -> new ChildView(child.id(), child.state().wire()))
            .toList();
    return Outcome.ok(new Status(viewOf(run), todoViews, messages, children));
  }

  Outcome answer(Map<String, Object> payload, Asking asking) {
    AnswerBody body = Payloads.as(payload, AnswerBody.class, FrameTypes.ORCHESTRATION_ANSWER);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_ANSWER);
    owned(FrameTypes.ORCHESTRATION_ANSWER, body.id(), handle);
    if (body.choices() != null && !body.choices().isNull()) {
      switch (orchestrations.answerChosen(body.id(), body.choices(), body.answer(), handle, true)) {
        case Orchestrations.Chosen.Answered answered -> {}
        case Orchestrations.Chosen.Refused refused ->
            throw new CallerFault(refused.why() + " Nothing was answered.");
        case Orchestrations.Chosen.Lost lost ->
            throw new CallerFault(
                orchestrations
                    .answeredAlready(body.id())
                    .orElse(
                        "Orchestration "
                            + body.id()
                            + " is not waiting for an answer; nothing changed."));
      }
      String state = store.find(body.id()).map(r -> r.state().wire()).orElse(null);
      return Outcome.ok(new Answered(body.id(), state));
    }
    if (body.answer() == null || body.answer().isBlank()) {
      throw new CallerFault("orchestration.answer needs the answer text; nothing was answered.");
    }
    if (!orchestrations.answer(body.id(), body.answer(), handle)) {
      // A CAP QUESTION IS ASKED OF THE PERSON AND THE PARENT MODEL AT ONCE (spec 2026-09-29
      // §2), so the likeliest reason the person's answer found nothing waiting is that the
      // model's came first — and who answered and with what is the one thing they need.
      throw new CallerFault(
          orchestrations
              .answeredAlready(body.id())
              .orElse(
                  "Orchestration "
                      + body.id()
                      + " is not waiting for an answer; nothing changed."));
    }
    String state = store.find(body.id()).map(r -> r.state().wire()).orElse(null);
    return Outcome.ok(new Answered(body.id(), state));
  }

  Outcome cancel(Map<String, Object> payload, Asking asking) {
    IdBody body = Payloads.as(payload, IdBody.class, FrameTypes.ORCHESTRATION_CANCEL);
    String handle = asking.requireHandle(FrameTypes.ORCHESTRATION_CANCEL);
    owned(FrameTypes.ORCHESTRATION_CANCEL, body.id(), handle);
    if (!cancel.cancel(body.id(), handle)) {
      throw new CallerFault("Orchestration " + body.id() + " has already ended; nothing changed.");
    }
    return Outcome.ok(new Cancelled(body.id(), "cancelled"));
  }

  // -- shared -------------------------------------------------------------------------------

  /**
   * The run named by {@code id}, and owned by {@code handle} — the one check every frame in this
   * area past {@code definitions} makes, and the same refusal whether the run does not exist at all
   * or belongs to somebody else, so neither answer tells a caller which.
   */
  private OrchestrationRecord owned(String type, String id, String handle) {
    if (id == null || id.isBlank()) {
      throw new CallerFault(type + " needs the orchestration id; nothing was done.");
    }
    if (inputs != null) inputs.requireLog(id, handle);
    return store
        .find(id)
        .filter(run -> handle.equals(run.callerHandle()))
        .orElseThrow(
            () -> new CallerFault("No orchestration with that id is owned by this account."));
  }

  private static DefinitionView viewOf(OrchestrationDefinition definition) {
    List<StageView> stages =
        definition.stages().stream()
            .map(stage -> new StageView(stage.id(), stage.doneWhen(), stage.mayReturnTo()))
            .toList();
    List<String> triggers =
        definition.triggers().stream().map(OrchestrationDefinition.Trigger::text).toList();
    return new DefinitionView(
        definition.name(),
        definition.description(),
        definition.tier().name().toLowerCase(Locale.ROOT),
        stages,
        triggers,
        true,
        null);
  }

  private RunView viewOf(OrchestrationRecord run) {
    return new RunView(
        run.id(),
        run.definitionName(),
        run.tier().name().toLowerCase(Locale.ROOT),
        run.project(),
        run.state().wire(),
        run.pendingCap(),
        run.result(),
        run.failure(),
        run.returnsUsed(),
        run.maxReturns(),
        run.nudges(),
        run.restarts(),
        run.callerAgent(),
        run.callerConversation(),
        run.conductorConversation(),
        run.parent(),
        run.depth(),
        run.waitingFor(),
        run.createdAt(),
        run.endedAt(),
        store.stalledSince(run.id()).orElse(null));
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode treeOf(String structure) {
    if (structure == null) {
      return null;
    }
    try {
      return JSON.readTree(structure);
    } catch (JsonProcessingException unreadable) {
      // The table's CHECK keeps this an object; a row it let through is shown as plain words.
      return null;
    }
  }

  private static MessageView viewOf(OrchestrationMessage message) {
    return new MessageView(
        message.id(),
        message.kind().wire(),
        message.text(),
        message.author(),
        message.createdAt(),
        message.deliveredAt(),
        message.capKind(),
        treeOf(message.structure()));
  }
}
