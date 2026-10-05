package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Sanitized authorization references, distinct from an operation's business input. */
public record AccessRequest(
    Set<String> projects, List<Resource> resources, boolean projectScope, Boolean paused) {
  public AccessRequest {
    projects = Set.copyOf(projects);
    projects.forEach(AccessRequest::text);
    resources = List.copyOf(resources);
    if (projects.size() > 256 || resources.size() > 256)
      throw new CallerFault("Too many authorization references");
  }

  public record Resource(ResourceScopeRepository.Kind kind, String id) {
    public Resource {
      Objects.requireNonNull(kind, "resource kind");
      id = text(id);
      if (kind == ResourceScopeRepository.Kind.OUTGOING) {
        try {
          UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
          throw new CallerFault("Outgoing work ID must be a UUID");
        }
      }
    }
  }

  public static AccessRequest project(String project) {
    return new AccessRequest(
        project == null ? Set.of() : Set.of(text(project)), List.of(), false, null);
  }

  public static AccessRequest resource(ResourceScopeRepository.Kind kind, String id) {
    return new AccessRequest(Set.of(), List.of(new Resource(kind, id)), false, null);
  }

  static String text(String text) {
    if (text == null
        || text.isBlank()
        || text.length() > 1024
        || text.codePoints().anyMatch(Character::isISOControl))
      throw new CallerFault("Authorization references must be bounded nonblank text");
    return text.strip();
  }
}
