package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.hooks.*;
import java.util.*;

/** Durable gated-transition receipts. All fenced writes participate in the caller's transaction. */
public interface InformationGateRepository {
  enum State {
    RESERVED,
    APPROVED,
    COMPLETED
  }

  record Receipt<T extends InformationGateResult>(UUID token, State state, T response, String log) {
    public Receipt {
      Objects.requireNonNull(token);
      Objects.requireNonNull(state);
      if (state == State.COMPLETED) Objects.requireNonNull(response);
    }
  }

  /** A definitive report refusal with the exact dependency set bound before the write. */
  record ReportRefusal(String reason, Set<UUID> inputs) {
    public ReportRefusal {
      Objects.requireNonNull(reason);
      inputs = Set.copyOf(inputs);
    }
  }

  /** Returns proof only for an account-owned, blocked report with no admitted revision. */
  Optional<ReportRefusal> refusedReport(String account, UUID request);

  /**
   * Serializes request identity, refuses collisions, and claims expired work without refiring
   * approved hooks.
   */
  <T extends InformationGateResult> Receipt<T> claim(
      String account, UUID request, String fingerprint, String operation, Class<T> result);

  void fence(String account, UUID request, UUID token);

  String openedLog(String account, UUID request);

  void opened(String account, UUID request, String log);

  void approved(String account, UUID request);

  void completed(String account, UUID request, InformationGateResult result);

  void blocked(String account, UUID request, UUID token, String error);

  void preGate(String account, UUID request, UUID token, Gate gate);

  void postGate(String account, UUID request, UUID token, Gate gate);

  void finishRecords(String account, UUID request, UUID token, List<HookRecord> records);
}
