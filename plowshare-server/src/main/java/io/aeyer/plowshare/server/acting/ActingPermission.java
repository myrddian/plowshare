package io.aeyer.plowshare.server.acting;

/**
 * Semantic capabilities classified by the owning service, not strings chosen by an external caller.
 * Transport and internal-tool classification are later slices. Owner/admin capabilities remain
 * distinct from ordinary writes even when an existing endpoint uses a CONTRIBUTOR role gate.
 */
public enum ActingPermission {
  READ,
  WORK,
  ADMINISTRATION,
  ACCOUNT_MANAGEMENT,
  CREDENTIAL_MANAGEMENT,
  GRANT_MANAGEMENT,
  IDENTITY_LINK_MANAGEMENT,
  MEMBERSHIP_MANAGEMENT,
  INFORMATION_OWNER_CONTROL,
  CONTENT_TYPE_MANAGEMENT,
  CONTENT_TYPE_CLEAR,
  APPROVAL,
  SCHEDULE_CREATE,
  TRIGGER_CREATE,
  UNCLASSIFIED
}
