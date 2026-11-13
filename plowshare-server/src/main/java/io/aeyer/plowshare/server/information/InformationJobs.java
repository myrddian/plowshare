package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Input dependencies are durable before execution; polling rechecks the original selection. */
public final class InformationJobs implements InformationLogAccess {
  private final JobInformationRepository repository;
  private final InformationAccess access;
  private InformationReadAccess catalogue;

  public InformationJobs(JobInformationRepository repository, InformationAccess access) {
    this.repository = repository;
    this.access = access;
  }

  public InformationJobs(
      JobInformationRepository repository,
      InformationAccess access,
      InformationReadAccess catalogue) {
    this(repository, access);
    this.catalogue = catalogue;
  }

  public boolean hasInputs(String log) {
    return repository.hasInputs(log);
  }

  /**
   * Records this log's input selections; the repository writes them under the log's owning account.
   *
   * <p>See {@code JdbcJobInformationRepository.bind} for why that is {@link
   * InformationContext#attribution()} and not the acting axis.
   */
  public void bind(String job, InformationContext context, List<UUID> revisions) {
    repository.bind(job, context, revisions);
  }

  public List<UUID> inputsOf(String log, String account) {
    requireLog(log, account);
    return repository.inputsOf(log);
  }

  /** A message carries its source log's exact input selections into the receiving log. */
  public void inherit(String source, String target, String account) {
    requireLog(source, account);
    repository.inherit(source, target);
    requireLog(target, account);
  }

  public boolean allowed(String job, String account) {
    return listing(account).allowed(job);
  }

  public boolean logAllowed(String log, String account) {
    return repository.logAllowed(log, account);
  }

  /**
   * Starts one read operation that decides many jobs for one account, such as a job listing.
   *
   * <p>Each distinct question is asked once per operation: a retained selection is resolved once,
   * each (selection, revision) pair is checked once and each log's readability is evaluated once.
   * The predicates and their order are exactly those of {@link #allowed}; only repeats within the
   * operation are reused, so every decision is still evaluated against current authority when the
   * operation first needs it. A listing must not outlive the request that created it.
   */
  public Listing listing(String account) {
    return new Listing(account);
  }

  /** Operation-scoped decisions for one account; not thread-safe and not a cache. */
  public final class Listing {
    private final String account;
    private final java.util.Map<InformationContext.Selection, Optional<InformationContext>>
        contexts = new java.util.HashMap<>();
    private final java.util.Map<JobInformationRepository.Input, Boolean> inputs =
        new java.util.HashMap<>();
    private final java.util.Map<String, Boolean> logs = new java.util.HashMap<>();

    private Listing(String account) {
      this.account = account;
    }

    public boolean allowed(String job) {
      var retained = repository.inputs(job);
      if (retained.isEmpty()) return logAllowed(job);
      // Attribution identifies who used an input. Disclosure is governed by the
      // asking account's current authority for each retained selection instead.
      if (account == null) return false;
      for (var input : retained) if (!inputs.computeIfAbsent(input, this::readable)) return false;
      return true;
    }

    public boolean logAllowed(String log) {
      return logs.computeIfAbsent(log, id -> repository.logAllowed(id, account));
    }

    private boolean readable(JobInformationRepository.Input input) {
      var selection = input.selection();
      var context = contexts.computeIfAbsent(selection, this::resolve);
      if (context.isEmpty()) return false;
      if (catalogue != null) {
        try {
          catalogue.requireReadable(context.get(), input.revision());
          return true;
        } catch (RuntimeException denied) {
          return false;
        }
      }
      return repository.readable(input.revision(), account, selection);
    }

    /** Empty when the account cannot use the selection; a denial is remembered like a grant. */
    private Optional<InformationContext> resolve(InformationContext.Selection selection) {
      try {
        return Optional.of(access.resolve(account, selection).withView(true));
      } catch (RuntimeException denied) {
        return Optional.empty();
      }
    }
  }

  public void requireLog(String log, String account) {
    if (log != null && !logAllowed(log, account))
      throw new NotFoundFault("log inputs are unavailable to this account");
  }

  /**
   * Binds one read revision onto this log and rechecks that the log is still readable.
   *
   * <p>Binding preserves execution attribution. The recheck uses that execution account's current
   * READ grants, matching the runner's other log checks. A contained actor's corpus ceiling is
   * independently enforced by the information read gates. Later human readers use their own grants
   * and the same retained-input predicates.
   */
  public java.util.function.Consumer<UUID> reads(String log, InformationContext context) {
    return revision -> {
      if (log == null) throw new IllegalStateException("a corpus-reading run needs a durable log");
      bind(log, context, List.of(revision));
      requireLog(log, context.attribution());
    };
  }

  public void require(String job, String account) {
    if (!allowed(job, account)) throw new NotFoundFault("job is unavailable to this account");
  }
}
