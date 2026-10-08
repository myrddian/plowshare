package io.aeyer.plowshare.server.hooks.script;

import io.aeyer.plowshare.server.hooks.HookFile;
import io.aeyer.plowshare.server.hooks.Stage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * One project's hooks directory, loaded: working hooks in filename order, and the files that did
 * not load.
 *
 * <p>Fingerprinted the way {@code DefinitionResolver} fingerprints a definitions directory — each
 * file's name, size and modified time — so a change is noticed on the next fire without a watcher
 * thread.
 */
final class ProjectHookSet implements AutoCloseable {

  record Working(String file, String name, Map<Stage, List<String>> stages, ContextPool pool) {}

  /**
   * A failure; {@code file} is {@code null} when no one file is to blame (a listing, a lookup).
   *
   * <p>{@code lookup} marks the one failure whose {@code reason} is not the model's to read: what a
   * project-id lookup threw is a driver's message, with hosts and ports in it. A refusal built from
   * it uses a fixed sentence instead, and the reason goes only into the {@code failed} record.
   */
  record Broken(String file, String reason, boolean lookup) {
    Broken(String file, String reason) {
      this(file, reason, false);
    }
  }

  final String fingerprint;
  final List<Working> working;
  final List<Broken> broken;

  /**
   * Why a log's local snapshot could not be loaded, for the first fire that finds it so, or {@code
   * null}. Such a set is empty: nothing runs, and the stage records the one reason (spec
   * 2026-09-30-local-hooks-are-served decision 7).
   */
  final String withheld;

  private ProjectHookSet(String fingerprint, List<Working> working, List<Broken> broken) {
    this(fingerprint, working, broken, null);
  }

  private ProjectHookSet(
      String fingerprint, List<Working> working, List<Broken> broken, String withheld) {
    this.fingerprint = fingerprint;
    this.working = List.copyOf(working);
    this.broken = List.copyOf(broken);
    this.withheld = withheld;
  }

  /** An empty set that says, once, why a log's local hooks did not run. */
  static ProjectHookSet withheld(String reason) {
    return new ProjectHookSet("(withheld)", List.of(), List.of(), reason);
  }

  static ProjectHookSet empty() {
    return new ProjectHookSet("(absent)", List.of(), List.of());
  }

  static String fingerprint(Path directory) {
    if (directory == null || !Files.isDirectory(directory)) {
      return "(absent)";
    }
    Map<String, String> entries = new TreeMap<>();
    try (Stream<Path> listing = Files.list(directory)) {
      for (Path file : listing.filter(ProjectHookSet::isHookFile).toList()) {
        BasicFileAttributes about = Files.readAttributes(file, BasicFileAttributes.class);
        entries.put(
            file.getFileName().toString(),
            about.size() + "@" + about.lastModifiedTime().toMillis());
      }
    } catch (IOException | UncheckedIOException unreadable) {
      // Unchecked too: a listing's stream reports an entry it could not read
      // while being iterated, as an UncheckedIOException, not at Files.list.
      return "(unreadable)";
    }
    return directory.toString() + "\0" + entries;
  }

  static ProjectHookSet load(
      Path directory,
      HookEngine engine,
      HooksProperties properties,
      Supplier<Instant> clock,
      ScheduledExecutorService timer) {
    String fingerprint = fingerprint(directory);
    if (directory == null || !Files.isDirectory(directory)) {
      return new ProjectHookSet(fingerprint, List.of(), List.of());
    }
    List<Path> files;
    try (Stream<Path> listing = Files.list(directory)) {
      files = listing.filter(ProjectHookSet::isHookFile).sorted().toList();
    } catch (IOException unreadable) {
      return new ProjectHookSet(
          fingerprint,
          List.of(),
          List.of(
              new Broken(
                  null,
                  "this project's hooks directory could not be listed: " + said(unreadable))));
    } catch (UncheckedIOException unreadable) {
      return new ProjectHookSet(
          fingerprint,
          List.of(),
          List.of(
              new Broken(
                  null,
                  "this project's hooks directory could not be listed: "
                      + said(unreadable.getCause()))));
    }
    List<Working> working = new ArrayList<>();
    List<Broken> broken = new ArrayList<>();
    Map<String, String> seenNames = new HashMap<>();
    for (Path path : files) {
      String file = path.getFileName().toString();
      String source;
      try {
        source = Files.readString(path, StandardCharsets.UTF_8);
      } catch (IOException unreadable) {
        broken.add(new Broken(file, file + " could not be read: " + said(unreadable)));
        continue;
      }
      loadOne(file, source, engine, properties, clock, timer, working, broken, seenNames);
    }
    return new ProjectHookSet(fingerprint, working, broken);
  }

