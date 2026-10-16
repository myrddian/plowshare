package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Startup-only operator controls for information writes. application.yml documents both controls;
 * matching fallbacks keep optional controls enabled when an operator replaces that file entirely.
 * These switches never replace scope or document authorization.
 */
@Configuration
public class InformationFeatureConfig {
  @Bean
  public InformationNavigationService informationNavigationService(
      InformationNavigationRepository repository,
      InformationAccess access,
      UnitOfWork work,
      @Value("${plowshare.information.navigation-writes-enabled:true}") boolean enabled) {
    return new DocumentNavigation(repository, access, work, enabled);
  }

  @Bean
  public InformationContentSettings informationContentSettings(
      @Value("${plowshare.information.content-types-enabled:true}") boolean enabled) {
    return new InformationContentSettings(enabled);
  }
}
