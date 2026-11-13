package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.server.llm.dispatch.Lane;

/** Live aggregate observations, separate from durable accounting and never used for admission. */
public interface InferenceTelemetry {
  /** Invoked only after canonical call admission succeeds; contains no execution identities. */
  Call admitted(Lane lane, UsageAttribution.Operation operation);

  interface Call {
    /** Once per actual terminal upstream attempt, including retries and incomplete accounting. */
    void attemptFinished(
        CallLifecycle outcome, UsageObservation usage, long durationMillis, Long firstOutputMillis);

    /** Once per call; repeated finishes are harmless. Null queue time means not measured. */
    void finished(CallLifecycle outcome, Long queueMillis);
  }

  InferenceTelemetry NONE =
      (lane, operation) ->
          new Call() {
            public void attemptFinished(
                CallLifecycle outcome,
                UsageObservation usage,
                long durationMillis,
                Long firstOutputMillis) {}

            public void finished(CallLifecycle outcome, Long queueMillis) {}
          };
}
