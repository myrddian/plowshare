package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Set;

/**
 * Bounded delegation scope. Personal permission is explicit and is never encoded in the ordinary
 * project set. An admitted scope can narrow a live token, never expand it.
 */
public record ActingScope(ProjectRole ceiling, Set<String> projects, boolean personal) {
  public ActingScope {
    ceiling = ActingValues.ceiling(ceiling);
    projects = Set.copyOf(projects);
    if (projects.size() > 256) {
      throw new IllegalArgumentException("Acting scope has too many projects");
    }
    projects.forEach(ActingValues::application);
  }
}
