package io.aeyer.plowshare.server.api;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.requests.RequestedBudget;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ConversationSpendingPolicyTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ConversationsProperties.class)
  static class PropertiesConfiguration {}

  @Test
  void omittedPolicyIsUnlimitedAndExplicitRequestLimitsRemainFinite() {
    ConversationsProperties properties = new ConversationsProperties();
    assertNull(properties.getDefaultBudget());
    assertFalse(RequestedBudget.in(null, null, properties.getDefaultBudget()).capped());
    assertEquals(3, RequestedBudget.in(3, null, properties.getDefaultBudget()).limit());
  }

  @Test
  void configurationBindsUnlimitedDisabledAndFinitePolicies() {
    for (String policy : java.util.List.of("unlimited", "0", "12"))
      new ApplicationContextRunner()
          .withUserConfiguration(PropertiesConfiguration.class)
          .withPropertyValues("plowshare.conversations.default-budget=" + policy)
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                Integer selected =
                    context.getBean(ConversationsProperties.class).getDefaultBudget();
                if (policy.equals("unlimited")) assertNull(selected);
                else assertEquals(Integer.valueOf(policy), selected);
              });
  }
}
