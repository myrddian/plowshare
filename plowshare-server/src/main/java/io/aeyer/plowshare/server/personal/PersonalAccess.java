package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.session.SessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Checks resource scope even when a request names a durable conversation instead of its project.
 */
@Component
public final class PersonalAccess {
  private final ConversationStore conversations;
  private SessionRegistry sessions;

  @Autowired(required = false)
  public void useSessions(SessionRegistry sessions) {
    this.sessions = sessions;
  }

  public void requireClientProject(String project, String session, String handle) {
    if (sessions != null
        && sessions.logicalSessions(session).stream()
            .anyMatch(logical -> ClientProjects.visible(project, logical, handle))) return;
    ClientProjects.requireOwn(project, session, handle);
  }

  public PersonalAccess(ConversationStore conversations) {
    this.conversations = conversations;
  }

  public void project(String project, String handle) {
    io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(project, null, handle);
    PersonalSpaces.requireOwn(project, handle);
  }

  public void conversation(String conversation, String handle) {
    if (conversation != null)
      conversations
          .find(conversation)
          .ifPresent(
              row -> {
                io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(
                    row.home().project(), null, handle);
                PersonalSpaces.requireOwn(row.home().project(), handle);
              });
  }

  public void check(PersonalScope scope, String handle, String session) {
    for (String project : scope.clientProjects()) requireClientProject(project, session, handle);
    for (String project : scope.projects()) {
      PersonalSpaces.requireOwn(project, handle);
      requireClientProject(project, session, handle);
    }
    for (String id : scope.conversations())
      conversations
          .find(id)
          .ifPresent(
              row -> {
                PersonalSpaces.requireOwn(row.home().project(), handle);
                requireClientProject(row.home().project(), session, handle);
              });
  }

  public void check(PersonalScope scope, String handle) {
    check(scope, handle, null);
  }
}
