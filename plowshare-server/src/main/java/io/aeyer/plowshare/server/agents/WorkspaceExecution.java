package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Execution-owner boundary for retaining work across a required workspace outage. Transport
 * detection is separate in ClientWork; callers cannot register a replacement provider here.
 */
public interface WorkspaceExecution extends ContinuationFrames {
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  Scope enter(String job, BooleanSupplier cancelled, BiConsumer<Job.State, String> changed);

  String logicalSession(String session);

  /** Wait at a safe boundary without inference slots, transactions or command permits. */
  void boundary();

  /** Rebuild at most one incomplete pure snapshot, within its remaining active deadline. */
  <T> T read(Supplier<T> read);

  boolean dependent();

  void checkpoint(
      String session,
      String executor,
      String allowance,
      Supplier<List<BudgetCheckpoint.Frame>> frames);

  Scope definition(AgentDefinition definition, Runnable validate);

  void modelCalls(int calls);

  Optional<Outcome> completedOutcome(String conversation);

  void completing(String conversation, Outcome outcome);

  Optional<String> restoringJob(String conversation);

  Optional<Job> retainedJob(String job);

  boolean retained(String job);

  boolean live(String job);

  boolean draining(String job);

  /** Settle durable cancellation before publishing any terminal callbacks or results. */
  Outcome finished(String job, Outcome outcome);

  Optional<Outcome> cancelRetained(String job);

  /** Retain safely checkpointed workers rather than fabricating terminal cancellation. */
  void shutdown();
}
