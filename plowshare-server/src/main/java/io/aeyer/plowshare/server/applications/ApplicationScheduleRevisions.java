package io.aeyer.plowshare.server.applications;

/**
 * Saves one schedule file of a MANAGED Application as a new, schedule-only deployment revision (ADR
 * 0068).
 *
 * <p>The revision is the active revision's retained files byte for byte, with only {@code
 * schedules/<name>.json} added or replaced. The manifest is copied, never re-resolved, and the
 * destination, writable areas and placement are those of the active release. The revision commits
 * through the deployment store under its head-revision fence, with a fresh request id and receipt.
 *
 * <p>Authority: the caller must be a current project Manager. Server administration and FileStore
 * {@code MANAGER} access on the destination are not required, because the revision lands in the
 * existing destination the server staged for the already-placed project. Service principals are
 * refused.
 *
 * <p>Failure: a refusal leaves the active head unchanged and removes the staged revision. A failure
 * whose commit outcome is unknown keeps the staged revision, which may be the new head, and is
 * never retried automatically: the caller reads the deployment status before saving again.
 */
public interface ApplicationScheduleRevisions {
  /**
   * Creates, validates and activates the schedule-only revision.
   *
   * @param caller the authenticated account; must be a current project Manager
   * @param projectId the MANAGED Application project's surrogate id
   * @param scheduleName the schedule's file name without {@code .json}; follows the schedule
   *     identity rule
   * @param text the complete definition file text, at most 64 KiB
   * @param overwrite {@code false} refuses an existing schedule of that name
   * @return the new active revision id
   */
  String revise(String caller, long projectId, String scheduleName, String text, boolean overwrite);
}
