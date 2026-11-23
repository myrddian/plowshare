package io.aeyer.plowshare.server.files;

import java.nio.file.Path;

/** Mutation capabilities of a routed file provider. */
public interface FileMutations {
  Changed write(Path path, String content);

  Changed create(Path path, String content);

  Changed edit(Path path, String old, String replacement);

  Changed delete(Path path);

  Changed move(Path from, Path to);
}
