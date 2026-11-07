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
    var inputs = repository.inputs(job);
    if (inputs.isEmpty()) return repository.logAllowed(job, account);
    if (account == null || !account.equals(inputs.getFirst().owner())) return false;
    for (var input : inputs) {
      var selection = input.selection();
      try {
        var context = access.resolve(account, selection).withView(true);
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

  /**
   * Binds one read revision onto this log and rechecks that the log is still readable.
   *
   * <p><strong>A log is owned by the attribution axis, so that is the axis this recheck asks
   * about.</strong> Both halves of this method are about the <em>log</em>, not about the corpus:
   * {@link #bind} stores the log's owner and {@link #requireLog} asks whether that owner may still
   * read everything bound to it. The corpus question — may this actor see this revision — belongs
   * to the acting axis and is asked by the SQL read gates, which are untouched.
   *
   * <p>This method used to pass {@code context.account()}, the acting axis, while {@code
   * InformationTool} passed {@code actor.attribution()} to the same {@link #requireLog} a few lines
   * earlier. The two were one handle for every caller that existed, so the disagreement was inert;
   * a contained run makes the same log's readability be checked against two different accounts in
   * one call, and the acting one is the one that cannot own a log at all (see {@code
   * JdbcJobInformationRepository.bind}: {@code owner_handle} is a foreign key into {@code admins}).
   * Agreeing on attribution widens nothing — {@link #requireLog} and {@link #logAllowed} are
   * unchanged, and for an uncontained caller the two axes hold one value.
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
