package io.aeyer.plowshare.server.acting;

/**
 * Semantic capabilities classified by the owning service, not strings chosen by an external caller.
 * READ and WORK map to scoped READ and WRITE. Creation and inference are separate checks, not
 * implied by permission to cancel or inspect existing work. Owner/admin capabilities remain
 * distinct from ordinary writes even when an existing endpoint uses a CONTRIBUTOR role gate.
 */
public enum ActingPermission {
  READ,
  WORK,
  CONVERSATION_CREATE,
  RUN_MODEL,
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
