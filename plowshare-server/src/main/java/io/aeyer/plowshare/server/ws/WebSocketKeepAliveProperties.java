package io.aeyer.plowshare.server.ws;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit transport timings; a pong must echo the outstanding probe's payload. */
@ConfigurationProperties("plowshare.transport.keepalive")
public record WebSocketKeepAliveProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("30s") Duration interval,
    @DefaultValue("30s") Duration pongTimeout,
    @DefaultValue("5s") Duration checkInterval) {
  public WebSocketKeepAliveProperties {
    for (var duration : new Duration[] {interval, pongTimeout, checkInterval}) {
      if (duration == null
          || duration.compareTo(Duration.ofSeconds(1)) < 0
          || duration.compareTo(Duration.ofHours(1)) > 0)
        throw new IllegalArgumentException(
            "keepalive timings must be between one second and one hour");
    }
    if (checkInterval.compareTo(interval) > 0 || checkInterval.compareTo(pongTimeout) > 0)
      throw new IllegalArgumentException(
          "keepalive check interval must not exceed its interval or timeout");
  }
}
