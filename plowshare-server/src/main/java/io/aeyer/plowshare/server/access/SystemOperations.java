package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.UUID;

/**
 * Trusted, existing model-only service purposes. This interface is never exposed by a public API.
 */
public interface SystemOperations {
  enum Purpose {
    SEMANTIC_QUERY,
    MEMORY_WRITE,
    DOCUMENT_PREPARATION,
    DOCUMENT_ASK,
    EMBEDDING_REPAIR,
    CONVERSATION_COMPACTION,
    SKILL_SUMMARY,
    SCRIBE,
    LEARNING,
    MEMORY_DIGEST,
    MEMORY_CURATION,
    STUCK_ADVISOR,
    CALL_VALIDATOR,
    COMMAND_JUDGE,
    SCHEDULE_READER
  }

  interface Work {
    UUID id();

    Home home();

    void requireLog(Home home, String agent);

    void require();

    void requireTask(Home home, AgentDefinition definition, String task);

    CallAuthorization claimed(ChatRequest request, Budget.Claim claim);

    /** Closed embedding purposes; this carriage never grants tools or caller execution. */
    default CallAuthorization claimed(EmbeddingRequest request, Budget.Claim claim) {
      throw new IllegalStateException("This SYSTEM purpose does not support embedding");
    }
  }

  /**
   * Binds a declared service to immutable input evidence and an existing operation validity fence.
   */
  Work admit(
      Purpose purpose, Home home, AgentDefinition definition, String binding, Runnable validity);

  Work admit(
      Purpose purpose,
      Home home,
      AuthorityIds.ScopeId scope,
      AgentDefinition definition,
      String binding,
      Runnable validity);

  /** One existing consult, spending its explicitly supplied configured allowance. */
  default ChatRequest authorize(
      Purpose purpose,
      Home home,
      AgentDefinition definition,
      ChatRequest request,
      Budget budget,
      Runnable validity) {
    var work = admit(purpose, home, definition, request.messages().toString(), validity);
    var claim =
        budget
            .tryClaim()
            .orElseThrow(() -> new IllegalStateException("SYSTEM operation allowance exhausted"));
    if ((purpose == Purpose.CONVERSATION_COMPACTION || purpose == Purpose.SKILL_SUMMARY)
        && claim.conversation() == null)
      throw new IllegalStateException(
          "Compaction requires the retained durable conversation allowance");
    return request.withAuthorization(work.claimed(request, claim));
  }
}
