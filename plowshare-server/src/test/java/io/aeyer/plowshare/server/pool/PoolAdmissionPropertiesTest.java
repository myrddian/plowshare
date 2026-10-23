package io.aeyer.plowshare.server.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PoolAdmissionPropertiesTest {
  private static final Duration SHIPPED_ACQUIRE_TIMEOUT = Duration.ofMillis(5000);

  private static PoolAdmissionProperties defaults() {
    return new PoolAdmissionProperties(
        true, 2, 0.5, Duration.ofMillis(20), Duration.ofSeconds(3), Duration.ofSeconds(10));
  }

  private static PoolAdmissionProperties withCeiling(Duration ceiling) {
    return new PoolAdmissionProperties(
        true, 2, 0.5, Duration.ofMillis(20), ceiling, Duration.ofSeconds(10));
  }

  @Test
  void permits_and_threshold_are_derived_from_the_pool_size() {
    PoolAdmissionProperties properties = defaults();

    assertThat(properties.permits(20)).isEqualTo(18);
    assertThat(properties.threshold(20)).isEqualTo(9);
    assertThat(properties.permits(10)).isEqualTo(8);
    assertThat(properties.threshold(10)).isEqualTo(4);
  }

  @Test
  void the_throttle_always_gets_at_least_two_permits_between_threshold_and_total() {
    // AdmissionThrottle rejects permits - threshold < 2. Every pool size the derivation accepts
    // must satisfy that, across several fractions; the rest must be rejected here, not there.
    for (double fraction : new double[] {0.1, 0.5, 0.9, 0.99}) {
      PoolAdmissionProperties properties =
          new PoolAdmissionProperties(
              true,
              2,
              fraction,
              Duration.ofMillis(20),
              Duration.ofSeconds(3),
              Duration.ofSeconds(10));
      for (int pool = 1; pool <= 200; pool++) {
        int size = pool;
        try {
          int gap = properties.permits(size) - properties.threshold(size);
          assertThat(gap).as("pool %d fraction %s", size, fraction).isGreaterThanOrEqualTo(2);
        } catch (IllegalArgumentException rejected) {
          assertThat(rejected.getMessage()).contains("" + size);
        }
      }
    }
  }

  @Test
  void a_combination_leaving_the_throttle_no_room_is_rejected_naming_its_inputs() {
    assertThatThrownBy(() -> defaults().threshold(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pool size 4")
        .hasMessageContaining("permitMargin 2")
        .hasMessageContaining("thresholdFraction 0.5")
        .hasMessageContaining("gap 1");
    PoolAdmissionProperties steep =
        new PoolAdmissionProperties(
            true, 2, 0.99, Duration.ofMillis(20), Duration.ofSeconds(3), Duration.ofSeconds(10));
    assertThatThrownBy(() -> steep.threshold(20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("thresholdFraction 0.99");
  }

  @Test
  void the_threshold_floors_and_is_at_least_one() {
    assertThat(defaults().threshold(5)).isEqualTo(1); // permits 3, floor(1.5)
    PoolAdmissionProperties low =
        new PoolAdmissionProperties(
            true, 2, 0.01, Duration.ofMillis(20), Duration.ofSeconds(3), Duration.ofSeconds(10));
    assertThat(low.threshold(20)).isEqualTo(1);
  }

  @Test
  void a_ceiling_above_the_timeout_and_a_base_above_the_ceiling_are_rejected() {
    assertThatThrownBy(
            () -> withCeiling(Duration.ofSeconds(9)).validateAgainst(SHIPPED_ACQUIRE_TIMEOUT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gap is -4000ms");
    assertThatThrownBy(() -> withCeiling(Duration.ofMillis(10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("baseQuantum");
  }

  @Test
  void a_pool_no_larger_than_the_margin_has_no_permits_to_derive() {
    assertThatThrownBy(() -> defaults().permits(2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults().threshold(1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void the_ceiling_must_be_below_the_pool_acquire_timeout() {
    PoolAdmissionProperties properties = withCeiling(Duration.ofSeconds(5));

    assertThatThrownBy(() -> properties.validateAgainst(SHIPPED_ACQUIRE_TIMEOUT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ceiling (5000ms)")
        .hasMessageContaining("connection-timeout (5000ms)")
        .hasMessageContaining("gap is 0ms");
  }

  @Test
  void the_ceiling_must_be_distinguishable_from_the_acquire_timeout() {
    PoolAdmissionProperties properties = withCeiling(Duration.ofMillis(4500));

    assertThatThrownBy(() -> properties.validateAgainst(SHIPPED_ACQUIRE_TIMEOUT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ceiling (4500ms)")
        .hasMessageContaining("connection-timeout (5000ms)")
        .hasMessageContaining("gap is 500ms")
        .hasMessageContaining("at least 1000ms");
  }

  @Test
  void a_gap_of_exactly_one_second_is_accepted() {
    withCeiling(Duration.ofMillis(4000)).validateAgainst(SHIPPED_ACQUIRE_TIMEOUT);
  }

  @Test
  void a_three_second_ceiling_against_the_shipped_timeout_is_accepted() {
    defaults().validateAgainst(SHIPPED_ACQUIRE_TIMEOUT);
  }

  @Test
  void a_missing_or_non_positive_acquire_timeout_is_rejected() {
    assertThatThrownBy(() -> defaults().validateAgainst(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> defaults().validateAgainst(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalid_bounds_are_rejected() {
    Duration base = Duration.ofMillis(20);
    Duration ceiling = Duration.ofSeconds(3);
    Duration window = Duration.ofSeconds(10);

    assertThatThrownBy(() -> new PoolAdmissionProperties(true, -1, 0.5, base, ceiling, window))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PoolAdmissionProperties(true, 2, 0.0, base, ceiling, window))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PoolAdmissionProperties(true, 2, 1.0, base, ceiling, window))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PoolAdmissionProperties(true, 2, Double.NaN, base, ceiling, window))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PoolAdmissionProperties(true, 2, 0.5, Duration.ZERO, ceiling, window))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PoolAdmissionProperties(true, 2, 0.5, base, Duration.ZERO, window))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PoolAdmissionProperties(true, 2, 0.5, base, base, window))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new PoolAdmissionProperties(true, 2, 0.5, base, ceiling, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PoolAdmissionProperties(true, 2, 0.5, null, ceiling, window))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
