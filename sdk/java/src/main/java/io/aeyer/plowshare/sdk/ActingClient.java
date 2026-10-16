package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.ActingContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Subject proof exchange and immutable request selection on an existing Application connection. */
public final class ActingClient {
  private final Plowshare connection;

  public ActingClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  public ActingContext.Opened open(ActingContext.Open request) throws IOException {
    Objects.requireNonNull(request);
    return connection.request(
        "acting.context.open",
        Map.of(
            "application",
            request.application(),
            "grant",
            request.grant(),
            "proof",
            Map.of("kind", request.proof().kind(), "credential", request.proof().credential())),
        ActingContext.Opened.class);
  }

  public void close(ActingContext.Selection selection) throws IOException {
    connection
        .request("acting.context.close", Map.of("context", selection.context().toString()))
        .requirePayload();
  }

  /** Reads only; the selection never changes the identity of another concurrent call. */
  public List<HistoryTurn> turns(ActingContext.Selection selection, String conversation)
      throws IOException {
    Objects.requireNonNull(selection);
    conversation = ContractChecks.narrative(conversation, "conversation", 1024, true);
    var reply =
        connection.requestActing(
            "conversation.turns", Map.of("conversation", conversation), selection);
    return List.of(SdkJson.decode(SdkJson.mapper(), reply.requirePayload(), HistoryTurn[].class));
  }

  /** Submit once. A lost response is recovered through receipt(), never by mutation replay. */
  public TurnReceipt start(
      ActingContext.Selection selection,
      java.util.UUID requestId,
      String agent,
      String task,
      String conversation)
      throws IOException {
    Objects.requireNonNull(selection);
    Objects.requireNonNull(requestId);
    agent = ContractChecks.narrative(agent, "agent", 64, true);
    task = ContractChecks.narrative(task, "task", 65536, true);
    if (!agent.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        || task.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536
        || conversation != null
            && (conversation.length() > 128 || !conversation.matches("cnv_[A-Za-z0-9]+")))
      throw new IllegalArgumentException("Invalid acting turn request");
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("requestId", requestId.toString());
    payload.put("agent", agent);
    payload.put("task", task);
    if (conversation != null) payload.put("conversation", conversation);
    return decodeReceipt(
        connection.requestActing("acting.turn.start", payload, selection).requirePayload());
  }

  public TurnReceipt receipt(ActingContext.Selection selection, java.util.UUID requestId)
      throws IOException {
    Objects.requireNonNull(selection);
    Objects.requireNonNull(requestId);
    return decodeReceipt(
        connection
            .requestActing(
                "acting.turn.receipt", Map.of("requestId", requestId.toString()), selection)
            .requirePayload());
  }

  private static TurnReceipt decodeReceipt(com.fasterxml.jackson.databind.JsonNode payload)
      throws IOException {
    return SdkJson.decode(SdkJson.mapper(), payload, TurnReceipt.class);
  }

  public record TurnReceipt(
      java.util.UUID requestId, String conversation, String agent, String state, String job) {
    public TurnReceipt {
      Objects.requireNonNull(requestId);
      if (conversation == null
          || (conversation.length() > 128 || !conversation.matches("cnv_[A-Za-z0-9]+"))
          || agent == null
          || !agent.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
          || state == null
          || !java.util.Set.of("ADMITTED", "STARTED", "FINISHED", "UNCERTAIN").contains(state)
          || job != null && (job.length() > 128 || !job.matches("job_[A-Za-z0-9]+"))
          || ("STARTED".equals(state) || "FINISHED".equals(state)) && job == null)
        throw new IllegalArgumentException("Invalid acting turn receipt");
    }
  }

  public record HistoryTurn(
      int ordinal, String utterance, String answer, String ending, Integer promptTokens) {
    public HistoryTurn {
      if (ordinal < 1 || promptTokens != null && promptTokens < 1)
        throw new IllegalArgumentException("Invalid turn measurement");
      utterance = ContractChecks.narrative(utterance, "utterance", 1048576, true);
      answer = ContractChecks.narrative(answer, "answer", 1048576, false);
      ending = ContractChecks.narrative(ending, "ending", 64, true);
    }
  }
}
