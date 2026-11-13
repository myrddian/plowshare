package io.aeyer.plowshare.server.archive;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** One typed source reader shared by messaging and granted execution publication. */
@Configuration(proxyBeanMethods = false)
public class TurnSourceConfiguration {
  @Bean
  TurnRelaySources turnRelaySources(JdbcTemplate jdbc) {
    return new JdbcTurnRelaySources(jdbc);
  }
}
