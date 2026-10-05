package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.*;
import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.search.SearchPage;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import okhttp3.*;

/**
 * Typed Java facade over Plowshare's WS frames, with lazy connection establishment. Image multipart
 * upload is the sole application HTTP exception. No REST fallback.
 */
public class WsServerClient implements ServerClient, AutoCloseable {
  private static final ObjectMapper JSON = SdkJson.mapper();
  private final String origin;
  private final String bearer;
  private final OkHttpClient uploads =
      new OkHttpClient.Builder()
          .retryOnConnectionFailure(false)
          .followRedirects(false)
          .followSslRedirects(false)
          .callTimeout(Duration.ofSeconds(60))
          .addNetworkInterceptor(
              chain -> {
                var sent = chain.request().tag(java.util.concurrent.atomic.AtomicBoolean.class);
                if (sent != null && !sent.compareAndSet(false, true))
                  throw new IOException("image upload replay is disabled");
                return chain.proceed(chain.request());
              })
          .build();
  private Plowshare connection;
  private boolean closed;

  public WsServerClient(String origin, String bearer) {
    HttpUrl url = HttpUrl.parse(origin);
    if (url == null
        || !url.encodedPath().equals("/")
        || url.query() != null
        || url.fragment() != null
        || !url.username().isEmpty()
        || !url.password().isEmpty())
      throw new IllegalArgumentException("not a usable server URL: requires an HTTP(S) origin");
    this.origin = url.toString();
    this.bearer = bearer;
  }

  @Override
  public String baseUrl() {
    return origin;
  }

  public synchronized Plowshare connection() throws IOException {
    if (closed) throw new IOException("Plowshare client is closed");
    if (connection != null && !connection.connected()) {
      connection.close();
      connection = null;
    }
    if (connection == null)
      connection = Plowshare.connect(origin, bearer, Duration.ofMinutes(30), null);
    return connection;
  }

  /** A missing project deliberately selects the global scope; blank never does. */
  private static String project(String value) {
    return value == null ? null : Home.of(value).project();
  }

  private static void positive(Integer value, String field) {
    if (value != null && value < 1) throw new IllegalArgumentException(field + " must be positive");
  }

  private static List<String> paths(List<String> values, String field, boolean required) {
    if (values == null && !required) return null;
    Objects.requireNonNull(values, field);
    ContractChecks.collection(values, field, 256);
    if (required && values.isEmpty())
      throw new IllegalArgumentException(field + " must not be empty");
    return values.stream().map(value -> ContractChecks.pathText(value, field)).toList();
  }

  /** Syntax validation is local; destination/network policy still belongs to the server. */
  private static String webUrl(String value) {
    return WebContractValues.url(value);
  }

