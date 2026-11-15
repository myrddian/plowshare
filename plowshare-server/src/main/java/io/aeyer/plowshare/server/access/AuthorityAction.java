package io.aeyer.plowshare.server.access;

/** Substrate vocabulary. Declaring an action does not enable an unmigrated runtime consumer. */
public enum AuthorityAction {
  READ,
  WRITE,
  MANAGE_ACCESS,
  MANAGE_SCOPE,
  SET_AUDIENCE,
  ADMINISTER,
  CONVERSATION_CREATE,
  RUN_MODEL,
  /** Create a new project. Granted only by PROJECT_CREATOR, at the PROJECT_CREATION scope. */
  PROJECT_CREATE
}
