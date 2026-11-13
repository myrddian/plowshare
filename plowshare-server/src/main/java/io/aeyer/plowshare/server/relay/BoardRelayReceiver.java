package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.board.Board;
import io.aeyer.plowshare.server.board.BoardBridge;
import io.aeyer.plowshare.server.board.BoardBridgeCodec;
import io.aeyer.plowshare.server.board.BoardNativeRequests;
import io.aeyer.plowshare.server.board.BoardPostRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pins the forum destination and attributes effects to the authenticated publication actor. */
public final class BoardRelayReceiver implements RelayReceiver {
  private final String receiver;
  private final BoardBridge bridge;
  private final RelayPublisherAccounts publishers;
  private final RelayPorts ports;
  private final RelayForwardingHistory history;
  private final int maximum;
  private final BoardNativeRequests nativeRequests;

  public BoardRelayReceiver(
      String receiver,
      BoardBridge bridge,
      RelayPublisherAccounts publishers,
      RelayPorts ports,
      RelayForwardingHistory history,
      int maximum) {
    this(receiver, bridge, publishers, ports, history, maximum, BoardNativeRequests.NONE);
  }

  public BoardRelayReceiver(
      String receiver,
      BoardBridge bridge,
      RelayPublisherAccounts publishers,
      RelayPorts ports,
      RelayForwardingHistory history,
      int maximum,
      BoardNativeRequests nativeRequests) {
    this.nativeRequests = Objects.requireNonNull(nativeRequests);
    if (!Set.of("board.open", "board.post", "board.close").contains(receiver))
      throw new IllegalArgumentException("Unsupported board receiver");
    this.receiver = receiver;
    this.bridge = Objects.requireNonNull(bridge);
    this.publishers = Objects.requireNonNull(publishers);
    this.ports = Objects.requireNonNull(ports);
    this.history = Objects.requireNonNull(history);
    RelayValues.limit(maximum, 32);
    this.maximum = maximum;
  }

  @Override
  public void require(Request request) {
    var principal = principal(request);
    try {
      var command = command(request, principal);
      if (!RelayEffectBound.inspecting(request)) bridge.require(command);
    } catch (Board.Refused refused) {
      if (!(principal.actor() instanceof BoardBridge.Agent))
        throw new Refused("board.destination.refused");
      // A proven native export needs a durable refusal return after dispatch intent. Preflight
      // remains read-only; SDK/person requests retain definite early destination refusal.
    } catch (Refused refused) {
      if (!(principal.actor() instanceof BoardBridge.Agent)) throw refused;
    }
  }

  @Override
  public Result dispatch(Request request) {
    if (request.delivery().state() != RelayDeliveries.State.DISPATCHING)
      throw new IllegalArgumentException("Board dispatch requires committed intent");
    var principal = principal(request);
    BoardBridge.Command command;
    try {
      command = command(request, principal);
    } catch (Refused refused) {
      if (principal.actor() instanceof BoardBridge.Agent)
        return refused(request, principal, refused);
      throw refused;
    }
    try {
      return accepted(bridge.accept(command));
    } catch (BoardPostRepository.Conflict conflict) {
      if (principal.actor() instanceof BoardBridge.Agent) return intentConflict();
      throw conflict;
    } catch (Board.Refused refusal) {
      try {
        var prior = bridge.inspect(command);
        if (prior.isPresent()) return accepted(prior.orElseThrow());
      } catch (BoardPostRepository.Conflict conflict) {
        if (principal.actor() instanceof BoardBridge.Agent) return intentConflict();
        throw conflict;
      }
      return refused(request, principal, new Refused("board.destination.refused"));
    }
  }

  private Settled refused(Request request, Principal principal, Refused refusal) {
    if (principal.actor() instanceof BoardBridge.Agent agent) {
      var result = nativeRequests.refuse(request.identity(), agent);
      if (result == BoardNativeRequests.Refusal.EFFECT_ACCEPTED) return intentConflict();
      if (result != BoardNativeRequests.Refusal.RECORDED)
        throw new IllegalStateException("Native refusal returned no outcome");
      return new Settled(new RelayDeliveries.Failed("board.native.refused"));
    }
    return new Settled(new RelayDeliveries.Failed(refusal.code()));
  }

