package io.aeyer.plowshare.server.approvals;

/** Reads scoped workspace settings for one SECURITY consultation. */
@FunctionalInterface
public interface WorkspaceSecurityGuidance {
  WorkspaceSecurityGuidance NONE = (project, provider, work) -> Guidance.EMPTY;

  record Guidance(String workspace, String checkout) {
    public static final Guidance EMPTY = new Guidance("", "");
  }

  /** The project and selected provider come from an already admitted operation. */
  Guidance read(String project, String provider, ReviewWork work);
}
