package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Incoming;
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
import io.aeyer.plowshare.server.board.Board;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Stable messaging capability; owns instance lifecycle, delivery, recovery and authenticated
 * routes.
 */
public interface MessageService extends Dispatcher.Wakes, Messaging {
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
      boolean handled) {
    public Route {
      message = MessagePayload.identifier(message, "bdm_");
      sender = MessagePayload.identifier(sender, "ins_");
      recipient = MessagePayload.identifier(recipient, "ins_");
      if (replyTo != null) replyTo = MessagePayload.identifier(replyTo, "bdm_");
      if (message.equals(replyTo)
          || finalReply && replyTo == null
          || generated && (!finalReply || ending == null))
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
      throw new Board.Refused("Delegate continuation is unavailable.");
    }
  }

  public interface Definitions {
    AgentDefinition resolve(String name, String project);
  }

  public record Address(
      String project, String to, String conversation, boolean retainConversation) {
    public Address(String project, String to) {
      this(project, to, null, false);
    }
  }

  public interface Routing {
    Address resolve(String account, String source, String project, String to, String route);

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

  Incoming.Task receive(String account, Incoming.Receive request);

  Incoming.Task receiveScheduled(
      String account,
      String sourceProject,
      String schedule,
      String firing,
      ScheduledWork definition);

  Incoming.Task externalTask(String account, String project, String client, UUID id);

  Incoming.Task cancelExternal(String account, String project, String client, UUID id);

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
