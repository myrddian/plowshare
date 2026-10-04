package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.search.SearchPage;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ServerClient {

  record Navigation(
      String level, List<String> ids, String text, boolean complete, int modelCalls) {}

  default Navigation navigateMemory(String project, String question) throws IOException {
    throw new IOException("This client transport does not implement memory navigation");
  }

  default StartedJob digestMemory(String project) throws IOException {
    throw new IOException("This client transport does not implement memory digest building");
  }

  default SearchPage search(String query, int pageSize, int max, int page) throws IOException {
    throw new IOException("This client transport does not implement search");
  }

  default FetchWindow fetch(String url, int offset) throws IOException {
    throw new IOException("This client transport does not implement fetch");
  }

  default Object information(String operation, java.util.Map<String, Object> payload)
      throws IOException {
    throw new IOException("This client transport does not implement information WebSocket frames");
  }

  String baseUrl();

  WriteResult write(String project, MemoryProposal proposal) throws IOException;

  Recall recall(String project, String question, Integer limit) throws IOException;

  Memory read(String id) throws IOException;

  List<IndexEntry> index(String project) throws IOException;

  DocumentSearch searchDocuments(String query, Integer limit) throws IOException;

  record DocumentSearch(
      String query, int limit, List<DocumentHit> hits, int searchable, int unsearchable) {}

  record DocumentHit(
      UUID chunkId,
      String text,
      double similarity,
      UUID paragraphId,
      String paragraphText,
      int paragraphOrdinal,
      UUID documentId,
      String sourceName,
      String title) {}

  Retrieved retrieve(String query, String documentId, Integer limit) throws IOException;

  record Retrieved(String query, UUID document, int limit, List<RetrievedHit> hits) {}

  record RetrievedHit(double score, ChunkDetail chunk) {}

  record ChunkDetail(
      UUID chunkId,
      String text,
      UUID paragraphId,
      int paragraphOrdinal,
      String paragraphSummary,
      Unit section,
      Unit chapter,
      UUID documentId,
      String sourceName,
      String title,
      String documentSummary) {}

  record Unit(UUID id, String title, boolean synthetic, String summary) {}

  Ranking rankDocuments(String query, Integer limit) throws IOException;

  record Ranking(
      String query, int limit, List<RankedDocument> documents, int rankable, int unranked) {}

  record RankedDocument(
      UUID documentId,
      String sourceName,
      String title,
      String summary,
      String ingestedAt,
      double score) {}

  Stance documentStance(String documentId, String claim) throws IOException;

  record Stance(UUID documentId, String claim, double topical, double stance, String basis) {}

  DocumentPage listDocuments(String naming, Integer limit, Integer offset) throws IOException;

  record DocumentPage(
      List<DocumentRow> documents, int total, int limit, int offset, String naming) {}

  record DocumentRow(
      UUID documentId,
      String sourceName,
      String title,
      String summary,
      String vocabulary,
      String ingestedAt,
      String ingestedBy,
      long byteSize,
      int chapters,
      int sections,
      int paragraphs,
      int chunks) {}

  DocumentOutline describeDocument(String documentId) throws IOException;

  record DocumentOutline(
      UUID documentId,
      String sourceName,
      String title,
      String summary,
      String vocabulary,
      String ingestedAt,
      String ingestedBy,
      long byteSize,
      List<ChapterOutline> chapters) {}

  record ChapterOutline(
      UUID id, String title, boolean synthetic, String summary, List<Unit> sections) {}

  Citations citations(String conversationId, String documentId, Integer limit) throws IOException;

  record Citations(String scope, int limit, List<Citation> citations) {}

  record Citation(
      UUID id,
      String standing,
      UUID paragraphId,
      UUID documentId,
      String sourceName,
      int paragraphOrdinal,
      String title,
      String paragraphText,
      String conversationId,
      Integer turnOrdinal,
      String agent,
      String citedAt) {}

  StartedJob run(String agent, String task, String project, String session, String conversation)
      throws IOException;

  record UploadedImage(String id, String project, String format) {}

  UploadedImage uploadImage(String project, String filename, byte[] bytes) throws IOException;

  Conversation openConversation(String project, Integer maxModelCalls) throws IOException;

  List<Conversation> conversations(String project) throws IOException;

  Entries chat(String conversationId, Integer offset, Integer limit) throws IOException;

  Entries trajectory(String conversationId, Integer offset, Integer limit) throws IOException;

  LogHits searchEntries(String project, String query, Integer offset, Integer limit)
      throws IOException;

  Context context(String conversationId, String agent) throws IOException;

  List<Seam> compactions(String conversationId) throws IOException;

  StartedJob curate(String project, Integer maxModelCalls) throws IOException;

  StartedJob askDocument(String documentId, String question, Integer maxModelCalls)
      throws IOException;

  JobStatus job(String id) throws IOException;

  JobStatus cancelJob(String id) throws IOException;

  ProjectView defineProject(String name, String workspace, List<String> exclusions)
      throws IOException;

  ProjectView lendProject(String name, List<String> roots) throws IOException;

  ProjectView unlendProject(String name, List<String> roots) throws IOException;

  ProjectView setProjectWorkspace(String name, String workspace) throws IOException;

  void moveProject(String name, String to) throws IOException;

  void forgetProject(String name) throws IOException;

  List<ProposalRow> proposals(String project) throws IOException;

  Resolution resolve(String id, boolean accept, String reason, String by) throws IOException;

  record StartedJob(String id, String agent, String revision, String conversation) {
    public StartedJob(String id, String agent) {
      this(id, agent, null, null);
    }
  }

  record Conversation(
      String id,
      String project,
      Integer maxModelCalls,
      Integer modelCallsSpent,
      Integer maxTurns,
      boolean noTurnCap,
      boolean noBudget,
      String title) {
    public Conversation(String id, String project, int maxModelCalls) {
      this(id, project, maxModelCalls, null, null, false, false, null);
    }
  }

  record Seam(int throughOrdinal, String summary) {}

  record Entries(List<Entry> entries, int total, int offset, int limit) {}

  record Entry(
      int ordinal,
      int turnOrdinal,
      String kind,
      String excerpt,
      int length,
      boolean cut,
      Integer supersededBy,
      String toolCallId,
      List<Asked> toolCalls,
      String handle,
      Instant recordedAt,
      Long tookMillis,
      Instant ejectedAt) {}

  record Asked(String id, String name, String arguments, int length, boolean cut) {}

  record LogHits(List<LogHit> hits, int total, int offset, int limit, Reach reach) {}

  record LogHit(
      String conversationId,
      int ordinal,
      int turnOrdinal,
      String kind,
      double rank,
      String snippet,
      int length,
      Integer supersededBy,
      String handle,
      Instant recordedAt) {}

  record Reach(int searched, int ejected, int recordedOnly) {}

  record Context(
      Integer sent,
      Integer sentAtTurn,
      int turns,
      int turnsMeasured,
      Integer systemPromptTokens,
      Integer toolTokens,
      Integer messageTokens,
      Double cacheHitRate,
      List<Unavailable> unavailable,
      Prefix prefix) {}

  record Unavailable(String component, String reason) {}

  record Prefix(
      String agent,
      String model,
      int systemPromptCharacters,
      int toolCharacters,
      List<ToolCost> tools) {}

  record ToolCost(String name, int characters) {}

  record ProjectView(String name, String workspace, List<String> lent, List<String> exclusions) {}

  record JobStatus(
      String id,
      String agent,
      String state,
      boolean cancelRequested,
      RunOutcome outcome,
      String conversation,
      Limits limits) {
    public JobStatus(
        String id, String agent, String state, boolean cancelRequested, RunOutcome outcome) {
      this(id, agent, state, cancelRequested, outcome, null, null);
    }
  }

  record Limits(
      Integer maxTurns,
      boolean noTurnCap,
      Integer maxModelCalls,
      boolean noBudget,
      int modelCallsSpent) {}

  record Pace(
      int toolCalls,
      Integer completionTokens,
      Integer reasoningTokens,
      Long firstTokenMillis,
      Double tokensPerSecond,
      boolean reasoningEstimated) {}

  record RunOutcome(
      String ending,
      boolean answered,
      String text,
      int steps,
      int modelCalls,
      String detail,
      boolean resumable,
      Pace pace) {
    public RunOutcome(
        String ending, boolean answered, String text, int steps, int modelCalls, String detail) {
      this(ending, answered, text, steps, modelCalls, detail, false, null);
    }
  }

  record ProposalRow(
      String id,
      String memoryId,
      String project,
      String action,
      String reason,
      String state,
      Instant createdAt,
      String proposedBy,
      Instant resolvedAt,
      String resolvedBy,
      String resolution) {}

  record Resolution(ProposalRow proposal, String promotedId, List<String> demoted) {}

  record Recall(List<Memory> memories, int unsearchable) {}

  record IndexEntry(String id, String summary, String scope, boolean unsearchable) {}

  final class ServerError extends RuntimeException {

    private final int status;

    public ServerError(int status, String detail) {
      super("the Plowshare server answered " + status + ": " + detail);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
