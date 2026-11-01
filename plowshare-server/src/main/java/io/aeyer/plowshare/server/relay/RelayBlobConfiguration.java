package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.blobs.BinaryBlobStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binary transfer composition; existing text broker/port composition is unchanged. */
@Configuration
@EnableConfigurationProperties(RelayBlobProperties.class)
public class RelayBlobConfiguration {
  @Bean
  public RelayBlobs relayBlobs(
      RelayPorts ports,
      Relay relay,
      BinaryBlobStorage blobs,
      UnitOfWork work,
      RelayBlobProperties properties) {
    return new ProjectRelayBlobs(ports, relay, blobs, work, properties);
  }
}
