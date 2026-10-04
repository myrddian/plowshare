package io.aeyer.plowshare.server.events;

public record TriggerRecord(
    String name,
    String event,
    String project,
    String conversation,
    String agent,
    String task,
    Integer maxModelCalls,
    Integer maxTurns,
    int queueCap,
    boolean paused,
    String definedBy) {

  /** What holds one run at a time: the conversation if there is one, else this trigger. */
  public String target() {
    return conversation != null ? "conversation:" + conversation : "trigger:" + name;
  }
}
