package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedSession;
import io.aeyer.plowshare.server.requests.RequestedTask;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Starting one declared agent on one task: the whole decision, in the order it has to be made in,
 * above {@link JobStore} and {@link Turn} and below whatever surface was asked.
 *
 * <h2>Why this is a class and not four lines in a controller</h2>
 *
 * <p>It was four lines in {@code api.AgentController.run}, and a WebSocket frame reaching the same
 * capability calls the service and never the controller — so every rule left in the handler is a
 * rule the second surface silently does not have. What moved here is therefore <b>the decision</b>
 * and not a list of checks: two refusals, yes, but also the order they are reached in and the
 * branch that decides whether a run is submitted or spoken. A dispatcher that re-derived any of
 * that from the refusals alone would get the order wrong and have nothing fail.
 *
 * <h2>Filed on its own subject, not on {@code Callers}'</h2>
 *
 * <p>{@link Callers} was the obvious home — it already resolves the caller and the definition every
 * run here starts from, and this class calls it three times. It is not the right one. Its subject
 * is a {@link DefinitionResolver.Caller}: which caller a request is and which agent that caller may
 * run. A run's submission is not that question, and {@code Callers} already carries one method
 * filed by proximity rather than by subject and says so in its own javadoc. Adding a second would
 * make the exception the rule. The subject here is a run, so the class is {@code Runs}, and {@code
 * Callers} stays the thing it answers for.
 *
 * <h2>{@link Shown} is a seam, and what it was waiting for has arrived</h2>
 *
 * <p>Turning the image ids a run names into bytes is the one part of {@code run} that did not come
 * down with the rest, and the reason was narrow: that code answers <b>404</b> for a UID that parses
 * but names no image this tier holds, and no fault below {@code api} mapped to 404 — {@code faults}
 * held {@link CallerFault} and nothing beside it. {@code faults.NotFoundFault} is that sibling now,
 * and {@link Pictures} is the decision itself, below {@code api} with every other one.
 *
 * <p><b>It arrives here as a parameter still, and that is now a choice rather than a blocker.</b>
 * What this class owns either way is <em>when</em> the ids are resolved — after the home is
 * decided, never for an utterance — and a parameter keeps that visible at the one call site that
 * decides it. Both surfaces pass the same {@link Pictures}; nothing else in this codebase
 * implements {@link Shown} but a test recorder, which is the other thing the seam is for.
 */
@Service
public final class Runs {

  private io.aeyer.plowshare.server.personal.PersonalSpaces personal;

  @org.springframework.beans.factory.annotation.Autowired
  public void usePersonalSpaces(io.aeyer.plowshare.server.personal.PersonalSpaces personal) {
    this.personal = personal;
  }

  private final Callers callers;
  private final JobStore jobs;
  private final Turn turns;

  public Runs(Callers callers, JobStore jobs, Turn turns) {
    this.callers = callers;
    this.jobs = jobs;
    this.turns = turns;
  }

  private ConversationStore conversations;
  private ConversationsProperties conversationProperties;
  private ObjectProvider<LogStages> logStages;

  @Autowired
  public void useConversations(
      ConversationStore conversations,
      ConversationsProperties properties,
      ObjectProvider<LogStages> logStages) {
    this.conversations = conversations;
    this.conversationProperties = properties;
    this.logStages = logStages;
  }

