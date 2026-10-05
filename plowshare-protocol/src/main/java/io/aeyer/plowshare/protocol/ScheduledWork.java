package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Objects;

/**
 * A portable schedule definition. Ownership and source location come from authenticated
 * registration.
 */
public record ScheduledWork(
    int version,
    String cron,
    String zone,
    boolean paused,
    Action action,
    Target target,
    Limits limits) {
  public ScheduledWork {
    if (version != 1) throw new IllegalArgumentException("Use schedule definition version 1");
    cron = text(cron, "cron", 256);
    zone = text(zone, "zone", 128);
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(target, "target");
    if (limits == null) limits = new Limits(null, null, 1);
    if (target.conversation() != null
        && (limits.maxModelCalls() != null || limits.maxTurns() != null))
      throw new IllegalArgumentException("Conversation schedules use the conversation's limits");
  }

  /** Skills and orchestrations use qualified commands with their ordinary caller grants. */
  public record Action(String kind, String agent, String name, String input, String mode) {
    public Action {
      if (!List.of("agent", "skill", "orchestration").contains(kind))
        throw new IllegalArgumentException("action.kind must be agent, skill or orchestration");
      agent = identity(agent, "agent");
      input = text(input, "input", 16000);
      if (kind.equals("agent")) {
        if (name != null || mode != null)
          throw new IllegalArgumentException("Agent tasks do not take name or mode");
      } else {
        name = identity(name, "action name");
        if (mode != null
            && (!kind.equals("skill")
                || !List.of("INHERITED", "SUMMARISED", "NEW", "DIRECT").contains(mode)))
          throw new IllegalArgumentException("Only skills take a valid context mode");
      }
    }

    public String command() {
      return kind.equals("agent")
          ? null
          : "/" + kind + ":" + name + (mode == null ? "" : " --mode=" + mode);
    }

    public String utterance() {
      return command() == null ? input : command() + " " + input;
    }
  }

  /** Message destinations use the existing named route or instance/definition address. */
  public record Target(String kind, String project, String conversation, String to, String route) {
    public Target {
      if (!List.of("mailbox", "conversation", "message").contains(kind))
        throw new IllegalArgumentException("target.kind must be mailbox, conversation or message");
      if (project != null) project = text(project, "target project", 256);
      if (kind.equals("conversation")) {
        conversation = identity(conversation, "conversation");
        if (project != null || to != null || route != null)
          throw new IllegalArgumentException("A conversation has its own home");
      } else if (conversation != null)
        throw new IllegalArgumentException("Only conversation targets take a conversation");
      if (kind.equals("message")) {
        if ((to == null) == (route == null))
          throw new IllegalArgumentException("Choose to or route for a message destination");
        if (to != null) to = identity(to, "to");
        if (route != null) {
          route = identity(route, "route");
          if (project != null)
            throw new IllegalArgumentException("A named route supplies its project");
        }
      } else if (to != null || route != null)
        throw new IllegalArgumentException("Only message targets take to or route");
    }
  }

  public record Limits(Integer maxModelCalls, Integer maxTurns, int queueCap) {
    public Limits {
      if (maxModelCalls != null && (maxModelCalls < 1 || maxModelCalls > 100000)
          || maxTurns != null && (maxTurns < 1 || maxTurns > 100000)
          || queueCap < 1
          || queueCap > 100)
        throw new IllegalArgumentException("Invalid schedule limits; queueCap must be 1..100");
    }
  }

  public record Save(
      String name, String project, String source, ScheduledWork definition, boolean overwrite) {
    public Save {
      name = identity(name, "file name");
      if (name.startsWith("."))
        throw new IllegalArgumentException("File names cannot start with a dot");
      if (project != null) project = text(project, "source project", 256);
      source = ScheduledWork.source(source);
      Objects.requireNonNull(definition, "definition");
    }
  }

  public record Sync(String project, String source) {
    public Sync {
      if (project != null) project = text(project, "source project", 256);
      source = ScheduledWork.source(source);
    }
  }

  public record File(
      String name,
      String project,
      String source,
      String path,
      String internalName,
      ScheduledWork definition,
      String status,
      String error) {}

  private static String source(String value) {
    if (!List.of("server", "workspace").contains(value))
      throw new IllegalArgumentException("source must be server or workspace");
    return value;
  }

  public static String identity(String value, String field) {
    value = text(value, field, 128);
    if (!value.matches("[A-Za-z0-9_.-]+") || value.equals(".") || value.equals(".."))
      throw new IllegalArgumentException("Invalid " + field);
    return value;
  }

  private static String text(String value, String field, int max) {
    if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid " + field);
    if (!field.equals("input")
        && (!value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Invalid " + field);
    return value;
  }
}
