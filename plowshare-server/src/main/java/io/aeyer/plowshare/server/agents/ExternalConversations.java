package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.ProjectAuthorization.ExternalAdmission;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import java.util.List;

/**
 * Account-local external naming facade; aliases never grant authority or deduplicate utterances.
 */
public interface ExternalConversations {
  /** These labels are claims under the authenticated account, never an impersonation selector. */
  record Locator(String project, String source, String externalConversation) {
    public Locator {
      Home.of(project);
      if (source == null
          || source.length() > 128
          || !source.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))
        throw new IllegalArgumentException("Invalid external conversation source");
      if (externalConversation == null || !externalConversation.matches("[\\x21-\\x7e]{1,256}"))
        throw new IllegalArgumentException("Invalid external conversation identifier");
    }
  }

  /** Transport-supplied identity, separate from a run's optional workspace session. */
  record Caller(String account, String session, ExternalAdmission admission) {
    public Caller(String account, String session) {
      this(account, session, null);
    }

    public Caller {
      if (account == null || account.isBlank())
        throw new IllegalArgumentException("An authenticated account is required");
    }
  }

  record Open(Integer maxModelCalls, Integer maxTurns, Boolean noBudget, Boolean noTurnCap) {
    public Open {
      limits(maxModelCalls, maxTurns, noTurnCap);
      if (Boolean.TRUE.equals(noBudget) && maxModelCalls != null)
        throw new IllegalArgumentException("Choose a budget total or noBudget");
    }
  }

  record Run(
      String agent,
      String task,
      String session,
      Integer maxModelCalls,
      Integer maxTurns,
      Boolean noTurnCap,
      List<String> images) {
    public Run {
      if (agent == null || agent.isBlank() || task == null || task.isBlank())
        throw new IllegalArgumentException("An agent and task are required");
      limits(maxModelCalls, maxTurns, noTurnCap);
      images = images == null ? List.of() : List.copyOf(images);
      if (!images.isEmpty())
        throw new IllegalArgumentException("Images are not supported in conversation turns");
    }
  }

  private static void limits(Integer calls, Integer turns, Boolean noTurnCap) {
    if (calls != null && calls < 1
        || turns != null && (turns < 1 || Boolean.TRUE.equals(noTurnCap)))
      throw new IllegalArgumentException("Invalid or conflicting conversation limits");
  }

  /** Atomic get-or-open; creation options never change an existing mapping's allowance. */
  ConversationRecord open(Locator locator, Open options, Caller caller);

  /** Resolve-existing only. A missing alias cannot start a job or create a replacement root. */
  Runs.Started run(Locator locator, Run options, Caller caller);
}
