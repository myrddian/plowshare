package io.aeyer.plowshare.protocol;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Explicit Linux process acceptance entry point; ordinary tests need no sandbox installation. */
public final class BubblewrapAcceptance {
  private BubblewrapAcceptance() {}

  /** Arguments: bubblewrap executable, trusted POSIX launcher and read-only runtime directories. */
  public static void main(String[] args) throws Exception {
    if (args[0].equals("--owner")) {
      Path root = Path.of(args[1]);
      var backend =
          new Bubblewrap(
              new Bubblewrap.Configuration(
                  Path.of(args[2]),
                  Path.of(args[3]),
                  Arrays.stream(args).skip(4).map(Path::of).toList(),
                  root.getParent().resolve("scratch")));
      backend.run(
          command(root, heartbeat(root.resolve("output/crash-heartbeat")), Duration.ofSeconds(20)),
          FileAccess.of(List.of(root), List.of()),
          FileAccess.of(List.of(root.resolve("output")), List.of()),
          Map.of(),
          () -> false);
      return;
    }
    Path temporary = Files.createTempDirectory("bubblewrap-acceptance-");
    Path scratch =
        Files.createDirectory(
            temporary.resolve("scratch"),
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
    var sandbox =
        new Bubblewrap(
            new Bubblewrap.Configuration(
                Path.of(args[0]),
                Path.of(args[1]),
                Arrays.stream(args).skip(2).map(Path::of).toList(),
                scratch));
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    Path output = Files.createDirectory(root.resolve("output"));
    Files.createDirectory(root.resolve(".plowshare"));
    Files.writeString(root.resolve("plowshare.json"), "{\"version\":1,\"name\":\"test\"}");
    Files.writeString(root.resolve(".secret"), "secret");
    Path privateFile = Files.writeString(root.resolve("private.txt"), "secret");
    Path outside = Files.writeString(temporary.resolve("outside.txt"), "secret");
    FileAccess reads = FileAccess.of(List.of(root), List.of(privateFile));
    FileAccess writes = FileAccess.of(List.of(output), List.of());
    var checked =
        sandbox.run(
            command(
                root,
                List.of(
                    "/bin/sh",
                    "-c",
                    "test ! -r \"$1\" && test ! -r .secret && test ! -r private.txt && "
                        + "test ! -r .plowshare && ! echo bad > root-write && "
                        + "! echo bad > plowshare.json && echo ok > output/result && "
                        + "test \"$EXPLICIT\" = kept && test -z \"${SECRET+x}\" && "
                        + "! dd if=/dev/zero of=/tmp/too-large bs=1048576 count=65 2>/dev/null",
                    "sandbox-test",
                    outside.toString()),
                Duration.ofSeconds(10)),
            reads,
            writes,
            Map.of("SECRET", "not-inherited"),
            () -> false);
    require(
        Integer.valueOf(0).equals(checked.exitCode()),
        "filesystem/environment/tmpfs: " + checked.stderr());
    require(Files.readString(output.resolve("result")).trim().equals("ok"), "allowed write");
    require(!Files.exists(root.resolve("root-write")), "denied write changed the host");
    var input =
        sandbox.run(
            new CommandRunner.Command(
                List.of("/bin/cat"),
                root,
                Map.of(),
                List.of(),
                Duration.ofSeconds(2),
                4096,
                "stdin survives the private descriptor\n"),
            reads,
            writes,
            Map.of(),
            () -> false);
    require(
        input.stdout().equals("stdin survives the private descriptor\n"), "command stdin was lost");
    var descriptors =
        sandbox.run(
            command(
                root, List.of("/bin/sh", "-c", "test ! -e /proc/self/fd/3"), Duration.ofSeconds(2)),
            reads,
            writes,
            Map.of(),
            () -> false);
    require(
        Integer.valueOf(0).equals(descriptors.exitCode()),
        "private arguments descriptor leaked to user code");
    // A hostile loader variable must apply to the confined child, never the host-side wrapper.
    Path source =
        Files.writeString(
            temporary.resolve("loader.c"),
            "#include <stdio.h>\n#include <stdlib.h>\n"
                + "__attribute__((constructor)) void inject(void) { const char *p=getenv(\"ESCAPE_MARKER\");"
                + " if(p) { FILE *f=fopen(p,\"w\"); if(f) {fputs(\"escaped\",f);fclose(f);} } }\n");
    Path library = temporary.resolve("loader.so");
    require(
        new ProcessBuilder(
                    "/usr/bin/cc", "-shared", "-fPIC", source.toString(), "-o", library.toString())
                .inheritIO()
                .start()
                .waitFor()
            == 0,
        "loader fixture did not compile");
    Path marker = temporary.resolve("loader-escaped");
    var loader =
        sandbox.run(
            new CommandRunner.Command(
                List.of("/bin/sh", "-c", "test -n \"$LD_PRELOAD\" && test -n \"$ESCAPE_MARKER\""),
                root,
                Map.of("LD_PRELOAD", library.toString(), "ESCAPE_MARKER", marker.toString()),
                List.of(),
                Duration.ofSeconds(2),
                4096),
            reads,
            writes,
            Map.of(),
            () -> false);
    require(
        Integer.valueOf(0).equals(loader.exitCode()), "loader environment was not passed to child");
    require(!Files.exists(marker), "command environment injected code before isolation");
    var unavailable =
        new Bubblewrap(
            new Bubblewrap.Configuration(
                Path.of(args[0]),
                Path.of("/usr/bin/false"),
                Arrays.stream(args).skip(2).map(Path::of).toList(),
                scratch));
    try {
      unavailable.run(
          command(
              root,
              List.of("/bin/sh", "-c", "echo bad > output/probe-escaped"),
              Duration.ofSeconds(1)),
          reads,
          writes,
          Map.of(),
          () -> false);
      throw new AssertionError("failed probe was accepted");
    } catch (CommandRunner.Refused expected) {
      require(!Files.exists(output.resolve("probe-escaped")), "failed probe executed user code");
    }
    try (ServerSocket listener = new ServerSocket(0)) {
      var network =
          sandbox.run(
              command(
                  root,
                  List.of(
                      "/usr/bin/node",
                      "-e",
                      "const s=require('net').connect(Number(process.argv[1]),'127.0.0.1');"
                          + "s.on('connect',()=>process.exit(1));s.on('error',()=>process.exit(0));",
                      Integer.toString(listener.getLocalPort())),
                  Duration.ofSeconds(3)),
              reads,
              writes,
              Map.of(),
              () -> false);
      require(Integer.valueOf(0).equals(network.exitCode()), "host network was reachable");
    }
    Path heartbeat = output.resolve("heartbeat");
    long start = System.nanoTime();
    var cancelled =
        sandbox.run(
            command(
                root,
                List.of(
                    "/bin/sh",
                    "-c",
                    "setsid sh -c 'while :; do echo tick >> \"$1\"; sleep 0.05; done' child \"$1\" & wait",
                    "sandbox-test",
                    heartbeat.toString()),
                Duration.ofSeconds(10)),
            reads,
            writes,
            Map.of(),
            () ->
                Files.exists(heartbeat)
                    && System.nanoTime() - start > Duration.ofMillis(500).toNanos());
    require(cancelled.cancelled(), "cancel did not stop the process");
    long size = Files.size(heartbeat);
    Thread.sleep(300);
    require(Files.size(heartbeat) == size, "a setsid child survived cancellation");
    Path exitedHeartbeat = output.resolve("exit-heartbeat");
    var exited =
        sandbox.run(
            command(
                root,
                List.of(
                    "/bin/sh",
                    "-c",
                    "setsid sh -c 'while :; do echo tick >> \"$1\"; sleep 0.05; done' child \"$1\" > /dev/null 2>&1 & while test ! -e \"$1\"; do sleep 0.05; done",
                    "test",
                    exitedHeartbeat.toString()),
                Duration.ofSeconds(3)),
            reads,
            writes,
            Map.of(),
            () -> false);
    require(Integer.valueOf(0).equals(exited.exitCode()), "normal-exit fixture failed");
    long exitedSize = Files.size(exitedHeartbeat);
    Thread.sleep(300);
    require(Files.size(exitedHeartbeat) == exitedSize, "a detached child survived normal exit");
    Path crashHeartbeat = output.resolve("crash-heartbeat");
    var ownerArgs =
        new java.util.ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                BubblewrapAcceptance.class.getName(),
                "--owner",
                root.toString()));
    ownerArgs.addAll(List.of(args));
    Process owner = new ProcessBuilder(ownerArgs).inheritIO().start();
    try {
      for (int count = 0; count < 100 && !Files.exists(crashHeartbeat) && owner.isAlive(); count++)
        Thread.sleep(50);
      require(Files.exists(crashHeartbeat), "owner command did not start");
      owner.destroyForcibly().waitFor();
      Thread.sleep(200);
      long stopped = Files.size(crashHeartbeat);
      Thread.sleep(300);
      require(Files.size(crashHeartbeat) == stopped, "a child survived owner death");
      try (var leftovers = Files.list(scratch)) {
        require(leftovers.count() == 1, "owner crash fixture left no recoverable policy");
      }
    } finally {
      owner.destroyForcibly();
    }
    var timedOut =
        sandbox.run(
            command(root, List.of("/bin/sh", "-c", "sleep 20"), Duration.ofMillis(200)),
            reads,
            writes,
            Map.of(),
            () -> false);
    require(timedOut.timedOut(), "deadline was not enforced");
    try (var leftovers = Files.list(scratch)) {
      require(leftovers.findAny().isEmpty(), "restart failed to recover dead owner policy");
    }
    // Current PID with different start ticks is stale; an actual live identity must be retained.
    String boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip();
    String stat = Files.readString(Path.of("/proc/self/stat"));
    String ticks = stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19];
    Path live =
        Files.createDirectory(
            scratch.resolve(
                "run-"
                    + boot
                    + "-"
                    + ProcessHandle.current().pid()
                    + "-"
                    + ticks
                    + "-"
                    + java.util.UUID.randomUUID()),
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
    Path reused =
        Files.createDirectory(
            scratch.resolve(
                "run-"
                    + boot
                    + "-"
                    + ProcessHandle.current().pid()
                    + "-0-"
                    + java.util.UUID.randomUUID()),
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
    Files.createSymbolicLink(scratch.resolve("foreign"), temporary);
    sandbox.run(
        command(root, List.of("/bin/true"), Duration.ofSeconds(2)),
        reads,
        writes,
        Map.of(),
        () -> false);
    require(
        Files.exists(live)
            && !Files.exists(reused)
            && Files.isSymbolicLink(scratch.resolve("foreign")),
        "recovery deleted live/foreign files or failed PID reuse");
    Files.delete(live);
    Files.delete(scratch.resolve("foreign"));
    Files.createSymbolicLink(root.resolve("escape"), outside);
    try {
      sandbox.run(
          command(
              root, List.of("/bin/sh", "-c", "echo bad > output/escaped"), Duration.ofSeconds(1)),
          reads,
          writes,
          Map.of(),
          () -> false);
      throw new AssertionError("symlink topology was accepted");
    } catch (CommandRunner.Refused expected) {
      require(!Files.exists(output.resolve("escaped")), "refused command was executed");
    }
    System.out.println(
        "Java bubblewrap acceptance passed: filesystem, environment, loader injection, private descriptor, stdin, failed startup, tmpfs, network, cancellation, normal-exit cleanup, owner death, restart recovery, live-owner preservation, PID reuse, deadline, symlink refusal");
  }

  private static List<String> heartbeat(Path path) {
    return List.of(
        "/bin/sh",
        "-c",
        "setsid sh -c 'while :; do echo tick >> \"$1\"; sleep 0.05; done' child \"$1\" & wait",
        "test",
        path.toString());
  }

  private static CommandRunner.Command command(Path root, List<String> argv, Duration deadline) {
    return new CommandRunner.Command(
        argv, root, Map.of("PATH", "/usr/bin:/bin", "EXPLICIT", "kept"), List.of(), deadline, 4096);
  }

  private static void require(boolean condition, String why) {
    if (!condition) throw new AssertionError(why);
  }
}
