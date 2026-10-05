package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.List;
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
    var inputs = repository.inputs(job);
    if (inputs.isEmpty()) return repository.logAllowed(job, account);
    if (account == null || !account.equals(inputs.getFirst().owner())) return false;
    for (var input : inputs) {
      var selection = input.selection();
      try {
        var context = access.resolve(account, selection);
        if (catalogue != null) {
          catalogue.requireReadable(context, input.revision());
          continue;
        }
      } catch (RuntimeException denied) {
        return false;
      }
      if (!repository.readable(input.revision(), account, selection)) return false;
    }
    return true;
  }

  public boolean logAllowed(String log, String account) {
    return repository.logAllowed(log, account);
  }

  public void requireLog(String log, String account) {
    if (log != null && !logAllowed(log, account))
      throw new NotFoundFault("log inputs are unavailable to this account");
  }

  public java.util.function.Consumer<UUID> reads(String log, InformationContext context) {
    return revision -> {
      if (log == null) throw new IllegalStateException("a corpus-reading run needs a durable log");
      bind(log, context, List.of(revision));
      requireLog(log, context.account());
    };
  }

  public void require(String job, String account) {
    if (!allowed(job, account)) throw new NotFoundFault("job is unavailable to this account");
  }
}
