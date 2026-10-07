package io.aeyer.plowshare.protocol;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Linux namespace isolation with an empty filesystem, explicit runtime mounts and no host network.
 * Workspace topology is inspected without following links; unsupported links, special files and
 * hard links are refused rather than exposed. Runtime roots are trusted operator configuration.
 */
public final class Bubblewrap implements CommandIsolation {
  /** Bounds filesystem inspection and the resulting argument list. */
  public static final int MAX_ENTRIES = 100_000;

  /** All paths are explicit installation values, independent of the command's PATH. */
  public record Configuration(
      Path executable, Path launcher, List<Path> runtimeRoots, Path scratchRoot) {
    public Configuration {
      Objects.requireNonNull(executable, "bubblewrap executable");
      Objects.requireNonNull(launcher, "sandbox launcher executable");
      Objects.requireNonNull(scratchRoot, "sandbox scratch root");
      runtimeRoots = List.copyOf(runtimeRoots);
      if (!executable.isAbsolute()
          || !launcher.isAbsolute()
          || !scratchRoot.isAbsolute()
          || scratchRoot.getParent() == null
          || runtimeRoots.isEmpty()
          || runtimeRoots.stream()
              .anyMatch(path -> !path.isAbsolute() || path.getParent() == null)) {
        throw new IllegalArgumentException(
            "Bubblewrap requires absolute executable/launcher/scratch paths and non-root runtime paths");
      }
    }
  }

  private final Configuration configuration;

  public Bubblewrap(Configuration configuration) {
    this.configuration = Objects.requireNonNull(configuration);
  }

  private record Mount(Path path, String option, Path source) {}

