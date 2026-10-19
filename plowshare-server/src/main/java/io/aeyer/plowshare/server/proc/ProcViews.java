package io.aeyer.plowshare.server.proc;

import java.time.Instant;
import java.util.List;

/** Content-free audit DTOs. Null measurements mean unknown, never an inferred zero. */
public final class ProcViews {
  private ProcViews() {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Conversation(
      String id,
      String root,
      String parent,
      String origin,
      String lifecycle,
      String project,
      String owner,
      String application,
      String actor,
      String actingSubject,
      String agent,
      String principal,
      String cause,
      String resource,
      String revision,
      String causationRoot,
      String causationParent,
      Integer causationDepth,
      Instant createdAt,
      Instant lastTurnAt,
      String state,
      String evidence,
      String latestJob,
      String ending,
      long children,
      long calls,
      long attempts,
      long activeCalls,
      long repeatedCalls) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Conversations(
      Instant snapshotAt,
      ProcQuery selection,
      List<Conversation> conversations,
      long total,
      String cursor,
      List<String> coverage) {
    public Conversations {
      conversations = List.copyOf(conversations);
      coverage = List.copyOf(coverage);
    }
  }

  /** Counts are item counts for the named unit and stage, not provider attempt counts. */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Queue(
      String id,
      String unit,
      String stage,
      String project,
      String owner,
      String conversation,
      String revision,
      String resource,
      long total,
      long completed,
      long pending,
      Long running,
      long blocked,
      long failed,
      long skipped,
      Instant oldestPendingAt,
      Instant leaseUntil,
      String coverage) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Queues(
      Instant snapshotAt,
      ProcQuery selection,
      List<Queue> queues,
      List<Queue> summaries,
      List<Pool> pools,
      String cursor,
      List<String> coverage) {
    public Queues {
      queues = List.copyOf(queues);
      summaries = List.copyOf(summaries);
      pools = List.copyOf(pools);
      coverage = List.copyOf(coverage);
    }
  }

  public record Pool(String name, String lane, int queued, int running, int capacity) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Tool(
      String id, String conversation, String job, String name, String state, Instant recordedAt) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Tools(
      Instant snapshotAt,
      ProcQuery selection,
      List<Tool> tools,
      String cursor,
      List<String> coverage) {
    public Tools {
      tools = List.copyOf(tools);
      coverage = List.copyOf(coverage);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Call(
      String id,
      String conversation,
      String root,
      String run,
      String owner,
      String principal,
      String project,
      String agent,
      String operation,
      String pool,
      String model,
      String lane,
      String route,
      String runtime,
      String state,
      Instant createdAt,
      Instant endedAt,
      int attempts,
      List<Attempt> observations) {
    public Call {
      observations = List.copyOf(observations);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Attempt(
      String id,
      int number,
      String state,
      Instant startedAt,
      Instant endedAt,
      Integer httpStatus,
      String finishReason,
      String inputTokens,
      String outputTokens,
      String costKind,
      String costAmount,
      String currency) {}

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Calls(
      Instant snapshotAt,
      ProcQuery selection,
      List<Call> calls,
      String cursor,
      List<String> coverage) {
    public Calls {
      calls = List.copyOf(calls);
      coverage = List.copyOf(coverage);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Inspection(
      Conversations conversations,
      Queues queues,
      Calls calls,
      List<Tool> tools,
      boolean toolsTruncated,
      List<String> signals) {
    public Inspection {
      tools = List.copyOf(tools);
      signals = List.copyOf(signals);
    }
  }
}
