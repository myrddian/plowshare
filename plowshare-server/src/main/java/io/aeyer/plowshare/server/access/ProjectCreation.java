package io.aeyer.plowshare.server.access;

/**
 * Whether an account may create new projects: the {@code PROJECT_CREATOR} role decision, and
 * nothing else. Server administration is deliberately not consulted; an administrator creates
 * projects only after the role is granted to them.
 */
public interface ProjectCreation {
  /**
   * {@code authority_may_create_projects(handle)} (V262): true only for an active, non-bootstrap
   * USER account holding a live (unrevoked, unexpired, lineage live) {@code PROJECT_CREATOR} grant.
   * A null or blank handle is false.
   */
  boolean mayCreate(String handle);
}
