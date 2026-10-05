package io.aeyer.plowshare.server.access;

import static io.aeyer.plowshare.server.access.ResourceScopeRepository.Kind.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Transport codec for authorization references. Raw payloads never reach authorization policy. */
public final class AccessRequestDecoder {
  private AccessRequestDecoder() {}

  public static AccessRequest decode(String operation, Map<String, ?> payload) {
    Set<String> projects = new LinkedHashSet<>();
    String project = text(payload.get("project"));
    if (project != null) projects.add(project);
    boolean projectScope = false;
    if (payload.get("scope") != null) {
      if (!(payload.get("scope") instanceof Map<?, ?> scope))
        throw new CallerFault("scope must be an object");
      if ("project".equals(scope.get("kind"))) {
        projectScope = true;
        String scoped = text(scope.get("project"));
        if (scoped != null) projects.add(scoped);
      }
    }
    List<AccessRequest.Resource> resources = new ArrayList<>();
    add(resources, CONVERSATION, payload.get("conversation"));
    if (payload.get("conversations") != null) {
      if (!(payload.get("conversations") instanceof List<?> ids) || ids.size() > 256)
        throw new CallerFault("conversations must be a bounded list");
      for (Object id : ids) add(resources, CONVERSATION, id);
    }
    add(resources, JOB, payload.get("job"));
    add(resources, MEMORY, payload.get("memory"));
    add(resources, PROPOSAL, payload.get("proposal"));
    add(resources, TOPIC, payload.get("topic"));
    add(resources, INSTANCE, payload.get("instance"));
    if (operation.startsWith("orchestration.")) {
      add(resources, ORCHESTRATION, payload.get("id"));
      add(resources, ORCHESTRATION, payload.get("root"));
    }
    if (operation.startsWith("message.")) add(resources, MESSAGE, payload.get("message"));
    if (operation.equals("schedule.pause") || operation.equals("schedule.forget"))
      add(resources, SCHEDULE, payload.get("schedule"));
    if (operation.startsWith("trigger.") && !operation.equals("trigger.define"))
      add(resources, TRIGGER, payload.get("trigger"));
    if (operation.startsWith("board.") || operation.startsWith("swarm.")) {
      add(resources, TOPIC, payload.get("root"));
      add(resources, TOPIC, payload.get("board"));
    }
    if (operation.startsWith("approval.")) add(resources, APPROVAL, payload.get("id"));
    if (operation.startsWith("usage.")) {
      add(resources, JOB, payload.get("run"));
      add(resources, ORCHESTRATION, payload.get("orchestration"));
    }
    if (operation.startsWith("outgoing.")) add(resources, OUTGOING, payload.get("id"));
    Object paused = payload.get("paused");
    if (operation.equals("schedule.pause") && paused != null && !(paused instanceof Boolean))
      throw new CallerFault("paused must be a boolean");
    return new AccessRequest(
        projects, resources, projectScope, paused instanceof Boolean value ? value : null);
  }

  private static String text(Object value) {
    if (value == null) return null;
    if (!(value instanceof String text))
      throw new CallerFault("Authorization reference must be text");
    return AccessRequest.text(text);
  }

  private static void add(
      List<AccessRequest.Resource> resources, ResourceScopeRepository.Kind kind, Object value) {
    String id = text(value);
    if (id != null) resources.add(new AccessRequest.Resource(kind, id));
  }
}
