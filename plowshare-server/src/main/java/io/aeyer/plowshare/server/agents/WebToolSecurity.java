package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.agents.WebToolReviews.Context;
import io.aeyer.plowshare.server.agents.WebToolReviews.Decision;
import io.aeyer.plowshare.server.agents.WebToolReviews.ExceptionEvidence;
import io.aeyer.plowshare.server.agents.WebToolReviews.Gate;
import io.aeyer.plowshare.server.agents.WebToolReviews.Intent;
import io.aeyer.plowshare.server.agents.WebToolReviews.Passage;
import io.aeyer.plowshare.server.agents.WebToolReviews.Policy;
import io.aeyer.plowshare.server.agents.WebToolReviews.Verdict;
import io.aeyer.plowshare.server.approvals.ReviewWork;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.WebApproval;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.Deadline;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.ToolPre;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.dispatch.WebEvidenceRelease;
import io.aeyer.plowshare.server.net.WebRequestWork;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptStore.WebResult;
import io.aeyer.plowshare.server.security.LocalTextFilter.InjectionRefusal;
import io.aeyer.plowshare.server.security.TextFiltering;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Owns result release independently of the provider and the ordinary hook chain. */
@Component
final class WebToolSecurity {
  static final String AUDIT_PREFIX = "Web security v1 ";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String UNAVAILABLE = "Web result unavailable after security review.";
  private static final Pattern LINE = Pattern.compile("[^\\r\\n]+");
  private static final int MAX_PASSAGES = 24;
  private static final int MAX_PASSAGE = 1200;
  private final WebToolReviews reviews;
  private final TextFiltering filtering;
  private final Duration deadline;
  private final Duration approvalLifetime;

  record Consent(String id, WebApproval approval, Instant oldest) {}

