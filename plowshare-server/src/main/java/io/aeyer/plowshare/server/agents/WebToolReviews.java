package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.approvals.ReviewWork;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.List;
import java.util.Objects;

/** Tool-free request and evidence review under the requesting execution's authority. */
interface WebToolReviews {
  enum Gate {
    ALLOW,
    ASK_WHY,
    ASK_HUMAN,
    DENY
  }

  enum Verdict {
    APPROVE,
    APPROVE_AND_NOTIFY,
    APPROVE_WITH_REDACTION,
    REJECT
  }

  record Context(
      String task,
      String project,
      String agent,
      String model,
      String owner,
      String conversation,
      OperationAuthority.Admission authority,
      Budget budget,
      UsageAttribution usage,
      Runnable requireCurrent) {}

  record Policy(String deployment, String workspace, String revision) {}

  record Intent(
      WebToolRequest.Operation operation,
      String justification,
      boolean heightened,
      String redirectedFrom) {
    Intent(WebToolRequest.Operation operation, String justification, boolean heightened) {
      this(operation, justification, heightened, null);
    }
  }

  record Passage(int id, int start, int end, String text) {}

  record ExceptionEvidence(String rule, String digest, List<Passage> passages) {
    public ExceptionEvidence {
      passages = List.copyOf(passages);
    }
  }

  record Decision(Verdict verdict, List<Integer> redactions) {
    public Decision {
      Objects.requireNonNull(verdict);
      redactions = List.copyOf(redactions);
      if ((verdict == Verdict.APPROVE_WITH_REDACTION) != !redactions.isEmpty()
          || redactions.size() > 32)
        throw new IllegalArgumentException("Invalid web security verdict");
    }
  }

  Policy policy(Context context, Verb verb, ReviewWork work);

  Gate gate(Context context, Policy policy, Intent intent, ReviewWork work);

  Decision monitor(
      Context context, Policy policy, Intent intent, ExceptionEvidence exception, ReviewWork work);

  /** Deliver a safe notification keyed by the retained audit ID. */
  default void notify(Context context, String auditId) {}
}
