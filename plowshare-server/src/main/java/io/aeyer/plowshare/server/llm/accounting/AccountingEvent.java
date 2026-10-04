package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata-only durable event. There is deliberately no request, content, arbitrary JSON, exception
 * message, URL, or credential field. New payload types require a journal version decision.
 */
public record AccountingEvent(
    UUID eventId, UUID instanceId, UUID callId, long callSequence, Instant at, Payload payload) {

  public AccountingEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(instanceId, "instanceId");
    Objects.requireNonNull(callId, "callId");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(payload, "payload");
    if (callSequence < 1 || (payload instanceof CallCreated && callSequence != 1)) {
      throw new IllegalArgumentException("accounting call sequences start at one");
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = CallCreated.class, name = "call_created"),
    @JsonSubTypes.Type(value = AttemptStarted.class, name = "attempt_started"),
    @JsonSubTypes.Type(value = AttemptFinished.class, name = "attempt_finished"),
    @JsonSubTypes.Type(value = CallFinished.class, name = "call_finished")
  })
  public sealed interface Payload
      permits CallCreated, AttemptStarted, AttemptFinished, CallFinished {}

  public record CallCreated(
      UsageAttribution attribution,
      String specifier,
      String pool,
      String wireModel,
      String modelFamily,
      String billingRoute,
      Lane lane,
      RateCard price)
      implements Payload {
    public CallCreated {
      Objects.requireNonNull(attribution, "attribution");
      UsageLineage.requireId(specifier);
      UsageLineage.requireId(pool);
      UsageLineage.requireId(wireModel);
      UsageLineage.requireId(billingRoute);
      Objects.requireNonNull(lane, "lane");
      if (modelFamily != null) {
        UsageLineage.requireId(modelFamily);
      }
      if (price != null
          && (!price.billingRoute().equals(billingRoute) || !price.model().equals(wireModel))) {
        throw new IllegalArgumentException(
            "price snapshot must match the actual billing route and wire model");
      }
    }
  }

  public record AttemptStarted(UUID attemptId, int attemptNumber) implements Payload {
    public AttemptStarted {
      Objects.requireNonNull(attemptId, "attemptId");
      if (attemptNumber < 1) {
        throw new IllegalArgumentException("attempt numbers start at one");
      }
    }
  }

  public record AttemptFinished(
      UUID attemptId,
      CallLifecycle outcome,
      UsageObservation usage,
      CostResult cost,
      String providerRequestId,
      Integer httpStatus,
      FinishReason finishReason,
      Long firstOutputMillis,
      long durationMillis,
      Long startAccountingMillis)
      implements Payload {
    public AttemptFinished(
        UUID attemptId,
        CallLifecycle outcome,
        UsageObservation usage,
        CostResult cost,
        String providerRequestId,
        Integer httpStatus,
        FinishReason finishReason,
        Long firstOutputMillis,
        long durationMillis) {
      this(
          attemptId,
          outcome,
          usage,
          cost,
          providerRequestId,
          httpStatus,
          finishReason,
          firstOutputMillis,
          durationMillis,
          null);
    }

    public AttemptFinished {
      Objects.requireNonNull(attemptId, "attemptId");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(usage, "usage");
      Objects.requireNonNull(cost, "cost");
      Objects.requireNonNull(finishReason, "finishReason");
      if (!outcome.terminal() || outcome == CallLifecycle.NOT_DISPATCHED) {
        throw new IllegalArgumentException("an attempted request needs a terminal attempt outcome");
      }
      if (providerRequestId != null && !providerRequestId.matches("[A-Za-z0-9._:-]{1,256}")) {
        throw new IllegalArgumentException("provider request ID must be an opaque safe identifier");
      }
      if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
        throw new IllegalArgumentException("invalid HTTP response status");
      }
      if (durationMillis < 0
          || (firstOutputMillis != null
              && (firstOutputMillis < 0 || firstOutputMillis > durationMillis))
          || (startAccountingMillis != null && startAccountingMillis < 0)) {
        throw new IllegalArgumentException("invalid attempt timing");
      }
    }
  }

  public record CallFinished(
      CallLifecycle outcome,
      Long queueMillis,
      Long admissionAccountingMillis,
      io.aeyer.plowshare.server.llm.counting.PromptCount preflight)
      implements Payload {
    public CallFinished(CallLifecycle outcome) {
      this(outcome, null, null, null);
    }

    public CallFinished(CallLifecycle outcome, Long queueMillis, Long admissionAccountingMillis) {
      this(outcome, queueMillis, admissionAccountingMillis, null);
    }

    public CallFinished {
      Objects.requireNonNull(outcome, "outcome");
      if (!outcome.terminal()) {
        throw new IllegalArgumentException("call finish needs a terminal outcome");
      }
      if ((queueMillis != null && queueMillis < 0)
          || (admissionAccountingMillis != null && admissionAccountingMillis < 0)) {
        throw new IllegalArgumentException("invalid call timing");
      }
    }
  }

  public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    OTHER,
    UNKNOWN
  }
}
