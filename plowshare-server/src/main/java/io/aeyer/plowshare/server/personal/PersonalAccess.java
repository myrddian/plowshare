package io.aeyer.plowshare.server.personal;

import io.aeyer.plowshare.server.archive.ConversationStore;
import java.util.Map;
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

  public void project(Object project, String handle) {
    if (project instanceof String name)
      io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(name, null, handle);
    if (project instanceof String name) PersonalSpaces.requireOwn(name, handle);
  }

  public void conversation(Object conversation, String handle) {
    if (conversation instanceof String id)
      conversations
          .find(id)
          .ifPresent(
              row -> {
                io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(
                    row.home().project(), null, handle);
                PersonalSpaces.requireOwn(row.home().project(), handle);
              });
  }

  public void payload(Map<?, ?> payload, String handle, String session) {
    io.aeyer.plowshare.server.archive.ClientProjects.requirePayload(payload, session, handle);
    if (payload.get("project") instanceof String name) {
      PersonalSpaces.requireOwn(name, handle);
      io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(name, session, handle);
    }
    if (payload.get("conversation") instanceof String id)
      conversations
          .find(id)
          .ifPresent(
              row -> {
                PersonalSpaces.requireOwn(row.home().project(), handle);
                io.aeyer.plowshare.server.archive.ClientProjects.requireOwn(
                    row.home().project(), session, handle);
              });
    if (payload.get("conversations") instanceof Iterable<?> ids)
      for (Object id : ids) payload(Map.of("conversation", id), handle, session);
  }

  public void payload(Map<?, ?> payload, String handle) {
    payload(payload, handle, null);
  }
}