  private static Map<String, Object> fields(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  private Plowshare.Reply reply(String type, Map<String, Object> fields) throws IOException {
    var result = connection().request(type, fields);
    if (!result.successful()) {
      int status;
      try {
        status = io.aeyer.plowshare.protocol.frames.Code.valueOf(result.code()).httpStatus();
      } catch (IllegalArgumentException unknown) {
        status = 500;
      }
      throw new ServerError(status, result.said() == null ? result.code() : result.said());
    }
    return result;
  }

  private <T> T ask(String type, Map<String, Object> fields, Class<T> response) throws IOException {
    JsonNode value = reply(type, fields).requirePayload();
    try {
      return SdkJson.decode(JSON, value, response);
    } catch (IOException invalid) {
      throw new Plowshare.TransportException(
          Plowshare.Delivery.INVALID_RESPONSE, "unreadable Plowshare payload; outcome is unknown");
    }
  }

  /** A valid DTO for another resource is still an invalid reply; never replay the request. */
  private static <T> void selected(T expected, T actual) throws IOException {
    if (!Objects.equals(expected, actual))
      throw new Plowshare.TransportException(
          Plowshare.Delivery.INVALID_RESPONSE,
          "Plowshare payload differs from the requested resource; outcome is unknown");
  }

  private <T> List<T> list(String type, Map<String, Object> fields, Class<T> item)
      throws IOException {
    JsonNode value = reply(type, fields).requirePayload();
    if (!value.isArray()) throw new IOException("Plowshare list response is unreadable");
    var result = new ArrayList<T>();
    for (JsonNode node : value) result.add(SdkJson.decode(JSON, node, item));
    return List.copyOf(result);
  }

  @Override
  public Recall recall(String project, String question, Integer limit) throws IOException {
    project = project(project);
    question = ContractChecks.text(question, "question");
    positive(limit, "limit");
    JsonNode value =
        reply("memory.recall", fields("project", project, "question", question, "limit", limit))
            .requirePayload();
    if (!value.path("memories").isArray() || !value.path("unsearchable").isIntegralNumber())
      throw new IOException("unreadable memory recall");
    var memories = new ArrayList<Memory>();
    for (JsonNode node : value.path("memories"))
      memories.add(SdkJson.decode(JSON, node, Memory.class));
    return new Recall(List.copyOf(memories), value.get("unsearchable").intValue());
  }

  @Override
  public io.aeyer.plowshare.protocol.InformationResponse information(
      io.aeyer.plowshare.protocol.InformationRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    return InformationCodec.response(
        request,
        reply("information." + request.operation(), InformationCodec.fields(request))
            .requirePayload());
  }

  @Override
  public UploadedImage uploadImage(String project, String filename, byte[] bytes)
      throws IOException {
    project = project(project);
    if (filename != null) filename = ContractChecks.identity(filename, "filename");
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length == 0) throw new IllegalArgumentException("image bytes must not be empty");
    var parts =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                filename == null || filename.isBlank() ? "image" : filename,
                RequestBody.create(bytes, MediaType.get("application/octet-stream")));
    if (project != null) parts.addFormDataPart("project", project);
    if (filename != null && !filename.isBlank()) parts.addFormDataPart("name", filename);
    var request =
        new Request.Builder()
            .url(HttpUrl.get(origin).newBuilder().addPathSegments("v1/images").build())
            .post(parts.build());
    request.tag(
        java.util.concurrent.atomic.AtomicBoolean.class,
        new java.util.concurrent.atomic.AtomicBoolean());
    if (bearer != null && !bearer.isBlank()) request.header("Authorization", "Bearer " + bearer);
    try (Response response = uploads.newCall(request.build()).execute()) {
      if (!response.isSuccessful()) throw new ServerError(response.code(), "image upload refused");
      if (response.body() == null) throw new IOException("image upload returned no receipt");
      return SdkJson.decode(JSON, JSON.readTree(response.body().string()), UploadedImage.class);
    }
  }

  @Override
  public WriteResult write(String project, MemoryProposal proposal) throws IOException {
    project = project(project);
    Objects.requireNonNull(proposal, "proposal");
    return ask("memory.write", fields("project", project, "proposal", proposal), WriteResult.class);
  }

  @Override
  public Navigation navigateMemory(String project, String question) throws IOException {
    project = project(project);
    question = ContractChecks.text(question, "question");
    return ask(
        "memory.navigate", fields("project", project, "question", question), Navigation.class);
  }

  @Override
  public StartedJob digestMemory(String project) throws IOException {
    project = project(project);
    return ask("memory.digest", fields("project", project), StartedJob.class);
  }

  @Override
  public Memory read(String id) throws IOException {
    id = ContractChecks.identity(id, "memory");
    var result = ask("memory.read", fields("memory", id), Memory.class);
    selected(id, result.id());
    return result;
  }

  @Override
  public List<IndexEntry> index(String project) throws IOException {
    project = project(project);
    return list("memory.index", fields("project", project), IndexEntry.class);
  }

  @Override
  public SearchPage search(String query, int pageSize, int max, int page) throws IOException {
    query = ContractChecks.text(query, "query");
    positive(pageSize, "pageSize");
    positive(max, "max");
    positive(page, "page");
    return ask(
        "web.search",
        fields("query", query, "pageSize", pageSize, "max", max, "page", page),
        SearchPage.class);
  }

  @Override
  public FetchWindow fetch(String url, int offset) throws IOException {
    url = webUrl(url);
    ContractChecks.nonnegative(offset, "offset");
    var result = ask("web.fetch", fields("url", url, "offset", offset), FetchWindow.class);
    selected(url, result.url());
    selected(offset, result.offset());
    return result;
  }

  @Override
  public DocumentSearch searchDocuments(String query, Integer limit) throws IOException {
    query = ContractChecks.text(query, "query");
    positive(limit, "limit");
    return ask("document.search", fields("query", query, "limit", limit), DocumentSearch.class);
  }

  @Override
  public Retrieved retrieve(String query, String documentId, Integer limit) throws IOException {
    query = ContractChecks.text(query, "query");
    documentId = ContractChecks.optionalIdentity(documentId, "document");
    positive(limit, "limit");
    return ask(
        "document.retrieve",
        fields("query", query, "document", documentId, "limit", limit),
        Retrieved.class);
  }

  @Override
  public Ranking rankDocuments(String query, Integer limit) throws IOException {
    query = ContractChecks.text(query, "query");
    positive(limit, "limit");
    return ask("document.rank", fields("query", query, "limit", limit), Ranking.class);
  }

  @Override
  public Stance documentStance(String documentId, String claim) throws IOException {
    documentId = ContractChecks.identity(documentId, "document");
    claim = ContractChecks.text(claim, "claim");
    return ask("document.stance", fields("document", documentId, "claim", claim), Stance.class);
  }

  @Override
  public DocumentPage listDocuments(String naming, Integer limit, Integer offset)
      throws IOException {
    naming = ContractChecks.narrative(naming, "q", 32768, false);
    positive(limit, "limit");
    ContractChecks.nonnegative(offset, "offset");
    return ask(
        "document.list", fields("q", naming, "limit", limit, "offset", offset), DocumentPage.class);
  }

  @Override
  public DocumentOutline describeDocument(String documentId) throws IOException {
    documentId = ContractChecks.identity(documentId, "document");
    return ask("document.detail", fields("document", documentId), DocumentOutline.class);
  }

  @Override
  public Citations citations(String conversationId, String documentId, Integer limit)
      throws IOException {
    conversationId = ContractChecks.optionalIdentity(conversationId, "conversation");
    documentId = ContractChecks.optionalIdentity(documentId, "document");
    positive(limit, "limit");
    return ask(
        "document.citations",
        fields("conversation", conversationId, "document", documentId, "limit", limit),
        Citations.class);
  }

  @Override
  public StartedJob run(
      String agent, String task, String project, String session, String conversation)
      throws IOException {
    var requested = new AgentClient.Run(agent, task, project(project), session, conversation, null);
    agent = requested.agent();
    task = requested.task();
    project = requested.project();
    session = requested.session();
    conversation = requested.conversation();
    return ask(
        "agent.run",
        fields(
            "agent",
            agent,
            "task",
            task,
            "project",
            project,
            "session",
            session,
            "conversation",
            conversation),
        StartedJob.class);
  }

  @Override
  public Conversation openConversation(String project, Integer maxModelCalls) throws IOException {
    project = project(project);
    positive(maxModelCalls, "maxModelCalls");
    var result =
        ask(
            "conversation.open",
            fields("project", project, "maxModelCalls", maxModelCalls),
            Conversation.class);
    selected(project, result.project());
    return result;
  }

  @Override
  public List<Conversation> conversations(String project) throws IOException {
    project = project(project);
    var result = list("conversation.list", fields("project", project), Conversation.class);
    for (var conversation : result) selected(project, conversation.project());
    return result;
  }

  @Override
  public Entries chat(String conversationId, Integer offset, Integer limit) throws IOException {
    conversationId = ContractChecks.identity(conversationId, "conversation");
    ContractChecks.nonnegative(offset, "offset");
    positive(limit, "limit");
    return ask(
        "conversation.chat",
        fields("conversation", conversationId, "offset", offset, "limit", limit),
        Entries.class);
  }

  @Override
  public Entries trajectory(String conversationId, Integer offset, Integer limit)
      throws IOException {
    conversationId = ContractChecks.identity(conversationId, "conversation");
    ContractChecks.nonnegative(offset, "offset");
    positive(limit, "limit");
    return ask(
        "conversation.trajectory",
        fields("conversation", conversationId, "offset", offset, "limit", limit),
        Entries.class);
  }

  @Override
  public LogHits searchEntries(String project, String query, Integer offset, Integer limit)
      throws IOException {
    project = project(project);
    query = ContractChecks.text(query, "query");
    ContractChecks.nonnegative(offset, "offset");
    positive(limit, "limit");
    return ask(
        "conversation.search",
        fields("project", project, "q", query, "offset", offset, "limit", limit),
        LogHits.class);
  }

  @Override
  public Context context(String conversationId, String agent) throws IOException {
    conversationId = ContractChecks.identity(conversationId, "conversation");
    agent = ContractChecks.optionalIdentity(agent, "agent");
    return ask(
        "conversation.context",
        fields("conversation", conversationId, "agent", agent),
        Context.class);
  }

  @Override
  public List<Seam> compactions(String conversationId) throws IOException {
    conversationId = ContractChecks.identity(conversationId, "conversation");
    return list("conversation.compactions", fields("conversation", conversationId), Seam.class);
  }

  @Override
  public StartedJob curate(String project, Integer maxModelCalls) throws IOException {
    project = project(project);
    positive(maxModelCalls, "maxModelCalls");
    return ask(
        "agent.curate",
        fields("project", project, "maxModelCalls", maxModelCalls),
        StartedJob.class);
  }

  @Override
  public StartedJob askDocument(String documentId, String question, Integer maxModelCalls)
      throws IOException {
    documentId = ContractChecks.identity(documentId, "document");
    question = ContractChecks.text(question, "question");
    positive(maxModelCalls, "maxModelCalls");
    return ask(
        "document.ask",
        fields("document", documentId, "question", question, "maxModelCalls", maxModelCalls),
        StartedJob.class);
  }

  @Override
  public JobStatus job(String id) throws IOException {
    id = ContractChecks.identity(id, "job");
    var result = ask("job.status", fields("job", id), JobStatus.class);
    selected(id, result.id());
    return result;
  }

  @Override
  public JobStatus cancelJob(String id) throws IOException {
    id = ContractChecks.identity(id, "job");
    var result = ask("job.cancel", fields("job", id), JobStatus.class);
    selected(id, result.id());
    return result;
  }

  @Override
  public ProjectView defineProject(String name, String workspace, List<String> exclusions)
      throws IOException {
    name = Home.of(name).project();
    workspace = ContractChecks.pathText(workspace, "workspace");
    exclusions = paths(exclusions, "exclusions", false);
    var result =
        ask(
            "project.define",
            fields("name", name, "workspace", workspace, "exclusions", exclusions),
            ProjectView.class);
    selected(name, result.name());
    return result;
  }

  @Override
  public ProjectView lendProject(String name, List<String> roots) throws IOException {
    name = Home.of(name).project();
    roots = paths(roots, "roots", true);
    var result = ask("project.lend", fields("project", name, "roots", roots), ProjectView.class);
    selected(name, result.name());
    return result;
  }

  @Override
  public ProjectView unlendProject(String name, List<String> roots) throws IOException {
    name = Home.of(name).project();
    roots = paths(roots, "roots", true);
    var result = ask("project.unlend", fields("project", name, "roots", roots), ProjectView.class);
    selected(name, result.name());
    return result;
  }

  @Override
  public ProjectView setProjectWorkspace(String name, String workspace) throws IOException {
    name = Home.of(name).project();
    workspace = ContractChecks.pathText(workspace, "workspace");
    var result =
        ask(
            "project.workspace",
            fields("project", name, "workspace", workspace),
            ProjectView.class);
    selected(name, result.name());
    return result;
  }

  @Override
  public void moveProject(String name, String to) throws IOException {
    name = Home.of(name).project();
    to = Home.of(to).project();
    reply("project.move", fields("project", name, "to", to));
  }

  @Override
  public void forgetProject(String name) throws IOException {
    name = Home.of(name).project();
    reply("project.forget", fields("project", name));
  }

  @Override
  public List<ProposalRow> proposals(String project) throws IOException {
    project = project(project);
    return list("proposal.list", fields("project", project), ProposalRow.class);
  }

  @Override
  public Resolution resolve(String id, boolean accept, String reason, String by)
      throws IOException {
    id = ContractChecks.identity(id, "proposal");
    reason = ContractChecks.narrative(reason, "reason", 32768, false);
    by = ContractChecks.optionalIdentity(by, "by");
    return ask(
        "proposal.resolve",
        fields("proposal", id, "accept", accept, "reason", reason, "by", by),
        Resolution.class);
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (connection != null) connection.close();
    uploads.dispatcher().executorService().shutdown();
    uploads.connectionPool().evictAll();
  }
}
