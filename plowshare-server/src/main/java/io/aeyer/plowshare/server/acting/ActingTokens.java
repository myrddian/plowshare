package io.aeyer.plowshare.server.acting;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Human-owned consent lifecycle. Callers supply authenticated direct authority, never a submitted
 * subject. Mutations and metadata receipts commit together; credentials are returned once only. A
 * lost issue reply must be reconciled through receipt(), then explicitly revoked/replaced.
 */
public interface ActingTokens {
  Credential issue(RequestAuthority.Direct caller, Issue request);

  Receipt revoke(RequestAuthority.Direct caller, UUID token, UUID requestId);

  Receipt eligibility(RequestAuthority.Direct caller, Eligibility request);

  Optional<Receipt> receipt(RequestAuthority.Direct caller, UUID requestId);

  /** Latest 256 tokens owned by this human, including expired and revoked metadata. */
  List<ActingPolicySource.Token> tokens(RequestAuthority.Direct caller);

  record Issue(
      String application,
      String grant,
      ActingScope scope,
      int expiresInDays,
      Optional<String> externalSubject,
      UUID requestId) {
    public Issue {
      application = ActingValues.application(application);
      grant = ActingValues.account(grant);
      Objects.requireNonNull(scope, "scope");
      if (scope.projects().isEmpty() && !scope.personal())
        throw new IllegalArgumentException("Choose at least one acting scope");
      if (expiresInDays < 1 || expiresInDays > 365)
        throw new IllegalArgumentException("Choose an expiry between 1 and 365 days");
      externalSubject = Objects.requireNonNull(externalSubject, "externalSubject");
      externalSubject.ifPresent(
          value -> {
            if (value.isBlank()
                || value.length() > 512
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl))
              throw new IllegalArgumentException("Choose a bounded external subject");
          });
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  /** Eligibility is a live union of explicit human accounts and current project memberships. */
  record Eligibility(
      String application,
      String grant,
      Set<String> accounts,
      Set<String> memberProjects,
      UUID requestId) {
    public Eligibility {
      application = ActingValues.application(application);
      grant = ActingValues.account(grant);
      accounts = Set.copyOf(accounts);
      memberProjects = Set.copyOf(memberProjects);
      if (accounts.size() > 256 || memberProjects.size() > 256)
        throw new IllegalArgumentException("Eligibility lists are bounded to 256 entries");
      accounts.forEach(ActingValues::subject);
      memberProjects.forEach(ActingValues::application);
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  enum Action {
    ISSUE,
    REVOKE,
    ELIGIBILITY
  }

  /** Retained outcome metadata contains neither credentials nor their digests. */
  record Receipt(
      UUID requestId,
      Action action,
      String application,
      String grant,
      Optional<UUID> token,
      Instant completedAt) {
    public Receipt {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(action, "action");
      application = ActingValues.application(application);
      grant = ActingValues.account(grant);
      token = Objects.requireNonNull(token, "token");
      if ((action == Action.ELIGIBILITY) != token.isEmpty())
        throw new IllegalArgumentException("Receipt token does not match its action");
      Objects.requireNonNull(completedAt, "completedAt");
    }
  }

  /** One-time output; logging this value redacts the credential. */
  record Credential(ActingPolicySource.Token token, Receipt receipt, String credential) {
    public Credential {
      Objects.requireNonNull(token, "token");
      Objects.requireNonNull(receipt, "receipt");
      if (credential == null || !credential.matches("psa_[0-9a-f]{48}"))
        throw new IllegalArgumentException("Invalid acting credential");
    }

    @Override
    public String toString() {
      return "ActingCredential[token=" + token.id() + ", credential=REDACTED]";
    }
  }
}
