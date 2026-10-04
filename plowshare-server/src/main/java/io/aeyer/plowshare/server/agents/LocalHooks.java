package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.hooks.HookFile;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import java.util.List;

/**
 * A log's local hooks, snapshotted as it opens (spec 2026-09-30-local-hooks-are-served decisions 3,
 * 4 and 7). {@link HookedLogStages#opened} asks this before {@code log.open} fires, so the local
 * tier sees its own {@code log.open}.
 *
 * <p><b>Never throws.</b> A log it could not snapshot has no local tier, and what it returns says
 * why, as {@code HOOK} records for the log at {@code log.open}. {@link #NONE} is a server, or a
 * fixture, with no local tier.
 */
@FunctionalInterface
public interface LocalHooks {

  LocalHooks NONE = opened -> List.of();

  /**
   * Snapshot and pin {@code opened}'s local hooks, or inherit its parent's.
   *
   * @return records to write at {@code log.open}: empty when the log got its set, inherited one, or
   *     has none by design; one {@code failed} record when files could not be read
   */
  List<HookRecord> pin(LogStages.LogOpened opened);

  /** A log that has no local tier because its files could not be read or stored (decision 7). */
  static HookRecord unpinned(String reason) {
    return new HookRecord(
        HookFile.WHOLE_SET,
        null,
        Tier.LOCAL,
        Stage.LOG_OPEN,
        null,
        HookRecord.FAILED,
        reason,
        null,
        null,
        0);
  }

  /**
   * A log left with no local tier by a failure nobody expected: said once, its detail escaped and
   * clipped as every reason that may carry a session's text is (decision 8), for both the pinner's
   * own catch-all and the one around it in {@code HookedLogStages}.
   */
  static HookRecord unsnapshotted(RuntimeException failed) {
    return unpinned(
        "this log's local hooks could not be snapshotted, so it has none: "
            + ChannelHooks.clip(JobRuntime.describe(failed)));
  }
}
