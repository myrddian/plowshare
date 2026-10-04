package io.aeyer.plowshare.server.todos;

import java.util.Arrays;
import java.util.Optional;

/** Where one todo stands. The wire name is what the column holds and what a model writes. */
public enum TodoStatus {
  PENDING("pending"),
  IN_PROGRESS("in_progress"),
  DONE("done"),
  DROPPED("dropped");

  private final String wire;

  TodoStatus(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  public static Optional<TodoStatus> fromWire(String wire) {
    return Arrays.stream(values()).filter(s -> s.wire.equals(wire)).findFirst();
  }
}
