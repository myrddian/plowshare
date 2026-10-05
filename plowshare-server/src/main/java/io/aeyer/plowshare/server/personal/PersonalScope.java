package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;

/** Validated resource references for Personal and session-owned workspace authorization. */
public record PersonalScope(
    List<String> clientProjects, List<String> projects, List<String> conversations) {
  public PersonalScope {
    clientProjects = identities(clientProjects);
    projects = identities(projects);
    conversations = identities(conversations);
  }

  private static List<String> identities(List<String> values) {
    if (values.size() > 256) throw new CallerFault("Too many scope references");
    return values.stream()
        .map(
            value -> {
              if (value == null
                  || value.isBlank()
                  || value.length() > 1024
                  || value.codePoints().anyMatch(Character::isISOControl))
                throw new CallerFault("Scope references must be bounded nonblank identities");
              return value;
            })
        .distinct()
        .toList();
  }
}
