package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ReadCapacityConfigurationTest {
  @Test
  void explicitConfigurationBindsTheCanonicalConstructorAndRejectsInvalidBudgets() {
    var runner =
        new ApplicationContextRunner().withUserConfiguration(ReadCapacityConfiguration.class);
    runner
        .withPropertyValues(
            "plowshare.read-capacity.reports=3", "plowshare.read-capacity.reports-per-principal=2")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var admission = context.getBean(ReadAdmission.class);
              var first = admission.admit("fixture", ReadAdmission.Work.REPORT);
              var second = admission.admit("fixture", ReadAdmission.Work.REPORT);
              try (first;
                  second) {
                assertThrows(
                    ReadUnavailableException.class,
                    () -> admission.admit("fixture", ReadAdmission.Work.REPORT));
              }
            });
    runner
        .withPropertyValues("plowshare.read-capacity.reports=0")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  @Test
  void interactiveSchedulingBindsAndQueueGaugesHaveNoPrincipalLabels() {
    new ApplicationContextRunner()
        .withUserConfiguration(ReadCapacityConfiguration.class)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(
            "plowshare.read-scheduling.waiting-interactive=3",
            "plowshare.read-scheduling.waiting-interactive-per-principal=2",
            "plowshare.read-scheduling.interactive-wait-millis=37")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(
                  Duration.ofMillis(37),
                  context.getBean(ReadAdmission.class).interactiveWaitBudget());
              var registry = context.getBean(MeterRegistry.class);
              for (String queue : new String[] {"interactive", "authoritative"}) {
                var gauge = registry.get("plowshare.read." + queue + ".waiting").gauge();
                assertEquals(0, gauge.value());
                assertTrue(gauge.getId().getTags().isEmpty());
              }
            });
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReadSchedulingProperties(64, 4, 2000, 2000, 1, 2, 2000));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReadSchedulingProperties(64, 4, 2000, 2000, 8, 2, 0));
  }
}