  /**
   * The key to {@link Turn}'s contained door, and the whole of that door's narrowing.
   *
   * <p>A contained run's conversation carries {@code Origin.CONTAINED}, which {@code
   * Turn.requireSpeakable} refuses by design: such a row is the log of a run and not a conversation
   * anybody speaks into, and that guard is the one a <em>caller-supplied</em> conversation id
   * reaches from {@code POST /v1/agents/&#123;name&#125;/runs} and the {@code agent.run} frame
   * alike. Widening it to accept {@code CONTAINED} would therefore let {@code agent.run} post an
   * utterance into a child's log and spend the child's allowance, driven as whatever agent the
   * caller named. So a second, narrower door exists instead — {@code requireConductor}'s and {@code
   * requireSeat}'s shape — and this type is what makes it narrow.
   *
   * <p><strong>Reachability is the safety property, and it is enforced by the compiler rather than
   * by a convention.</strong> This interface is {@code sealed} and permits exactly one
   * implementation, which is {@code private} to {@code Runs}. No class outside {@code Runs} can
   * implement it and none can construct the implementation, so <em>no code outside {@code Runs} can
   * produce a value of this type at all</em> — and {@code Turn.speakContained} cannot be called
   * without one. {@code Turn.speakContained} is itself package-private, so nothing outside {@code
   * io.aeyer.plowshare.server.agents} can even name it.
   *
   * <p><strong>What must not be simplified.</strong> Replacing this with a boolean, a flag on a
   * context object, or a public overload would make the door wide with a convention attached: any
   * caller could then ask for the contained guard. The {@code sealed} modifier and the {@code
   * private} implementation are the mechanism; a package-private record here would be constructible
   * by every class in this package, which is wider than the one branch that may use it.
   *
   * <p><strong>The residual link, stated rather than overclaimed.</strong> "Only {@code Runs}" is
   * compiler-enforced. The narrowing from there to "only {@link #start}'s contained branch" rests
   * on two things a compiler cannot see and tests pin instead: this type is constructed at exactly
   * one site, and that site is inside {@code if (ask.contained() != null)} — a branch no wire shape
   * can reach, because {@code api.RunAgentRequest} has no component of type {@link Ask.Contained}
   * and {@code ContainedRunTest} asserts that by reflection.
   */
  sealed interface ContainedStart permits ContainedOpened {
    /**
     * The conversation the contained door opened, which is the only one this start may speak to.
     */
    String conversation();
  }

  /**
   * The one implementation, private so that {@code Runs} is the only code that can make one.
   *
   * <p>It carries the conversation id and nothing else on purpose: the authority a contained run
   * holds is proved before this is built and rechecked below {@code Turn} by {@code
   * OperationExecution.admit} against the bound envelope, so a second copy of it here would be a
   * second source of truth for one fact.
   */
  private record ContainedOpened(String conversation) implements ContainedStart {
    private ContainedOpened {
      if (conversation == null || conversation.isBlank())
        throw new IllegalArgumentException("A contained start names the conversation it opened");
    }
  }

