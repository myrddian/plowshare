package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.OrchestrationClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Current public SDK contracts only. A separate seam permits protocol fixtures. */
public interface Gateway extends AutoCloseable {
  RelayLog.Page log(RelayLog.Query query) throws IOException;

  RelayPort.Published publish(RelayPort.Publish publication) throws IOException;

  Orchestration.Started start(Orchestration.Start payload) throws IOException;

  Optional<Orchestration.Started> receipt(UUID request) throws IOException;

  Orchestration.Status status(String run) throws IOException;

  @Override
  void close();

  final class Sdk implements Gateway {
    private final Plowshare sdk;
    private final RelayClient relay;

    public Sdk(Plowshare sdk) {
      this.sdk = sdk;
      relay = new RelayClient(sdk);
    }

    public RelayLog.Page log(RelayLog.Query query) throws IOException {
      return relay.log(query);
    }

    public RelayPort.Published publish(RelayPort.Publish publication) throws IOException {
      return relay.publish(publication);
    }

    public Orchestration.Started start(Orchestration.Start payload) throws IOException {
      return new OrchestrationClient(sdk).start(payload);
    }

    public Optional<Orchestration.Started> receipt(UUID request) throws IOException {
      return new OrchestrationClient(sdk).receipt(request);
    }

    public Orchestration.Status status(String run) throws IOException {
      return new OrchestrationClient(sdk).status(run);
    }

    public void close() {
      sdk.close();
    }
  }
}
