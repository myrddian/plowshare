package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Optional;

/** Retained approval identities and notification acknowledgements; delivery never answers them. */
public interface ApprovalNotificationStore {
  /** Read authoritative scope and decision state before delivering a reference-only event. */
  Optional<RunApproval> find(String id);

  /** Pending direct-fallback deliveries for one root, after its execution has closed. */
  List<RunApproval> undelivered(String conversation);

  /** Pending direct-fallback deliveries retained across a process restart. */
  List<RunApproval> undelivered();

  /** Acknowledges notification only; the human decision remains pending. */
  void delivered(String id);
}
