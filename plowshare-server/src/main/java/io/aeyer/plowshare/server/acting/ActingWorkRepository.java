package io.aeyer.plowshare.server.acting;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns retained Personal bindings and admission receipts. Mutations inside admission join the
 * consent transaction and its shared lock. Job identity must be committed before execution;
 * completion and uncertainty updates are independent, monotonic writes. Receipts never retain tasks
 * or credentials.
 */
public interface ActingWorkRepository {
  record Binding(String conversation, RequestAuthority.Delegated authority) {
    public Binding {
      java.util.Objects.requireNonNull(authority);
      if (conversation == null
          || (conversation.length() > 128 || !conversation.matches("cnv_[A-Za-z0-9]+")))
        throw new IllegalArgumentException("invalid conversation");
      if (!authority.delegation().scope().personal()
          || !authority.delegation().scope().projects().isEmpty())
        throw new IllegalArgumentException("Personal-only authority required");
    }
  }

  record Admission(Binding binding, String fingerprint, ActingWork.Receipt receipt) {
    public Admission {
      java.util.Objects.requireNonNull(binding);
      java.util.Objects.requireNonNull(receipt);
      if (fingerprint == null
          || !fingerprint.matches("[0-9a-f]{64}")
          || !binding.conversation().equals(receipt.conversation()))
        throw new IllegalArgumentException("invalid admission");
    }
  }

  void lock();

  String open(String subject, Integer budget);

  Optional<Binding> binding(String conversation);

  /** Checks current owning conversation/project records, including Personal ownership. */
  boolean owns(Binding binding);

  /** Only active owning conversations admit or continue work; history remains readable. */
  boolean active(Binding binding);

  void bind(Binding binding);

  Optional<Admission> receipt(String actor, UUID requestId);

  Optional<Admission> job(String job);

  void admit(Admission admission);

  void started(String actor, UUID requestId, String job);

  void finished(String actor, UUID requestId);

  /** May follow an uncertain submit; never implies that execution did not happen. */
  void uncertain(String actor, UUID requestId);
}
