package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Messaging;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Stable messaging capability; owns instance lifecycle, delivery, recovery and authenticated
 * routes.
 */
public interface MessageService
    extends Dispatcher.Wakes, Messaging, NativeMessageExports, MessageIngress {
  /** Verify a named alert route resolves to an eligible bot in the account's own Personal home. */
  void requirePersonalAlertRoute(String account, String route);

  /**
   * Enqueue a reference-only alert through private messaging. Identical item and route retry
   * safely.
   */
  String sendPersonalAlert(
      String account, String route, PersonalAlert reference, RelayCausation cause);

  /** A notice may carry a capability to read one exact account Mailbox result. */
  record PersonalAlert(String item, String firing) {
    public PersonalAlert {
      item = ScheduledWork.identity(item, "Personal alert item");
      if (firing != null) firing = ScheduledWork.identity(firing, "Personal alert firing");
    }
  }

  Optional<PersonalAlert> mailboxAlertFor(RunExtras.Context context);

  /** Signals waiting instances when a turn in their retained conversation releases its gate. */
  void whenFree(String conversation);

  public record Instance(
      String id,
      String account,
      String project,
      String agent,
      String conversation,
      String lifetime,
      boolean active) {
    public Instance {
      id = MessagePayload.identifier(id, "ins_");
      account = RelayPort.identity(account);
      if (Home.of(project).isGlobal())
        throw new IllegalArgumentException("Messaging requires a project");
      agent = RelayPort.identity(agent);
      conversation = MessagePayload.identifier(conversation, "cnv_");
      if (!Set.of("persistent", "task", "caller", "external").contains(lifetime))
        throw new IllegalArgumentException("Invalid message instance lifetime");
    }
  }

  public record Route(
      String message,
      String sender,
      String recipient,
      String replyTo,
      boolean replyExpected,
      boolean finalReply,
      boolean generated,
      String ending,
      boolean handled,
      RelayCausation causation,
      GoalMessagePurpose goalPurpose) {
    public Route(
        String message,
        String sender,
        String recipient,
        String replyTo,
        boolean replyExpected,
        boolean finalReply,
        boolean generated,
        String ending,
        boolean handled,
        RelayCausation causation) {
      this(
          message,
          sender,
          recipient,
          replyTo,
          replyExpected,
          finalReply,
          generated,
          ending,
          handled,
          causation,
          GoalMessagePurpose.ORDINARY);
    }

    public Route(
        String message,
        String sender,
        String recipient,
        String replyTo,
        boolean replyExpected,
        boolean finalReply,
        boolean generated,
        String ending,
        boolean handled) {
      this(
          message,
          sender,
          recipient,
          replyTo,
          replyExpected,
          finalReply,
          generated,
          ending,
          handled,
          null);
    }

    public Route {
      Objects.requireNonNull(goalPurpose);
      message = MessagePayload.identifier(message, "bdm_");
      sender = MessagePayload.identifier(sender, "ins_");
      recipient = MessagePayload.identifier(recipient, "ins_");
      if (replyTo != null) replyTo = MessagePayload.identifier(replyTo, "bdm_");
      if (message.equals(replyTo)
          || finalReply && replyTo == null
          || generated
              && (replyTo == null
                  || ending == null
                  || !finalReply
                      && (replyExpected
                          || !Set.of(
                                  "UNAVAILABLE",
                                  "SUB_AGENT_FAILED",
                                  "SESSION_GONE",
                                  "STUCK",
                                  "CALL_FAILURES")
                              .contains(ending))))
        throw new IllegalArgumentException("Invalid message reply relationship");
      if (ending != null) Outcome.Ending.valueOf(ending);
    }
  }

  public interface Voice {
    boolean busy(String conversation);

    default boolean busy(Instance instance) {
      return busy(instance.conversation());
    }

    String speak(
        Instance instance,
        AgentDefinition definition,
        String utterance,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended);

    /** Starts a message turn with an explicit source; older fixture voices can ignore metadata. */
    default String speakFrom(
        Instance instance,
        AgentDefinition definition,
        String utterance,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended,
        Speaker source,
        boolean command) {
      return command
          ? speakCommand(instance, definition, utterance, lease, cap, ended)
          : speak(instance, definition, utterance, lease, cap, ended);
    }

    default String speakCommand(
        Instance instance,
        AgentDefinition definition,
        String utterance,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended) {
      return speak(instance, definition, utterance, lease, cap, ended);
    }

    default String continueApproved(
        Instance instance,
        AgentDefinition definition,
        String utterance,
        String approval,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended) {
      return speak(instance, definition, utterance, lease, cap, ended);
    }

    default String resumeDelegate(
        Instance instance,
        String child,
        AgentDefinition definition,
        String utterance,
        String approval,
        Budget lease,
        Consumer<Outcome> ended) {
      throw new MessageRefused("Delegate continuation is unavailable.");
    }

    /** Exact message ancestry follows every native continuation into its paid turn. */
    default String speakFrom(
        Instance instance,
        AgentDefinition definition,
        String utterance,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended,
        Speaker source,
        boolean command,
        RelayCausation causation) {
      return speakFrom(instance, definition, utterance, lease, cap, ended, source, command);
    }

    default String continueApproved(
        Instance instance,
        AgentDefinition definition,
        String utterance,
        String approval,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended,
        RelayCausation causation) {
      return continueApproved(instance, definition, utterance, approval, lease, cap, ended);
    }

    default String resumeDelegate(
        Instance instance,
        String child,
        AgentDefinition definition,
        String utterance,
        String approval,
        Budget lease,
        Consumer<Outcome> ended,
        RelayCausation causation) {
      return resumeDelegate(instance, child, definition, utterance, approval, lease, ended);
    }
  }

  public interface Definitions {
    AgentDefinition resolve(String name, String project);

    /** The server-side exported candidates visible to this account in the messaging destination. */
    default List<DefinitionAddress> exported(String account, String project) {
      return List.of();
    }
  }

  /** A sendable public name and the canonical definition that owns its default mailbox. */
  public record DefinitionAddress(String definition, String to) {}

  public record Address(
      String project, String to, String conversation, boolean retainConversation) {
    public Address(String project, String to) {
      this(project, to, null, false);
    }
  }

  public interface Routing {
    Address resolve(String account, String source, String project, String to, String route);

    /** Resolve a direct destination using the same account-specific aliases as message sends. */
    default String project(String account, String source, String destination) {
      return destination == null ? source : destination;
    }

    void require(String account, String source, String destination);

    default String identify(String project) {
      return project;
    }
  }

  public record InstanceView(
      String id,
      String project,
      String agent,
      String conversation,
      String lifetime,
      boolean defaultInstance,
      boolean active,
      boolean archived,
      String state,
      int pending,
      String job,
      Instant createdAt) {}

  public record Delivery(
      String message,
      String sender,
      String recipient,
      String replyTo,
      boolean replyExpected,
      boolean finalReply,
      boolean generated,
      String state,
      String ending,
      String reply,
      String job,
      Instant deadlineAt,
      Instant postedAt,
      String body,
      String conversation,
      String sourceConversation) {}

  public record InstancePage(List<InstanceView> instances, boolean more, int offset) {}

  Optional<Instance> instance(String id);

  Optional<Route> route(String message);

  /**
   * Bind a passive Goal return reader to one live native transport. The lineage callback brings the
   * exact source log's current information restrictions into this run's authenticated log before
   * reply prose enters it.
   */
  default Optional<AgentTool> goalReturnReader(
      RunExtras.Context context, Consumer<String> inherit) {
    return Optional.empty();
  }

  SendMessageTool tool(RunExtras.Context context);

  String send(RunExtras.Context context, SendMessageTool.Request request, Home home);

  String send(
      RunExtras.Context context,
      SendMessageTool.Request request,
      Home home,
      UsageAttribution owner);

  Optional<String> finalReply(String message);

  String incoming(String message);

  void complete(String message, Outcome outcome);

  /** Projects an addressed input only for its exact retained recipient. */
  default Optional<String> goalInput(Instance recipient, String message) {
    return Optional.empty();
  }

  boolean owns(FiringRecord wake);

  boolean busy(FiringRecord wake);

  boolean continueApproved(RunApproval approval, String utterance);

  String start(FiringRecord wake, BiConsumer<String, Outcome> ended);

  void settled(FiringRecord wake);

  void recover();

  Instance owned(String id, String account);

  InstanceView inspect(String id, String account);

  List<InstanceView> listing(
      String account, String project, boolean archived, int offset, int limit);

  InstancePage listingPage(String account, String project, boolean archived, int offset, int limit);

  InstanceView open(
      String account, String project, String agent, boolean makeDefault, String requestId);

  /** Checked only by the schedule owner while resolving an existing Messaging destination. */
  default boolean goalScheduleTarget(Instance instance) {
    return false;
  }

  /** Allocates a non-default inbox inside the authorized Goal launch transaction. */
  default InstanceView openGoalInbox(String account, String project, String agent) {
    throw new UnsupportedOperationException("A persistent Goal inbox is unavailable");
  }

  /** An exact native Messaging request retained for one Goal handoff. */
  record GoalReturn(String sender, String recipient, String message) {
    public GoalReturn {
      sender = MessagePayload.identifier(sender, "ins_");
      recipient = MessagePayload.identifier(recipient, "ins_");
      message = MessagePayload.identifier(message, "bdm_");
      if (sender.equals(recipient))
        throw new IllegalArgumentException("Distinct Goal return endpoints required");
    }
  }

  /** Purpose encoded by the native invocation namespace, outside model call IDs. */
  enum GoalMessagePurpose {
    ORDINARY(""),
    HANDOFF("goal-return:launch:"),
    QUESTION("goal-return:question:"),
    NOTICE("goal-return:notice:"),
    TERMINAL("goal-return:terminal:");

    private final String prefix;

    GoalMessagePurpose(String prefix) {
      this.prefix = prefix;
    }

    String invocation(UUID identity) {
      if (this == ORDINARY) throw new IllegalStateException("Native Goal purpose required");
      return prefix + identity;
    }

    static GoalMessagePurpose of(String invocation) {
      if (invocation == null || !invocation.startsWith("goal-return:")) return ORDINARY;
      for (var purpose : List.of(HANDOFF, QUESTION, NOTICE, TERMINAL)) {
        if (invocation.startsWith(purpose.prefix)) return purpose;
      }
      return ORDINARY;
    }
  }

  enum GoalReturnKind {
    RESULT,
    QUESTION,
    BLOCKED,
    CANCELLED
  }

  sealed interface GoalReturnAttempt permits GoalReturnPublished, GoalReturnUnavailable {}

  record GoalReturnPublished(String message) implements GoalReturnAttempt {}

  record GoalReturnUnavailable() implements GoalReturnAttempt {}

  /**
   * Join the launch transaction and retain a passive native request addressed to the new Goal. The
   * original request is supplied by the skill's admitted turn, never by its arguments.
   */
  default GoalReturn openGoalReturn(
      String account,
      String project,
      String conversation,
      AgentDefinition caller,
      String originalMessage,
      String goalInstance,
      UUID invocation) {
    throw new MessageRefused("A correlated Goal return is unavailable");
  }

  /**
   * Publish a passive reply to the exact retained request. The acceptance callback runs with
   * Messaging's mailbox and request locks, in the same transaction as the reply.
   */
  default GoalReturnAttempt publishGoalReturn(
      GoalReturn binding,
      UUID intent,
      String body,
      GoalReturnKind kind,
      Consumer<String> accepted) {
    return new GoalReturnUnavailable();
  }

  sealed interface GoalAnswer permits GoalAnswerReady, GoalAnswerPending, GoalAnswerClosed {}

  record GoalAnswerReady(MessageRecord message) implements GoalAnswer {}

  record GoalAnswerPending() implements GoalAnswer {}

  record GoalAnswerClosed() implements GoalAnswer {}

  /** Read the exact bound caller's final answer or report that the native question has closed. */
  default GoalAnswer goalAnswer(GoalReturn binding, String questionMessage) {
    return new GoalAnswerClosed();
  }

  /** Claim an exact still-authorized response while serialized against native route closure. */
  default boolean withGoalAnswer(
      GoalReturn binding, String questionMessage, String answerMessage, Runnable claim) {
    return false;
  }

  /** Record a body-free unavailable outcome for the exact native launch. */
  default void withholdGoalReturn(GoalReturn binding, Runnable recorded) {
    recorded.run();
  }

  /** Settle a withdrawn exact Goal question without generating a Messaging outcome. */
  default void settleGoalQuestion(GoalReturn binding, String questionMessage) {}

  InstanceView makeDefault(String id, String account);

  List<Delivery> deliveries(String id, String account, int offset, int limit);

  Delivery delivery(String message, String account);

  Delivery cancel(String message, String account);

  InstanceView stop(String id, String account, boolean archive);

  void expire();

  Optional<SwarmScheduler.Share> share(RunExtras.Context context);

  boolean isTransport(String conversation);

  boolean visible(String target, String current);

  AgentTool protect(AgentTool tool, AgentDefinition definition, String conversation);
}
