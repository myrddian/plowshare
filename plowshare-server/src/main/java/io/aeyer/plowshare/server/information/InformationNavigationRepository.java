package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationNavigation.*;
import java.util.*;

/** Specialist store for document projections, reference policy and account-owned navigation. */
public interface InformationNavigationRepository {
  /** Serialize navigation mutations and retain role rows until transaction commit. */
  void lock();

  /** First statement in a fresh read transaction: keep multi-query projections on one snapshot. */
  void snapshot();

  void requireActive(String account);

  void lockAccount(String account);

  void requireManager(String account, PolicyTarget target);

  boolean canManage(String account, PolicyTarget target);

  PolicyView policy(String account, PolicyTarget target);

  PolicyView setPolicy(
      String account, PolicyTarget target, long expectedVersion, PolicySettings settings);

  Documents documents(
      InformationContext context,
      UUID relatedTo,
      String relation,
      String direction,
      int limit,
      int offset);

  io.aeyer.plowshare.protocol.Information.Facets facets(
      InformationContext context, UUID relatedTo, String relation, String direction);

  default io.aeyer.plowshare.protocol.Information.Facets facets(InformationContext context) {
    return facets(context, null, null, null);
  }

  Selection select(InformationContext context, Target target);

  Connections relationships(
      InformationContext context,
      UUID resource,
      String direction,
      String relation,
      UUID otherResource,
      int limit,
      int offset);

  default Connections relationships(
      InformationContext context,
      UUID resource,
      String direction,
      String relation,
      int limit,
      int offset) {
    return relationships(context, resource, direction, relation, null, limit, offset);
  }

  Relationship create(
      InformationContext context,
      UUID id,
      UUID source,
      Target target,
      String relation,
      UUID expectedSourceRevision);

  Changed remove(InformationContext context, UUID id, long version);

  Views views(String account);

  SavedView view(String account, UUID id);

  SavedView save(String account, UUID id, long version, String label, Path path);

  Changed delete(String account, UUID id, long version);

  /** Recheck current disclosure before recovering a relationship creation result. */
  void requireReceiptReadable(InformationContext context, Receipt result);

  Optional<StoredReceipt> receipt(String account, UUID requestId);

  void record(
      String account,
      UUID requestId,
      String fingerprint,
      Receipt result,
      String action,
      String subject,
      String reason,
      Optional<Receipt> previous);

  String fingerprint(io.aeyer.plowshare.protocol.InformationRequest command);

  /** Typed receipt alternatives; serialization is a persistence concern. */
  record Receipt(PolicyView policy, Relationship relationship, SavedView view, Changed changed) {
    public Receipt {
      if ((policy == null ? 0 : 1)
              + (relationship == null ? 0 : 1)
              + (view == null ? 0 : 1)
              + (changed == null ? 0 : 1)
          != 1) throw new IllegalArgumentException("exactly one receipt alternative required");
    }
  }

  record StoredReceipt(String fingerprint, Receipt result) {}
}
