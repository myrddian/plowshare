package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.*;

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
}
