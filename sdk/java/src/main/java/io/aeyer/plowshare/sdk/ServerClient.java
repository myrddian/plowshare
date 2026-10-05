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

  record Navigation(String level, List<String> ids, String text, boolean complete, int modelCalls) {
    public Navigation {
      level = ContractChecks.identity(level, "level");
      text = ContractChecks.narrative(text, "text", 1048576, false);
      ContractChecks.nonnegative(modelCalls, "modelCalls");
      ContractChecks.collection(ids, "ids", 10000);
      if (ids != null)
        ids = ids.stream().map(value -> ContractChecks.identity(value, "ids")).toList();
      ids = List.copyOf(java.util.Objects.requireNonNull(ids, "ids"));
    }
  }

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

  default io.aeyer.plowshare.protocol.InformationResponse information(
      io.aeyer.plowshare.protocol.InformationRequest request) throws IOException {
    throw new IOException("This client transport does not implement information WebSocket frames");
  }

  String baseUrl();

  WriteResult write(String project, MemoryProposal proposal) throws IOException;

  Recall recall(String project, String question, Integer limit) throws IOException;

  Memory read(String id) throws IOException;

  List<IndexEntry> index(String project) throws IOException;

  DocumentSearch searchDocuments(String query, Integer limit) throws IOException;

  record DocumentSearch(
      String query, int limit, List<DocumentHit> hits, int searchable, int unsearchable) {
    public DocumentSearch {
      query = ContractChecks.narrative(query, "query", 32768, true);
      ContractChecks.nonnegative(limit, "limit");
      ContractChecks.nonnegative(searchable, "searchable");
      ContractChecks.nonnegative(unsearchable, "unsearchable");
      ContractChecks.collection(hits, "hits", 10000);
      hits = List.copyOf(java.util.Objects.requireNonNull(hits, "hits"));
    }
  }

  record DocumentHit(
      UUID chunkId,
      String text,
      double similarity,
      UUID paragraphId,
      String paragraphText,
      int paragraphOrdinal,
      UUID documentId,
      String sourceName,
      String title) {
    public DocumentHit {
      text = ContractChecks.narrative(text, "text", 1048576, false);
      paragraphText = ContractChecks.narrative(paragraphText, "paragraphText", 1048576, false);
      ContractChecks.nonnegative(paragraphOrdinal, "paragraphOrdinal");
      sourceName = ContractChecks.narrative(sourceName, "sourceName", 1024, false);
      title = ContractChecks.narrative(title, "title", 32768, false);
      ContractChecks.finite(similarity, "similarity");
    }
  }

  Retrieved retrieve(String query, String documentId, Integer limit) throws IOException;

  record Retrieved(String query, UUID document, int limit, List<RetrievedHit> hits) {
    public Retrieved {
      query = ContractChecks.narrative(query, "query", 32768, true);
      ContractChecks.nonnegative(limit, "limit");
      ContractChecks.collection(hits, "hits", 10000);
      hits = List.copyOf(java.util.Objects.requireNonNull(hits, "hits"));
    }
  }

  record RetrievedHit(double score, ChunkDetail chunk) {
    public RetrievedHit {
      ContractChecks.finite(score, "score");
    }
  }

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
      String documentSummary) {
    public ChunkDetail {
      text = ContractChecks.narrative(text, "text", 1048576, false);
      ContractChecks.nonnegative(paragraphOrdinal, "paragraphOrdinal");
      paragraphSummary =
          ContractChecks.narrative(paragraphSummary, "paragraphSummary", 1048576, false);
      sourceName = ContractChecks.narrative(sourceName, "sourceName", 1024, false);
      title = ContractChecks.narrative(title, "title", 32768, false);
      documentSummary =
          ContractChecks.narrative(documentSummary, "documentSummary", 1048576, false);
    }
  }

  record Unit(UUID id, String title, boolean synthetic, String summary) {
    public Unit {
      title = ContractChecks.narrative(title, "title", 32768, false);
      summary = ContractChecks.narrative(summary, "summary", 32768, false);
    }
  }

  Ranking rankDocuments(String query, Integer limit) throws IOException;

  record Ranking(
      String query, int limit, List<RankedDocument> documents, int rankable, int unranked) {
    public Ranking {
      query = ContractChecks.narrative(query, "query", 32768, true);
      ContractChecks.nonnegative(limit, "limit");
      ContractChecks.nonnegative(rankable, "rankable");
      ContractChecks.nonnegative(unranked, "unranked");
      ContractChecks.collection(documents, "documents", 10000);
      documents = List.copyOf(java.util.Objects.requireNonNull(documents, "documents"));
    }
  }

  record RankedDocument(
      UUID documentId,
      String sourceName,
      String title,
      String summary,
      String ingestedAt,
      double score) {
    public RankedDocument {
      sourceName = ContractChecks.narrative(sourceName, "sourceName", 1024, false);
      title = ContractChecks.narrative(title, "title", 32768, false);
      summary = ContractChecks.narrative(summary, "summary", 32768, false);
      ingestedAt = ContractChecks.timestamp(ingestedAt, "ingestedAt");
      ContractChecks.finite(score, "score");
    }
  }

  Stance documentStance(String documentId, String claim) throws IOException;

  record Stance(UUID documentId, String claim, double topical, double stance, String basis) {
    public Stance {
      claim = ContractChecks.narrative(claim, "claim", 32768, true);
      basis = ContractChecks.narrative(basis, "basis", 1048576, false);
      ContractChecks.finite(topical, "topical");
      ContractChecks.finite(stance, "stance");
    }
  }

  DocumentPage listDocuments(String naming, Integer limit, Integer offset) throws IOException;

  record DocumentPage(
      List<DocumentRow> documents, int total, int limit, int offset, String naming) {
    public DocumentPage {
      ContractChecks.nonnegative(total, "total");
      ContractChecks.nonnegative(limit, "limit");
      ContractChecks.nonnegative(offset, "offset");
      naming = ContractChecks.optionalIdentity(naming, "naming");
      ContractChecks.collection(documents, "documents", 10000);
      documents = List.copyOf(java.util.Objects.requireNonNull(documents, "documents"));
    }
  }

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
      int chunks) {
    public DocumentRow {
      sourceName = ContractChecks.narrative(sourceName, "sourceName", 1024, false);
      title = ContractChecks.narrative(title, "title", 32768, false);
      summary = ContractChecks.narrative(summary, "summary", 32768, false);
      vocabulary = ContractChecks.optionalIdentity(vocabulary, "vocabulary");
      ingestedAt = ContractChecks.timestamp(ingestedAt, "ingestedAt");
      ingestedBy = ContractChecks.optionalIdentity(ingestedBy, "ingestedBy");
      ContractChecks.nonnegative(byteSize, "byteSize");
      ContractChecks.nonnegative(chapters, "chapters");
      ContractChecks.nonnegative(sections, "sections");
      ContractChecks.nonnegative(paragraphs, "paragraphs");
      ContractChecks.nonnegative(chunks, "chunks");
    }
  }

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
      List<ChapterOutline> chapters) {
    public DocumentOutline {
      sourceName = ContractChecks.narrative(sourceName, "sourceName", 1024, false);
      title = ContractChecks.narrative(title, "title", 32768, false);
      summary = ContractChecks.narrative(summary, "summary", 32768, false);
      vocabulary = ContractChecks.optionalIdentity(vocabulary, "vocabulary");
      ingestedAt = ContractChecks.timestamp(ingestedAt, "ingestedAt");
      ingestedBy = ContractChecks.optionalIdentity(ingestedBy, "ingestedBy");
      ContractChecks.nonnegative(byteSize, "byteSize");
      ContractChecks.collection(chapters, "chapters", 10000);
      chapters = List.copyOf(java.util.Objects.requireNonNull(chapters, "chapters"));
    }
  }

  record ChapterOutline(
      UUID id, String title, boolean synthetic, String summary, List<Unit> sections) {
    public ChapterOutline {
      title = ContractChecks.narrative(title, "title", 32768, false);
      summary = ContractChecks.narrative(summary, "summary", 32768, false);
      ContractChecks.collection(sections, "sections", 10000);
      sections = List.copyOf(java.util.Objects.requireNonNull(sections, "sections"));
    }
  }

  Citations citations(String conversationId, String documentId, Integer limit) throws IOException;

  record Citations(String scope, int limit, List<Citation> citations) {
    public Citations {
      scope = ContractChecks.narrative(scope, "scope", 4096, true);
      ContractChecks.nonnegative(limit, "limit");
      ContractChecks.collection(citations, "citations", 10000);
      citations = List.copyOf(java.util.Objects.requireNonNull(citations, "citations"));
    }
  }

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
      String citedAt) {
    public Citation {
      standing = ContractChecks.optionalIdentity(standing, "standing");
      sourceName = ContractChecks.narrative(sourceName, "sourceName", 1024, false);
      ContractChecks.nonnegative(paragraphOrdinal, "paragraphOrdinal");
      title = ContractChecks.narrative(title, "title", 32768, false);
      paragraphText = ContractChecks.narrative(paragraphText, "paragraphText", 1048576, false);
      conversationId = ContractChecks.optionalIdentity(conversationId, "conversationId");
      ContractChecks.nonnegative(turnOrdinal, "turnOrdinal");
      agent = ContractChecks.optionalIdentity(agent, "agent");
      citedAt = ContractChecks.timestamp(citedAt, "citedAt");
    }
  }

  StartedJob run(String agent, String task, String project, String session, String conversation)
      throws IOException;

  record UploadedImage(String id, String project, String format) {
    public UploadedImage {
      id = ContractChecks.optionalIdentity(id, "id");
      project = ContractChecks.optionalIdentity(project, "project");
      format = ContractChecks.optionalIdentity(format, "format");
      id = ContractChecks.identity(id, "id");
      format = ContractChecks.identity(format, "format");
    }
  }

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
    public StartedJob {
      id = ContractChecks.optionalIdentity(id, "id");
      agent = ContractChecks.optionalIdentity(agent, "agent");
      revision = ContractChecks.optionalIdentity(revision, "revision");
      conversation = ContractChecks.optionalIdentity(conversation, "conversation");
      id = ContractChecks.identity(id, "id");
      agent = ContractChecks.identity(agent, "agent");
    }

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
      @com.fasterxml.jackson.annotation.JsonProperty(defaultValue = "false") boolean noTurnCap,
      @com.fasterxml.jackson.annotation.JsonProperty(defaultValue = "false") boolean noBudget,
      String title) {
    public Conversation {
      id = ContractChecks.optionalIdentity(id, "id");
      project = ContractChecks.optionalIdentity(project, "project");
      ContractChecks.nonnegative(maxModelCalls, "maxModelCalls");
      ContractChecks.nonnegative(modelCallsSpent, "modelCallsSpent");
      ContractChecks.nonnegative(maxTurns, "maxTurns");
      title = ContractChecks.narrative(title, "title", 32768, false);
      id = ContractChecks.identity(id, "id");
    }

    public Conversation(String id, String project, int maxModelCalls) {
      this(id, project, maxModelCalls, null, null, false, false, null);
    }
  }

  record Seam(int throughOrdinal, String summary) {
    public Seam {
      ContractChecks.nonnegative(throughOrdinal, "throughOrdinal");
      summary = ContractChecks.narrative(summary, "summary", 32768, false);
    }
  }

  record Entries(List<Entry> entries, int total, int offset, int limit) {
    public Entries {
      ContractChecks.nonnegative(total, "total");
      ContractChecks.nonnegative(offset, "offset");
      ContractChecks.nonnegative(limit, "limit");
      ContractChecks.collection(entries, "entries", 10000);
      entries = List.copyOf(java.util.Objects.requireNonNull(entries, "entries"));
    }
  }

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
      Instant ejectedAt) {
    public Entry {
      ContractChecks.nonnegative(ordinal, "ordinal");
      ContractChecks.nonnegative(turnOrdinal, "turnOrdinal");
      kind = ContractChecks.identity(kind, "kind");
      excerpt = ContractChecks.narrative(excerpt, "excerpt", 1048576, false);
      ContractChecks.nonnegative(length, "length");
      ContractChecks.nonnegative(supersededBy, "supersededBy");
      toolCallId = ContractChecks.optionalIdentity(toolCallId, "toolCallId");
      handle = ContractChecks.optionalIdentity(handle, "handle");
      ContractChecks.nonnegative(tookMillis, "tookMillis");
      ContractChecks.collection(toolCalls, "toolCalls", 128);
      if (toolCalls != null) toolCalls = List.copyOf(toolCalls);
    }
  }

  record Asked(String id, String name, String arguments, int length, boolean cut) {
    public Asked {
      id = ContractChecks.optionalIdentity(id, "id");
      name = ContractChecks.optionalIdentity(name, "name");
      arguments = ContractChecks.narrative(arguments, "arguments", 1048576, false);
      ContractChecks.nonnegative(length, "length");
      id = ContractChecks.identity(id, "id");
      name = ContractChecks.identity(name, "name");
    }
  }

  record LogHits(List<LogHit> hits, int total, int offset, int limit, Reach reach) {
    public LogHits {
      ContractChecks.nonnegative(total, "total");
      ContractChecks.nonnegative(offset, "offset");
      ContractChecks.nonnegative(limit, "limit");
      ContractChecks.collection(hits, "hits", 10000);
      hits = List.copyOf(java.util.Objects.requireNonNull(hits, "hits"));
    }
  }

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
      Instant recordedAt) {
    public LogHit {
      conversationId = ContractChecks.identity(conversationId, "conversationId");
      ContractChecks.nonnegative(ordinal, "ordinal");
      ContractChecks.nonnegative(turnOrdinal, "turnOrdinal");
      kind = ContractChecks.identity(kind, "kind");
      snippet = ContractChecks.narrative(snippet, "snippet", 1048576, false);
      ContractChecks.nonnegative(length, "length");
      ContractChecks.nonnegative(supersededBy, "supersededBy");
      handle = ContractChecks.optionalIdentity(handle, "handle");
      ContractChecks.finite(rank, "rank");
    }
  }

  record Reach(int searched, int ejected, int recordedOnly) {
    public Reach {
      ContractChecks.nonnegative(searched, "searched");
      ContractChecks.nonnegative(ejected, "ejected");
      ContractChecks.nonnegative(recordedOnly, "recordedOnly");
    }
  }

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
      Prefix prefix) {
    public Context {
      ContractChecks.nonnegative(sent, "sent");
      ContractChecks.nonnegative(sentAtTurn, "sentAtTurn");
      ContractChecks.nonnegative(turns, "turns");
      ContractChecks.nonnegative(turnsMeasured, "turnsMeasured");
      ContractChecks.nonnegative(systemPromptTokens, "systemPromptTokens");
      ContractChecks.nonnegative(toolTokens, "toolTokens");
      ContractChecks.nonnegative(messageTokens, "messageTokens");
      ContractChecks.collection(unavailable, "unavailable", 10000);
      if (cacheHitRate != null && (cacheHitRate < 0 || cacheHitRate > 1))
        throw new IllegalArgumentException("cacheHitRate must be 0..1");
      if (cacheHitRate != null) ContractChecks.finite(cacheHitRate, "cacheHitRate");
      unavailable = List.copyOf(java.util.Objects.requireNonNull(unavailable, "unavailable"));
    }
  }

  record Unavailable(String component, String reason) {
    public Unavailable {
      component = ContractChecks.optionalIdentity(component, "component");
      reason = ContractChecks.narrative(reason, "reason", 32768, false);
      component = ContractChecks.identity(component, "component");
      reason = ContractChecks.identity(reason, "reason");
    }
  }

  record Prefix(
      String agent,
      String model,
      int systemPromptCharacters,
      int toolCharacters,
      List<ToolCost> tools) {
    public Prefix {
      agent = ContractChecks.identity(agent, "agent");
      model = ContractChecks.identity(model, "model");
      ContractChecks.nonnegative(systemPromptCharacters, "systemPromptCharacters");
      ContractChecks.nonnegative(toolCharacters, "toolCharacters");
      ContractChecks.collection(tools, "tools", 10000);
      tools = List.copyOf(java.util.Objects.requireNonNull(tools, "tools"));
    }
  }

  record ToolCost(String name, int characters) {
    public ToolCost {
      name = ContractChecks.optionalIdentity(name, "name");
      ContractChecks.nonnegative(characters, "characters");
      name = ContractChecks.identity(name, "name");
    }
  }

  record ProjectView(String name, String workspace, List<String> lent, List<String> exclusions) {
    public ProjectView {
      name = ContractChecks.optionalIdentity(name, "name");
      workspace = ContractChecks.narrative(workspace, "workspace", 8192, false);
      ContractChecks.collection(lent, "lent", 256);
      if (lent != null)
        lent = lent.stream().map(value -> ContractChecks.pathText(value, "lent")).toList();
      ContractChecks.collection(exclusions, "exclusions", 256);
      if (exclusions != null)
        exclusions =
            exclusions.stream().map(value -> ContractChecks.pathText(value, "exclusions")).toList();
      workspace = ContractChecks.pathText(workspace, "workspace");
      name = ContractChecks.identity(name, "name");
      lent = lent == null ? List.of() : List.copyOf(lent);
      exclusions = List.copyOf(java.util.Objects.requireNonNull(exclusions, "exclusions"));
    }
  }

  record JobStatus(
      String id,
      String agent,
      String state,
      boolean cancelRequested,
      RunOutcome outcome,
      String conversation,
      Limits limits) {
    public JobStatus {
      id = ContractChecks.optionalIdentity(id, "id");
      agent = ContractChecks.optionalIdentity(agent, "agent");
      state = ContractChecks.optionalIdentity(state, "state");
      conversation = ContractChecks.optionalIdentity(conversation, "conversation");
      id = ContractChecks.identity(id, "id");
      agent = ContractChecks.identity(agent, "agent");
      state =
          ContractChecks.oneOf(state, "job state", java.util.Set.of("RUNNING", "DONE", "FINISHED"));
    }

    public JobStatus(
        String id, String agent, String state, boolean cancelRequested, RunOutcome outcome) {
      this(id, agent, state, cancelRequested, outcome, null, null);
    }
  }

  record Limits(
      Integer maxTurns,
      @com.fasterxml.jackson.annotation.JsonProperty(defaultValue = "false") boolean noTurnCap,
      Integer maxModelCalls,
      @com.fasterxml.jackson.annotation.JsonProperty(defaultValue = "false") boolean noBudget,
      int modelCallsSpent) {
    public Limits {
      ContractChecks.nonnegative(maxTurns, "maxTurns");
      ContractChecks.nonnegative(maxModelCalls, "maxModelCalls");
      ContractChecks.nonnegative(modelCallsSpent, "modelCallsSpent");
    }
  }

  record Pace(
      int toolCalls,
      Integer completionTokens,
      Integer reasoningTokens,
      Long firstTokenMillis,
      Double tokensPerSecond,
      boolean reasoningEstimated) {
    public Pace {
      ContractChecks.nonnegative(toolCalls, "toolCalls");
      ContractChecks.nonnegative(completionTokens, "completionTokens");
      ContractChecks.nonnegative(reasoningTokens, "reasoningTokens");
      ContractChecks.nonnegative(firstTokenMillis, "firstTokenMillis");
      if (tokensPerSecond != null) ContractChecks.finite(tokensPerSecond, "tokensPerSecond");
    }
  }

  record RunOutcome(
      String ending,
      boolean answered,
      String text,
      int steps,
      int modelCalls,
      String detail,
      @com.fasterxml.jackson.annotation.JsonProperty(defaultValue = "false") boolean resumable,
      Pace pace) {
    public RunOutcome {
      ending = ContractChecks.narrative(ending, "ending", 1048576, false);
      text = ContractChecks.narrative(text, "text", 1048576, false);
      ContractChecks.nonnegative(steps, "steps");
      ContractChecks.nonnegative(modelCalls, "modelCalls");
      detail = ContractChecks.narrative(detail, "detail", 1048576, false);
      ending =
          ContractChecks.oneOf(
              ending,
              "run ending",
              java.util.Set.of(
                  "ANSWERED",
                  "TURN_CAP",
                  "TURN_LIMIT",
                  "CALL_BUDGET",
                  "CANCELLED",
                  "STUCK",
                  "UNAVAILABLE",
                  "SUB_AGENT_FAILED",
                  "SESSION_GONE",
                  "AWAITING"));
    }

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
      String resolution) {
    public ProposalRow {
      id = ContractChecks.optionalIdentity(id, "id");
      memoryId = ContractChecks.optionalIdentity(memoryId, "memoryId");
      project = ContractChecks.optionalIdentity(project, "project");
      action = ContractChecks.optionalIdentity(action, "action");
      reason = ContractChecks.narrative(reason, "reason", 32768, false);
      state = ContractChecks.optionalIdentity(state, "state");
      proposedBy = ContractChecks.optionalIdentity(proposedBy, "proposedBy");
      resolvedBy = ContractChecks.optionalIdentity(resolvedBy, "resolvedBy");
      resolution = ContractChecks.narrative(resolution, "resolution", 32768, false);
      id = ContractChecks.identity(id, "id");
      memoryId = ContractChecks.identity(memoryId, "memoryId");
      action = ContractChecks.identity(action, "action");
      state = ContractChecks.identity(state, "state");
      if (proposedBy != null) proposedBy = ContractChecks.identity(proposedBy, "proposedBy");
    }
  }

  record Resolution(ProposalRow proposal, String promotedId, List<String> demoted) {
    public Resolution {
      promotedId = ContractChecks.narrative(promotedId, "promotedId", 1048576, false);
      ContractChecks.collection(demoted, "demoted", 10000);
      if (demoted != null)
        demoted = demoted.stream().map(value -> ContractChecks.identity(value, "demoted")).toList();
      demoted = List.copyOf(java.util.Objects.requireNonNull(demoted, "demoted"));
    }
  }

  record Recall(List<Memory> memories, int unsearchable) {
    public Recall {
      ContractChecks.nonnegative(unsearchable, "unsearchable");
      ContractChecks.collection(memories, "memories", 10000);
      memories = List.copyOf(java.util.Objects.requireNonNull(memories, "memories"));
    }
  }

  record IndexEntry(String id, String summary, String scope, boolean unsearchable) {
    public IndexEntry {
      id = ContractChecks.optionalIdentity(id, "id");
      summary = ContractChecks.narrative(summary, "summary", 32768, true);
      scope = ContractChecks.narrative(scope, "scope", 4096, false);
      id = ContractChecks.identity(id, "id");
      scope = ContractChecks.text(scope, "scope");
    }
  }

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
