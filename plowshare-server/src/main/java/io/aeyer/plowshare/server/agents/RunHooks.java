package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageStart;
import java.util.List;

/**
 * The log stages that happen inside one run's turn — {@code stage.pre}, {@code stage.post} and
 * {@code approval.pre} — asked of that run's own chain, its profile's harness hooks then the
 * project's (spec 2026-09-28-hooks-reach-the-log decision 10).
 *
 * <p><b>Fails closed</b> (§2.4): a chain that throws denies. <b>Writes nothing</b>: each gate hands
 * back its records, and a caller with no {@code ToolPre} to carry them parks them with {@link
 * #record}; {@code JobRuntime} writes what is parked into the log with the tool call in flight, so
 * the records land in the asking run's own log (decision 7).
 *
 * <p><b>Ask a gate, and park, only inside one of this run's tool calls.</b> The loop drains what is
 * parked only after a tool call's result: a record parked anywhere else would be lost when the run
 * ends, or written after the next call's result, in the wrong place. Every caller today is inside a
 * tool ({@code todo_write}, {@code run}, {@code orchestration_check}); a new one outside a tool
 * needs a drain of its own first.
 *
 * <p>Built once per run by {@code JobRuntime} and handed to what asks: {@link RunExtras.Context}
 * and {@link RunTool}. {@link #NONE} is a run with no hooks, and every fixture.
 */
public interface RunHooks {

  RunHooks NONE = new RunHooks() {};

  /** {@code stage.pre}: a locked stage is about to start or be returned to. */
  default Gate stagePre(HookContext.Orchestration orchestration, StageStart start) {
    return Gate.NOTHING;
  }

  /** {@code stage.post}: a locked stage is about to be marked done, every system gate passed. */
  default Gate stagePost(HookContext.Orchestration orchestration, StageDone done) {
    return Gate.NOTHING;
  }

  /**
   * {@code approval.pre}: a person is about to be asked. Never an allow (decision 5).
   *
   * @param environment where the command would run
   * @param orchestration the orchestration the question is asked for, or {@code null}
   */
  default Gate approvalPre(
      HookContext.RunEnvironment environment,
      HookContext.Orchestration orchestration,
      Approving approving) {
    return Gate.NOTHING;
  }

  /**
   * Parks records for the run loop to write with the tool call in flight. The caller must be inside
   * a tool call of this run: the loop drains only after a tool result.
   */
  default void record(List<HookRecord> records) {}

  /** Parks the gate's records and returns it, for a caller with no {@code ToolPre}. */
  default Gate recorded(Gate gate) {
    record(gate.records());
    return gate;
  }
}
