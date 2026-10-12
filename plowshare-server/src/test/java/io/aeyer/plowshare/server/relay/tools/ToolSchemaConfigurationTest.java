package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ToolSchemaConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Bindings.class, ToolSchemaConfiguration.class)
          .withPropertyValues(
              "plowshare.relay.tools.bindings[0].project=project",
              "plowshare.relay.tools.bindings[0].provider=provider",
              "plowshare.relay.tools.bindings[0].account=operator",
              "plowshare.relay.tools.bindings[0].name=nested",
              "plowshare.relay.tools.bindings[0].description=Nested inputs",
              "plowshare.relay.tools.bindings[0].timeoutSeconds=30");

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RelayToolProperties.class)
  static class Bindings {}

  @Test
  void deployment_schema_is_decoded_before_the_binding_enters_the_registry() {
    runner
        .withPropertyValues(
            "plowshare.relay.tools.bindings[0].inputSchema="
                + "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\","
                + "\"items\":{\"type\":[\"string\",\"null\"]}}},\"additionalProperties\":false}")
        .run(
            context -> {
              if (context.getStartupFailure() != null)
                throw new AssertionError(context.getStartupFailure());
              var binding = context.getBean(RelayToolProperties.class).getBindings().getFirst();
              assertNotNull(binding.inputSchema());
              binding.validate(RelayToolCodec.arguments("{\"items\":[\"first\",null]}"));
              assertThrows(
                  IllegalArgumentException.class,
                  () -> binding.validate(RelayToolCodec.arguments("{\"items\":[1]}")));
            });
  }

  @Test
  void invalid_schema_keywords_and_duplicate_fields_refuse_startup() {
    for (String schema :
        new String[] {
          "{\"type\":\"object\",\"unknown\":true}",
          "{\"type\":\"object\",\"type\":\"object\"}",
          "{\"type\":\"object\",\"pattern\":\".*\"}"
        }) {
      runner
          .withPropertyValues("plowshare.relay.tools.bindings[0].inputSchema=" + schema)
          .run(context -> assertNotNull(context.getStartupFailure()));
    }
  }
}
