package io.aeyer.plowshare.server.information;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant-independent facts of one log tree that log visibility reads, as an immutable typed
 * value cached under the READABILITY item {@code conversation:<root>} (migrations V257, V258).
 *
 * <p>A tree is every log reachable from its root by the four descendant edges of {@code
 * information_unreadable_logs} (V235): conversation {@code parent_id}, orchestration caller to
 * conductor, parent orchestration's conductor to the child's conductor, and orchestration id under
 * its conductor. For each such log it records all of its parents under the same edges (including
 * parents outside the tree, so a reader can tell when the tree alone does not hold a log's whole
 * ancestry), whether the log is quarantined and its retained job inputs. No value is an allow
 * decision: {@link LogReadability} combines these facts with the reader's authority.
 */
public sealed interface LogTreeFact {
  /** Most logs one tree holds; one more makes it {@link Oversized}. */
  int MAX_LOGS = 1024;

  /** Most retained inputs one tree holds across its logs; one more makes it {@link Oversized}. */
  int MAX_INPUTS = 8192;

  /** Longest log id a tree holds; a longer id makes it {@link Oversized} instead of truncated. */
  int MAX_LOG_LENGTH = 4096;

  /** The root log this value was loaded for. */
  String root();

  /** Approximate retained bytes, for the cache's weight budget. */
  long weight();

  /**
   * One retained {@code information_job_inputs} selection. The row's {@code owner_handle} is
   * attribution only: V235 removed it from the read decision, so it is not loaded.
   *
   * @param scope the stored selection scope, compared literally by the SQL rule
   * @param project {@code project_name}, which may be NULL
   */
  record JobInput(UUID revision, String scope, Optional<String> project, boolean includeShared) {
    public JobInput {
      Objects.requireNonNull(revision);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(project);
    }

    long weight() {
      return 96L + 2L * scope.length() + project.map(name -> 2L * name.length()).orElse(0L);
    }
  }

  /**
   * One log of the tree.
   *
   * @param parents every parent of the log under the four ancestry edges, inside the tree or not;
   *     empty only for a root
   * @param quarantined an {@code information_quarantined_payloads} row names the log
   * @param inputs the log's own retained inputs, at most one per revision (the table's key)
   */
  record Log(List<String> parents, boolean quarantined, List<JobInput> inputs) {
    public Log {
      parents = List.copyOf(parents);
      inputs = List.copyOf(inputs);
    }

    long weight() {
      long weight = 64L;
      for (String parent : parents) weight += 48L + 2L * parent.length();
      for (var input : inputs) weight += input.weight();
      return weight;
    }
  }

  /** The tree under {@code root}, which always holds the root's own log, existing or not. */
  record Tree(String root, Map<String, Log> logs) implements LogTreeFact {
    public Tree {
      Objects.requireNonNull(root);
      logs = Map.copyOf(new HashMap<>(logs));
      if (!logs.containsKey(root)) throw new IllegalArgumentException("Tree must hold its root");
      if (logs.size() > MAX_LOGS) throw new IllegalArgumentException("Tree exceeds its log bound");
      if (logs.values().stream().mapToInt(log -> log.inputs().size()).sum() > MAX_INPUTS)
        throw new IllegalArgumentException("Tree exceeds its input bound");
    }

    @Override
    public long weight() {
      long weight = 256L;
      for (var entry : logs.entrySet())
        weight += 48L + 2L * entry.getKey().length() + entry.getValue().weight();
      return weight;
    }
  }

  /** A tree past {@link #MAX_LOGS}, {@link #MAX_INPUTS} or {@link #MAX_LOG_LENGTH}: ask SQL. */
  record Oversized(String root) implements LogTreeFact {
    public Oversized {
      Objects.requireNonNull(root);
    }

    @Override
    public long weight() {
      return 128L + 2L * root.length();
    }
  }
}