  /**
   * What one caller asked to run, in fields and never in a wire shape.
   *
   * <p>{@code api.RunAgentRequest} is the HTTP body Jackson binds and it stays on the HTTP surface;
   * this is what that body is parsed into, and what a frame's payload is parsed into as well. The
   * two surfaces share the decision below this type, not the shape above it — {@code requests}' own
   * package rule, applied one level up.
   *
   * @param agent the name the run was addressed to, off the path or off the frame
   * @param task what the agent is being asked to do, in prose
   * @param project the tier the run answers from, or {@code null} for global
   * @param session the session this run is submitted under, or {@code null}
   * @param conversation the conversation this run is a turn in, or {@code null} for a standalone
   *     run or a fresh conversation when requested
   * @param maxTurns how many turns this run may take, or {@code null}
   * @param noTurnCap whether this run is to have no turn cap at all, or {@code null}
   * @param images the ids of images this run is to be shown; never null after construction, so
   *     nothing here branches before it iterates
   * @param speaker who is speaking, when this run is a turn in a conversation — the signed-in
   *     account for a surface a person reached, the harness with its source for an approval's
   *     answer. {@code null} is read as {@link Speaker#person(String) a person} whose handle is not
   *     known: the surface knows who asked and this record does not, so nothing here invents one. A
   *     run that is not a turn records none of it — a plain submission's utterance is a person's
   *     with no handle — but a person's handle owns the submission's log (spec
   *     2026-09-28-hooks-reach-the-log decision 8)
   * @param newConversation whether to open a fresh conversation for this first turn
   * @param callerHandle the authenticated account submitting the run, also when the harness speaks
   *     an approval reply on its behalf
   * @param contained what a run inside a confined child Application brings with it, or {@code null}
   *     for every other run — which is every run that is not {@code application.child.run}. See
   *     {@link Contained}; the three facts travel together because they come from one proved
   *     admission and are meaningless apart.
   */
  public record Ask(
      String agent,
      String task,
      String project,
      String session,
      String conversation,
      Integer maxTurns,
      Boolean noTurnCap,
      List<String> images,
      Speaker speaker,
      String callerHandle,
      Boolean newConversation,
      Contained contained) {

    /**
     * The authority a contained run carries, proved before it was built (ADR 0042).
     *
     * <p>One record and not three nullable fields on {@link Ask}, because the three are one fact: a
     * containment hop produced an admission, and this is that admission, the pool the run spends
     * and the project its work authority was proved against. A caller holding any of them holds all
     * of them.
     *
     * <p><strong>Server-side only, and that is what keeps it safe.</strong> {@code
     * api.RunAgentRequest} — the record Jackson binds for the HTTP body and for the frame payload —
     * has no component of this type, so no wire shape can supply one; the only caller that does is
     * the contained-run service, which proved the admission first. {@code ContainedRunTest} pins
     * that by reflection. A wire-settable version would let a caller prove work authority against a
     * project it holds and then run somewhere else.
     *
     * @param admission the child's admission, whose account is its <em>container's</em>: a hop
     *     keeps the parent's executor and account unchanged, so nothing here invents a second
     *     accounting subject and ADR 0025's immutable attribution is untouched
     * @param budget the child's <em>own</em> allowance, under the ceiling its container set.
     *     Restored from the existing allowance rather than minted, so the conversation points at
     *     the pool the container already bounded and no counter is shared
     * @param workProject the project this run's work authority is proved against: the nearest
     *     enclosing <em>uncontained</em> scope's home. A contained project carries no {@code
     *     PROJECT} scope and therefore no role for anybody — not for the child's principal and not
     *     for its container — so asking the work question about the child's own project name
     *     answers "no role" whoever asks. Supplying the enclosing project here asks the question
     *     that genuinely decides a child's work authority, through the existing predicate, instead
     *     of widening the predicate
     */
    public record Contained(
        io.aeyer.plowshare.server.access.OperationAuthority.Admission admission,
        Budget budget,
        String workProject) {
      public Contained {
        java.util.Objects.requireNonNull(admission, "admission");
        java.util.Objects.requireNonNull(budget, "budget");
        if (workProject == null || workProject.isBlank())
          throw new IllegalArgumentException(
              "A contained run names the project its work authority is proved against");
      }
    }

    /** Never null, for {@code api.RunAgentRequest}'s own reason. */
    public Ask {
      images = images == null ? List.of() : List.copyOf(images);
      // A contained run opens its own conversation through the contained door and speaks into it.
      // Carrying contained authority while naming an existing conversation, or without asking for
      // a fresh one, would take the utterance branch below -- which resolves the conversation's
      // own home and never reaches that door. It fails closed today at ConversationStore's guard,
      // but incidentally; this is the structural statement, made where the combination is built.
      if (contained != null && (conversation != null || !Boolean.TRUE.equals(newConversation)))
        throw new IllegalArgumentException(
            "A contained run opens its own conversation: it names none and asks for a fresh one");
    }

    public Ask(
        String agent,
        String task,
        String project,
        String session,
        String conversation,
        Integer maxTurns,
        Boolean noTurnCap,
        List<String> images,
        Speaker speaker,
        String callerHandle,
        Boolean newConversation) {
      this(
          agent,
          task,
          project,
          session,
          conversation,
          maxTurns,
          noTurnCap,
          images,
          speaker,
          callerHandle,
          newConversation,
          null);
    }

    public Ask(
        String agent,
        String task,
        String project,
        String session,
        String conversation,
        Integer maxTurns,
        Boolean noTurnCap,
        List<String> images,
        Speaker speaker,
        String callerHandle) {
      this(
          agent,
          task,
          project,
          session,
          conversation,
          maxTurns,
          noTurnCap,
          images,
          speaker,
          callerHandle,
          null);
    }

    /** A person's account is also the submitting caller's; harness replies name it separately. */
    public Ask(
        String agent,
        String task,
        String project,
        String session,
        String conversation,
        Integer maxTurns,
        Boolean noTurnCap,
        List<String> images,
        Speaker speaker) {
      this(
          agent,
          task,
          project,
          session,
          conversation,
          maxTurns,
          noTurnCap,
          images,
          speaker,
          speaker != null && speaker.kind() == Speaker.Kind.PERSON ? speaker.name() : null);
    }

    /** An ask whose speaker nobody named: a person whose handle is not known. */
    public Ask(
        String agent,
        String task,
        String project,
        String session,
        String conversation,
        Integer maxTurns,
        Boolean noTurnCap,
        List<String> images) {
      this(agent, task, project, session, conversation, maxTurns, noTurnCap, images, null);
    }
  }

