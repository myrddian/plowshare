package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.access.ApplicationGrantLineage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Component-scanned beans with more than one constructor must name the one Spring uses. A server
 * that cannot choose fails at startup, which no hand-constructed unit test observes.
 */
class JdbcJobInformationRepositoryWiringTest {
  @Test
  void springConstructsTheScannedRepositoryWithItsVisibility() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
      context.registerBean(
          InformationLogVisibility.class, () -> mock(InformationLogVisibility.class));
      context.register(JdbcJobInformationRepository.class);
      context.refresh();
      assertNotNull(context.getBean(JdbcJobInformationRepository.class));
    }
  }

  @Test
  void everyChangedScannedClassDeclaresItsInjectionConstructor() {
    for (Class<?> type :
        new Class<?>[] {JdbcJobInformationRepository.class, ApplicationGrantLineage.class}) {
      var constructors = type.getConstructors();
      if (constructors.length < 2) continue;
      boolean named = false;
      for (var constructor : constructors)
        named |=
            constructor.isAnnotationPresent(
                org.springframework.beans.factory.annotation.Autowired.class);
      if (!named) throw new AssertionError(type.getName() + " has no @Autowired constructor");
    }
  }
}
