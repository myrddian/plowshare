package io.aeyer.plowshare.server.relay;

import java.util.List;

/** Persistence for the native approval-to-Inbox adapter; it never changes an approval decision. */
public interface RelayApprovalInboxRepository {
  String SUBSCRIBER = "builtin.approvals.inbox";

  List<Relay.TopicKey> ready(String after, int limit);

  /**
   * IDs missing their Inbox item or due another unread alert; reading or settling stops retries.
   */
  List<String> missing(long projectId, String after, int limit);

  /** Settled decisions whose Inbox item survived a lost resolution delivery. */
  List<String> unsettledNotices(long projectId, String after, int limit);

  /** Acknowledge only the live lease; the approval itself remains the durable recovery source. */
  void advance(RelayConsumerRepository.Lease lease, long position);

  /** Audit the retained pending source before acknowledging a publication-retention gap. */
  void recoverGap(RelayConsumerRepository.Lease lease, long boundary);

  /** Bounded operator-audit retention; pending approvals and Inbox items are never pruned here. */
  int prune(java.time.Duration retention, int limit);
}