  /**
   * A run that started: the id it was given, and the agent's own name as the registry spells it
   * rather than as the caller did.
   *
   * @param id the job id, pollable at once
   * @param agent the definition's own name
   * @param conversation the opened or reused conversation, or null for a standalone run
   */
  public record Started(String id, String agent, String conversation) {
    public Started(String id, String agent) {
      this(id, agent, null);
    }
  }

  /**
   * How the surface turns the image ids a run named into the bytes a run is shown. See this class's
   * own javadoc for why this is a parameter and not a collaborator, and for what closes it.
   */
  @FunctionalInterface
  public interface Shown {

    /**
     * @param definition the agent the pictures are for — it must have declared that it can see
     * @param home the tier the ids resolve against, which is the run's own and is never the
     *     caller's to choose
     * @param uids what the body named; empty for the ordinary run
     * @return the pictures, in the order they were named
     */
    List<Content.Image> pictures(AgentDefinition definition, Home home, List<String> uids);
  }

  /**
   * Start it, or refuse it.
   *
   * <p><b>The order below is behaviour and not style.</b> Every refusal here is reachable by a body
   * that is wrong in more than one way, and which one a caller is told about is decided entirely by
   * this sequence.
   *
   * @param ask what the caller asked for
   * @param shown how the surface resolves the ids into pictures
   * @return the id and the agent's own name
   * @throws CallerFault for a body this server can name as wrong: an unknown agent, no task, a
   *     session that is present and empty, a turn cap said twice, an utterance carrying images, or
   *     a run naming a conversation and a project both
   */
  public Started start(Ask ask, Shown shown) {
    // Named first, ahead of every other reading of the body: the resolved
    // set an unknown-agent refusal lists, and the run itself once one is
    // found, both have to agree with what this session and this project
    // can see -- so both need this before anything else runs. A
    // conversation names its own project through its own home, never
    // through the body's `project` -- the field this refuses outright a
    // few lines down when a conversation is also given -- so the caller
    // for a turn is built from Turn.homeOf, not from the request body.
    if (Boolean.TRUE.equals(ask.newConversation()) && ask.conversation() != null) {
      throw new CallerFault("newConversation cannot be combined with a conversation id");
    }
    String runProject =
        personal != null && ask.conversation() == null
            ? personal.home(ask.project(), ask.callerHandle()).project()
            : ask.project();
    if (personal != null
        && ask.conversation() != null
        && callers.homeOfConversation(ask.conversation()).isGlobal()) {
      throw new CallerFault(
          "Global holds shared setup and resources; conversations run in Personal or a project");
    }
    String session = RequestedSession.in(ask.session());
    callers.requireSession(session, ask.callerHandle());
    DefinitionResolver.Caller caller =
        ask.conversation() == null
            ? callers.callerFor(runProject, session, ask.callerHandle())
            : callers.callerForConversation(ask.conversation(), session);
    AgentDefinition definition = callers.requireAgent(ask.agent(), caller);
    String task = RequestedTask.in(ask.task(), definition.name());
    // The narrowest of the three levels a turn cap is decided at. Null for a
    // body that decides nothing, which leaves the conversation's answer, or
    // the agent's own when the conversation has none.
    TurnCap turnCap = RequestedTurnCap.in(ask.maxTurns(), ask.noTurnCap(), "this run");
    if (ask.conversation() == null) {
      Home home = RequestedHome.in(runProject);
      // The project the work question is asked about is not always the one the run answers from.
      // Ask.workProject explains the one case where they differ and why it cannot be set from the
      // wire; null -- every ordinary caller -- asks about the run's own project as it always has.
      callers.requireWork(
          ask.contained() == null ? home.project() : ask.contained().workProject(),
          ask.callerHandle());
      if (Boolean.TRUE.equals(ask.newConversation())) {
        if (!ask.images().isEmpty())
          throw new CallerFault("a new conversation cannot carry images; use a standalone run");
        if (conversations == null) throw new CallerFault("new conversations are unavailable");
        // Two doors, and which one is not a detail: a contained home's scope is never resolved
        // from its project name and its admission comes from a containment hop rather than from
        // admitting a scope, so openContained takes the proved admission and binds it. The pool is
        // the child's own allowance, not this server's default conversation budget.
        var opened =
            ask.contained() == null
                ? conversations.open(
                    home,
                    io.aeyer.plowshare.server.embedding.SpendingPolicy.budget(
                        conversationProperties.defaultBudgetLimit()),
                    turnCap,
                    ask.callerHandle())
                : conversations.openContained(
                    home,
                    ask.contained().budget(),
                    turnCap,
                    ask.callerHandle(),
                    ask.contained().admission(),
                    definition.name(),
                    // Computed here and not carried on the Ask: the fingerprint is a property of
                    // the resolved definition, which is resolved here, and a caller-supplied one
                    // could claim capabilities the agent does not have.
                    OperationExecution.fingerprint(definition));
        logStages.getObject().opened(LogStages.LogOpened.ofConversation(opened, session));
        // Two doors here as well, and for the same reason the two above are two: a contained run's
        // log carries Origin.CONTAINED, which requireSpeakable refuses by design because that guard
        // is the one a caller-supplied conversation id reaches. The contained branch therefore goes
        // through speakContained, whose key -- ContainedStart -- no code outside this class can
        // construct. THIS IS THE ONLY SITE THAT CONSTRUCTS ONE; see ContainedStart for what rests
        // on that and what must not be simplified.
        String id =
            ask.contained() == null
                ? turns.speak(
                    opened.id(),
                    definition,
                    task,
                    session,
                    turnCap,
                    ask.speaker() == null ? Speaker.person(null) : ask.speaker(),
                    outcome -> {})
                : turns.speakContained(
                    new ContainedOpened(opened.id()),
                    definition,
                    task,
                    session,
                    turnCap,
                    ask.speaker() == null ? Speaker.person(null) : ask.speaker(),
                    outcome -> {});
        return new Started(id, definition.name(), opened.id());
      }
      // incoming = true: a plain submission with no conversation is a person's own words
      // arriving fresh -- spec §6 -- exactly as an utterance into one is through
      // turns.speak() below.
      String id =
          jobs.submit(
              definition,
              task,
              home,
              session,
              turnCap,
              shown.pictures(definition, home, ask.images()),
              true,
              ownerOf(ask));
      return new Started(id, definition.name());
    }
    if (!ask.images().isEmpty()) {
      throw new CallerFault(
          "an utterance in a conversation cannot carry images. A turn's opening"
              + " message is the conversation's, and a picture that reached one"
              + " turn and no other would be a conversation whose history cannot"
              + " be replayed -- so it is refused rather than dropped. Submit the"
              + " run without a conversation, or describe the image in the task");
    }
    if (ask.project() != null) {
      throw new CallerFault(
          "this run names both a conversation and a project, and only one of them can"
              + " decide where it runs. A conversation is opened in a home and its"
              + " turns run in that home, so leave 'project' out of an utterance");
    }
    callers.requireConversationWork(ask.conversation(), ask.callerHandle());
    String id =
        turns.speak(
            ask.conversation(),
            definition,
            task,
            session,
            turnCap,
            ask.speaker() == null ? Speaker.person(null) : ask.speaker(),
            outcome -> {});
    return new Started(id, definition.name(), ask.conversation());
  }