  WebToolSecurity(
      WebToolReviews reviews,
      TextFiltering filtering,
      @Value("${plowshare.security.web-review-budget:PT90S}") Duration deadline,
      @Value("${plowshare.security.web-approval-lifetime:PT15M}") Duration approvalLifetime) {
    this.reviews = Objects.requireNonNull(reviews);
    this.filtering = Objects.requireNonNull(filtering);
    this.deadline = Objects.requireNonNull(deadline);
    this.approvalLifetime = Objects.requireNonNull(approvalLifetime);
    if (deadline.isZero()
        || deadline.isNegative()
        || deadline.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException(
          "Web review budget must be positive and at most ten minutes");
    if (approvalLifetime.isZero()
        || approvalLifetime.isNegative()
        || approvalLifetime.compareTo(Duration.ofHours(24)) > 0)
      throw new IllegalArgumentException(
          "Web approval lifetime must be positive and at most a day");
  }

  Session within(
      Context context,
      BooleanSupplier cancelled,
      Consumer<LoggedEntry> audit,
      Function<WebApproval, RunApproval> ask,
      Predicate<Consent> consume) {
    return new Session(context, cancelled, audit, ask, consume);
  }

  /** One requesting run owns its outstanding challenges and heightened-review state. */
  final class Session {
    private final Context context;
    private final BooleanSupplier cancelled;
    private final Consumer<LoggedEntry> audit;
    private final Function<WebApproval, RunApproval> ask;
    private final Predicate<Consent> consume;
    private final Map<String, Challenge> pending = new HashMap<>();
    private boolean heightened;

    private Session(
        Context context,
        BooleanSupplier cancelled,
        Consumer<LoggedEntry> audit,
        Function<WebApproval, RunApproval> ask,
        Predicate<Consent> consume) {
      this.context = context;
      this.cancelled = cancelled;
      this.audit = audit;
      this.ask = ask;
      this.consume = consume;
    }

    Bound wrap(AgentTool tool) {
      return new Bound(tool, this);
    }

    private Plan prepare(Bound tool, String arguments) {
      WebToolRequest request;
      try {
        request = WebToolRequest.parse(tool.schema().name(), arguments);
        // An external query or URL cannot include text that local disclosure rules would mask.
        filtering.inspect(request.operation().destination(), false, false);
      } catch (BadArguments | LlmException invalid) {
        return Plan.refusal("Web request refused by argument or disclosure policy.");
      }
      var work = new ReviewWork(Deadline.after(deadline), cancelled);
      var intent = new Intent(request.operation(), request.justification(), heightened);
      String auditId = UUID.randomUUID().toString();
      String requestDigest = WebEvidenceRelease.digest(json(request.operation()));
      Policy policy;
      Gate gate;
      try {
        requireActive(work);
        if (context.conversation() == null || context.task() == null || context.task().isBlank())
          throw new IllegalStateException("Authenticated web task is unavailable");
        policy = reviews.policy(context, intent.operation().verb(), work);
        if (request.challenge() != null) {
          Challenge challenge = pending.remove(request.challenge());
          if (challenge != null) auditId = challenge.auditId();
          if (challenge == null
              || !challenge.operation().equals(intent.operation())
              || !challenge.policy().equals(policy.revision())
              || !Instant.now().isBefore(challenge.expiresAt())) {
            note(
                tool,
                auditId,
                requestDigest,
                policy.revision(),
                "DENY",
                "invalid_challenge",
                null,
                List.of(),
                null);
            return Plan.refusal("Web request denied. Its justification challenge is not current.");
          }
        }
        gate = Objects.requireNonNull(reviews.gate(context, policy, intent, work));
        requireActive(work);
      } catch (RuntimeException failed) {
        note(tool, auditId, requestDigest, null, "DENY", "gate_unavailable", null, List.of(), null);
        return Plan.refusal("Web request denied. SECURITY could not verify the request.");
      }
      boolean askWhy = gate == Gate.ASK_WHY && request.challenge() == null && pending.size() < 32;
      note(
          tool,
          auditId,
          requestDigest,
          policy.revision(),
          gate == Gate.ASK_WHY && !askWhy ? Gate.DENY.name() : gate.name(),
          gate == Gate.ASK_WHY && !askWhy ? "justification_unresolved" : "intent_review",
          null,
          List.of(),
          null);
      if (askWhy) {
        String id = UUID.randomUUID().toString();
        pending.put(
            id,
            new Challenge(
                intent.operation(),
                policy.revision(),
                Instant.now().plus(Duration.ofMinutes(5)),
                auditId,
                tool.call,
                tool.schema().name(),
                requestDigest));
        return Plan.refusal(
            "SECURITY asks why this web request serves the task. "
                + "Call the same tool with the same operation arguments, challenge "
                + id
                + ", and a justification.");
      }
      if (gate == Gate.DENY || gate == Gate.ASK_WHY)
        return Plan.refusal(
            "Web request denied. SECURITY found insufficient authorization or justification.");
      return new Plan(gate, arguments, intent, policy, work, auditId, requestDigest, null);
    }

    void closeChallenges() {
      for (Challenge challenge : pending.values()) {
        note(
            challenge.call(),
            challenge.tool(),
            challenge.auditId(),
            challenge.requestDigest(),
            challenge.policy(),
            Gate.DENY.name(),
            "justification_missing",
            null,
            List.of(),
            null);
      }
      pending.clear();
    }

    private Held execute(Bound tool, Plan plan) {
      var policy = plan.policy();
      var intent = plan.intent();
      var work = plan.work();
      String auditId = plan.auditId();
      String requestDigest = plan.requestDigest();
      try {
        requirePolicy(policy, intent, work);
      } catch (RuntimeException stale) {
        note(
            tool,
            auditId,
            requestDigest,
            policy.revision(),
            "DENY",
            "authorization_changed",
            null,
            List.of(),
            null);
        return Held.refusal(
            tool.refuse("Web request denied because its reviewed policy or authority changed."));
      }
      // The restricted child gets only the exact approved, typed operation. It has no model loop
      // or general tool, filesystem, or transport facade through which to request another effect.
      final WebToolRequest.Result returned;
      var providerWork =
          intent.operation() instanceof WebToolRequest.Fetch
              ? new WebRequestWork(
                  work.deadline(),
                  work.cancelled(),
                  destination -> reviewRedirect(tool, plan, destination))
              : new WebRequestWork(work.deadline(), work.cancelled());
      try {
        returned = new WebReadWorker(tool, intent.operation()).execute(providerWork);
      } catch (CallerFault invalid) {
        return Held.refusal(tool.refuse("Web request was refused by the provider service."));
      }
      String raw = returned.text();
      try {
        requireActive(work);
        if (!returned.hasEvidence())
          return Held.refusal(tool.refuse("The web service could not complete the request."));
        requirePolicy(policy, intent, work);
        String inspected = filtering.inspect(raw, false, true);
        try {
          return new Held(
              filtering.inspect(inspected, true, true),
              null,
              auditId,
              requestDigest,
              policy,
              intent,
              work,
              false,
              false);
        } catch (InjectionRefusal flagged) {
          return monitor(tool, auditId, requestDigest, policy, intent, work, inspected, flagged);
        }
      } catch (LlmException refused) {
        note(
            tool,
            auditId,
            requestDigest,
            policy.revision(),
            "REJECT",
            "disclosure_policy",
            WebEvidenceRelease.digest(raw),
            List.of(),
            null);
        return Held.refusal(tool.refuse(UNAVAILABLE));
      } catch (IllegalStateException ended) {
        note(
            tool,
            auditId,
            requestDigest,
            policy.revision(),
            "REJECT",
            "execution_ended",
            WebEvidenceRelease.digest(raw),
            List.of(),
            null);
        return Held.refusal(tool.refuse(UNAVAILABLE));
      }
    }

    private boolean reviewRedirect(Bound tool, Plan plan, String destination) {
      var work = plan.work();
      var policy = plan.policy();
      var original = plan.intent();
      String digest = null;
      Gate decision;
      try {
        var next = WebToolRequest.validatedFetch(destination, 0);
        filtering.inspect(next.url(), false, false);
        digest = WebEvidenceRelease.digest(json(next));
        var redirected = new Intent(next, null, heightened, original.operation().destination());
        requirePolicy(policy, redirected, work);
        decision = reviews.gate(context, policy, redirected, work);
        requirePolicy(policy, redirected, work);
      } catch (RuntimeException refused) {
        decision = Gate.DENY;
      }
      boolean approved = decision == Gate.ALLOW;
      note(
          tool,
          plan.auditId(),
          digest,
          policy.revision(),
          approved ? "ALLOW" : "DENY",
          "redirect_destination",
          null,
          List.of(),
          null);
      return approved;
    }

    private Held monitor(
        Bound tool,
        String auditId,
        String requestDigest,
        Policy policy,
        Intent intent,
        ReviewWork work,
        String raw,
        InjectionRefusal flagged) {
      String digest = WebEvidenceRelease.digest(raw);
      try {
        boolean structured = tool.tool instanceof SearchTool search && search.structuredResult();
        var passages = flaggedPassages(raw, structured);
        if (passages.isEmpty()) throw new IllegalArgumentException("No bounded flagged passages");
        Decision decision =
            reviews.monitor(
                context,
                policy,
                intent,
                new ExceptionEvidence(flagged.rule(), digest, passages),
                work);
        requirePolicy(policy, intent, work);
        List<Span> redactions = selected(passages, decision.redactions());
        String result;
        String rule = null;
        switch (decision.verdict()) {
          case APPROVE, APPROVE_AND_NOTIFY -> {
            result = filtering.inspectReleased(raw, flagged.rule());
            rule = flagged.rule();
          }
          case APPROVE_WITH_REDACTION ->
              result = filtering.inspect(redact(raw, redactions, structured), true, true);
          case REJECT -> result = tool.refuse(UNAVAILABLE);
          default -> throw new IllegalArgumentException("Unknown web security verdict");
        }
        note(
            tool,
            auditId,
            requestDigest,
            policy.revision(),
            decision.verdict().name(),
            flagged.rule(),
            digest,
            redactions,
            WebEvidenceRelease.digest(result));
        return new Held(
            result,
            rule,
            auditId,
            requestDigest,
            policy,
            intent,
            work,
            decision.verdict() == Verdict.REJECT,
            decision.verdict() == Verdict.APPROVE_AND_NOTIFY);
      } catch (RuntimeException failed) {
        note(
            tool,
            auditId,
            requestDigest,
            policy.revision(),
            "REJECT",
            "monitor_unavailable",
            digest,
            List.of(),
            null);
        return Held.refusal(tool.refuse(UNAVAILABLE));
      }
    }

    private List<Passage> flaggedPassages(String body, boolean structured) {
      var found = new ArrayList<Passage>();
      if (structured) {
        try (var tokens = JSON.createParser(body)) {
          while (tokens.nextToken() != null) {
            if (tokens.currentToken() != JsonToken.VALUE_STRING) continue;
            int start = Math.toIntExact(tokens.currentTokenLocation().getCharOffset());
            String text = tokens.getText();
            int end = Math.toIntExact(tokens.currentLocation().getCharOffset());
            addFindings(found, start, end, text, true);
          }
        } catch (IOException malformed) {
          throw new IllegalArgumentException(
              "Structured web evidence could not be inspected", malformed);
        }
        return List.copyOf(found);
      }
      var lines = LINE.matcher(body);
      while (lines.find()) addFindings(found, lines.start(), lines.end(), lines.group(), false);
      return List.copyOf(found);
    }

    private void addFindings(
        List<Passage> found, int start, int end, String text, boolean structured) {
      if (!isFlagged(text)) return;
      if (text.length() <= MAX_PASSAGE) {
        addPassage(found, start, end, text);
        return;
      }
      int before = found.size();
      // Overlap keeps a short finding reviewable even when it straddles two window boundaries.
      for (int index = 0; index < text.length(); index += MAX_PASSAGE / 2) {
        int from = characterBoundary(text, index);
        int to = characterBoundary(text, Math.min(text.length(), from + MAX_PASSAGE));
        String window = text.substring(from, to);
        if (isFlagged(window)) {
          // A structured value is replaced as a whole JSON token, including any escape sequences.
          addPassage(
              found, structured ? start : start + from, structured ? end : start + to, window);
        }
        if (to == text.length()) break;
      }
      if (before == found.size())
        throw new IllegalArgumentException("A finding spans more than one review window");
    }

    private boolean isFlagged(String text) {
      try {
        filtering.inspect(text, true, true);
        return false;
      } catch (InjectionRefusal flagged) {
        return true;
      }
    }

    private void addPassage(List<Passage> found, int start, int end, String text) {
      if (found.size() == MAX_PASSAGES)
        throw new IllegalArgumentException("Flagged web evidence exceeds its review bounds");
      found.add(new Passage(found.size(), start, end, text));
    }

    private void requirePolicy(Policy expected, Intent intent, ReviewWork work) {
      requireActive(work);
      if (!expected
          .revision()
          .equals(reviews.policy(context, intent.operation().verb(), work).revision()))
        throw new IllegalStateException("Web security policy changed");
    }

    private void requireActive(ReviewWork work) {
      if (work.stopped()) throw new IllegalStateException("Web tool work ended");
      context.requireCurrent().run();
    }

    private void note(
        Bound tool,
        String id,
        String request,
        String policy,
        String verdict,
        String reason,
        String original,
        List<Span> redactions,
        String released) {
      note(
          tool.call,
          tool.schema().name(),
          id,
          request,
          policy,
          verdict,
          reason,
          original,
          redactions,
          released);
    }

    private void note(
        String call,
        String tool,
        String id,
        String request,
        String policy,
        String verdict,
        String reason,
        String original,
        List<Span> redactions,
        String released) {
      audit.accept(
          LoggedEntry.diagnostic(
              AUDIT_PREFIX
                  + json(
                      new Audit(
                          id,
                          call,
                          tool,
                          context.agent(),
                          context.model(),
                          request,
                          policy,
                          verdict,
                          reason,
                          original,
                          redactions,
                          released,
                          original == null ? null : "not_retained"))));
    }
  }

  final class Bound implements RetainedApprovalTool {
    private final AgentTool tool;
    private final Session session;
    private String call;
    private Held held;
    private WebEvidenceRelease release;
    private boolean withheld;
    private Plan plan;
    private RunApproval waiting;
    private String approvalId;
    private boolean notifyAfterCommit;

    private Bound(AgentTool tool, Session session) {
      this.tool = tool;
      this.session = session;
    }

    @Override
    public ToolSchema schema() {
      return tool.schema();
    }

    @Override
    public void calledAs(String id) {
      if (!id.equals(call)) beginInvocation(id);
    }

    /** A new assistant batch can reuse a wire ID. Retained execution keeps the active binding. */
    void beginInvocation(String id) {
      held = null;
      release = null;
      withheld = false;
      plan = null;
      waiting = null;
      approvalId = null;
      notifyAfterCommit = false;
      call = id;
    }

    @Override
    public String run(String arguments, Home home) {
      if (call == null || held != null) throw new IllegalStateException("Web call already used");
      if (plan == null) plan = session.prepare(this, arguments);
      if (plan.refusal() != null
          || plan.gate() != Gate.ALLOW
          || !plan.arguments().equals(arguments)) {
        String reason =
            plan.refusal() == null
                ? "SECURITY requires explicit authorization for this exact web request."
                : plan.refusal();
        held = Held.refusal(refuse(reason));
      } else {
        held = session.execute(this, plan);
      }
      withheld = held.withheld();
      return held.text();
    }

    @Override
    public ToolPre gate(
        String arguments,
        Home home,
        HookContext context,
        BiFunction<HookContext, String, ToolPre> chain) {
      ToolPre hook = chain.apply(context, arguments);
      if (hook.isDenied()) return hook;
      plan = session.prepare(this, hook.arguments());
      if (plan.refusal() != null)
        return new ToolPre(hook.arguments(), plan.refusal(), hook.records(), false);
      if (plan.gate() == Gate.ALLOW) return hook;
      if (session.ask != null) waiting = session.ask.apply(plan.display());
      return new ToolPre(
          hook.arguments(),
          waiting == null
              ? "SECURITY requires explicit authorization for this web request."
              : "Awaiting human web authorization [" + waiting.id() + "]",
          hook.records(),
          false);
    }

    @Override
    public Optional<RunApproval> waitingApproval() {
      return Optional.ofNullable(waiting);
    }

    @Override
    public boolean securityRejected() {
      return plan != null && (plan.refusal() != null || plan.gate() != Gate.ALLOW);
    }

    @Override
    public String retainedArguments(String arguments, Home home) {
      if (waiting == null || !plan.arguments().equals(arguments))
        throw new IllegalStateException("No exact web review is awaiting consent");
      return json(
          new Frozen(
              arguments,
              plan.policy().revision(),
              plan.auditId(),
              plan.work().remaining().toNanos()));
    }

    @Override
    public void retainedApproval(String id) {
      approvalId = id;
    }

    @Override
    public ToolPre gateRetained(
        String arguments,
        String side,
        Home home,
        HookContext context,
        BiFunction<HookContext, String, ToolPre> chain) {
      if (approvalId == null || !HookContext.SERVER.equals(side) || session.consume == null)
        return new ToolPre(
            arguments, "No exact retained web approval is available.", List.of(), false);
      try {
        var frozen = JSON.readValue(arguments, Frozen.class);
        ToolPre checked = chain.apply(context, frozen.arguments());
        if (checked.isDenied()) return checked;
        if (!frozen.arguments().equals(checked.arguments()))
          return new ToolPre(
              checked.arguments(), "The approved web request changed.", checked.records(), false);
        var request = WebToolRequest.parse(schema().name(), checked.arguments());
        filtering.inspect(request.operation().destination(), false, false);
        if (frozen.remainingNanos() <= 0 || frozen.remainingNanos() > deadline.toNanos())
          return new ToolPre(
              checked.arguments(),
              "The web review budget is no longer valid.",
              checked.records(),
              false);
        var work =
            new ReviewWork(
                Deadline.after(Duration.ofNanos(frozen.remainingNanos())), session.cancelled);
        session.requireActive(work);
        var policy = reviews.policy(session.context, request.operation().verb(), work);
        var prepared =
            new Plan(
                Gate.ALLOW,
                checked.arguments(),
                new Intent(request.operation(), request.justification(), session.heightened),
                policy,
                work,
                frozen.auditId(),
                WebEvidenceRelease.digest(json(request.operation())),
                null);
        if (!frozen.policy().equals(policy.revision())
            || !session.consume.test(
                new Consent(approvalId, prepared.display(), Instant.now().minus(approvalLifetime))))
          return new ToolPre(
              checked.arguments(),
              "The exact web approval or its policy changed.",
              checked.records(),
              false);
        plan = prepared;
        session.note(
            this,
            plan.auditId(),
            plan.requestDigest(),
            policy.revision(),
            "ALLOW",
            "human_authorization",
            null,
            List.of(),
            null);
        return checked;
      } catch (JsonProcessingException | RuntimeException rejected) {
        return new ToolPre(
            arguments, "Retained web approval could not be verified.", List.of(), false);
      }
    }

    Bound scripted() {
      return tool instanceof SearchTool search ? session.wrap(search.scripted()) : this;
    }

    WebResult scriptReceipt(String result) {
      if (held == null) held = Held.refusal(result);
      return new WebResult(
          WebEvidenceRelease.digest(result),
          held.policy() == null ? null : held.policy().revision(),
          auditId(),
          held.rule(),
          held.work() == null ? null : Instant.now().plus(held.work().remaining()),
          held.withheld(),
          held.notifyOwner());
    }

    String restoreScriptResult(String id, String arguments, String result, WebResult receipt) {
      calledAs(id);
      if (receipt == null || !WebEvidenceRelease.digest(result).equals(receipt.digest()))
        return withholdScriptResult();
      if (receipt.withheld()) {
        held = Held.refusal(result);
        return result;
      }
      try {
        var request = WebToolRequest.parse(schema().name(), arguments);
        Duration remaining = Duration.between(Instant.now(), receipt.expires());
        if (remaining.isZero() || remaining.isNegative()) return withholdScriptResult();
        var work =
            new ReviewWork(
                Deadline.after(remaining.compareTo(deadline) > 0 ? deadline : remaining),
                session.cancelled);
        session.requireActive(work);
        var policy = reviews.policy(session.context, request.operation().verb(), work);
        if (!policy.revision().equals(receipt.policy())) return withholdScriptResult();
        String inspected =
            receipt.rule() == null
                ? filtering.inspect(result, true, true)
                : filtering.inspectReleased(result, receipt.rule());
        if (!result.equals(inspected)) return withholdScriptResult();
        session.requireActive(work);
        held =
            new Held(
                result,
                receipt.rule(),
                receipt.reviewId(),
                WebEvidenceRelease.digest(json(request.operation())),
                policy,
                new Intent(request.operation(), request.justification(), session.heightened),
                work,
                false,
                receipt.notifyOwner());
        return result;
      } catch (RuntimeException stale) {
        return withholdScriptResult();
      }
    }

    private String withholdScriptResult() {
      withheld = true;
      held =
          Held.refusal(
              refuse("Web result withheld because its script review receipt is missing or stale."));
      return held.text();
    }

    String finish(String result, boolean hookWithheld) {
      if (held == null) return refuse(UNAVAILABLE);
      if (held.withheld()) return held.text();
      String delivered;
      try {
        if (hookWithheld) throw new IllegalStateException("Web evidence was withheld by tool.post");
        if (held.policy() != null) session.requirePolicy(held.policy(), held.intent(), held.work());
        if (held.rule() != null && held.text().equals(result)) {
          delivered = filtering.inspectReleased(result, held.rule());
          release =
              new WebEvidenceRelease(
                  call,
                  WebEvidenceRelease.digest(delivered),
                  held.rule(),
                  held.intent().operation().verb(),
                  held.policy().revision(),
                  Instant.now().plus(deadline),
                  session.heightened || held.notifyOwner()
                      ? session.context.usage().runs().id()
                      : null);
        } else {
          delivered = filtering.inspect(result, true, true);
        }
      } catch (LlmException | IllegalStateException refused) {
        withheld = true;
        delivered = refuse(UNAVAILABLE);
      }
      if (release != null)
        session.audit.accept(LoggedEntry.diagnostic(WebReleaseAudit.encode(release)));
      if (held.auditId() != null)
        session.note(
            this,
            held.auditId(),
            held.requestDigest(),
            held.policy().revision(),
            withheld ? "WITHHELD" : "RELEASED",
            "after_tool_post",
            null,
            List.of(),
            WebEvidenceRelease.digest(delivered));
      notifyAfterCommit = !withheld && held.notifyOwner();
      return delivered;
    }

    /** The caller acknowledges the recorded result, never just the provisional monitor verdict. */
    void resultRecorded() {
      if (!notifyAfterCommit) return;
      notifyAfterCommit = false;
      session.heightened = true;
      try {
        reviews.notify(session.context, held.auditId());
      } catch (RuntimeException deliveryFailed) {
        session.note(
            this,
            held.auditId(),
            held.requestDigest(),
            held.policy().revision(),
            "NOTIFY_FAILED",
            "notification_unavailable",
            null,
            List.of(),
            null);
      }
    }

    boolean withheld() {
      return withheld;
    }

    String auditId() {
      return held != null && held.auditId() != null
          ? held.auditId()
          : plan == null ? null : plan.auditId();
    }

    boolean approvalExpired(RunApproval question) {
      return !Instant.now().isBefore(question.createdAt().plus(approvalLifetime));
    }

    String currentPolicy(Verb verb) {
      var work = new ReviewWork(Deadline.after(deadline), session.cancelled);
      try {
        session.requireActive(work);
        return reviews.policy(session.context, verb, work).revision();
      } catch (RuntimeException unavailable) {
        return "";
      }
    }

    void restoreHeightened(WebEvidenceRelease release) {
      if (release.heightenedRun() != null
          && release.heightenedRun().equals(session.context.usage().runs().id()))
        session.heightened = true;
    }

    ChatMessage project(String id, String result) {
      WebEvidenceRelease exact =
          release != null
                  && release.call().equals(id)
                  && release.digest().equals(WebEvidenceRelease.digest(result))
              ? release
              : null;
      return new ChatMessage(
          ChatMessage.Role.TOOL, List.of(new Content.Text(result)), List.of(), id, exact);
    }

    ChatMessage projectRetained(String id, String result) {
      var projected = project(id, result);
      if (projected.webRelease() != null) return projected;
      // A crash can leave a completed approval row before its result was appended to the log.
      // Without an in-memory receipt, the next archive projection can pair the recorded result.
      try {
        return ChatMessage.tool(id, filtering.inspect(result, true, true));
      } catch (LlmException refused) {
        return ChatMessage.tool(id, refuse(UNAVAILABLE));
      }
    }

    String refuse(String reason) {
      return tool instanceof SearchTool search ? search.refused(reason) : "REFUSED: " + reason;
    }
  }

  /** A one-shot worker capability. The provider service owns transport and network controls. */
  private static final class WebReadWorker {
    private final Bound tool;
    private final WebToolRequest.Operation request;
    private boolean dispatched;

    private WebReadWorker(Bound tool, WebToolRequest.Operation request) {
      this.tool = tool;
      this.request = request;
    }

    private WebToolRequest.Result execute(WebRequestWork work) {
      if (dispatched) throw new IllegalStateException("Web request already dispatched");
      dispatched = true;
      return switch (request) {
        case WebToolRequest.Search search -> ((SearchTool) tool.tool).execute(search, work);
        case WebToolRequest.Fetch fetch -> ((FetchTool) tool.tool).execute(fetch, work);
      };
    }
  }

  private static List<Span> selected(List<Passage> passages, List<Integer> ids) {
    var unique = new HashSet<>(ids);
    if (unique.size() != ids.size()
        || unique.stream().anyMatch(id -> id < 0 || id >= passages.size()))
      throw new IllegalArgumentException("Invalid web redaction span");
    var spans = new ArrayList<Span>();
    for (Passage passage : passages) {
      if (!unique.contains(passage.id())) continue;
      if (!spans.isEmpty() && passage.start() < spans.getLast().end()) {
        Span previous = spans.removeLast();
        spans.add(new Span(previous.start(), Math.max(previous.end(), passage.end())));
      } else spans.add(new Span(passage.start(), passage.end()));
    }
    return List.copyOf(spans);
  }

  private static int characterBoundary(String text, int index) {
    return index > 0
            && index < text.length()
            && Character.isLowSurrogate(text.charAt(index))
            && Character.isHighSurrogate(text.charAt(index - 1))
        ? index - 1
        : index;
  }

  private static String redact(String original, List<Span> spans, boolean structured) {
    var result = new StringBuilder(original);
    String marker = "[passage withheld by SECURITY]";
    for (Span span : spans.reversed()) {
      result.replace(span.start(), span.end(), structured ? json(marker) : marker);
    }
    return result.toString();
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Web security record could not be encoded", invalid);
    }
  }

