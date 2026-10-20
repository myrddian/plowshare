package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.board.BoardMessaging.Instance;
import io.aeyer.plowshare.server.board.BoardMessaging.Route;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable private-message routes, instances and ingress receipts. Lock methods require the caller's
 * UnitOfWork transaction. The service coordinates related board/log writes in that same
 * transaction, then dispatches only after commit. This repository never executes a model, drains a
 * firing, or retries an interrupted handling turn.
 */
public interface BoardMessagingRepository {
  enum Termination {
    CANCELLED,
    EXPIRED,
    STOPPED
  }

  record InstanceState(boolean defaultInstance, boolean archived, Instant createdAt) {}

  /**
   * One statement's operator listing data; live dispatch/lease decisions must recheck their owners.
   */
  record InstanceListing(
      Instance instance, InstanceState state, boolean awaiting, int pending, Optional<String> job) {
    public InstanceListing {
      java.util.Objects.requireNonNull(instance);
      java.util.Objects.requireNonNull(state);
      java.util.Objects.requireNonNull(job);
      if (pending < 0) throw new IllegalArgumentException("Negative pending messages");
    }
  }

  /** Read-only delivery page snapshot; claims, cancellation and dispatch always recheck owners. */
  record DeliveryListing(
      Route route,
      Instance sender,
      Instance recipient,
      DeliveryState state,
      Optional<String> job,
      boolean running,
      Optional<String> finalReply,
      Instant postedAt,
      String body) {
    public DeliveryListing {
      java.util.Objects.requireNonNull(route);
      java.util.Objects.requireNonNull(sender);
      java.util.Objects.requireNonNull(recipient);
      java.util.Objects.requireNonNull(state);
      java.util.Objects.requireNonNull(job);
      java.util.Objects.requireNonNull(finalReply);
      java.util.Objects.requireNonNull(postedAt);
      java.util.Objects.requireNonNull(body);
    }
  }

  record DeliveryState(boolean awaiting, String termination, Instant deadline) {}

  record ExternalPrior(
      java.util.UUID id,
      java.util.UUID requestContext,
      String body,
      String command,
      String agent,
      boolean matches) {}

  record ExternalContext(String sender, String recipient) {}

  record ExternalTask(
      java.util.UUID context,
      String agent,
      String message,
      io.aeyer.plowshare.protocol.Incoming.Source source,
      Instant createdAt) {}

  Optional<Route> route(String message);

  List<String> retainedBindings(String account, String project, String name);

  void bindRoute(String account, String project, String name, String instance);

  void lockConversation(String id);

  void lockAddress(String key);

  int activeInstances(String account, String project);

  void insertInstance(Instance instance, boolean isDefault);

  void passiveSender(String id, String client);

  void insertExternalContext(
      java.util.UUID context,
      String account,
      io.aeyer.plowshare.protocol.Incoming.Receive request,
      String sender,
      String recipient);

  List<String> replies(String message);

  int lastTurn(String conversation);

  boolean queued(String id);

  /**
   * Original intent for conflict validation under the existing send lock. NULL fingerprint is
   * legacy.
   */
  record PriorSend(String message, String fingerprint) {}

  Optional<PriorSend> priorSend(String sender, String call);

  /** Upgrade fence: old handling turns cannot safely acquire a different invocation-key scheme. */
  boolean legacySendTurn(String sender, int turn);

  boolean terminated(String message);

  int pendingCount(String recipient);

  void insertRoute(Route route, String call, String fingerprint);

  void deadline(String message, Instant deadline);

  void addAllowance(String topic, int allowance);

  void handled(String message);

  Optional<String> externalCommand(String message);

  void lockRequest(String message);

  Optional<String> finalReply(String message);

  void awaiting(String message);

  void endRequest(String message, Outcome.Ending ending);

  void deactivate(String id);

  boolean otherStarted(String recipient, String message);

  boolean approvalQueued(String approval);

  List<String> lockHandling(String recipient);

  Instant deadline(String message);

  void started(String message);

  List<String> interrupted();

  List<String> answeredApprovals();

  Optional<String> instanceJob(String topic);

  boolean awaitingAny(String recipient);

  boolean openingDefault(String id);

  void clearDefault(String account, String project, String agent);

  void recordOpening(String id, String requestId, boolean makeDefault);

  void makeDefault(String id);

  List<String> deliveries(String id, int offset, int limit);

  /**
   * Same raw page/order as deliveries, with its route/instance/message/status projections batched.
   */
  List<DeliveryListing> deliveryPage(String id, int offset, int limit);

  /** Latest submitted job, including completed runs, while its firing record is retained. */
  Optional<String> messageJob(String message);

  void terminate(String message, Termination state);

  void refuseQueued(String message, String reason);

  List<String> runningJobs(String message);

  void stop(String id, boolean archive);

  List<String> pending(String id);

  List<String> expired(Instant now);

  Optional<Instance> instance(String id);

  List<Instance> byConversationAgent(String conversation, String agent);

  List<Instance> byConversation(String conversation);

  List<Instance> transport(String conversation);

  List<Instance> defaults(String account, String project, String agent);

  List<Instance> opening(String account, String project, String requestId);

  List<InstanceListing> listing(
      String account, String project, boolean archived, int offset, int limit);

  InstanceState instanceState(String id);

  DeliveryState deliveryState(String message);

  Optional<ExternalPrior> priorExternal(
      String account, io.aeyer.plowshare.protocol.Incoming.Receive request);

  Optional<ExternalContext> externalContext(
      java.util.UUID context, String account, io.aeyer.plowshare.protocol.Incoming.Receive request);

  void insertExternalTask(
      java.util.UUID id,
      java.util.UUID context,
      String account,
      io.aeyer.plowshare.protocol.Incoming.Receive request,
      String message);

  Optional<ExternalTask> externalTask(
      String account, String project, String client, java.util.UUID id);
}
