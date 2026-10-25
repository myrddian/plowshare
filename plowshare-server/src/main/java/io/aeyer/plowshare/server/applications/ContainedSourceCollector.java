package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Collects contained source areas that no committed child accounts for.
 *
 * <p><strong>This inverts ADR 0005 deliberately.</strong> That record states that filesystem
 * staging cannot participate in PostgreSQL rollback, that a rejected or crashed stage can remain on
 * disk, and that the server does not garbage-collect it — correct there, because a top-level
 * deployment's staged revision may be a retained release a human still wants. For a contained
 * Application destruction is the <em>normal</em> path, so a stage with no committed child is
 * garbage rather than evidence, and leaving it is the orphan the capability names as failure.
 *
 * <p><strong>Two inputs, and the difference between them is the recovery.</strong>
 *
 * <ol>
 *   <li>Unmarked collection records. A destruction committed and the removal that follows it did
 *       not. The record names the exact source area, so this is precise work, not a guess.
 *   <li>Directories under the containment root with no committed record. A creation was interrupted
 *       between staging the bytes and committing the row, or crashed before its own cleanup ran.
 *       These have no record at all, which is exactly what makes them collectable: only a committed
 *       containment record makes a stage live.
 * </ol>
 *
 * <p><strong>It cannot reach an ordinary Application's retained revisions.</strong> That is
 * structural and not a check. The only directory this object ever enumerates is a direct child of
 * {@link ContainmentConfig#root()}, a server-configured directory that exists for contained source
 * and nothing else; a top-level release lives under a FileStore destination and is addressed
 * through {@code application_releases}, and this holds no collaborator through which either could
 * be reached. {@code ContainedRecoveryTest.sweepLeavesTopLevelRetainedRevisionsAlone} asserts both
 * halves — the retained tree beside the root is untouched, and a contained-looking directory
 * planted inside it is never even enumerated.
 *
 * <p>An unconfigured server has no root and this does nothing. It never guesses a path.
 */
public final class ContainedSourceCollector {
  /**
   * One sweep's accounting, which is the observable half of recovery.
   *
   * @param collected source areas removed, from either input
   * @param retained directories left alone because a committed child accounts for them
   */
  public record Sweep(int collected, int retained) {}

  private final ContainedApplicationRepository records;
  private final UnitOfWork work;
  private final ContainmentConfig config;
  private final ContainedStaging staging;
  private final java.time.Duration grace;

  public ContainedSourceCollector(
      ContainedApplicationRepository records,
      UnitOfWork work,
      ContainmentConfig config,
      ContainedStaging staging,
      java.time.Duration grace) {
    this.records = Objects.requireNonNull(records);
    this.work = Objects.requireNonNull(work);
    this.config = Objects.requireNonNull(config);
    this.staging = Objects.requireNonNull(staging);
    this.grace = Objects.requireNonNull(grace);
  }

  /** Finishes interrupted removals, then collects unaccounted stages. Never partially reports. */
  public Sweep sweep() {
    if (!config.configured()) return new Sweep(0, 0);
    int collected = 0;
    for (var pending : records.uncollectedSources()) {
      // Confined to the root even though the record names the path: a stored row is data, and the
      // one directory this will remove for it is the one its project name resolves to here.
      Path workspace = within(pending.project());
      // A claimed project cannot be one of these -- a collection row means the destruction already
      // committed -- but the guard is unconditional so no path through this object can delete a
      // directory a creation holds.
      if (workspace == null || staging.staging(pending.project())) continue;
      if (remove(workspace)) {
        records.sourceCollected(pending.child());
        collected++;
      }
    }
    int retained = 0;
    try (var entries = Files.newDirectoryStream(config.root())) {
      for (Path entry : entries) {
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
        String name = entry.getFileName().toString();
        // The root holds one directory per child, named by the project's own UUID suffix. Anything
        // else was not put there by creation, so this does not claim it.
        if (!uuid(name)) continue;
        String project = "contained:" + name;
        if (records.committed(project)) {
          retained++;
          continue;
        }
        // A creation stages before any durable row exists, which is what refusal totality rests
        // on, so between the first byte and the commit there is a directory this sweep would
        // otherwise claim. Collecting it would delete the source of a creation about to succeed,
        // and the caller would hold a receipt for an Application whose files are gone -- worse
        // than the leak this exists to close. Two guards, in order of exactness.
        if (staging.staging(project) || young(entry)) {
          retained++;
          continue;
        }
        if (!remove(entry)) continue;
        // Written after the bytes are gone and already marked, because for this input there was no
        // earlier record to mark: a sweep's own collection is never left half-recorded.
        var record =
            new ContainedApplicationRepository.Collection(
                UUID.randomUUID(),
                null,
                null,
                project,
                null,
                entry,
                ContainedApplicationRepository.Cause.SWEPT,
                false);
        work.inTransaction(
            () -> {
              records.collected(record);
              records.sourceCollected(record.child());
              return null;
            });
        collected++;
      }
    } catch (IOException unreadable) {
      // A sweep is best effort by necessity and reports what it did rather than failing a caller
      // that asked for cleanup. What it could not read this time it reads on the next sweep, and
      // nothing treats a surviving directory as live.
    }
    return new Sweep(collected, retained);
  }

  /**
   * Whether a stage is too young to be assumed abandoned.
   *
   * <p>The in-process claim is exact but local, so this is the cross-process bound: a directory
   * modified more recently than the configured grace is assumed to belong to a creation that is
   * still running. The grace errs long on purpose, and {@link ContainmentConfig} floors it, because
   * the two failure directions are not symmetric — too long leaks for one more sweep, too short
   * deletes live work. An unreadable timestamp is treated as young, for the same reason.
   */
  private boolean young(Path entry) {
    if (grace.isZero() || grace.isNegative()) return false;
    try {
      return Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
          .toInstant()
          .isAfter(java.time.Instant.now().minus(grace));
    } catch (IOException unreadable) {
      return true;
    }
  }

  /** The source area a contained project name resolves to, or null if it is not under the root. */
  private Path within(String project) {
    String suffix = project.substring("contained:".length());
    if (!uuid(suffix)) return null;
    Path workspace = config.root().resolve(suffix).normalize();
    return workspace.getParent().equals(config.root()) ? workspace : null;
  }

  private static boolean uuid(String name) {
    try {
      return UUID.fromString(name).toString().equals(name);
    } catch (IllegalArgumentException other) {
      return false;
    }
  }

  /** Removes a tree, deepest entry first. Answers whether it is gone, never whether it tried. */
  private static boolean remove(Path workspace) {
    if (Files.notExists(workspace, LinkOption.NOFOLLOW_LINKS)) return true;
    try (var entries = Files.walk(workspace)) {
      for (Path path : entries.sorted(Comparator.reverseOrder()).toList())
        Files.deleteIfExists(path);
    } catch (IOException | RuntimeException incomplete) {
      // Swallowed on purpose, and the answer below is what the caller acts on rather than this:
      // a partially removed tree stays unmarked and is swept again.
    }
    return Files.notExists(workspace, LinkOption.NOFOLLOW_LINKS);
  }
}
