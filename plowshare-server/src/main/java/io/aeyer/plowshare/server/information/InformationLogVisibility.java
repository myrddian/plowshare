package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Current retained-input visibility, reusable across feeds with the same effective account. Facts
 * never replace the owner's log ownership/selection checks. The callback applies them before
 * paging/counting; its result is returned only after a current access/authority fence. Callbacks
 * must be repeatable pure database reads without mutations or external effects: a raced cache fence
 * discards their tentative result and may invoke them once more with authoritative facts, under
 * authority admission. Each callback is one bounded SQL unit and inherits any earlier enclosing
 * transaction deadline; cache/admission waits do not create a whole-workflow deadline. An
 * authoritative callback evaluates visibility in its own selection statement and needs no global
 * cache-version fence. SQL errors and deadline failures propagate without retry. Repositories own
 * SQL cancellation; the shared SQL-unit deadline rejects late results and never renews an active
 * caller transaction's budget.
 */
public interface InformationLogVisibility {
  /** Empty means the repository must evaluate the authoritative predicate in its own statement. */
  record Facts(Optional<List<String>> blocked) {
    public Facts {
      blocked = blocked.map(List::copyOf);
    }

    public static Facts authoritative() {
      return new Facts(Optional.empty());
    }
  }

  <T> T read(String account, Function<Facts, T> reader);

  /**
   * Whether {@code account} may read {@code log}, with the verdict of {@code
   * information_log_readable}. Current facts of the log's trees answer in memory: the same ancestry
   * edges, quarantine and retained-input authority, evaluated in Java and confirmed current by
   * their item versions before the verdict is returned. Whenever facts cannot be shared — no
   * account, a dirty or historical transaction, an oversized or unaddressable tree, a stale
   * confirmation, or a disabled cache — the owner's {@code authoritative} predicate decides
   * instead. No SQL runs between confirming the facts and using them, so no further fence is
   * needed.
   */
  boolean readable(String account, String log, BooleanSupplier authoritative);

  static InformationLogVisibility direct() {
    return new InformationLogVisibility() {
      public <T> T read(String account, Function<Facts, T> reader) {
        return reader.apply(Facts.authoritative());
      }

      public boolean readable(String account, String log, BooleanSupplier authoritative) {
        return authoritative.getAsBoolean();
      }
    };
  }
}
