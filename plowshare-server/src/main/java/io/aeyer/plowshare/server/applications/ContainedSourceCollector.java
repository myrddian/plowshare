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
  /** Whether a sweep looked at the whole containment root, part of it, or none of it. */
  public enum Outcome {
    /** The root was enumerated to the end. {@code collected} and {@code retained} are the total. */
    COMPLETE,
    /**
     * The root could not be read, or the enumeration stopped partway. The counts are what this
     * sweep managed, <em>not</em> what the root holds. An operator reading this is being told the
     * difference between a quiet system and a blind one, which is the whole point of the field.
     */
    PARTIAL,
    /**
     * The configured root does not exist yet, so there is nothing staged to look at. Not a fault
     * and not a blind sweep: creation makes the root's entries, so a server that has never created
     * a contained Application has no root directory, and on a fresh installation that is the steady
     * state. Reporting it as {@link #PARTIAL} made the scheduler warn every interval about a system
     * that is merely unused, which is the noise that teaches an operator to ignore the one warning
     * that matters.
     */
    ABSENT,
    /** No containment root is configured, so there is nothing to look at. Not a fault. */
    UNCONFIGURED
  }

  /**
   * One sweep's accounting, which is the observable half of recovery.
   *
   * <p>{@code outcome} exists because the counts alone cannot distinguish "looked, found nothing"
   * from "could not look": both answered zero and zero, and a scheduler that logged only non-zero
   * collections said nothing about either. For the component that carries this record's
   * observability claim, that was the one failure mode it could least afford.
   *
   * @param collected source areas removed, from either input
   * @param retained directories left alone, because a committed child accounts for them, because a
   *     creation holds them, because they are younger than the grace, or because their removal did
   *     not complete
   */
  public record Sweep(int collected, int retained, Outcome outcome) {
    public Sweep {
      java.util.Objects.requireNonNull(outcome);
      if (collected < 0 || retained < 0)
        throw new IllegalArgumentException("A sweep counts what it did");
    }
  }

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

  /**
   * Finishes interrupted removals, then collects unaccounted stages.
   *
   * <p>Best effort, and it says which: the result's {@link Outcome} reports whether the whole root
   * was enumerated. It does <strong>not</strong> guarantee that everything collectable was
   * collected — a directory whose removal does not complete is counted as retained and swept again
   * next time — and it never throws for a filesystem it could not read, because a caller asking for
   * housekeeping is not asking to be failed.
   */
  public Sweep sweep() {
    if (!config.configured()) return new Sweep(0, 0, Outcome.UNCONFIGURED);
    int collected = 0;
    int pendingRetained = 0;
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
      } else {
        // Still there, still unmarked: next sweep's work, and counted so the totals account for
        // every row this one looked at.
        pendingRetained++;
      }
    }
    int retained = pendingRetained;
    // Enumerated to the end unless something below says otherwise. The previous shape wrapped the
    // whole loop in one catch and returned the partial count as though it were the total.
    var outcome = Outcome.COMPLETE;
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
        if (!remove(entry)) {
          // Tried and could not finish. Counted as retained rather than dropped from the
          // accounting: a sweep whose totals do not add up to what it looked at is the same
          // dishonesty as one that reports a partial count as complete.
          retained++;
          continue;
        }
        // Written after the bytes are gone and already marked, because for this input there was no
        // earlier record to mark: a sweep's own collection is never left half-recorded.
        var record =
            new ContainedApplicationRepository.Collection(
                UUID.randomUUID(),
                null,
                null,
                // No destruction rooted this: a swept stage had no committed record, so it is on
                // no destruction's chain and no replay can answer for it.
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
    } catch (NoSuchFileException absent) {
      // Told apart from "could not look" on purpose. The root's entries are made by creation, so
      // its absence says nothing has been staged on this server yet -- the steady state of a fresh
      // installation, not a filesystem an operator must go and inspect. Anything else that cannot
      // be read is still PARTIAL below.
      outcome = Outcome.ABSENT;
    } catch (IOException | java.nio.file.DirectoryIteratorException unreadable) {
      // A sweep is best effort by necessity and reports what it did rather than failing a caller
      // that asked for cleanup. What it could not read this time it reads on the next sweep, and
      // nothing treats a surviving directory as live. But it no longer reports the partial count
      // as a total: an unreadable root and a clean root are different facts about the system, and
      // only one of them needs an operator's attention.
      outcome = Outcome.PARTIAL;
    }
    return new Sweep(collected, retained, outcome);
  }

  /**
   * Whether a stage is too young to be assumed abandoned.
   *
   * <p>The in-process claim is exact but local, so this is the cross-process bound: a stage written
   * to more recently than the configured grace is assumed to belong to a creation that is still
   * running. The grace errs long on purpose, and {@link ContainmentConfig} floors it, because the
   * two failure directions are not symmetric — too long leaks for one more sweep, too short deletes
   * live work. An unreadable timestamp is treated as young, for the same reason.
   *
   * <p><strong>The age is the newest write anywhere in the stage, not the workspace directory's
   * own.</strong> A directory's mtime changes when its entries change, so the workspace's stops
   * moving the moment {@code revisions/} is created — and a creation then writes everything else
   * underneath that, for however long the bytes take. Measuring the workspace therefore ran the
   * grace from the start of staging rather than from the last write, which shortens exactly the
   * protection the multi-process window depends on. The tree a stage may hold is bounded by the
   * package limits ({@code validateFiles}: 128 files, 16 directory levels), so walking it is a
   * bounded read; the depth bound here is that limit with room for {@code revisions/<revision>}
   * above it, and an entry count bound so a hand-planted tree cannot make a sweep walk forever.
   */
  private boolean young(Path entry) {
    if (grace.isZero() || grace.isNegative()) return false;
    var floor = java.time.Instant.now().minus(grace);
    try (var tree = Files.walk(entry, STAGE_DEPTH)) {
      // Short-circuits on the first recent entry. Files.walk does not follow links.
      return tree.limit(STAGE_ENTRIES).anyMatch(path -> written(path, floor));
    } catch (IOException | RuntimeException unreadable) {
      return true;
    }
  }

  /** Depth of a staged tree: the package's 16 directory levels under {@code revisions/<id>}. */
  private static final int STAGE_DEPTH = 24;

  /**
   * Entries one stage may be read for. The package limits allow 128 files and their directories.
   */
  private static final long STAGE_ENTRIES = 4096;

  /** Whether one entry was written after the floor. An unreadable timestamp counts as recent. */
  private static boolean written(Path path, java.time.Instant floor) {
    try {
      return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(floor);
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
