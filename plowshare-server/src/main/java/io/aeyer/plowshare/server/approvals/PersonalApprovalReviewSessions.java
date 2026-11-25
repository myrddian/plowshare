package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.access.BuiltInIdentities;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileProvider.PersonalWorkspace;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.union.MirrorProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Captures reviewer evidence per request and fences automatic results by policy revision. A human
 * override is carried only by the existing retained-call path, never inferred from prose.
 */
public final class PersonalApprovalReviewSessions implements ApprovalReviewSessions {
  private final ApprovalPolicyRepository policies;
  private final AvailableApprovalBots bots;
  private final ReviewInferenceFactory inference;
  private final Supplier<AgentDefinition> security;
  private final Duration budget;
  private final HumanApprovalHistory history;
  private final DeploymentSecurityPolicy deployment;
  private final WorkspaceSecurityGuidance workspaceGuidance;

  public PersonalApprovalReviewSessions(
      ApprovalPolicyRepository policies,
      AvailableApprovalBots bots,
      ReviewInferenceFactory inference,
      Supplier<AgentDefinition> security,
      Duration budget) {
    this(
        policies,
        bots,
        inference,
        security,
        budget,
        new HumanApprovalHistory() {
          @Override
          public boolean eligible(ApprovalIntent intent) {
            return false;
          }

          @Override
          public void remember(ApprovalIntent intent, String approval) {}
        });
  }

  public PersonalApprovalReviewSessions(
      ApprovalPolicyRepository policies,
      AvailableApprovalBots bots,
      ReviewInferenceFactory inference,
      Supplier<AgentDefinition> security,
      Duration budget,
      HumanApprovalHistory history) {
    this(
        policies,
        bots,
        inference,
        security,
        budget,
        history,
        DeploymentSecurityPolicy.NONE,
        WorkspaceSecurityGuidance.NONE);
  }

