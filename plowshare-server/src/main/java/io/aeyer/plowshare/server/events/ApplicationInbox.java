package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.orchestrations.OrchestrationRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Authorized application output and inspection, independent of private execution-log custody. */
public interface ApplicationInbox extends ApplicationNotices {
  InboxStore.Page page(String account, String project, boolean unread, int offset, int limit);

  /** Authorizes an Application subscription using the same Viewer gate as inbox inspection. */
  long subscriptionProject(String account, String project);

  /** Exact published result under current Viewer authority and a pinned Application identity. */
  Optional<InboxItem> subscriptionItem(
      String account, String project, long expectedProject, String item);

  /** Validate a reference-only alert with the same Viewer gate, without loading report text. */
  boolean canSeeSubscriptionItem(
      String account, String project, long expectedProject, String item, String kind);

  record Acknowledged(int marked, int unread) {}

  Acknowledged read(String account, String project, List<String> items);

  /**
   * Publish once under this producer/project/UUID, with current Managers as attention recipients.
   */
  void publish(String account, String project, UUID request, String kind, String text);

  /** Read the original producer's exact admission without resubmitting a mutation. */
  boolean published(String account, String project, UUID request);

  /** Whether current Manager authority permits answering this published background question. */
  boolean manages(OrchestrationRecord run, String account);

  boolean managesQuestion(String conversation, String source, String account);
}