  @Override
  public CommandRunner.Outcome run(
      CommandRunner.Command command,
      FileAccess reads,
      FileAccess writes,
      Map<String, String> host,
      BooleanSupplier cancelled) {
    if (!System.getProperty("os.name").equals("Linux")) {
      throw new CommandRunner.Refused("bubblewrap isolation is supported only on Linux");
    }
    if (cancelled.getAsBoolean()) {
      throw new CommandRunner.Refused("the command was cancelled before sandbox startup");
    }
    Path scratch = null;
    try {
      List<Path> mounts = new ArrayList<>(reads.roots());
      for (Path runtime : configuration.runtimeRoots()) mounts.add(runtime.toRealPath());
      scratch = IsolationScratch.acquire(configuration.scratchRoot(), mounts);
      Path hiddenFile = Files.createFile(scratch.resolve("file"));
      Path hiddenDirectory = Files.createDirectory(scratch.resolve("directory"));
      Files.setPosixFilePermissions(hiddenFile, PosixFilePermissions.fromString("---------"));
      Files.setPosixFilePermissions(hiddenDirectory, PosixFilePermissions.fromString("---------"));
      Path options =
          Files.createFile(
              scratch.resolve("options"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      List<String> prefix = arguments(reads, writes, command.cwd(), hiddenFile, hiddenDirectory);
      // Probe the same mount/namespace policy with a trusted no-op before admitting user code.
      // Failure is not retried unconfined. It is intentionally not cached across topology changes.
      List<String> probe = new ArrayList<>(prefix);
      probe.add(configuration.launcher().toString());
      probe.addAll(List.of("-c", ":"));
      CommandRunner.Outcome checked =
          CommandRunner.run(
              new CommandRunner.Command(
                  launch(probe, options, prefix.size()),
                  command.cwd(),
                  Map.of(),
                  List.of(),
                  Duration.ofSeconds(5),
                  4096),
              Map.of(),
              cancelled);
      if (!Integer.valueOf(0).equals(checked.exitCode())
          || checked.cancelled()
          || checked.timedOut()) {
        throw new CommandRunner.Refused(
            "bubblewrap could not establish the sandbox; check user namespaces, runtime mounts and the configured probe");
      }
      if (cancelled.getAsBoolean()) {
        throw new CommandRunner.Refused("the command was cancelled before sandbox startup");
      }
      List<String> argv = new ArrayList<>(prefix.subList(0, prefix.size() - 1));
      Map<String, String> environment =
          CommandRunner.environment(host, command.inherit(), command.env());
      // Loader variables must never affect bubblewrap before it establishes the boundary.
      // Its own process starts with an empty environment; only its sandbox child receives these.
      environment.forEach((name, value) -> argv.addAll(List.of("--setenv", name, value)));
      argv.add("--");
      if (command.argv().isEmpty()) throw new CommandRunner.Refused("a command needs a program");
      argv.add(CommandRunner.executable(command.argv().getFirst(), command.cwd(), environment));
      argv.addAll(command.argv().subList(1, command.argv().size()));
      checkArgumentBound(argv);
      return CommandRunner.run(
          new CommandRunner.Command(
              launch(argv, options, prefix.size() + environment.size() * 3),
              command.cwd(),
              Map.of(),
              List.of(),
              command.timeout(),
              command.outputBytes(),
              command.stdin()),
          Map.of(),
          cancelled);
    } catch (IOException failed) {
      throw new CommandRunner.Refused("the sandbox filesystem policy could not be prepared");
    } finally {
      if (scratch != null) {
        try {
          IsolationScratch.remove(scratch, configuration.scratchRoot());
        } catch (IOException failed) {
          // Preserve the delivered command's outcome; cleanup cannot turn a completed mutation
          // into a startup refusal that invites replay. The placeholders contain no user data.
          System.getLogger(Bubblewrap.class.getName())
              .log(
                  System.Logger.Level.WARNING,
                  "sandbox temporary policy files could not be removed");
        }
      }
    }
  }

  /** A private descriptor keeps command variables out of host argv while leaving stdin intact. */
  private List<String> launch(List<String> argv, Path options, int commandStart)
      throws IOException {
    checkArgumentBound(argv);
    if (argv.stream().anyMatch(value -> value.indexOf('\0') >= 0))
      throw new CommandRunner.Refused("sandbox arguments cannot contain NUL bytes");
    Files.writeString(options, String.join("\0", argv.subList(1, commandStart - 1)) + "\0");
    // Only this fixed script executes on the host. Paths are quoted positional parameters,
    // never interpolated shell source; exec retains parent-death and cancellation behavior.
    List<String> launch =
        new ArrayList<>(
            List.of(
                configuration.launcher().toString(),
                "-c",
                "exec 3<\"$1\"; shift; plowshare_wrapper=$1; shift; exec \"$plowshare_wrapper\" --args 3 -- \"$@\"",
                "plowshare-bubblewrap",
                options.toString(),
                configuration.executable().toString()));
    launch.addAll(argv.subList(commandStart, argv.size()));
    return List.copyOf(launch);
  }

  /** Compiles argv only; package visibility lets ordinary tests inspect the complete policy. */
  List<String> arguments(
      FileAccess reads, FileAccess writes, Path cwd, Path hiddenFile, Path hiddenDirectory)
      throws IOException {
    if (!reads.permits(cwd))
      throw new CommandRunner.Refused("sandbox cwd is outside the workspace");
    List<Path> roots = reads.roots();
    for (Path executable : List.of(configuration.executable(), configuration.launcher())) {
      Path real = executable.toRealPath();
      if (!Files.isRegularFile(real)
          || !Files.isExecutable(real)
          || roots.stream().anyMatch(real::startsWith)) {
        throw new CommandRunner.Refused(
            "sandbox executables must be operator-owned programs outside workspace roots");
      }
    }
    List<Mount> mounts = new ArrayList<>();
    for (Path configured : configuration.runtimeRoots()) {
      Path runtime = configured.toRealPath();
      if (runtime.getParent() == null
          || !Files.isDirectory(runtime)
          || roots.stream().anyMatch(root -> root.startsWith(runtime) || runtime.startsWith(root))
          || reads.exclusions().stream()
              .anyMatch(excluded -> excluded.startsWith(runtime) || runtime.startsWith(excluded))
          || List.of(Path.of("/proc"), Path.of("/dev"), Path.of("/tmp"))
              .contains(configured.normalize())) {
        throw new CommandRunner.Refused(
            "sandbox runtime roots must be directories separate from workspace roots");
      }
      // Preserve an explicitly configured distribution symlink (for example merged /usr).
      mounts.add(new Mount(configured.normalize(), "--ro-bind", runtime));
    }
    int[] count = {0};
    for (Path root : roots) {
      mounts.add(new Mount(root, "--ro-bind", root));
      for (Path write : writes.roots()) {
        if (write.startsWith(root) && reads.permits(write)) {
          if (!Files.isDirectory(write, LinkOption.NOFOLLOW_LINKS)) {
            throw new CommandRunner.Refused("sandbox writable areas must already be directories");
          }
          // A writable root must already have mountpoints for both protected configuration names.
          // Otherwise creating a bind destination would mutate the host and could let a command
          // create a new privileged manifest/environment for the next run.
          if (write.equals(root)
              && (!Files.isRegularFile(root.resolve("plowshare.json"), LinkOption.NOFOLLOW_LINKS)
                  || !Files.isDirectory(root.resolve(".plowshare"), LinkOption.NOFOLLOW_LINKS))) {
            throw new CommandRunner.Refused(
                "initialize plowshare.json and .plowshare before sandboxing a fully writable root");
          }
          mounts.add(new Mount(write, "--bind", write));
        }
      }
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            private FileVisitResult inspect(Path path, BasicFileAttributes attributes)
                throws IOException {
              if (++count[0] > MAX_ENTRIES)
                throw new CommandRunner.Refused(
                    "sandbox workspace inspection exceeded its entry bound");
              if (attributes.isSymbolicLink()) {
                throw new CommandRunner.Refused("sandbox mountpoints cannot be symlinks");
              }
              if (!reads.permits(path)) {
                mounts.add(
                    new Mount(
                        path,
                        "--ro-bind",
                        attributes.isDirectory() ? hiddenDirectory : hiddenFile));
                return FileVisitResult.SKIP_SUBTREE;
              }
              if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                throw new CommandRunner.Refused(
                    "sandbox workspaces cannot contain visible symlinks or special files");
              }
              if (attributes.isRegularFile() && (int) Files.getAttribute(path, "unix:nlink") > 1) {
                throw new CommandRunner.Refused(
                    "sandbox workspaces cannot contain visible hard links");
              }
              if (!ProjectFile.modelMayChange(root, path))
                mounts.add(new Mount(path, "--ro-bind", path));
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes)
                throws IOException {
              return inspect(path, attributes);
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes)
                throws IOException {
              return inspect(path, attributes);
            }
          });
    }
    List<String> argv =
        new ArrayList<>(
            List.of(
                configuration.executable().toString(),
                "--unshare-all",
                "--unshare-user",
                "--disable-userns",
                "--die-with-parent",
                "--new-session",
                "--cap-drop",
                "ALL",
                "--clearenv",
                "--size",
                "8388608",
                "--tmpfs",
                "/",
                "--proc",
                "/proc",
                "--dev",
                "/dev",
                "--size",
                "67108864",
                "--tmpfs",
                "/tmp"));
    // Parent mounts precede child masks/grants. Java's stable sort retains read-before-write ties.
    mounts.sort(Comparator.comparingInt(mount -> mount.path().getNameCount()));
    for (Mount mount : mounts)
      argv.addAll(List.of(mount.option(), mount.source().toString(), mount.path().toString()));
    argv.addAll(List.of("--chdir", cwd.toString(), "--"));
    checkArgumentBound(argv);
    return List.copyOf(argv);
  }

  private static void checkArgumentBound(List<String> argv) {
    if (argv.stream()
            .mapToLong(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L)
            .sum()
        > 131072) {
      throw new CommandRunner.Refused("sandbox launch exceeded its argument byte bound");
    }
  }
}
