package io.aeyer.plowshare.server.ws;

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
      SocketSessionProperties properties,
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
          registries) {
    var queue = new QueuedSocketSessions(facts, properties);
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
