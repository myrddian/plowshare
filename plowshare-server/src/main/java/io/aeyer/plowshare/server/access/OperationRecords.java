package io.aeyer.plowshare.server.access;

/** Specialist persistence for immutable operation admission and existing job correlation. */
public interface OperationRecords {
  /** Admission and all supporting grants commit atomically; failures prevent dispatch. */
  void retain(OperationAuthority.Admission admission);

  /** Repeated correlation is allowed only for the same job. No new job or resume is created. */
  void started(java.util.UUID operation, String job);
}
