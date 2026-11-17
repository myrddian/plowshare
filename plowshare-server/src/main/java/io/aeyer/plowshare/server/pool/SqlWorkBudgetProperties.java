package io.aeyer.plowshare.server.pool;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Finite resource ceilings for explicitly scoped work; ordinary unscoped SQL is unchanged. */
@ConfigurationProperties("plowshare.sql-work-budget")
public record SqlWorkBudgetProperties(
    @DefaultValue("1s") Duration queryCeiling,
    @DefaultValue("500ms") Duration cleanupBudget,
    @DefaultValue("128") int scopes,
    @DefaultValue("8") int leasesPerScope,
    @DefaultValue("64") int queriesPerScope,
    @DefaultValue("4") int cleanupWorkers) {
  public SqlWorkBudgetProperties {
    positive(queryCeiling);
    positive(cleanupBudget);
    if (queryCeiling.compareTo(Duration.ofSeconds(1)) > 0
        || scopes < 1
        || scopes > 4096
        || leasesPerScope < 1
        || leasesPerScope > 64
        || queriesPerScope < 1
        || queriesPerScope > 512
        || cleanupWorkers < 1
        || cleanupWorkers > 32)
      throw new IllegalArgumentException("Invalid scoped SQL budget limits");
    Math.multiplyExact(scopes, leasesPerScope);
  }

  static Duration positive(Duration value) {
    Objects.requireNonNull(value);
    if (value.isZero() || value.isNegative())
      throw new IllegalArgumentException("A positive finite SQL budget is required");
    value.toNanos();
    return value;
  }
}
