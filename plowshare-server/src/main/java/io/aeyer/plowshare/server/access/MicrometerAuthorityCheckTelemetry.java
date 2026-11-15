package io.aeyer.plowshare.server.access;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes {@value #ADMISSIONS} and {@value #SESSIONS}, each tagged only by {@code outcome} (the
 * lower-cased enum name). Every outcome is registered up front so a zero is visible and the meter
 * set is constant for the life of the process.
 */
public final class MicrometerAuthorityCheckTelemetry implements AuthorityCheckTelemetry {
  public static final String ADMISSIONS = "plowshare.authority.admission.checks";
  public static final String GRANTS = "plowshare.authority.grant.reads";
  public static final String SESSIONS = "plowshare.authority.session.checks";

  private final Map<AdmissionOutcome, Counter> admissions = new EnumMap<>(AdmissionOutcome.class);
  private final Map<GrantOutcome, Counter> grants = new EnumMap<>(GrantOutcome.class);
  private final Map<SessionOutcome, Counter> sessions = new EnumMap<>(SessionOutcome.class);

  public MicrometerAuthorityCheckTelemetry(MeterRegistry registry) {
    Objects.requireNonNull(registry);
    for (var outcome : GrantOutcome.values()) {
      grants.put(
          outcome,
          Counter.builder(GRANTS)
              .description("Grant reads by how they were answered")
              .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
              .register(registry));
    }
    for (var outcome : AdmissionOutcome.values()) {
      admissions.put(
          outcome,
          Counter.builder(ADMISSIONS)
              .description("Operation admission rechecks by how they were answered")
              .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
              .register(registry));
    }
    for (var outcome : SessionOutcome.values()) {
      sessions.put(
          outcome,
          Counter.builder(SESSIONS)
              .description("Socket session checks by how they were answered")
              .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
              .register(registry));
    }
  }

  @Override
  public void grant(GrantOutcome outcome) {
    grants.get(outcome).increment();
  }

  @Override
  public void admission(AdmissionOutcome outcome) {
    admissions.get(outcome).increment();
  }

  @Override
  public void session(SessionOutcome outcome) {
    sessions.get(outcome).increment();
  }
}
