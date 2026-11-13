package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.A2aClient;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.AdministrationClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Single-owner outbound process. Unknown effects require operator action, not a supervisor retry.
 */
public final class RelayMain {
  private RelayMain() {}

  public static final int OPERATOR_ACTION_EXIT = 78;
  public static final int TEMPORARY_FAILURE_EXIT = 75;

  private static final class TemporaryFailure extends IOException {
    TemporaryFailure() {
      super("A2A transport temporarily unavailable; retained evidence prevents peer replay");
    }
  }

  static IOException temporaryFailure() {
    return new TemporaryFailure();
  }

  /**
   * CONFIG runs the worker. Administrative commands hold the same journal lock: reconcile AFTER
   * [PAGES], abandon-reply UUID EVIDENCE, archive, owner, or rotate-config PREVIOUS_OWNER.
   */
  public static void main(String[] args) throws Exception {
    int status = execute(args, System::getenv);
    if (status != 0) System.exit(status);
  }

  @FunctionalInterface
  interface Operation {
    void run() throws Exception;
  }

  static int classify(Operation operation) throws Exception {
    try {
      operation.run();
      return 0;
    } catch (TemporaryFailure temporary) {
      System.err.println(
          "A2A transport temporarily unavailable; restart with the same journal after backoff. No peer effect was replayed.");
      return TEMPORARY_FAILURE_EXIT;
    } catch (IOException | IllegalStateException | IllegalArgumentException operatorAction) {
      System.err.println(
          "A2A worker stopped; inspect invocation, journal/configuration or explicit reconciliation. No uncertain operation was replayed.");
      return OPERATOR_ACTION_EXIT;
    }
  }

  static int execute(String[] args, Function<String, String> environment) throws Exception {
    return classify(() -> executeConfigured(args, environment));
  }

  private static void executeConfigured(String[] args, Function<String, String> environment)
      throws Exception {
    if (args.length < 1)
      throw new IllegalArgumentException(
          "Usage: A2A Relay CONFIG [reconcile AFTER [PAGES] | abandon-reply UUID EVIDENCE | archive | pending [AFTER_UUID] | owner | rotate-config PREVIOUS_OWNER]");
    String command = args.length == 1 ? "run" : args[1];
    if (!validArguments(command, args.length))
      throw new IllegalArgumentException("Invalid A2A Relay command arguments");
    RelayConfiguration config = RelayConfiguration.read(Path.of(args[0]), environment);
    if (command.equals("rotate-config")) {
      try (var sdk =
          Plowshare.connect(config.origin(), config.token(), Duration.ofSeconds(30), null)) {
        verifyPublisher(config.binding(), sdk);
      }
      try (var journal =
          FileDispatchJournal.rotate(
              config.journal(),
              config.fingerprint(),
              config.identityFingerprint(),
              config.legacyFingerprint(),
              args[2])) {
        System.out.println(
            "A2A configuration rotation recorded; owner=" + journal.ownerFingerprint());
      }
      return;
    }
    try (var journal =
        new FileDispatchJournal(
            config.journal(),
            config.fingerprint(),
            config.identityFingerprint(),
            config.legacyFingerprint())) {
      if (command.equals("pending")) {
        String after = args.length == 3 ? UUID.fromString(args[2]).toString() : "";
        var pending =
            journal.entries().stream()
                .filter(e -> !e.terminal() && e.requestId().toString().compareTo(after) > 0)
                .sorted(Comparator.comparing(e -> e.requestId().toString()))
                .limit(100)
                .toList();
        for (var entry : pending)
          System.out.println(
              "requestId="
                  + entry.requestId()
                  + "; phase="
                  + entry.phase()
                  + "; source="
                  + entry.sourceEvent()
                  + "; outcome="
                  + (entry.observation() == null ? "unobserved" : entry.observation().state()));
        System.out.println(
            "Next pending cursor=" + (pending.isEmpty() ? after : pending.getLast().requestId()));
        return;
      }
      if (command.equals("owner")) {
        System.out.println(journal.ownerFingerprint());
        return;
      }
      if (command.equals("archive")) {
        System.out.println("Archived settled records: " + journal.archiveConfirmed());
        return;
      }
      if (command.equals("abandon-reply")) {
        var entry =
            journal
                .find(UUID.fromString(args[2]))
                .orElseThrow(() -> new IOException("No retained uncertain publication"));
        journal.save(entry.abandon(args[3]));
        System.out.println(
            "Unconfirmed reply explicitly abandoned; no delivery proof was asserted and no publication or peer operation was repeated.");
        return;
      }
      run(config, journal, command, args);
    }
  }

