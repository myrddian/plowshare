package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds the shipped {@code spring.datasource.hikari} block exactly as Boot would, without opening a
 * connection. Hikari's timeouts are plain millisecond longs: a duration string such as {@code 5s}
 * fails to bind and stops the server from starting, which no other non-database test would notice.
 */
class DatasourceConfigurationTest {
  @Test
  void shipped_pool_settings_bind_to_hikari() throws Exception {
    var sources = new MutablePropertySources();
    List.of(
            new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")))
        .forEach(list -> list.forEach(sources::addLast));
    var binder =
        new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources));
    HikariConfig config =
        binder.bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();
    assertEquals(20, config.getMaximumPoolSize());
    assertEquals(5000L, config.getConnectionTimeout());
  }
}
