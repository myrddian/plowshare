package io.aeyer.plowshare.server.llm;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Provider-independent recovery of model-produced field objects. This capability plans a single
 * model-only correction; its caller journals and executes that command under the original
 * authority, cancellation and shared allowance. It never executes tools, retries effects or changes
 * evidence. Domain-specific factual/reference validation remains with the consuming workflow.
 */
public interface ModelOutputRecovery {
  String VERSION = "model-output-recovery/1";
  int MAX_BYTES = 65536;
  int MAX_REPAIR_CHARACTERS = 32768;

  enum Action {
    PARSED,
    ACCEPT,
    SUMMARISE,
    CORRECT,
    NEEDS_REVIEW,
    STOP
  }

  enum Problem {
    FORMAT,
    SHAPE,
    WORD_LIMIT,
    CONTENT,
    PRESERVATION
  }

  record Limit(int words, boolean required) {
    public Limit {
      if (words < 1 || words > 4096) throw new IllegalArgumentException("Invalid word budget");
    }
  }

  /**
   * Version-one contracts describe string fields; nested/domain judgments need their own validator.
   */
  record Contract(String name, Map<String, Limit> fields, String worker) {
    public Contract {
      if (name == null || !name.matches("[a-z][a-z0-9_-]{0,63}"))
        throw new IllegalArgumentException("Invalid recovery contract name");
      if (worker == null || !worker.matches("[A-Za-z0-9_-]{1,128}"))
        throw new IllegalArgumentException("Invalid recovery worker");
      Objects.requireNonNull(fields, "fields");
      if (fields.isEmpty() || fields.size() > 32)
        throw new IllegalArgumentException("Recovery requires 1–32 fields");
      fields.forEach(
          (key, value) -> {
            if (key == null || !key.matches("[a-z][a-z0-9_]{0,63}"))
              throw new IllegalArgumentException("Invalid recovery field");
            Objects.requireNonNull(value, "limit");
          });
      fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    }
  }

  record Issue(Problem problem, String field, int actualWords, int maximumWords) {}

  /** Persist alongside the next command, never in a separate retry queue. */
  record Checkpoint(String original, String contractHash, Action action) {
    public Checkpoint {
      if (original == null || original.length() > MAX_REPAIR_CHARACTERS)
        throw new IllegalArgumentException("Invalid recovery checkpoint response");
      if (contractHash == null || !contractHash.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid recovery checkpoint contract");
      if (action != Action.SUMMARISE && action != Action.CORRECT)
        throw new IllegalArgumentException("Invalid recovery checkpoint action");
    }
  }

  record Request(String response, Contract contract, Optional<Checkpoint> checkpoint) {
    public Request {
      Objects.requireNonNull(response, "response");
      Objects.requireNonNull(contract, "contract");
      Objects.requireNonNull(checkpoint, "checkpoint");
    }
  }

  record Task(String worker, String instruction) {}

  /** No response text: the ledger links to the original command and hashes retained results. */
  record Diagnostic(
      String policy,
      String contract,
      Action action,
      int attempt,
      String originalHash,
      String responseHash,
      String parserPass,
      List<Issue> issues,
      String reason) {
    public Diagnostic {
      issues = List.copyOf(issues);
    }
  }

  record Plan(
      Action action,
      Map<String, String> value,
      Optional<Task> task,
      Optional<Checkpoint> checkpoint,
      Diagnostic diagnostic) {
    public Plan {
      value = Map.copyOf(value);
      Objects.requireNonNull(task, "task");
      Objects.requireNonNull(checkpoint, "checkpoint");
      Objects.requireNonNull(diagnostic, "diagnostic");
      if ((action == Action.SUMMARISE || action == Action.CORRECT)
          != (task.isPresent() && checkpoint.isPresent()))
        throw new IllegalArgumentException("Recovery task/checkpoint mismatch");
    }
  }

  Plan examine(Request request);
}
