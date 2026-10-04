package io.aeyer.plowshare.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The root project manifest is operator configuration; the shebang CLI remains an ordinary file.
 */
public final class ProjectFile {
  private ProjectFile() {}

  public static boolean modelMayChange(Path root, Path target) {
    if (!target.equals(root.resolve("plowshare"))) return true;
    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return false;
    try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
      return input.read() == '#' && input.read() == '!';
    } catch (IOException | SecurityException absentOrUnreadable) {
      return false;
    }
  }
}
