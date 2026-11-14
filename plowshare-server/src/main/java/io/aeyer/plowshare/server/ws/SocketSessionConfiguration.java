package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.auth.CachedSessionAuthority;
import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.auth.StoredSessionAuthority;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** The repository adapter stays separate from the shared, bounded transport admission lane. */
@Configuration
@EnableConfigurationProperties(SocketSessionProperties.class)
public class SocketSessionConfiguration {
  @Bean(destroyMethod = "close")
  @Primary
  public SessionAuthority queuedSocketSessions(
      StoredSessionAuthority facts,
      AuthoritySnapshots snapshots,
      SocketSessionProperties properties,
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
          registries) {
    // Session facts come from the authority snapshot within its notification-trusted bound; the
    // stored adapter confirms refusals and answers whenever no snapshot may be used.
    var queue = new QueuedSocketSessions(new CachedSessionAuthority(facts, snapshots), properties);
    var registry = registries.getIfAvailable();
    if (registry != null) {
      io.micrometer.core.instrument.Gauge.builder(
              "plowshare.socket.sessions.pending", queue, QueuedSocketSessions::pending)
          .register(registry);
      io.micrometer.core.instrument.FunctionCounter.builder(
              "plowshare.socket.sessions.rejected", queue, QueuedSocketSessions::rejected)
          .register(registry);
      io.micrometer.core.instrument.FunctionCounter.builder(
              "plowshare.socket.sessions.timed.out", queue, QueuedSocketSessions::timedOut)
          .register(registry);
      io.micrometer.core.instrument.FunctionCounter.builder(
              "plowshare.socket.sessions.batches", queue, QueuedSocketSessions::batches)
          .register(registry);
    }
    return queue;
  }
}