  /**
   * A person's served hooks, from the snapshot their log opened with (spec
   * 2026-09-30-local-hooks-are-served decisions 1 and 9): the same load as a directory's, from
   * {@code (name, text)} pairs, so a snapshot loads without a {@link Path}. Immutable: its
   * fingerprint is the snapshot's hash, and a new hash is a new set.
   *
   * <p>What is not a {@link HookFailure} leaves as it came, after closing the pools already built:
   * the caller makes the whole snapshot an empty tier, and nothing may be left open behind it (plan
   * choice 11).
   */
  static ProjectHookSet load(
      String hash,
      List<HookFile> files,
      HookEngine engine,
      HooksProperties properties,
      Supplier<Instant> clock,
      ScheduledExecutorService timer) {
    List<Working> working = new ArrayList<>();
    List<Broken> broken = new ArrayList<>();
    Map<String, String> seenNames = new HashMap<>();
    try {
      for (HookFile file :
          files.stream()
              .filter(one -> HookFile.isHookName(one.name()))
              .sorted(Comparator.comparing(HookFile::name))
              .toList()) {
        loadOne(
            file.name(), file.text(), engine, properties, clock, timer, working, broken, seenNames);
      }
    } catch (RuntimeException unloadable) {
      for (Working built : working) {
        built.pool().retire();
      }
      throw unloadable;
    }
    return new ProjectHookSet(hash, working, broken);
  }

  /** One file's source, stripped and loaded into a pool, or recorded as broken. */
  private static void loadOne(
      String file,
      String source,
      HookEngine engine,
      HooksProperties properties,
      Supplier<Instant> clock,
      ScheduledExecutorService timer,
      List<Working> working,
      List<Broken> broken,
      Map<String, String> seenNames) {
    try {
      String javascript = Stripping.javascript(file, source);
      // Bounded, both here and in the pool's loader below: a module's
      // top level is code that no call's limit covers, and an unbounded
      // `while (true) {}` there would hang this fire and every later one
      // queued behind it. `timeout` is the one limit an operator sets,
      // so it bounds a load as it bounds a call.
      LoadedHook first = LoadedHook.load(engine, file, javascript, properties.getTimeout(), timer);
      pool(file, first, javascript, engine, properties, clock, timer, working, broken, seenNames);
    } catch (HookFailure failed) {
      broken.add(new Broken(file, failed.getMessage()));
    }
  }

  /**
   * One loaded file's pool, seeded with {@code first}; or its name clash, recorded as broken.
   * Anything thrown before the pool holds {@code first} closes it here: nothing else could, since
   * {@link #load} retires only the pools already in {@code working} (final review M5).
   */
  static void pool(
      String file,
      LoadedHook first,
      String javascript,
      HookEngine engine,
      HooksProperties properties,
      Supplier<Instant> clock,
      ScheduledExecutorService timer,
      List<Working> working,
      List<Broken> broken,
      Map<String, String> seenNames) {
    try {
      String earlier = seenNames.putIfAbsent(first.name(), file);
      if (earlier != null) {
        first.close();
        broken.add(
            new Broken(
                file,
                file
                    + " declares the name '"
                    + first.name()
                    + "', which "
                    + earlier
                    + " already has; a name in two files is a"
                    + " mistake, not an override"));
        return;
      }
      ContextPool pool =
          new ContextPool(
              first.name(),
              first,
              () -> LoadedHook.load(engine, file, javascript, properties.getTimeout(), timer),
              properties.getPoolMax(),
              properties.getIdle(),
              clock,
              timer);
      working.add(new Working(file, first.name(), first.stages(), pool));
    } catch (RuntimeException unpooled) {
      first.close();
      throw unpooled;
    }
  }

  /**
   * A set that is nothing but one failure with no file behind it — a lookup that threw — so tool
   * stages close on it exactly as they do on a file that did not load. Not cached: the next fire
   * asks again.
   */
  static ProjectHookSet unreachable(String reason) {
    return new ProjectHookSet("(broken)", List.of(), List.of(new Broken(null, reason, true)));
  }

  /**
   * What went wrong with a file operation, without the path. A reason reaches the model inside a
   * refusal, and a {@link FileSystemException}'s message is the absolute path under this server's
   * data directory, which is not the model's business.
   */
  private static String said(IOException failed) {
    if (failed instanceof FileSystemException named) {
      return named.getReason() != null ? named.getReason() : failed.getClass().getSimpleName();
    }
    return failed.getClass().getSimpleName();
  }

  private static boolean isHookFile(Path path) {
    return HookFile.isHookName(path.getFileName().toString()) && Files.isRegularFile(path);
  }

  @Override
  public void close() {
    for (Working hook : working) {
      hook.pool().retire();
    }
  }
}