  /**
   * The account a submission's log is owned by: the person the surface signed in, or nobody (spec
   * 2026-09-28-hooks-reach-the-log decision 8).
   */
  private static String ownerOf(Ask ask) {
    return ask.speaker() != null && ask.speaker().kind() == Speaker.Kind.PERSON
        ? ask.speaker().name()
        : null;
  }

  /**
   * Continue a machine-owned root after its pinned account answered an approval, as the plain
   * harness.
   */
  public Started continueApproved(
      String conversation, String agent, String utterance, Consumer<Outcome> ended) {
    return continueApproved(conversation, agent, utterance, Speaker.harness(), ended);
  }

  /**
   * Continue a machine-owned root, naming the approval that continues it, so the log records the
   * answer as the harness relaying that approval and never as the person's own words.
   *
   * @param speaker who is continuing the run; never null
   */
  public Started continueApproved(
      String conversation,
      String agent,
      String utterance,
      Speaker speaker,
      Consumer<Outcome> ended) {
    DefinitionResolver.Caller caller = callers.callerForConversation(conversation, null);
    AgentDefinition definition = callers.requirePinnedAgent(agent, caller);
    String id = turns.speakToApprovedRun(conversation, definition, utterance, speaker, ended);
    return new Started(id, definition.name());
  }
}
