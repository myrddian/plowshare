package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.protocol.Usage;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.llm.accounting.UsageReports;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Usage has one authenticated WebSocket surface and no REST controller. */
@Component
public class UsageFrames implements FrameArea {
  private final UsageReports queries;
  private final UsageSubscriptions subscriptions;

  public UsageFrames(UsageReports queries, UsageSubscriptions subscriptions) {
    this.queries = queries;
    this.subscriptions = subscriptions;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(
            FrameTypes.USAGE_CONVERSATION, (p, a) -> report(FrameTypes.USAGE_CONVERSATION, p, a)),
        Map.entry(FrameTypes.USAGE_PROJECT, (p, a) -> report(FrameTypes.USAGE_PROJECT, p, a)),
        Map.entry(FrameTypes.USAGE_AGENT, (p, a) -> report(FrameTypes.USAGE_AGENT, p, a)),
        Map.entry(FrameTypes.USAGE_RUN, (p, a) -> report(FrameTypes.USAGE_RUN, p, a)),
        Map.entry(
            FrameTypes.USAGE_ORCHESTRATION, (p, a) -> report(FrameTypes.USAGE_ORCHESTRATION, p, a)),
        Map.entry(FrameTypes.USAGE_MODELS, (p, a) -> report(FrameTypes.USAGE_MODELS, p, a)),
        Map.entry(FrameTypes.USAGE_POOLS, (p, a) -> report(FrameTypes.USAGE_POOLS, p, a)),
        Map.entry(FrameTypes.USAGE_CALLS, this::calls),
        Map.entry(FrameTypes.USAGE_SUBSCRIBE, this::subscribe),
        Map.entry(FrameTypes.USAGE_UNSUBSCRIBE, this::unsubscribe));
  }

  private Outcome report(String type, Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(type);
    var query = queries.resolve(type, Payloads.as(payload, Usage.Filter.class, type));
    return Outcome.ok(queries.report(account, query));
  }

  record CallPage(String call, @JsonProperty("attempt_cursor") String attemptCursor) {}

  private Outcome calls(Map<String, Object> payload, Asking asking) {
    String account = asking.requireHandle(FrameTypes.USAGE_CALLS);
    var query =
        queries.resolve(
            FrameTypes.USAGE_CALLS,
            Payloads.as(payload, Usage.Filter.class, FrameTypes.USAGE_CALLS));
    var call = Payloads.as(payload, CallPage.class, FrameTypes.USAGE_CALLS);
    if (call.call() != null)
      return Outcome.ok(queries.attempts(account, query, call.call(), call.attemptCursor()));
    if (call.attemptCursor() != null)
      throw new io.aeyer.plowshare.server.faults.CallerFault("an attempt cursor needs its call");
    return Outcome.ok(queries.calls(account, query));
  }

  record Subscribe(@JsonProperty("report_type") String reportType) {}

  private Outcome subscribe(Map<String, Object> payload, Asking asking) {
    asking.requireHandle(FrameTypes.USAGE_SUBSCRIBE);
    var type = Payloads.as(payload, Subscribe.class, FrameTypes.USAGE_SUBSCRIBE).reportType();
    var query =
        queries.resolve(type, Payloads.as(payload, Usage.Filter.class, FrameTypes.USAGE_SUBSCRIBE));
    return Outcome.ok(subscriptions.subscribe(asking, query));
  }

  private Outcome unsubscribe(Map<String, Object> payload, Asking asking) {
    asking.requireHandle(FrameTypes.USAGE_UNSUBSCRIBE);
    subscriptions.unsubscribe(
        asking,
        Payloads.required(
            payload, "subscription", FrameTypes.USAGE_UNSUBSCRIBE, "the subscription to remove"));
    return Outcome.ok(Map.of());
  }
}
