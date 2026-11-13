package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.board.Board;
import io.aeyer.plowshare.server.board.BoardBridge;
import io.aeyer.plowshare.server.board.BoardBridgeCodec;
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

  public BoardRelayReceiver(
      String receiver,
      BoardBridge bridge,
      RelayPublisherAccounts publishers,
      RelayPorts ports,
      RelayForwardingHistory history,
      int maximum) {
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
    var command = command(request);
    if (!RelayEffectBound.inspecting(request)) {
      try {
        bridge.require(command);
      } catch (Board.Refused refused) {
        throw new Refused("board.destination.refused");
      }
    }
  }

  @Override
  public Result dispatch(Request request) {
    if (request.delivery().state() != RelayDeliveries.State.DISPATCHING)
      throw new IllegalArgumentException("Board dispatch requires committed intent");
    var command = command(request);
    try {
      return accepted(bridge.accept(command));
    } catch (Board.Refused refused) {
      // An after-commit callback can fail after the forum accepted. A retained receipt is stronger
      // evidence than the exception; a transactional refusal with no receipt produced no effect.
      return bridge
          .inspect(command)
          .<Result>map(this::accepted)
          .orElseGet(() -> new Settled(new RelayDeliveries.Failed("board.destination.refused")));
    }
  }

  @Override
  public Optional<RelayDeliveries.Resolution> inspect(Request request) {
    if (!RelayEffectBound.inspecting(request))
      throw new IllegalArgumentException("Board inspection requires uncertain dispatch");
    return bridge
        .inspect(command(request))
        .map(
            receipt ->
                new RelayDeliveries.Accepted(
                    new RelayDeliveries.Receipt("board.message", receipt.message())));
  }

  private Settled accepted(BoardBridge.Receipt receipt) {
    return new Settled(
        new RelayDeliveries.Accepted(
            new RelayDeliveries.Receipt("board.message", receipt.message())));
  }

  private BoardBridge.Command command(Request request) {
    var delivery = request.delivery();
    var branch = delivery.branch();
    if (!receiver.equals(branch.receiver())) throw new Refused("board.receiver.mismatch");
    RelayWork.require(receiver, branch.work(), branch.handler() != null);
    var publication = delivery.publication();
    String account =
        publishers
            .publisherAccount(publication.event().publisher())
            .orElseThrow(() -> new Refused("board.publisher.unavailable"));
    if (!RelayEffectBound.inspecting(request)) {
      if (!publication
          .topic()
          .equals(
              ports.authorizePublisher(
                  account, request.access().project(), publication.topic().name())))
        throw new Refused("board.publisher.scope");
    }
    if (!(publication.event().payload() instanceof RelayPayload.Text text))
      throw new Refused("board.payload.refused");
    BoardBridge.Payload payload;
    try {
      payload = BoardBridgeCodec.request(text.text());
    } catch (IllegalArgumentException invalid) {
      throw new Refused("board.payload.refused");
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
        request.identity(),
        account,
        request.access().projectId(),
        publication.event().eventId(),
        target,
        payload,
        RelayEffectBound.inspecting(request)
            ? publication.event().causation()
            : RelayEffectBound.next(history, publication, maximum));
  }
}
