package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.archive.ConversationStore;
import org.springframework.stereotype.Component;

/**
 * Checks resource scope even when a request names a durable conversation instead of its project.
 */
@Component
public final class PersonalAccess {
  private final ConversationStore conversations;

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
    for (String project : scope.clientProjects())
      io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(project, session, handle);
    for (String project : scope.projects()) {
      PersonalSpaces.requireOwn(project, handle);
      io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(project, session, handle);
    }
    for (String id : scope.conversations())
      conversations
          .find(id)
          .ifPresent(
              row -> {
                PersonalSpaces.requireOwn(row.home().project(), handle);
                io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(
                    row.home().project(), session, handle);
              });
  }

  public void check(PersonalScope scope, String handle) {
    check(scope, handle, null);
  }
}
