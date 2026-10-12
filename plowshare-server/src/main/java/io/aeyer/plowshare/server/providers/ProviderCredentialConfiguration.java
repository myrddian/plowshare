package io.aeyer.plowshare.server.providers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.auth.AdminStore;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composition owns persistence and encryption; transports consume the typed management capability.
 */
@Configuration
@EnableConfigurationProperties(ProviderCredentialProperties.class)
public class ProviderCredentialConfiguration {
  @Bean
  public ProviderConnections providerConnections(
      ProviderCredentialProperties properties,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      AdminStore accounts,
      ProjectMembers members,
      UnitOfWork work,
      @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:}")
          String contextPath) {
    if (!properties.isEnabled()) return new DisabledProviderConnections();
    var policy =
        new ConfiguredProviderEndpoints(
            properties.getAllowedOrigins(), properties.getCallback(), contextPath);
    var cipher = new AesCredentialCipher(properties.getActiveKey(), properties.getKeys());
    Clock clock = Clock.systemUTC();
    return new OwnedProviderConnections(
        new JdbcProviderCredentialRepository(jdbc, mapper),
        new CurrentProviderCredentialAccess(accounts, members),
        policy,
        cipher,
        new JsonProviderSecrets(mapper),
        new HttpProviderOAuth(new okhttp3.OkHttpClient(), mapper, clock),
        work,
        clock);
  }

  @Bean
  public ProviderCredentialGrants providerCredentialGrants(
      ProviderCredentialProperties properties,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      AdminStore accounts,
      ProjectMembers members,
      @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:}")
          String contextPath) {
    if (!properties.isEnabled())
      return new ProviderCredentialGrants() {
        public io.aeyer.plowshare.protocol.ProviderCredentials.Connection connection(
            String owner, io.aeyer.plowshare.protocol.ProviderCredentials.Target target) {
          throw new io.aeyer.plowshare.server.faults.CallerFault(
              "Server-held provider credentials are disabled");
        }

        public Reference inspect(
            String owner, io.aeyer.plowshare.protocol.ProviderCredentials.Target target) {
          throw new io.aeyer.plowshare.server.faults.CallerFault(
              "Server-held provider credentials are disabled");
        }

        public Reference require(
            String owner, io.aeyer.plowshare.protocol.ProviderCredentials.Target target) {
          throw new io.aeyer.plowshare.server.faults.CallerFault(
              "Server-held provider credentials are disabled");
        }

        public Header header(Reference reference) {
          throw new io.aeyer.plowshare.server.faults.CallerFault(
              "Server-held provider credentials are disabled");
        }
      };
    return new CurrentProviderCredentialGrants(
        new JdbcProviderCredentialRepository(jdbc, mapper),
        new CurrentProviderCredentialAccess(accounts, members),
        new ConfiguredProviderEndpoints(
            properties.getAllowedOrigins(), properties.getCallback(), contextPath),
        new AesCredentialCipher(properties.getActiveKey(), properties.getKeys()),
        new JsonProviderSecrets(mapper),
        Clock.systemUTC());
  }
}
