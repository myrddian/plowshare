package io.aeyer.plowshare.server.archive;

/**
 * Whether a project name is already registered: the one fact the file channel needs to tell
 * creating a project apart from attaching to one.
 *
 * <p>"Registered" means any {@code projects} row has that name, whatever kind of project it is and
 * whether or not it has members. A row with no members is an orphan (a memory write registers its
 * project) and stays administrator-only; it is not available to a project creator.
 */
public interface ProjectRegistry {
  /**
   * @param project the project name, as the client declared it
   * @return true when a {@code projects} row has exactly this name
   * @throws ArchiveUnavailableException if the archive cannot be reached
   */
  boolean registered(String project);
}
