package io.aeyer.plowshare.server.access;

/**
 * Counts how authority checks were answered, so an operator can see how often the in-memory
 * snapshot and the verified-admission memory avoid a walk or a statement.
 *
 * <p>Implementations must be cheap, thread-safe and must not throw into a check: observing an
 * authority decision never changes it. Outcomes are fixed enums, never identities.
 */
public interface AuthorityCheckTelemetry {
  /** How {@link BoundedVerifiedAdmissions#require} dealt with one admission. */
  enum AdmissionOutcome {
    /** Answered from a remembered, still-valid verification; no walk. */
    VERIFIED_HIT,
    /** Walked and the verification was recorded for reuse. */
    WALKED,
    /** Walked because the caller's transaction may hold its own writes; never reused. */
    TRANSACTION_WALK,
    /** Walked because no snapshot may be used for the caller; never reused. */
    NO_SNAPSHOT_WALK,
    /** The walk threw; a refusal is never recorded. */
    REFUSED
  }

  /** How one socket session was decided. */
  enum SessionOutcome {
    /** Accepted from the authority snapshot; no statement. */
    SNAPSHOT,
    /** Accepted by the stored authority (no snapshot, or a version newer than the snapshot). */
    SQL,
    /** The final answer was that the session is not current. */
    REFUSED
  }

  void admission(AdmissionOutcome outcome);

  void session(SessionOutcome outcome);

  /** Telemetry that records nothing. */
  static AuthorityCheckTelemetry none() {
    return new AuthorityCheckTelemetry() {
      @Override
      public void admission(AdmissionOutcome outcome) {}

      @Override
      public void session(SessionOutcome outcome) {}
    };
  }
}
