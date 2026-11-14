package io.aeyer.plowshare.server.events;

import java.util.Optional;

/**
 * The project facts an Application schedule save branches on: the project's current name (the audit
 * target) and its type, which decides whether the save writes in place or needs a new deployment
 * revision (ADR 0068). Implementations read the project row; schedule services never do.
 */
public interface ApplicationProjects {
  /** Project types as {@code projects.type} stores them. An unknown stored value is refused. */
  enum ProjectType {
    STANDARD,
    MANAGED,
    DISJOINT;

    /** Converts the stored text, refusing anything outside the three known types. */
    public static ProjectType parse(String stored) {
      if (stored != null)
        for (ProjectType type : values()) if (type.name().equals(stored)) return type;
      throw new IllegalStateException("Unknown project type");
    }
  }

  /** A project's current name and type. */
  record ApplicationProject(String name, ProjectType type) {
    public ApplicationProject {
      if (name == null || name.isBlank() || name.length() > 256 || type == null)
        throw new IllegalArgumentException("Invalid Application project");
    }
  }

  /**
   * The project with this surrogate id, or empty when no project row on this server has it.
   *
   * @param projectId the schedule source's project id; positive
   */
  Optional<ApplicationProject> find(long projectId);
}
