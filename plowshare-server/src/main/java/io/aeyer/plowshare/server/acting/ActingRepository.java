package io.aeyer.plowshare.server.acting;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Specialist persistence for declared grants, live eligibility and human consent. Every mutation
 * joins an enclosing UnitOfWork. Lock administrators before deployment/project/token rows, matching
 * the existing account and Application identity lifecycle. No secret is retained or recoverable.
 */
public interface ActingRepository extends ActingPolicySource {
  /** Shared account lifecycle transaction lock; callers must hold it until mutation commit. */
  void lock();

  /** Exact acting credential digest lookup; never accepts an account or context ID as proof. */
  Optional<Token> proof(String digest);

  /**
   * Exchange audit commits before context admission; denied proofs contain no subject or secret.
   */
  void contextAudit(ContextAudit audit);

  enum ContextAction {
    OPEN,
    REFUSED,
    CLOSE
  }

  record ContextAudit(
      String actor,
      String application,
      String grant,
      ContextAction action,
      Optional<UUID> context,
      Optional<String> subject) {
    public ContextAudit {
      actor = ActingValues.serviceActor(actor);
      application = ActingValues.application(application);
      grant = ActingValues.account(grant);
      java.util.Objects.requireNonNull(action);
      java.util.Objects.requireNonNull(context);
      java.util.Objects.requireNonNull(subject);
      subject.ifPresent(ActingValues::account);
      if ((action == ContextAction.REFUSED) != context.isEmpty()
          || (action == ContextAction.REFUSED) != subject.isEmpty())
        throw new IllegalArgumentException("Invalid context audit metadata");
    }
  }

  boolean administrator(String account);

  /** Replace declarations atomically with the active source head; retain withdrawn rows inert. */
  void activate(String application, UUID revision, List<Grant> grants, String actor);

  void eligibility(ActingTokens.Eligibility request);

  void insert(Token token, String digest, Optional<String> externalSubject, Instant createdAt);

  void revoke(UUID token, Instant revokedAt);

  List<Token> tokens(String subject);

  Optional<Submission> receipt(UUID requestId);

  /** Retain the receipt and successful mutation audit together in the caller's transaction. */
  void complete(Submission submission);

  record Submission(String actor, String fingerprint, ActingTokens.Receipt receipt) {
    public Submission {
      actor = ActingValues.account(actor);
      if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid acting request fingerprint");
      java.util.Objects.requireNonNull(receipt, "receipt");
    }
  }
}
