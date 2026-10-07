package io.aeyer.plowshare.server.security;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.relay.RelayPortProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;

/** Proves the deployment JSON path, existing YAML support and fail-fast binding of policy. */
class FilteringConfigurationTest {
  private static final String JSON =
      """
      {"plowshare": {
        "security": {"filtering": {
          "enabled":true,"conditional-matches":false,
          "patterns":[{"name":"email","action":"MASK"}],
          "external":[{"project":"fixture","account":"subject",
            "request-topic":"checks.requests","response-topic":"checks.responses",
            "reviewer":"reviewer","timeout-seconds":15}]}},
        "relay":{"ports":{"bindings":[
          {"project":"fixture","topic":"checks.requests","account":"reviewer",
            "direction":"EGRESS","groups":["detectors"]},
          {"project":"fixture","topic":"checks.responses","account":"reviewer",
            "direction":"INGRESS"}]}}}}
      """;

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({FilteringProperties.class, RelayPortProperties.class})
  static class Bindings {
    @Bean
    TextFiltering local(FilteringProperties properties) {
      return new LocalTextFilter(properties);
    }
  }

  private ApplicationContextRunner json(String document) {
    return new ApplicationContextRunner()
        .withUserConfiguration(Bindings.class)
        .withInitializer(
            context -> {
              var environment = context.getEnvironment();
              environment
                  .getPropertySources()
                  .addFirst(
                      new MapPropertySource(
                          "fixture", Map.of("SPRING_APPLICATION_JSON", document)));
              new SpringApplicationJsonEnvironmentPostProcessor()
                  .postProcessEnvironment(environment, new SpringApplication(Bindings.class));
            });
  }

  @Test
  void deployment_json_binds_lists_records_enum_policies_and_exact_grants() {
    json(JSON)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var filtering = context.getBean(FilteringProperties.class);
              assertTrue(filtering.isEnabled());
              assertEquals(
                  FilteringProperties.Action.MASK, filtering.getPatterns().getFirst().action());
              assertEquals(15, filtering.getExternal().getFirst().timeoutSeconds());
              var ports = context.getBean(RelayPortProperties.class);
              assertTrue(
                  ports.permits(
                      "reviewer",
                      "fixture",
                      "checks.requests",
                      RelayPortProperties.Direction.EGRESS,
                      "detectors"));
              assertFalse(
                  ports.permits(
                      "reviewer",
                      "fixture",
                      "checks.requests",
                      RelayPortProperties.Direction.EGRESS,
                      "foreign"));
              assertEquals(
                  "Contact [REDACTED_EMAIL]",
                  context
                      .getBean(TextFiltering.class)
                      .inspect("Contact person@example.org", true, true));
            });
  }

  @Test
  void misspellings_and_invalid_security_or_port_policies_fail_startup() {
    for (var invalid :
        java.util.List.of(
            JSON.replace("\"enabled\":true", "\"enabledd\":true"),
            JSON.replace("\"bindings\":", "\"bindingz\":"),
            JSON.replace("\"timeout-seconds\":15", "\"timeout-secondz\":15"),
            JSON.replace("\"groups\":[\"detectors\"]", "\"groupz\":[\"detectors\"]"),
            JSON.replace("\"timeout-seconds\":15", "\"timeout-seconds\":0"),
            JSON.replace("\"groups\":[\"detectors\"]", "\"groups\":[]"),
            JSON.replace("\"reviewer\":\"reviewer\"", "\"reviewer\":\"subject\""),
            JSON.replace("\"name\":\"email\"", "\"name\":\"unknown_pattern\""))) {
      json(invalid).run(context -> assertNotNull(context.getStartupFailure(), invalid));
    }
  }

  @Test
  void yaml_deployment_configuration_remains_supported() {
    var yaml =
        """
        plowshare:
          security:
            filtering:
              enabled: false
              patterns:
                - name: email
                  action: BLOCK
        """;
    new ApplicationContextRunner()
        .withUserConfiguration(Bindings.class)
        .withInitializer(
            context -> {
              try {
                for (var source :
                    new YamlPropertySourceLoader()
                        .load(
                            "fixture",
                            new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))))
                  context.getEnvironment().getPropertySources().addFirst(source);
              } catch (java.io.IOException invalid) {
                throw new AssertionError(invalid);
              }
            })
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertFalse(context.getBean(FilteringProperties.class).isEnabled());
              assertEquals(
                  FilteringProperties.Action.BLOCK,
                  context.getBean(FilteringProperties.class).getPatterns().getFirst().action());
            });
  }
}
