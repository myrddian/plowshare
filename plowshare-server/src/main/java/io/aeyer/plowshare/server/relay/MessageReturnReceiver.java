package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.messaging.MessageExports;
import io.aeyer.plowshare.server.messaging.MessageReturnCodec;
import java.util.Objects;
import java.util.Optional;

/** External returns resolve a server-retained requester; the payload cannot address an instance. */
public final class MessageReturnReceiver implements RelayReceiver {
  private final MessageExports exports;
  private final RelayPublisherAccounts publishers;

  public MessageReturnReceiver(MessageExports exports, RelayPublisherAccounts publishers) {
    this.exports = Objects.requireNonNull(exports);
    this.publishers = Objects.requireNonNull(publishers);
  }

  @Override
  public void require(Request request) {
    var command = command(request);
    if (!RelayEffectBound.inspecting(request)) {
      try {
        exports.require(command);
      } catch (CallerFault refused) {
        throw new Refused("message.return.refused");
      }
    }
  }

  @Override
  public Result dispatch(Request request) {
    if (request.delivery().state() != RelayDeliveries.State.DISPATCHING)
      throw new IllegalArgumentException("Return dispatch requires committed intent");
    var command = command(request);
    try {
      return accepted(exports.accept(command));
    } catch (CallerFault refused) {
      return exports
          .inspect(command)
          .<Result>map(this::accepted)
          .orElseGet(() -> new Settled(new RelayDeliveries.Failed("message.return.refused")));
    }
  }

  @Override
  public Optional<RelayDeliveries.Resolution> inspect(Request request) {
    if (!RelayEffectBound.inspecting(request))
      throw new IllegalArgumentException("Return inspection requires uncertainty");
    return exports
        .inspect(command(request))
        .map(
            receipt ->
                new RelayDeliveries.Accepted(
                    new RelayDeliveries.Receipt("message.return", receipt.message())));
  }

  private Settled accepted(MessageExports.Receipt receipt) {
    return new Settled(
        new RelayDeliveries.Accepted(
            new RelayDeliveries.Receipt("message.return", receipt.message())));
  }

  private MessageExports.Command command(Request request) {
    var branch = request.delivery().branch();
    if (!branch.receiver().equals("message.return")
        || branch.handler() != null
        || branch.work() != null) throw new Refused("message.return.destination");
    var publication = request.delivery().publication();
    if (RelayExecutionPublisher.reserved(publication.event().publisher())
        || publishers.executionPublication(publication))
      throw new Refused("message.return.publisher");
    String account =
        publishers
            .publisherAccount(publication.event().publisher())
            .orElseThrow(() -> new Refused("message.return.publisher"));
    if (!(publication.event().payload() instanceof RelayPayload.Text text))
      throw new Refused("message.return.payload");
    try {
      return new MessageExports.Command(
          request.access().projectId(),
          request.access().project(),
          branch.name(),
          publication.topic().name(),
          account,
          MessageReturnCodec.read(text.text()),
          publication.event().eventId());
    } catch (IllegalArgumentException malformed) {
      throw new Refused("message.return.payload");
    }
  }
}
