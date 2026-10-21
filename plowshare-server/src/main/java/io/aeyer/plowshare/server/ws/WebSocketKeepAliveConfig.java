package io.aeyer.plowshare.server.ws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared event/file transport liveness; native control frames need no application catalogue entry.
 */
@Configuration
@EnableConfigurationProperties(WebSocketKeepAliveProperties.class)
public class WebSocketKeepAliveConfig {
  /** One lifecycle owner shares deployment timings across the event and file registrations. */
  @Bean(destroyMethod = "close")
  public WebSocketKeepAlive webSocketKeepAlive(WebSocketKeepAliveProperties properties) {
    return new ScheduledWebSocketKeepAlive(properties);
  }
}
