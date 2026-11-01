package io.aeyer.plowshare.server.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded pending session checks across all event/file sockets, before database acquisition. */
@ConfigurationProperties("plowshare.socket-sessions")
public record SocketSessionProperties(
    @DefaultValue("256") int pending,
    @DefaultValue("32") int pendingPerPrincipal,
    @DefaultValue("128") int batch,
    @DefaultValue("2") int gatherMillis,
    @DefaultValue("2000") int waitMillis) {
  public SocketSessionProperties {
    if (pending < 1
        || pending > 4096
        || pendingPerPrincipal < 1
        || pendingPerPrincipal > pending
        || batch < 1
        || batch > 128
        || batch > pending
        || gatherMillis < 0
        || gatherMillis > 10
        || waitMillis < 1
        || waitMillis > 5000
        || gatherMillis >= waitMillis)
      throw new IllegalArgumentException("Invalid socket session check bounds");
  }
}
