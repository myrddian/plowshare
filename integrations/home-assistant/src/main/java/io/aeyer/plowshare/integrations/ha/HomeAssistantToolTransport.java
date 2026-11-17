package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.sdk.RequestCancellation;
import io.aeyer.plowshare.sdk.RequestOptions;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Remaining-budget public SDK transport. Cancellation detaches this caller, never remote work. */
public interface HomeAssistantToolTransport {
  RelayPort.Batch consume(
      RelayPort.Consume request, OperationBudget budget, RequestCancellation cancellation)
      throws IOException;

  RelayPort.Acknowledged acknowledge(
      RelayPort.Ack request, OperationBudget budget, RequestCancellation cancellation)
      throws IOException;

  RelayPort.Published publish(
      RelayPort.Publish request, OperationBudget budget, RequestCancellation cancellation)
      throws IOException;

  RelayLog.Page log(
      RelayLog.Query request, OperationBudget budget, RequestCancellation cancellation)
      throws IOException;

  /** Uses supported async facades with fail-fast dispatch: no hidden capacity wait or replay. */
  final class Sdk implements HomeAssistantToolTransport {
    private final RelayClient relay;

    public Sdk(RelayClient relay) {
      this.relay = Objects.requireNonNull(relay);
    }

    private static RequestOptions options(OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      return new RequestOptions(
          budget.remaining(), cancellation, RequestOptions.Capacity.NON_BLOCKING);
    }

    @Override
    public RelayPort.Batch consume(
        RelayPort.Consume request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      return await(
          relay.consumeAsync(request, options(budget, cancellation)), budget, cancellation);
    }

    @Override
    public RelayPort.Acknowledged acknowledge(
        RelayPort.Ack request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      return await(
          relay.acknowledgeAsync(request, options(budget, cancellation)), budget, cancellation);
    }

    @Override
    public RelayPort.Published publish(
        RelayPort.Publish request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      return await(
          relay.publishAsync(request, options(budget, cancellation)), budget, cancellation);
    }

    @Override
    public RelayLog.Page log(
        RelayLog.Query request, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      return await(relay.logAsync(request, options(budget, cancellation)), budget, cancellation);
    }

    private static <T> T await(
        CompletionStage<T> stage, OperationBudget budget, RequestCancellation cancellation)
        throws IOException {
      try {
        T result =
            stage.toCompletableFuture().get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
        budget.remaining();
        return result;
      } catch (InterruptedException interrupted) {
        cancellation.cancel();
        Thread.currentThread().interrupt();
        throw new IOException("HA provider SDK operation interrupted; submission may be uncertain");
      } catch (TimeoutException expired) {
        cancellation.cancel();
        throw new IOException("HA provider SDK operation expired; submission may be uncertain");
      } catch (ExecutionException failed) {
        if (failed.getCause() instanceof IOException io) throw io;
        throw new IOException("HA provider SDK operation failed", failed.getCause());
      } catch (IOException failed) {
        cancellation.cancel();
        throw failed;
      }
    }
  }
}