  public PersonalApprovalReviewSessions(
      ApprovalPolicyRepository policies,
      AvailableApprovalBots bots,
      ReviewInferenceFactory inference,
      Supplier<AgentDefinition> security,
      Duration budget,
      HumanApprovalHistory history,
      DeploymentSecurityPolicy deployment,
      WorkspaceSecurityGuidance workspaceGuidance) {
    this.history = Objects.requireNonNull(history);
    this.deployment = Objects.requireNonNull(deployment);
    this.workspaceGuidance = Objects.requireNonNull(workspaceGuidance);
    this.policies = Objects.requireNonNull(policies);
    this.bots = Objects.requireNonNull(bots);
    this.inference = Objects.requireNonNull(inference);
    this.security = Objects.requireNonNull(security);
    this.budget = Objects.requireNonNull(budget);
    if (budget.isZero() || budget.isNegative() || budget.compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException("Review budget must be positive and at most five minutes");
  }

  @Override
  public Optional<OperationApprovalReview> operations(Context context) {
    if (context.owner() == null
        || context.conversation() == null
        || context.operation() == null
        || ServiceCredentials.principal(context.owner())
        || BuiltInIdentities.reservedAccount(context.owner())) return Optional.empty();
    if (!context.owner().equals(context.operation().executor()))
      throw new IllegalArgumentException(
          "Approval policy owner must be the retained execution owner");
    return Optional.of(new Session(context));
  }

  private final class Session implements OperationApprovalReview {
    private record Target(
        String call,
        String provider,
        ApprovalPolicy.Kind kind,
        List<String> argv,
        List<String> paths,
        String cwd,
        String inputDigest,
        Optional<PersonalWorkspace> personalWorkspace,
        boolean securityFileMutation) {}

    private record Grant(long revision, String bot, AgentDefinition definition, boolean human) {}

    private final Context context;
    private final ConcurrentHashMap<Target, Grant> allowed = new ConcurrentHashMap<>();

    Session(Context context) {
      this.context = context;
    }

    @Override
    public ApprovalReviews.Decision review(Request request, ApprovalReviews.Consent consent) {
      Target target = target(request);
      allowed.remove(target);
      ApprovalPolicy policy;
      ApprovalIntent capturedIntent;
      ApprovalReviews.Decision result;
      AtomicReference<AgentDefinition> selectedBot = new AtomicReference<>();
      context.validity().run();
      try {
        policy = policies.read(context.owner());
        List<ApprovalPolicy.Rule> effectiveRules = rules(policy);
        AtomicReference<ModelApprovalReviewers> models = new AtomicReference<>();
        SecurityReviewer securityReview =
            (intent, rules, work) -> {
              ModelApprovalReviewers selected =
                  new ModelApprovalReviewers(
                      inference.within(
                          context.operation(),
                          context.budget(),
                          context.usage(),
                          context.validity()),
                      security.get(),
                      name -> {
                        if (!Objects.equals(name, policy.bot()))
                          throw new IllegalStateException("The selected personal bot changed");
                        AgentDefinition definition =
                            Objects.requireNonNull(bots.selected(context.owner(), name));
                        selectedBot.set(definition);
                        return definition;
                      },
                      deployment.guidance());
              models.set(selected);
              var guidance = workspaceGuidance.read(intent.project(), intent.provider(), work);
              context.validity().run();
              return selected.review(intent, rules, guidance, work);
            };
        PersonalBotReviewer botReview =
            (name, intent, rules, cleared, work) ->
                Objects.requireNonNull(models.get()).review(name, intent, rules, cleared, work);
        capturedIntent = intent(request);
        result =
            new PolicyApprovalReviews(securityReview, botReview, budget)
                .review(
                    policy,
                    effectiveRules,
                    capturedIntent,
                    new ApprovalReviews.Consent(
                        consent.existingConsent(),
                        consent.humanOnly(),
                        !consent.existingConsent()
                            && !consent.humanOnly()
                            && policy.mode() != ApprovalPolicy.Mode.MANUAL
                            && effectiveRules.stream()
                                .noneMatch(
                                    rule ->
                                        rule.effect() != ApprovalPolicy.Effect.PERSONAL_READ_INTENT
                                            && rule.matches(capturedIntent))
                            && history.eligible(capturedIntent)),
                    context.cancelled());
      } catch (RuntimeException unavailable) {
        // An authority failure is not an overridable review outage.
        context.validity().run();
        return new ApprovalReviews.Decision(
            context.cancelled().getAsBoolean() || request.securityFileMutation()
                ? ApprovalReviews.Action.REJECT
                : ApprovalReviews.Action.ASK_HUMAN,
            context.cancelled().getAsBoolean()
                ? "The requesting operation was cancelled."
                : request.securityFileMutation()
                    ? "SECURITY could not verify the explicit request for the security file change."
                    : "Approval review is unavailable; a human decision is required.",
            0,
            List.of(),
            null,
            null);
      }
      // Long model work cannot publish a human question or an allow after authority withdrawal.
      context.validity().run();
      // A durable audit failure is an infrastructure failure, not permission to execute or to
      // convert a security rejection into a human question. Let the owning runtime handle it.
      context.audit().accept(capturedIntent, result);
      if (result.action() == ApprovalReviews.Action.ALLOW) {
        if (allowed.size() >= 256)
          throw new IllegalStateException("Too many unconsumed review results");
        AgentDefinition definition = selectedBot.get();
        allowed.put(
            target,
            new Grant(
                policy.revision(), definition == null ? null : policy.bot(), definition, false));
      }
      return result;
    }

    @Override
    public void humanOverride(Request request) {
      authorizeHumanOverride(request, false);
    }

    private void authorizeHumanOverride(Request request, boolean securityEditVerified) {
      context.validity().run();
      Target target = target(request);
      if (request.securityFileMutation() && !securityEditVerified)
        throw new WorkspaceRefusedException(
            "Approval cannot substitute for SECURITY verification of an explicit security file edit request.");
      ApprovalPolicy policy = policies.read(context.owner());
      refuseProhibited(rules(policy), request);
      if (allowed.size() >= 256)
        throw new WorkspaceRefusedException("Too many unconsumed review results");
      allowed.put(target, new Grant(policy.revision(), null, null, true));
    }

    @Override
    public void humanOverride(Request request, String approval) {
      humanOverride(request, approval, false);
    }

    @Override
    public void humanOverride(Request request, String approval, boolean securityEditVerified) {
      authorizeHumanOverride(request, securityEditVerified);
      history.remember(intent(request), approval);
    }

    @Override
    public void requireCurrent(Request request) {
      context.validity().run();
      if (context.cancelled().getAsBoolean())
        throw new WorkspaceRefusedException("The reviewed command was cancelled");
      Grant grant = allowed.remove(target(request));
      if (grant == null)
        throw new WorkspaceRefusedException("No current review covers this command; nothing ran");
      ApprovalPolicy policy = policies.read(context.owner());
      refuseProhibited(rules(policy), request);
      if (!grant.human() && policy.revision() != grant.revision())
        throw new WorkspaceRefusedException(
            "Approval policy changed after review; nothing ran. Request a fresh review.");
      if (!grant.human() && grant.bot() != null) {
        AgentDefinition selected;
        try {
          selected = bots.selected(context.owner(), grant.bot());
        } catch (CallerFault | IllegalArgumentException unavailable) {
          // Removal/disablement invalidates this permit. It is a model-visible refusal, not a
          // failed tool execution or a reason to replay the original call.
          throw new WorkspaceRefusedException(
              "The selected personal bot is no longer available; nothing ran. Request a fresh review.");
        }
        if (!grant.definition().equals(selected))
          throw new WorkspaceRefusedException(
              "The selected personal bot changed after review; nothing ran");
      }
    }

    private List<ApprovalPolicy.Rule> rules(ApprovalPolicy account) {
      return Stream.concat(
              deployment.rules().stream(),
              account.rules().stream()
                  .filter(rule -> rule.effect() != ApprovalPolicy.Effect.PERSONAL_READ_INTENT))
          .toList();
    }

    private void refuseProhibited(List<ApprovalPolicy.Rule> rules, Request request) {
      ApprovalIntent intent = intent(request);
      if (rules.stream()
          .anyMatch(rule -> rule.effect() == ApprovalPolicy.Effect.REJECT && rule.matches(intent)))
        throw new WorkspaceRefusedException(
            "Personal approval rules prohibit this command; nothing ran");
    }

    private ApprovalIntent intent(Request request) {
      String task = context.authenticatedTask();
      boolean taskTooLong = task != null && task.length() > 16000;
      // A supplied task that exceeds the evidence limit may contain restrictions. Treat it as
      // unavailable evidence even for a read ordinarily covered by standing intent.
      if (taskTooLong || (task != null && task.isBlank())) task = null;
      var ownerPersonal =
          request
              .personalWorkspace()
              .filter(workspace -> workspace.owner().equals(context.owner()));
      var schedule = context.scheduledTask();
      // The mirror can verify the canonical target. A remote provider attests only to the
      // named path, which cannot rule out an alias to a protected or restricted file.
      String scheduledTask =
          context.authenticatedTask() == null
                  && request.kind() == ApprovalPolicy.Kind.FILE_WRITE
                  && !request.securityFileMutation()
                  && MirrorProvider.NAME.equals(request.provider())
                  && schedule != null
                  && schedule.personalFileReview()
                  && schedule.owner().equals(context.owner())
                  && ownerPersonal
                      .filter(workspace -> isOutput(workspace, request.paths()))
                      .isPresent()
              ? schedule.instruction()
              : null;
      if (scheduledTask != null && (scheduledTask.isBlank() || scheduledTask.length() > 16000))
        scheduledTask = null;
      return new ApprovalIntent(
          context.owner(),
          context.conversation(),
          request.call(),
          request.project(),
          request.provider(),
          request.kind(),
          request.argv(),
          request.paths(),
          request.cwd(),
          digest(request.stdin()),
          task,
          null,
          request.stdin() == null || request.stdin().length() > 16000 ? null : request.stdin(),
          !taskTooLong
              && ownerPersonal.isPresent()
              && request.kind() == ApprovalPolicy.Kind.FILE_READ,
          request.securityFileMutation(),
          scheduledTask);
    }

    private Target target(Request request) {
      if (!context.operation().home().project().equals(request.project()))
        throw new WorkspaceRefusedException(
            "Review project differs from the admitted execution home");
      // Review grants belong to one original call, even when another call requests identical
      // effects. Retain the complete input hash rather than extending the lifetime of large input.
      return new Target(
          request.call(),
          request.provider(),
          request.kind(),
          request.argv(),
          request.paths(),
          request.cwd(),
          digest(request.stdin()),
          request.personalWorkspace(),
          request.securityFileMutation());
    }
  }

  /** Checks the exact path attested by the Personal provider against its output directory. */
  private static boolean isOutput(PersonalWorkspace workspace, List<String> paths) {
    if (paths.size() != 1) return false;
    Path output = workspace.root().resolve("Out");
    Path file = Path.of(paths.getFirst());
    return file.equals(file.normalize()) && file.startsWith(output) && !file.equals(output);
  }

  private static String digest(String input) {
    if (input == null) return null;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
