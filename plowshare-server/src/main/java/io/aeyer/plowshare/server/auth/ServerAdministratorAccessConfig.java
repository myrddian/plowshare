package io.aeyer.plowshare.server.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes the existing persisted account-role predicate through the narrow access contract. */
@Configuration(proxyBeanMethods = false)
public class ServerAdministratorAccessConfig {
  /** Each check reads current authority; composition never caches the account's role. */
  @Bean
  ServerAdministratorAccess serverAdministratorAccess(AdminStore accounts) {
    return accounts::isServerAdmin;
  }
}
