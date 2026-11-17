package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ConversationAliasProperties;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.aeyer.plowshare.server.requests.RequestedBudget;
import io.aeyer.plowshare.server.requests.RequestedTurnCap;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Thin naming facade over ordinary creation/run owners. No hook or inference runs under alias SQL.
 */
@Service
public final class ExternalConversationService implements ExternalConversations {
  private final ConversationAliasRepository aliases;
  private final IdentityRepository identities;
  private final WorkAccess access;
  private final ConversationRuns runs;
  private final Runs.Shown pictures;
  private final LogStages stages;
  private final ConversationsProperties defaults;
  private final ConversationAliasProperties budgets;
  private final SqlWorkBudget sql;

  public ExternalConversationService(
      ConversationAliasRepository aliases,
      IdentityRepository identities,
      WorkAccess access,
      ConversationRuns runs,
      Runs.Shown pictures,
      LogStages stages,
      ConversationsProperties defaults,
      ConversationAliasProperties budgets,
      SqlWorkBudget sql) {
    this.aliases = Objects.requireNonNull(aliases);
    this.identities = Objects.requireNonNull(identities);
    this.access = Objects.requireNonNull(access);
    this.runs = Objects.requireNonNull(runs);
    this.pictures = Objects.requireNonNull(pictures);
    this.stages = Objects.requireNonNull(stages);
    this.defaults = Objects.requireNonNull(defaults);
    this.budgets = Objects.requireNonNull(budgets);
    this.sql = Objects.requireNonNull(sql);
  }

  @Override
  public ConversationRecord open(Locator locator, Open options, Caller caller) {
    Objects.requireNonNull(options);
    return within(
        () -> {
          // Validate creation choices even on a hit; never let a hit repair invalid input silently.
          Budget budget =
              RequestedBudget.in(
                  options.maxModelCalls(), options.noBudget(), defaults.defaultBudgetLimit());
          TurnCap cap =
              RequestedTurnCap.in(
                  options.maxTurns(), options.noTurnCap(), "each turn in this conversation");
          var key = key(locator, caller, "conversation.open.ex");
          var opened = aliases.open(key, new ConversationAliasRepository.Opening(budget, cap));
          // The repository returned after commit. Preserve the ordinary post-commit fail-open hook
          // window; a later repeat resolves the row and does not emit log.open a second time.
          requireTarget(key, opened.conversation(), caller);
          if (opened.created())
            stages.opened(
                LogStages.LogOpened.ofConversation(opened.conversation(), caller.session()));
          ReadDeadline.check();
          return opened.conversation();
        });
  }

  @Override
  public Runs.Started run(Locator locator, Run options, Caller caller) {
    Objects.requireNonNull(options);
    return within(
        () -> {
          var key = key(locator, caller, "agent.run.ex");
          var target =
              aliases
                  .find(key)
                  .orElseThrow(
                      () ->
                          new NotFoundFault(
                              "No external conversation exists in this authorized namespace"));
          requireTarget(key, target, caller);
          ReadDeadline.check();
          // The external project selected the alias. It must not become a conflicting project
          // selector on the ordinary existing-conversation request.
          var started =
              runs.startResolved(
                  new Runs.Ask(
                      options.agent(),
                      options.task(),
                      null,
                      options.session(),
                      target.id(),
                      options.maxTurns(),
                      options.noTurnCap(),
                      options.images(),
                      Speaker.person(caller.account()),
                      caller.account(),
                      null,
                      null,
                      options.maxModelCalls()),
                  pictures,
                  new ConversationRuns.Resolved(target, key.projectId(), caller.account()));
          try {
            ReadDeadline.check();
          } catch (ReadUnavailableException expired) {
            throw new ExternalConversationUnavailableException(
                "Run admission may have completed before its deadline; inspect the original work and do not replay",
                expired);
          }
          return started;
        });
  }

  private <T> T within(Supplier<T> action) {
    // Direct typed callers receive the same budget as the public router. A nested call keeps the
    // earlier request deadline; the repository still owns its transaction and commit boundary.
    try {
      return sql.within(
          budgets.timeout(), () -> ExternalConversationAdmission.within(budgets.timeout(), action));
    } catch (ArchiveUnavailableException unavailable) {
      // A repository can lose its commit acknowledgement without knowing whether the alias was
      // written. Do not let the ordinary archive mapper turn that into a no-effect/retry promise.
      throw ExternalConversationUnavailableException.preserveUncertainty(unavailable);
    }
  }

  private ConversationAliasRepository.Key key(Locator locator, Caller caller, String operation) {
    ReadDeadline.check();
    Objects.requireNonNull(locator);
    Objects.requireNonNull(caller);
    access.requireSession(caller.session(), caller.account());
    // Authorization precedes both namespace identity lookup and alias existence disclosure.
    if (caller.admission() == null
        || !caller
            .admission()
            .consume(locator.project(), caller.account(), caller.session(), operation))
      access.requireWork(locator.project(), caller.account());
    var account = identities.account(caller.account());
    if (account.locked()) throw new CallerFault("External conversations require an account");
    long project = projectId(identities.project(locator.project()));
    ReadDeadline.check();
    return new ConversationAliasRepository.Key(
        project, account.id(), locator.source(), locator.externalConversation());
  }

  private void requireTarget(
      ConversationAliasRepository.Key key, ConversationRecord target, Caller caller) {
    ReadDeadline.check();
    if (target.home().isGlobal()
        || target.origin() != Origin.TURN
        || target.parentId() != null
        || target.agent() != null
        || target.budget() == null)
      throw new CallerFault("The external conversation target is unavailable or incompatible");
    // This is a post-transaction identity/current-authority fence, not a second target fetch.
    // A project can have been renamed (or its old name reused) since namespace admission. The
    // actual target Home must still resolve to the stable project ID admitted for this key.
    if (projectId(identities.project(target.home().project())) != key.projectId())
      throw new CallerFault("The external conversation target has an incompatible project");
    // CONTRIBUTOR includes readable role presence. One live pipeline at this boundary preserves
    // stored roles, service-token ceilings, application manifests and grant lineage together.
    access.requireWork(target.home().project(), caller.account());
    ReadDeadline.check();
  }

  private static long projectId(AuthorityScope scope) {
    if (scope instanceof AuthorityScope.Project project) return project.projectId();
    if (scope instanceof AuthorityScope.Personal personal) return personal.projectId();
    throw new CallerFault(
        "External conversations require an ordinary project or explicit Personal");
  }
}
