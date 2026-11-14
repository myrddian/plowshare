package io.aeyer.plowshare.server.orchestrations;

/** Bounded child-notice consumption and owning-record reconciliation in one leased group. */
@FunctionalInterface
public interface ChildCompletionConsumer {
  void consume(long project, int limit);
}
