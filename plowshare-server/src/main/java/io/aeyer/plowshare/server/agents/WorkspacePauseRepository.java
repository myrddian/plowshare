package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.session.Presence;
import java.util.List;
import java.util.Optional;

/** Durable safe suspension points. SQL and atomic claims belong behind this boundary. */
public interface WorkspacePauseRepository {
  /** Correlation and effect kind only; command environments and file payloads are never copied. */
  record Uncertain(String session, String request, String operation) {
    public Uncertain {
      for (String value : List.of(session, request, operation))
        if (value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0)
          throw new IllegalArgumentException("A bounded operation identity is required");
    }
  }

  record Snapshot(
      String job,
      BudgetCheckpoint checkpoint,
      String allowance,
      List<Presence> dependencies,
      List<String> definitions,
      List<Integer> modelCalls,
      String reason,
      boolean blocked,
      String completedIn,
      Outcome completed,
      Uncertain uncertain) {
    public Snapshot {
      dependencies = List.copyOf(dependencies);
      definitions = List.copyOf(definitions);
      modelCalls = List.copyOf(modelCalls);
      if (job == null
          || job.isBlank()
          || checkpoint == null
          || dependencies.isEmpty()
          || definitions.size() != checkpoint.frames().size()
          || modelCalls.size() != definitions.size()
          || modelCalls.stream().anyMatch(count -> count < 0)
          || (completedIn == null) != (completed == null)
          || reason == null
          || reason.isBlank()
          || reason.length() > 4000
          || job.length() > 1024
          || dependencies.size() > 128
          || allowance == null
          || !allowance.matches("[a-f0-9-]{36}")
          || definitions.stream().anyMatch(hash -> !hash.matches("[a-f0-9]{64}"))
          || uncertain != null && !blocked)
        throw new IllegalArgumentException("A complete workspace checkpoint is required");
    }

    public String root() {
      return checkpoint.frames().getFirst().conversation();
    }
  }

  record Retained(Snapshot snapshot, long generation, String state) {}

  /** Replace only a live owner's prior safe point, incrementing its recovery generation. */
  void suspended(Snapshot snapshot, String owner);

  boolean renew(String job, String owner);

  boolean resumed(String job, String owner);

  /** At most limit records, with deterministic ordering. No unbounded recovery scan. */
  List<Retained> pending(String after, int limit);

  Optional<Retained> find(String job);

  boolean awaiting(String root);

  /** Compare-and-set; duplicate wakes can never claim one generation twice. */
  boolean claim(String job, long generation, String owner);

  /** Fence effects before recovery begins. A second interruption leaves a blocked checkpoint. */
  void started(String job, long generation, String owner);

  /** Retire after a live resume or completion, never before a successful persistence boundary. */
  void completed(String job, String owner);

  /** Explicit authorized cancellation withdraws the checkpoint across execution owners. */
  Optional<Snapshot> cancel(String job);

  void blocked(String job, String reason);
}
