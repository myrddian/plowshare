package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.sdk.*;
import java.io.IOException;
import java.util.*;

/** Current public SDK contracts only. A separate seam permits protocol fixtures. */
public interface Gateway extends AutoCloseable {
  void advertise(String project, List<String> peers) throws IOException;

  Outgoing.Claimed claim(String project, List<String> peers) throws IOException;

  void report(Outgoing.Report report) throws IOException;

  Outgoing.Work outgoing(UUID id) throws IOException;

  Orchestration.Started start(Orchestration.Start payload) throws IOException;

  Optional<Orchestration.Started> receipt(UUID request) throws IOException;

  Orchestration.Status status(String run) throws IOException;

  @Override
  void close();

  final class Sdk implements Gateway {
    private final Plowshare sdk;
    private final OutgoingClient outgoing;

    public Sdk(Plowshare sdk) {
      this.sdk = sdk;
      outgoing = new OutgoingClient(sdk);
    }

    public void advertise(String project, List<String> peers) throws IOException {
      outgoing.advertise(project, peers);
    }

    public Outgoing.Claimed claim(String project, List<String> peers) throws IOException {
      return outgoing.claim(project, peers);
    }

    public void report(Outgoing.Report report) throws IOException {
      Outgoing.Work saved = outgoing.report(report);
      if (!report.state().equals(saved.state())
          || saved.revision() != report.revision() + 1
          || !Objects.equals(saved.remoteTask(), report.remoteTask())
          || !Objects.equals(saved.remoteContext(), report.remoteContext())
          || !Objects.equals(saved.result(), report.result()))
        throw new IOException("outgoing report acknowledgment differs; outcome unconfirmed");
    }

    public Outgoing.Work outgoing(UUID id) throws IOException {
      return outgoing.status(id);
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
