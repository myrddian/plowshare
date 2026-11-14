package io.aeyer.plowshare.server.information;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code information_log_readable(log, account)} (V235, with V240's function settings) evaluated in
 * Java over the facts of the log's trees ({@link LogTreeFact}).
 *
 * <p>The SQL predicate walks the ancestry of {@code log} (the log itself and every parent under the
 * four edges, transitively; UNION ends cycles) and answers true iff no ancestor is quarantined and
 * every distinct retained selection {@code (revision, scope, project_name, include_shared)} of
 * every ancestor is readable for {@code account} under {@code
 * information_readable_input_selections} (evaluated by {@link
 * KnowledgeReadability#retainedInputs}). A NULL account reads no retained input at all ({@code
 * WHERE $2 IS NULL OR ...}); its log is readable only when no ancestor has one. Input attribution
 * ({@code owner_handle}) plays no part since V235.
 *
 * <p>The ancestry comes from the loaded trees: every visited log must be held by one of them, with
 * its complete parent list. When it is not, the trees do not cover the log's ancestry and the
 * evaluation reports {@link Outcome.Incomplete}, so the caller can resolve the roots again; it
 * never decides from a partial walk. {@code LogReadabilityDifferentialDatabaseTest} compares this
 * class with the SQL predicate; a migration that changes a mirrored routine must change it too.
 */
public final class LogReadability {
  private LogReadability() {}

  /** The result of one evaluation. */
  public sealed interface Outcome {
    /** The verdict of {@code information_log_readable} for the loaded facts. */
    record Verdict(boolean readable) implements Outcome {}

    /** A visited log is in none of the trees: the roots used were not the log's roots. */
    record Incomplete() implements Outcome {}

    /** A retained input could not be decided from cached facts: ask SQL. */
    record Unservable() implements Outcome {}
  }

  /** Decides one retained selection for the reading account; empty means "ask SQL". */
  @FunctionalInterface
  public interface Inputs {
    Optional<Boolean> readable(LogTreeFact.JobInput input);
  }

  /**
   * @param account the reading account, or null for {@code information_log_readable(log, NULL)}
   * @param inputs consulted only for a non-null account
   */
  public static Outcome evaluate(
      String account, String log, Collection<LogTreeFact.Tree> trees, Inputs inputs) {
    Objects.requireNonNull(log);
    Objects.requireNonNull(inputs);
    var logs = new HashMap<String, LogTreeFact.Log>();
    for (var tree : trees) logs.putAll(tree.logs());
    // ancestors(id): SELECT $1 UNION SELECT parent ... ON child=a.id
    var ancestors = new LinkedHashSet<String>();
    var queue = new ArrayList<String>();
    ancestors.add(log);
    queue.add(log);
    for (int index = 0; index < queue.size(); index++) {
      var known = logs.get(queue.get(index));
      if (known == null) return new Outcome.Incomplete();
      for (String parent : known.parents()) if (ancestors.add(parent)) queue.add(parent);
    }
    // NOT EXISTS(ancestors JOIN information_quarantined_payloads)
    for (String ancestor : ancestors)
      if (logs.get(ancestor).quarantined()) return new Outcome.Verdict(false);
    // inputs: SELECT DISTINCT revision_id,scope,project_name,include_shared over the ancestors.
    Set<LogTreeFact.JobInput> selections = new LinkedHashSet<>();
    for (String ancestor : ancestors) selections.addAll(logs.get(ancestor).inputs());
    if (selections.isEmpty()) return new Outcome.Verdict(true);
    if (account == null) return new Outcome.Verdict(false);
    for (var selection : selections) {
      var readable = inputs.readable(selection);
      if (readable.isEmpty()) return new Outcome.Unservable();
      if (!readable.get()) return new Outcome.Verdict(false);
    }
    return new Outcome.Verdict(true);
  }
}
