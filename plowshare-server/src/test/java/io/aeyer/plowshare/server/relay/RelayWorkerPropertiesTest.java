package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RelayWorkerPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Bindings.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RelayWorkerProperties.class)
  static class Bindings {}

  @Test
  void deployment_bindings_supply_explicit_identity_and_validated_bounds() {
    runner
        .withPropertyValues(
            "plowshare.relay.workers.projects[0].project=project",
            "plowshare.relay.workers.projects[0].account=operator",
            "plowshare.relay.workers.idle-interval=PT2S",
            "plowshare.relay.workers.concurrency=2")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var properties = context.getBean(RelayWorkerProperties.class);
              assertEquals(
                  new RelayWorkerProperties.Project("project", "operator"),
                  properties.getProjects().getFirst());
              assertEquals(Duration.ofSeconds(2), properties.getIdleInterval());
              assertEquals(2, properties.getConcurrency());
            });
  }

  @Test
  void missing_principal_invalid_bounds_and_unknown_fields_fail_startup() {
    runner
        .withPropertyValues("plowshare.relay.workers.projects[0].project=project")
        .run(context -> assertNotNull(context.getStartupFailure()));
    runner
        .withPropertyValues("plowshare.relay.workers.concurrency=0")
        .run(context -> assertNotNull(context.getStartupFailure()));
    runner
        .withPropertyValues("plowshare.relay.workers.projectz=project")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void an_unconfigured_deployment_enables_no_ambient_workers() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          assertTrue(context.getBean(RelayWorkerProperties.class).getProjects().isEmpty());
        });
  }
}