  @Override
  public Optional<RelayDeliveries.Resolution> inspect(Request request) {
    if (!RelayEffectBound.inspecting(request))
      throw new IllegalArgumentException("Board inspection requires uncertain dispatch");
    var principal = principal(request);
    if (principal.actor() instanceof BoardBridge.Agent agent && nativeRequests.refused(agent))
      return Optional.of(new RelayDeliveries.Failed("board.native.refused"));
    try {
      return bridge
          .inspect(command(request, principal))
          .map(
              receipt ->
                  new RelayDeliveries.Accepted(
                      new RelayDeliveries.Receipt("board.message", receipt.message())));
    } catch (BoardPostRepository.Conflict conflict) {
      if (principal.actor() instanceof BoardBridge.Agent)
        return Optional.of(new RelayDeliveries.Failed("board.native.intent-conflict"));
      throw conflict;
    } catch (Refused refused) {
      if (principal.actor() instanceof BoardBridge.Agent agent && nativeRequests.accepted(agent))
        return Optional.of(new RelayDeliveries.Failed("board.native.intent-conflict"));
      throw refused;
    }
  }

  private static Settled intentConflict() {
    return new Settled(new RelayDeliveries.Failed("board.native.intent-conflict"));
  }

  private record Principal(BoardBridge.Actor actor, boolean authorized) {}

  private Principal principal(Request request) {
    var branch = request.delivery().branch();
    if (!receiver.equals(branch.receiver())) throw new Refused("board.receiver.mismatch");
    RelayWork.require(receiver, branch.work(), branch.handler() != null);
    var publication = request.delivery().publication();
    var execution = publishers.executionIdentity(publication);
    if (execution.isPresent()) {
      try {
        var proven =
            nativeRequests.actor(
                publication,
                request.access().project(),
                branch.name(),
                receiver,
                execution.orElseThrow(),
                RelayEffectBound.inspecting(request));
        return new Principal(proven.actor(), proven.authorized());
      } catch (BoardNativeRequests.Unavailable unavailable) {
        throw new Refused("board.publisher.native-unavailable");
      }
    }
    if (RelayExecutionPublisher.reserved(publication.event().publisher()))
      throw new Refused("board.publisher.execution");
    var account =
        publishers
            .publisherAccount(publication.event().publisher())
            .orElseThrow(() -> new Refused("board.publisher.unavailable"));
    return new Principal(new BoardBridge.Person(account), true);
  }

  private Settled accepted(BoardBridge.Receipt receipt) {
    return new Settled(
        new RelayDeliveries.Accepted(
            new RelayDeliveries.Receipt("board.message", receipt.message())));
  }

  private BoardBridge.Command command(Request request, Principal principal) {
    var delivery = request.delivery();
    var branch = delivery.branch();
    var publication = delivery.publication();
    if (!RelayEffectBound.inspecting(request) && !principal.authorized())
      throw new Refused("board.publisher.execution-grant");
    if (!(publication.event().payload() instanceof RelayPayload.Text text))
      throw new Refused("board.payload.refused");
    BoardBridge.Payload payload;
    try {
      payload = BoardBridgeCodec.request(text.text());
    } catch (IllegalArgumentException invalid) {
      throw new Refused("board.payload.refused");
    }
    var actor = principal.actor();
    if (actor instanceof BoardBridge.Agent) {
      if (payload.replyTo() != null) throw new Refused("board.reply.native-required");
    } else if (!RelayEffectBound.inspecting(request)) {
      var account = actor.account();
      if (!publication
          .topic()
          .equals(
              ports.authorizePublisher(
                  account, request.access().project(), publication.topic().name())))
        throw new Refused("board.publisher.scope");
      if (payload.replyTo() != null) {
        try {
          var destination =
              ports.authorizePublisher(account, request.access().project(), payload.replyTo());
          if (!destination.equals(
              new Relay.TopicKey(request.access().projectId(), payload.replyTo())))
            throw new Refused("board.reply.scope");
        } catch (CallerFault denied) {
          throw new Refused("board.reply.scope");
        }
      }
    }
    var work = branch.work();
    BoardBridge.Target target =
        switch (receiver) {
          case "board.open" -> new BoardBridge.Open(request.access().project(), work.swarm());
          case "board.post" -> new BoardBridge.Post(work.topic());
          case "board.close" -> new BoardBridge.Close(work.topic());
          default -> throw new Refused("board.receiver.mismatch");
        };
    return new BoardBridge.Command(
        actor instanceof BoardBridge.Agent agent ? agent.effectRequestId() : request.identity(),
        actor,
        request.access().projectId(),
        publication.event().eventId(),
        target,
        payload,
        RelayEffectBound.inspecting(request)
            ? publication.event().causation()
            : RelayEffectBound.next(history, publication, maximum));
  }
}