  private static void verifyPublisher(RelayAdapter.Binding binding, Plowshare sdk)
      throws IOException {
    String actual = RelayPort.publisher(new AdministrationClient(sdk).status().handle());
    if (!binding.replyPublisher().equals(actual))
      throw new IOException("Authenticated adapter publisher differs from configured identity");
  }

  private static void run(
      RelayConfiguration config, FileDispatchJournal journal, String command, String[] args)
      throws Exception {
    var peers = new LinkedHashMap<String, RemotePeer>();
    try (var connection =
        Plowshare.connect(config.origin(), config.token(), Duration.ofSeconds(30), null)) {
      verifyPublisher(config.binding(), connection);
      config
          .peers()
          .forEach(
              (name, peer) ->
                  peers.put(
                      name,
                      new A2aClient(
                          peer.endpoint(), peer.agentCard(), peer.bearer(), peer.timeout())));
      var relay = new RelayClient(connection);
      var gateway =
          new RelayGateway() {
            @Override
            public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
              return relay.consume(request);
            }

            @Override
            public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
              try {
                return relay.publish(request);
              } catch (Plowshare.TransportException failure) {
                if (failure.delivery() == Plowshare.Delivery.NOT_SUBMITTED)
                  throw new PublicationNotSubmitted();
                throw failure;
              }
            }

            @Override
            public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
              return relay.acknowledge(request);
            }

            @Override
            public RelayLog.Page log(RelayLog.Query request) throws IOException {
              return relay.log(request);
            }
          };
      var adapter = new RelayAdapter(config.binding(), gateway, journal, peers, Instant::now);
      if (command.equals("reconcile")) {
        int pages = args.length == 4 ? Integer.parseInt(args[3]) : 100;
        var result = adapter.reconcileThroughHead(args[2], pages);
        System.out.println(
            "Confirmed replies="
                + result.confirmed()
                + "; next="
                + result.next()
                + "; head="
                + result.head()
                + "; reachedHead="
                + result.reachedHead()
                + "; stillUncertain="
                + result.unresolved());
        return;
      }
      supervise(adapter, connection::connected, duration -> Thread.sleep(duration.toMillis()));
    } catch (Plowshare.TransportException transport) {
      if (journal.entries().stream().anyMatch(e -> e.phase() == RelayWork.Phase.REPLY_INTENT))
        throw transport;
      throw new TemporaryFailure();
    } finally {
      peers.values().forEach(RemotePeer::close);
    }
  }

  /** Bounded publication-only retry policy; exceptions retain the owning journal's evidence. */
  static void supervise(RelayAdapter adapter, BooleanSupplier connected, RelayAdapter.Pause pause)
      throws IOException, InterruptedException {
    int notSubmitted = 0;
    while (!Thread.currentThread().isInterrupted() && connected.getAsBoolean()) {
      try {
        if (!adapter.tick()) pause.await(Duration.ofMillis(250));
        notSubmitted = 0;
      } catch (RelayAdapter.ReplyNotSubmitted proven) {
        // Positive transport proof permits retrying only the publication. Saved observations
        // prevent any repeated send/cancel, including after a supervisor restarts the process.
        if (++notSubmitted >= 5) throw new TemporaryFailure();
        pause.await(Duration.ofMillis(Math.min(5000, 250L << notSubmitted)));
      }
    }
    if (!Thread.currentThread().isInterrupted() && !connected.getAsBoolean())
      throw new TemporaryFailure();
  }

  private static boolean validArguments(String command, int count) {
    return switch (command) {
      case "run" -> count == 1;
      case "owner", "archive" -> count == 2;
      case "pending" -> count == 2 || count == 3;
      case "rotate-config" -> count == 3;
      case "reconcile" -> count == 3 || count == 4;
      case "abandon-reply" -> count == 4;
      default -> false;
    };
  }
}
