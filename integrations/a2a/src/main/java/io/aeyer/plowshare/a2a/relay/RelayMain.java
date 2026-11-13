package io.aeyer.plowshare.a2a.relay;

import io.aeyer.plowshare.a2a.A2aClient;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

/** Separate replacement outbound entry point; legacy inbound cutover requires platform work. */
public final class RelayMain {
  private RelayMain() {}

  /** Run with CONFIG, or CONFIG reconcile AFTER to inspect one bounded reply-log page. */
  public static void main(String[] args) throws Exception {
    if (args.length != 1 && (args.length != 3 || !args[1].equals("reconcile")))
      throw new IllegalArgumentException("Usage: A2A Relay CONFIG [reconcile AFTER]");
    var config = RelayConfiguration.read(Path.of(args[0]), System::getenv);
    var peers = new LinkedHashMap<String, RemotePeer>();
    try (var journal = new FileDispatchJournal(config.journal(), config.fingerprint());
        var connection =
            Plowshare.connect(config.origin(), config.token(), Duration.ofSeconds(30), null)) {
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
              return relay.publish(request);
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
      if (args.length == 3) {
        var page =
            gateway.log(
                new RelayLog.Query(
                    config.binding().project(), false, config.binding().replies(), args[2], 100));
        System.out.println(
            "Confirmed replies: " + adapter.reconcile(page) + "; next cursor: " + page.next());
        return;
      }
      while (!Thread.currentThread().isInterrupted() && connection.connected()) {
        if (!adapter.tick()) Thread.sleep(250);
      }
    } finally {
      peers.values().forEach(RemotePeer::close);
    }
  }
}