  private record Plan(
      Gate gate,
      String arguments,
      Intent intent,
      Policy policy,
      ReviewWork work,
      String auditId,
      String requestDigest,
      String refusal) {
    private static Plan refusal(String reason) {
      return new Plan(Gate.DENY, null, null, null, null, null, null, reason);
    }

    private WebApproval display() {
      return switch (intent.operation()) {
        case WebToolRequest.Search search ->
            new WebApproval(
                search.verb(),
                search.query(),
                search.page(),
                search.pageSize(),
                search.max(),
                null,
                requestDigest);
        case WebToolRequest.Fetch fetch ->
            new WebApproval(
                fetch.verb(), fetch.url(), null, null, null, fetch.offset(), requestDigest);
      };
    }
  }

  private record Frozen(String arguments, String policy, String auditId, long remainingNanos) {}

  private record Challenge(
      WebToolRequest.Operation operation,
      String policy,
      Instant expiresAt,
      String auditId,
      String call,
      String tool,
      String requestDigest) {}

  private record Span(int start, int end) {}

  private record Audit(
      String id,
      String call,
      String tool,
      String agent,
      String model,
      String requestDigest,
      String policy,
      String verdict,
      String reason,
      String originalDigest,
      List<Span> redactions,
      String releasedDigest,
      String withheldEvidence) {}

  private record Held(
      String text,
      String rule,
      String auditId,
      String requestDigest,
      Policy policy,
      Intent intent,
      ReviewWork work,
      boolean withheld,
      boolean notifyOwner) {
    private static Held refusal(String text) {
      return new Held(text, null, null, null, null, null, null, true, false);
    }
  }
}
