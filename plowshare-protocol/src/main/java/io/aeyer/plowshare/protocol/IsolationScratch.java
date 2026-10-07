package io.aeyer.plowshare.protocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Private launch files, recoverable after owner death without following links or deleting trees.
 */
final class IsolationScratch {
  private static final Pattern NAME =
      Pattern.compile("run-([0-9a-f-]{36})-([0-9]+)-([0-9]+)-([0-9a-f-]{36})");
  private static final Set<String> FILES = Set.of("file", "directory", "options");

  private IsolationScratch() {}

  static Path acquire(Path configured, java.util.List<Path> roots) throws IOException {
    Path root = configured.toRealPath();
    if (!root.equals(configured.normalize())
        || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))
        || !Files.getAttribute(root, "unix:uid")
            .equals(Files.getAttribute(Path.of("/proc/self"), "unix:uid"))
        || roots.stream().anyMatch(path -> root.startsWith(path) || path.startsWith(root))) {
      throw new CommandRunner.Refused(
          "sandbox scratch must be an owned mode-0700 directory separate from mounts");
    }
    String boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip();
    UUID.fromString(boot);
    int count = 0;
    try (var entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        if (++count > 1024)
          throw new CommandRunner.Refused("sandbox scratch recovery exceeded its entry bound");
        var match = NAME.matcher(entry.getFileName().toString());
        if (!match.matches()) continue;
        if (boot.equals(match.group(1))) {
          Path process = Path.of("/proc", match.group(2));
          // An unreadable live owner is never assumed dead. Start ticks distinguish PID reuse.
          if (Files.exists(process) && start(process).equals(match.group(3))) continue;
        }
        remove(entry, root);
      }
    }
    String identity =
        "run-" + boot + "-" + ProcessHandle.current().pid() + "-" + start(Path.of("/proc/self"));
    return Files.createDirectory(
        root.resolve(identity + "-" + UUID.randomUUID()),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private static String start(Path process) throws IOException {
    String stat = Files.readString(process.resolve("stat"));
    // comm is parenthesized and may itself contain spaces or parentheses. The suffix starts
    // at field 3; Linux starttime is field 22, hence offset 19.
    return stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19];
  }

  /** Reject unknown contents, owners and links; removal is deliberately non-recursive. */
  static void remove(Path entry, Path root) throws IOException {
    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
        || !Files.getAttribute(entry, "unix:uid", LinkOption.NOFOLLOW_LINKS)
            .equals(Files.getAttribute(root, "unix:uid"))
        || !Files.getPosixFilePermissions(entry)
            .equals(PosixFilePermissions.fromString("rwx------"))) return;
    try (var children = Files.newDirectoryStream(entry)) {
      for (Path child : children) {
        String name = child.getFileName().toString();
        if (!FILES.contains(name)
            || Files.isSymbolicLink(child)
            || !Files.getAttribute(child, "unix:uid", LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getAttribute(root, "unix:uid"))
            || (name.equals("directory")
                ? !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    || (int) Files.getAttribute(child, "unix:nlink", LinkOption.NOFOLLOW_LINKS)
                        != 1)) return;
      }
    }
    for (String name : FILES) Files.deleteIfExists(entry.resolve(name));
    Files.delete(entry);
  }
}
