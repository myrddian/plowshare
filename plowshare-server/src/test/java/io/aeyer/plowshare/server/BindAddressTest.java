package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Shipped configuration requires explicit listener values and cannot silently bind locally. */
class BindAddressTest {
  private static MockEnvironment environment() throws Exception {
    var environment = new MockEnvironment();
    for (var source :
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    return environment;
  }

  @Test
  void missing_listener_configuration_fails_with_the_required_key() throws Exception {
    var environment = environment();
    assertTrue(
        assertThrows(
                IllegalArgumentException.class, () -> environment.getProperty("server.address"))
            .getMessage()
            .contains("PLOWSHARE_BIND"));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> environment.getProperty("server.port"))
            .getMessage()
            .contains("PLOWSHARE_PORT"));
  }

  @Test
  void explicit_deployment_values_are_resolved_without_local_fallbacks() throws Exception {
    var environment =
        environment()
            .withProperty("PLOWSHARE_BIND", "192.0.2.15")
            .withProperty("PLOWSHARE_PORT", "8127");
    assertEquals("192.0.2.15", environment.getProperty("server.address"));
    assertEquals(8127, environment.getProperty("server.port", Integer.class));
  }

  @Test
  void connection_credentials_are_required_and_models_have_no_local_url_default() throws Exception {
    var environment = environment();
    for (String key :
        new String[] {
          "spring.datasource.url", "spring.datasource.username", "spring.datasource.password"
        }) {
      assertThrows(IllegalArgumentException.class, () -> environment.getProperty(key));
    }
    assertEquals("", environment.getProperty("plowshare.llm.pools[0].base-url"));
    assertNull(
        environment.getProperty("plowshare.llm.pools[1].base-url"),
        "the packaged configuration must not assume a second inference host");
    assertEquals("", environment.getProperty("plowshare.llm.embedding-model"));
    assertEquals("", environment.getProperty("plowshare.llm.classes.fast"));
    assertEquals("", environment.getProperty("plowshare.llm.classes.reasoning"));
  }
}
