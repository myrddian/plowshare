package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The store-side invariant of a schedule-only revision (ADR 0068): compared with the current
 * revision root, the staged root may only add or change {@code schedules/<name>.json}. Every other
 * file, {@code plowshare.json} included, must be present and byte-identical, nothing may be
 * removed, and neither root may contain a link. The deployment store enforces this under the
 * project row lock before it lowers the required authority from server administrator to project
 * Manager and before it re-binds acting declarations, so a caller cannot use the schedule-only flag
 * to change code, agents, grants or identity.
 */
final class ScheduleOnlyRevision {
  static final String REFUSAL = "A schedule-only revision may change only schedules/*.json";

  /**
   * Per-root caps, the deployment package limits ({@code ApplicationDeployment.validateFiles}): at
   * most 128 regular files, 64 KiB each and 128 KiB in all. {@code MAX_ENTRIES} only bounds the
   * walk itself (files plus directories, at most 17 path segments each), so an oversized tree is
   * refused before it is read.
   */
  private static final int MAX_FILES = 128,
      MAX_FILE_BYTES = 65536,
      MAX_TOTAL_BYTES = 131072,
      MAX_ENTRIES = MAX_FILES * 18;

  private ScheduleOnlyRevision() {}

  static void requireOnlySchedulesChanged(Path current, Path proposed) {
    Map<String, byte[]> before = files(current), after = files(proposed);
    for (var entry : before.entrySet()) {
      byte[] replacement = after.get(entry.getKey());
      if (replacement == null
          || !schedule(entry.getKey()) && !Arrays.equals(entry.getValue(), replacement))
        throw new CallerFault(REFUSAL);
    }
    for (String path : after.keySet())
      if (!before.containsKey(path) && !schedule(path)) throw new CallerFault(REFUSAL);
  }

  /** {@code schedules/<name>.json}, one segment deep; the name rule is the service's to check. */
  private static boolean schedule(String path) {
    return path.startsWith("schedules/")
        && path.indexOf('/', "schedules/".length()) < 0
        && path.endsWith(".json");
  }

  /** Regular files by root-relative path, read without following links. */
  private static Map<String, byte[]> files(Path root) {
    try (var paths = Files.walk(root)) {
      var entries = paths.limit(MAX_ENTRIES + 1L).toList();
      if (entries.size() > MAX_ENTRIES) throw new CallerFault(REFUSAL);
      Map<String, byte[]> files = new HashMap<>();
      long total = 0;
      for (Path path : entries) {
        if (Files.isSymbolicLink(path)) throw new CallerFault(REFUSAL);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.size(path) > MAX_FILE_BYTES) throw new CallerFault(REFUSAL);
        byte[] bytes = Files.readAllBytes(path);
        total += bytes.length;
        if (files.size() >= MAX_FILES || bytes.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
          throw new CallerFault(REFUSAL);
        files.put(root.relativize(path).toString().replace('\\', '/'), bytes);
      }
      return files;
    } catch (IOException | java.io.UncheckedIOException unavailable) {
      throw new CallerFault("Retained Application source is unavailable");
    }
  }
}
