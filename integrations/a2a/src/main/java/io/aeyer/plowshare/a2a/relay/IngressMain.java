package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.Ingress;
import io.aeyer.plowshare.a2a.Receiver;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.AdministrationClient;
import io.aeyer.plowshare.sdk.AgentClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Receiving launcher. Existing WS Relay operations own all admission, inspection and cancellation.
 */
public final class IngressMain {
  private IngressMain() {}

  public static void main(String[] args) throws Exception {
    int status =
        RelayMain.classify(
            () -> {
              if (args.length < 1 || args.length > 3)
                throw new IllegalArgumentException(
                    "Usage: plowshare-a2a CONFIG [owner | rotate-config PREVIOUS_OWNER | import-provenance PRIVATE_BUNDLE]");
              var config = IngressConfiguration.read(Path.of(args[0]), System::getenv);
              if (args.length == 3 && args[1].equals("rotate-config")) {
                try (var sdk =
                    Plowshare.connect(
                        config.origin(), config.token(), Duration.ofSeconds(30), null)) {
                  verifyPrincipal(config, sdk);
                }
                try (var journal =
                    FileIngressJournal.rotate(
                        config.journal(),
                        config.fingerprint(),
                        config.identityFingerprint(),
                        args[2])) {
                  System.out.println(
                      "Receiving credential rotation recorded; owner="
                          + journal.ownerFingerprint());
                }
                return;
              }
              if (args.length > 1
                  && !(args.length == 2 && args[1].equals("owner"))
                  && !(args.length == 3 && args[1].equals("import-provenance")))
                throw new IllegalArgumentException("Invalid receiving command arguments");
              try (var journal =
                  new FileIngressJournal(
                      config.journal(), config.fingerprint(), config.identityFingerprint())) {
                if (args.length == 2) {
                  System.out.println(journal.ownerFingerprint());
                  return;
                }
                try (var sdk =
                    Plowshare.connect(
                        config.origin(), config.token(), Duration.ofSeconds(30), null)) {
                  var bridge = connect(config, sdk, journal);
                  if (args.length == 3) {
                    int count =
                        LegacyProvenanceImport.apply(
                            config,
                            LegacyProvenanceBundle.read(Path.of(args[2])),
                            bridge,
                            journal,
                            Instant::now);
                    System.out.println("Imported historical task provenance records=" + count);
                    return;
                  }
                  journal.requireHealthy();
                  try (var receiver = new Receiver(config.receiver(), bridge)) {
                    System.out.println("A2A receiving listener ready on port " + receiver.port());
                    while (!Thread.currentThread().isInterrupted() && sdk.connected()) {
                      journal.requireHealthy();
                      Thread.sleep(250);
                    }
                    if (!Thread.currentThread().isInterrupted() && !sdk.connected())
                      throw RelayMain.temporaryFailure();
                  }
                } catch (Plowshare.TransportException transientTransport) {
                  throw RelayMain.temporaryFailure();
                }
              }
            });
    if (status != 0) System.exit(status);
  }

  private static void verifyPrincipal(IngressConfiguration config, Plowshare sdk)
      throws IOException {
    if (config.principal() != null
        && !config.principal().equals(new AdministrationClient(sdk).status().handle()))
      throw new IOException("Authenticated receiving principal differs from configured identity");
  }

  /** Explicit composition also used by independent SDK/server acceptance harnesses. */
  public static RelayIngressBridge connect(
      IngressConfiguration config, Plowshare sdk, IngressJournal journal) throws IOException {
    verifyPrincipal(config, sdk);
    var relay = new RelayClient(sdk);
    var gateway =
        new RelayGateway() {
          public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
            return relay.consume(request);
          }

          public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
            try {
              return relay.publish(request);
            } catch (Plowshare.TransportException failure) {
              if (failure.delivery() == Plowshare.Delivery.NOT_SUBMITTED)
                throw new PublicationNotSubmitted();
              throw failure;
            }
          }

          public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
            return relay.acknowledge(request);
          }

          public RelayLog.Page log(RelayLog.Query request) throws IOException {
            return relay.log(request);
          }
        };
    return new RelayIngressBridge(
        config.binding(),
        gateway,
        journal,
        () -> {
          var rows = new AgentClient(sdk).list(config.binding().project());
          var selected =
              rows.stream()
                  .filter(row -> row.name().equals(config.binding().agent()))
                  .findFirst()
                  .orElseThrow(() -> new IOException("Configured receiving agent is unavailable"));
          var commands =
              selected.commands().stream()
                  .map(
                      command ->
                          new Ingress.Command(
                              command.command(),
                              command.aliases(),
                              command.kind(),
                              command.name(),
                              command.description(),
                              command.argumentHint(),
                              command.executor(),
                              command.mode(),
                              command.tier(),
                              command.hash(),
                              command.agentVisible()))
                  .toList();
          return new Ingress.Catalog(
              selected.name(), selected.description(), selected.served(), commands);
        },
        Instant::now);
  }
}
