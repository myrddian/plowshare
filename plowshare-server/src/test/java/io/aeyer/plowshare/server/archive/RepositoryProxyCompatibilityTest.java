package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.applications.ApplicationIdentityRepository;
import io.aeyer.plowshare.server.applications.JdbcApplicationIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reproduces the Boot exception-translation proxy mode without needing PostgreSQL. */
class RepositoryProxyCompatibilityTest {
  @Test
  void applicationAndContextRepositoriesSupportClassProxiesAndTypedInjection() {
    var jdbc = mock(JdbcTemplate.class);
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(JdbcTemplate.class, () -> jdbc);
      context.registerBean(
          PersistenceExceptionTranslationPostProcessor.class,
          () -> {
            var translation = new PersistenceExceptionTranslationPostProcessor();
            translation.setProxyTargetClass(true);
            return translation;
          });
      context.register(
          JdbcApplicationRegistrations.class,
          JdbcConversationContextRepository.class,
          JdbcApplicationIdentityRepository.class);
      context.refresh();
      var registrations = context.getBean(ApplicationRegistrations.class);
      var conversations = context.getBean(ConversationContextRepository.class);
      var identities = context.getBean(ApplicationIdentityRepository.class);
      assertTrue(AopUtils.isCglibProxy(registrations));
      assertTrue(AopUtils.isCglibProxy(conversations));
      assertTrue(AopUtils.isCglibProxy(identities));
      assertFalse(registrations.required("fixture-project"));
      assertTrue(conversations.ownership("fixture-conversation").isEmpty());
      assertTrue(identities.find("fixture-project", false).isEmpty());
    }
  }
}
