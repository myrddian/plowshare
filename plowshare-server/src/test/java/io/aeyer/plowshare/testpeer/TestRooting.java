package io.aeyer.plowshare.testpeer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Explicit identity for an isolated test socket's presence declaration. */
public record TestRooting(String machine, String root, String project) {
  public static TestRooting of(Path root, String project) {
    try {
      return new TestRooting("test-peer", root.toRealPath().toString(), project);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }
}
