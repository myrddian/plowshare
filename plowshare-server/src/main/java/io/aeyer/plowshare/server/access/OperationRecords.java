package io.aeyer.plowshare.server.access;

/** Specialist persistence for immutable operation admission and existing job correlation. */
public interface OperationRecords {
  /** Admission and all supporting grants commit atomically; failures prevent dispatch. */
  void retain(OperationAuthority.Admission admission);

  /** Child admission and parent edge commit together; no partial authority is usable. */
  void retainChild(OperationAuthority.Admission admission, java.util.UUID parent);

  /** Rehydrates sealed immutable evidence; stored evidence alone is never current permission. */
  java.util.Optional<OperationAuthority.Admission> find(java.util.UUID id);

  /** Exact immutable ancestor; never selects a replacement grant or operation. */
  java.util.Optional<java.util.UUID> parent(java.util.UUID id);

  /** Original agent, source budget and admission for an existing conversation. */
  java.util.Optional<OperationAuthority.Conversation> conversation(String id);

  /** Retained conversation inputs, output and durable allowance owner for live scope checks. */
  java.util.List<AccessRequest.Resource> targets(java.util.UUID operation);

  /** Immutable, idempotent only for an identical binding. No foreign conversation is adopted. */
  void bind(OperationAuthority.Conversation conversation);

  /** Repeated correlation is allowed only for the same job. No new job or resume is created. */
  void started(java.util.UUID operation, String job);
}
