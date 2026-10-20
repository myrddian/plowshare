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
public class WsServerClient implements ServerClient, AsyncServerClient, AutoCloseable {
  private static final ObjectMapper JSON = SdkJson.mapper();
  private final String origin;
  private final String bearer;
  private final Plowshare.TransportMode transportMode;
  private final Plowshare.DispatchMode dispatchMode;
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
  private java.util.concurrent.CompletionStage<Plowshare> opening;
  private final java.util.concurrent.Semaphore openingWaiters =
      new java.util.concurrent.Semaphore(64);
  private volatile boolean closed;

  public WsServerClient(String origin, String bearer) {
    this(origin, bearer, Plowshare.TransportMode.SEGMENTED);
  }

  public WsServerClient(String origin, String bearer, Plowshare.TransportMode transportMode) {
    this(origin, bearer, transportMode, Plowshare.DispatchMode.CONCURRENT);
  }

  public WsServerClient(
      String origin,
      String bearer,
      Plowshare.TransportMode transportMode,
      Plowshare.DispatchMode dispatchMode) {
    this.dispatchMode = Objects.requireNonNull(dispatchMode);
    this.transportMode = Objects.requireNonNull(transportMode);
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

  public Plowshare connection() throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)), this::connectionAsync);
  }

  /** Concurrent lazy callers share one upgrade/negotiation; a failed call is never replayed. */
  public java.util.concurrent.CompletionStage<Plowshare> connectionAsync() {
    return connectionAsync(null);
  }

  public java.util.concurrent.CompletionStage<Plowshare> connectionAsync(RequestOptions options) {
    if (!openingWaiters.tryAcquire())
      return java.util.concurrent.CompletableFuture.failedFuture(
          new Plowshare.TransportException(
              Plowshare.Delivery.NOT_SUBMITTED, "Too many calls waiting for connection opening"));
    var answer = new java.util.concurrent.CompletableFuture<Plowshare>();
    var terminal = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.CompletionStage<Plowshare> source;
    synchronized (this) {
      if (closed)
        source =
            java.util.concurrent.CompletableFuture.failedFuture(
                new Plowshare.TransportException(
                    Plowshare.Delivery.NOT_SUBMITTED, "Client is closed"));
      else if (connection != null && connection.connected())
        source = java.util.concurrent.CompletableFuture.completedFuture(connection);
      else {
        if (opening == null) {
          if (connection != null) connection.close();
          opening =
              Plowshare.connectAsync(
                  origin,
                  bearer,
                  UUID.randomUUID().toString(),
                  Duration.ofMinutes(30),
                  null,
                  transportMode,
                  dispatchMode);
        }
        source = opening;
      }
    }
    var waiter = new OpeningWaiter(answer, terminal, openingWaiters);
    waiter.arm(options == null ? RequestOptions.defaults(Duration.ofMinutes(30)) : options);
    source.whenComplete(
        (value, failure) -> {
          boolean wasClosed;
          synchronized (this) {
            if (opening == source) opening = null;
            if (failure == null && !closed) connection = value;
            wasClosed = closed;
          }
          if (wasClosed) {
            if (value != null) value.close();
            failure =
                new Plowshare.TransportException(
                    Plowshare.Delivery.NOT_SUBMITTED, "Client closed during opening");
          }
          waiter.finish(value, failure);
        });
    return answer;
  }

  /** Setup/memory operations may select global; runtime admissions require a project first. */
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
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> replyAsync(type, fields, options));
  }

  private java.util.concurrent.CompletionStage<Plowshare.Reply> replyAsync(
      String type, Map<String, Object> fields, RequestOptions options) {
    var budget = options == null ? RequestOptions.defaults(Duration.ofMinutes(30)) : options;
    long started = System.nanoTime();
    return connectionAsync(budget)
        .thenCompose(
            live -> {
              long left = budget.timeout().toNanos() - (System.nanoTime() - started);
              if (left <= 0)
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new Plowshare.TransportException(
                        Plowshare.Delivery.NOT_SUBMITTED,
                        "Request deadline expired before connection opened"));
              return AsyncSupport.map(
                  live.requestAsync(
                      type,
                      fields,
                      new RequestOptions(Duration.ofNanos(left), budget.cancellation())),
                  result -> {
                    if (!result.successful()) {
                      int status;
                      try {
                        status =
                            io.aeyer.plowshare.protocol.frames.Code.valueOf(result.code())
                                .httpStatus();
                      } catch (IllegalArgumentException unknown) {
                        status = 500;
                      }
                      throw new ServerError(
                          status, result.said() == null ? result.code() : result.said());
                    }
                    return result;
                  });
            });
  }

  private <T> T ask(String type, Map<String, Object> fields, Class<T> response) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> askAsync(type, fields, response, options));
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
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> listAsync(type, fields, item, options));
  }

  @Override
  public Recall recall(String project, String question, Integer limit) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> recallAsync(project, question, limit, options));
  }

  @Override
  public io.aeyer.plowshare.protocol.InformationResponse information(
      io.aeyer.plowshare.protocol.InformationRequest request) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> informationAsync(request, options));
  }

  @Override
  public UploadedImage uploadImage(String project, String filename, byte[] bytes)
      throws IOException {
    return Plowshare.await(uploadImageAsync(project, filename, bytes));
  }

  @Override
  public WriteResult write(String project, MemoryProposal proposal) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> writeAsync(project, proposal, options));
  }

  @Override
  public Navigation navigateMemory(String project, String question) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> navigateMemoryAsync(project, question, options));
  }

  @Override
  public StartedJob digestMemory(String project) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> digestMemoryAsync(project, options));
  }

  @Override
  public Memory read(String id) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)), options -> readAsync(id, options));
  }

  @Override
  public List<IndexEntry> index(String project) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)), options -> indexAsync(project, options));
  }

  @Override
  public SearchPage search(String query, int pageSize, int max, int page) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> searchAsync(query, pageSize, max, page, options));
  }

  @Override
  public FetchWindow fetch(String url, int offset) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> fetchAsync(url, offset, options));
  }

  @Override
  public DocumentSearch searchDocuments(String query, Integer limit) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> searchDocumentsAsync(query, limit, options));
  }

  @Override
  public Retrieved retrieve(String query, String documentId, Integer limit) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> retrieveAsync(query, documentId, limit, options));
  }

  @Override
  public Ranking rankDocuments(String query, Integer limit) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> rankDocumentsAsync(query, limit, options));
  }

  @Override
  public Stance documentStance(String documentId, String claim) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> documentStanceAsync(documentId, claim, options));
  }

  @Override
  public DocumentPage listDocuments(String naming, Integer limit, Integer offset)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> listDocumentsAsync(naming, limit, offset, options));
  }

  @Override
  public DocumentOutline describeDocument(String documentId) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> describeDocumentAsync(documentId, options));
  }

  @Override
  public DocumentOutline describeDocument(
      String documentId, io.aeyer.plowshare.protocol.InformationRequest.Scope scope)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> describeDocumentAsync(documentId, scope, options));
  }

  @Override
  public Citations citations(String conversationId, String documentId, Integer limit)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> citationsAsync(conversationId, documentId, limit, options));
  }

  @Override
  public StartedJob run(
      String agent, String task, String project, String session, String conversation)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> runAsync(agent, task, project, session, conversation, options));
  }

  @Override
  public Conversation openConversation(String project, Integer maxModelCalls) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> openConversationAsync(project, maxModelCalls, options));
  }

  @Override
  public List<Conversation> conversations(String project) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> conversationsAsync(project, options));
  }

  @Override
  public Entries chat(String conversationId, Integer offset, Integer limit) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> chatAsync(conversationId, offset, limit, options));
  }

  @Override
  public Entries trajectory(String conversationId, Integer offset, Integer limit)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> trajectoryAsync(conversationId, offset, limit, options));
  }

  @Override
  public LogHits searchEntries(String project, String query, Integer offset, Integer limit)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> searchEntriesAsync(project, query, offset, limit, options));
  }

  @Override
  public Context context(String conversationId, String agent) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> contextAsync(conversationId, agent, options));
  }

  @Override
  public List<Seam> compactions(String conversationId) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> compactionsAsync(conversationId, options));
  }

  @Override
  public StartedJob curate(String project, Integer maxModelCalls) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> curateAsync(project, maxModelCalls, options));
  }

  @Override
  public StartedJob askDocument(String documentId, String question, Integer maxModelCalls)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> askDocumentAsync(documentId, question, maxModelCalls, options));
  }

  @Override
  public JobStatus job(String id) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)), options -> jobAsync(id, options));
  }

  @Override
  public JobStatus cancelJob(String id) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)), options -> cancelJobAsync(id, options));
  }

  @Override
  public ProjectView defineProject(String name, String workspace, List<String> exclusions)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> defineProjectAsync(name, workspace, exclusions, options));
  }

  @Override
  public ProjectView lendProject(String name, List<String> roots) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> lendProjectAsync(name, roots, options));
  }

  @Override
  public ProjectView unlendProject(String name, List<String> roots) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> unlendProjectAsync(name, roots, options));
  }

  @Override
  public ProjectView setProjectWorkspace(String name, String workspace) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> setProjectWorkspaceAsync(name, workspace, options));
  }

  @Override
  public void moveProject(String name, String to) throws IOException {
    AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> moveProjectAsync(name, to, options));
  }

  @Override
  public void forgetProject(String name) throws IOException {
    AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> forgetProjectAsync(name, options));
  }

  @Override
  public List<ProposalRow> proposals(String project) throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> proposalsAsync(project, options));
  }

  @Override
  public Resolution resolve(String id, boolean accept, String reason, String by)
      throws IOException {
    return AsyncSupport.await(
        RequestOptions.defaults(Duration.ofMinutes(30)),
        options -> resolveAsync(id, accept, reason, by, options));
  }

  @Override
  public void close() {
    Plowshare live;
    java.util.concurrent.CompletionStage<Plowshare> starting;
    synchronized (this) {
      if (closed) return;
      closed = true;
      live = connection;
      starting = opening;
      opening = null;
    }
    if (live != null) live.close();
    if (starting != null) starting.toCompletableFuture().cancel(false);
    uploads.dispatcher().cancelAll();
    uploads.dispatcher().executorService().shutdown();
    uploads.connectionPool().evictAll();
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  private <T> java.util.concurrent.CompletionStage<T> askAsync(
      String type, Map<String, Object> fields, Class<T> response, RequestOptions options) {

    return AsyncSupport.map(
        replyAsync(type, fields, options),
        asyncResult -> {
          JsonNode value = asyncResult.requirePayload();
          try {
            return SdkJson.decode(JSON, value, response);
          } catch (IOException invalid) {
            throw new Plowshare.TransportException(
                Plowshare.Delivery.INVALID_RESPONSE,
                "unreadable Plowshare payload; outcome is unknown");
          }
        });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  private <T> java.util.concurrent.CompletionStage<List<T>> listAsync(
      String type, Map<String, Object> fields, Class<T> item, RequestOptions options) {

    return AsyncSupport.map(
        replyAsync(type, fields, options),
        asyncResult -> {
          JsonNode value = asyncResult.requirePayload();
          if (!value.isArray()) throw new IOException("Plowshare list response is unreadable");
          var result = new ArrayList<T>();
          for (JsonNode node : value) result.add(SdkJson.decode(JSON, node, item));
          return List.copyOf(result);
        });
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Recall> recallAsync(
      String project, String question, Integer limit, RequestOptions options) {

    project = project(project);
    question = ContractChecks.text(question, "question");
    positive(limit, "limit");
    var selectedProject = project;
    var selectedQuestion = question;
    return AsyncSupport.map(
        replyAsync(
            "memory.recall",
            fields("project", project, "question", question, "limit", limit),
            options),
        asyncResult -> {
          JsonNode value = asyncResult.requirePayload();
          if (!value.path("memories").isArray() || !value.path("unsearchable").isIntegralNumber())
            throw new IOException("unreadable memory recall");
          var memories = new ArrayList<Memory>();
          for (JsonNode node : value.path("memories"))
            memories.add(SdkJson.decode(JSON, node, Memory.class));
          return new Recall(List.copyOf(memories), value.get("unsearchable").intValue());
        });
  }

  public java.util.concurrent.CompletionStage<Recall> recallAsync(
      String project, String question, Integer limit) {
    return recallAsync(project, question, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<io.aeyer.plowshare.protocol.InformationResponse>
      informationAsync(
          io.aeyer.plowshare.protocol.InformationRequest request, RequestOptions options) {

    Objects.requireNonNull(request, "request");
    return AsyncSupport.map(
        replyAsync("information." + request.operation(), InformationCodec.fields(request), options),
        asyncResult -> {
          return InformationCodec.response(request, asyncResult.requirePayload());
        });
  }

  public java.util.concurrent.CompletionStage<io.aeyer.plowshare.protocol.InformationResponse>
      informationAsync(io.aeyer.plowshare.protocol.InformationRequest request) {
    return informationAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<WriteResult> writeAsync(
      String project, MemoryProposal proposal, RequestOptions options) {

    project = project(project);
    Objects.requireNonNull(proposal, "proposal");
    var selectedProject = project;
    return AsyncSupport.map(
        askAsync(
            "memory.write",
            fields("project", project, "proposal", proposal),
            WriteResult.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<WriteResult> writeAsync(
      String project, MemoryProposal proposal) {
    return writeAsync(project, proposal, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Navigation> navigateMemoryAsync(
      String project, String question, RequestOptions options) {

    project = project(project);
    question = ContractChecks.text(question, "question");
    var selectedProject = project;
    var selectedQuestion = question;
    return AsyncSupport.map(
        askAsync(
            "memory.navigate",
            fields("project", project, "question", question),
            Navigation.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Navigation> navigateMemoryAsync(
      String project, String question) {
    return navigateMemoryAsync(project, question, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<StartedJob> digestMemoryAsync(
      String project, RequestOptions options) {

    project = project(project);
    var selectedProject = project;
    return AsyncSupport.map(
        askAsync("memory.digest", fields("project", project), StartedJob.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<StartedJob> digestMemoryAsync(String project) {
    return digestMemoryAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Memory> readAsync(String id, RequestOptions options) {

    id = ContractChecks.identity(id, "memory");
    var selectedId = id;
    return AsyncSupport.map(
        askAsync("memory.read", fields("memory", id), Memory.class, options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedId, result.id());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<Memory> readAsync(String id) {
    return readAsync(id, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<IndexEntry>> indexAsync(
      String project, RequestOptions options) {

    project = project(project);
    var selectedProject = project;
    return AsyncSupport.map(
        listAsync("memory.index", fields("project", project), IndexEntry.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<List<IndexEntry>> indexAsync(String project) {
    return indexAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<SearchPage> searchAsync(
      String query, int pageSize, int max, int page, RequestOptions options) {

    query = ContractChecks.text(query, "query");
    positive(pageSize, "pageSize");
    positive(max, "max");
    positive(page, "page");
    var selectedQuery = query;
    return AsyncSupport.map(
        askAsync(
            "web.search",
            fields("query", query, "pageSize", pageSize, "max", max, "page", page),
            SearchPage.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<SearchPage> searchAsync(
      String query, int pageSize, int max, int page) {
    return searchAsync(query, pageSize, max, page, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<FetchWindow> fetchAsync(
      String url, int offset, RequestOptions options) {

    url = webUrl(url);
    ContractChecks.nonnegative(offset, "offset");
    var selectedUrl = url;
    return AsyncSupport.map(
        askAsync("web.fetch", fields("url", url, "offset", offset), FetchWindow.class, options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedUrl, result.url());
          selected(offset, result.offset());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<FetchWindow> fetchAsync(String url, int offset) {
    return fetchAsync(url, offset, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<DocumentSearch> searchDocumentsAsync(
      String query, Integer limit, RequestOptions options) {

    query = ContractChecks.text(query, "query");
    positive(limit, "limit");
    var selectedQuery = query;
    return AsyncSupport.map(
        askAsync(
            "document.search",
            fields("query", query, "limit", limit),
            DocumentSearch.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<DocumentSearch> searchDocumentsAsync(
      String query, Integer limit) {
    return searchDocumentsAsync(query, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Retrieved> retrieveAsync(
      String query, String documentId, Integer limit, RequestOptions options) {

    query = ContractChecks.text(query, "query");
    documentId = ContractChecks.optionalIdentity(documentId, "document");
    positive(limit, "limit");
    var selectedQuery = query;
    var selectedDocumentId = documentId;
    return AsyncSupport.map(
        askAsync(
            "document.retrieve",
            fields("query", query, "document", documentId, "limit", limit),
            Retrieved.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Retrieved> retrieveAsync(
      String query, String documentId, Integer limit) {
    return retrieveAsync(query, documentId, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Ranking> rankDocumentsAsync(
      String query, Integer limit, RequestOptions options) {

    query = ContractChecks.text(query, "query");
    positive(limit, "limit");
    var selectedQuery = query;
    return AsyncSupport.map(
        askAsync("document.rank", fields("query", query, "limit", limit), Ranking.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Ranking> rankDocumentsAsync(
      String query, Integer limit) {
    return rankDocumentsAsync(query, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Stance> documentStanceAsync(
      String documentId, String claim, RequestOptions options) {

    documentId = ContractChecks.identity(documentId, "document");
    claim = ContractChecks.text(claim, "claim");
    var selectedDocumentId = documentId;
    return AsyncSupport.map(
        askAsync(
            "document.stance",
            fields("document", documentId, "claim", claim),
            Stance.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Stance> documentStanceAsync(
      String documentId, String claim) {
    return documentStanceAsync(documentId, claim, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<DocumentPage> listDocumentsAsync(
      String naming, Integer limit, Integer offset, RequestOptions options) {

    naming = ContractChecks.narrative(naming, "q", 32768, false);
    positive(limit, "limit");
    ContractChecks.nonnegative(offset, "offset");
    return AsyncSupport.map(
        askAsync(
            "document.list",
            fields("q", naming, "limit", limit, "offset", offset),
            DocumentPage.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<DocumentPage> listDocumentsAsync(
      String naming, Integer limit, Integer offset) {
    return listDocumentsAsync(naming, limit, offset, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<DocumentOutline> describeDocumentAsync(
      String documentId, RequestOptions options) {

    documentId = ContractChecks.identity(documentId, "document");
    var selectedDocumentId = documentId;
    return AsyncSupport.map(
        askAsync("document.detail", fields("document", documentId), DocumentOutline.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<DocumentOutline> describeDocumentAsync(
      String documentId) {
    return describeDocumentAsync(documentId, (RequestOptions) null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<DocumentOutline> describeDocumentAsync(
      String documentId,
      io.aeyer.plowshare.protocol.InformationRequest.Scope scope,
      RequestOptions options) {

    Objects.requireNonNull(scope, "scope");
    UUID document = UUID.fromString(ContractChecks.identity(documentId, "document"));
    if (scope.project() != null
        && (!scope.project().equals(scope.project().strip()) || scope.project().length() > 256))
      throw new IllegalArgumentException("Invalid document project scope");
    return AsyncSupport.map(
        askAsync(
            "document.detail",
            fields("document", document.toString(), "scope", scope),
            DocumentOutline.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<DocumentOutline> describeDocumentAsync(
      String documentId, io.aeyer.plowshare.protocol.InformationRequest.Scope scope) {
    return describeDocumentAsync(documentId, scope, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Citations> citationsAsync(
      String conversationId, String documentId, Integer limit, RequestOptions options) {

    conversationId = ContractChecks.optionalIdentity(conversationId, "conversation");
    documentId = ContractChecks.optionalIdentity(documentId, "document");
    positive(limit, "limit");
    var selectedConversationId = conversationId;
    var selectedDocumentId = documentId;
    return AsyncSupport.map(
        askAsync(
            "document.citations",
            fields("conversation", conversationId, "document", documentId, "limit", limit),
            Citations.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Citations> citationsAsync(
      String conversationId, String documentId, Integer limit) {
    return citationsAsync(conversationId, documentId, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<StartedJob> runAsync(
      String agent,
      String task,
      String project,
      String session,
      String conversation,
      RequestOptions options) {

    var requested = new AgentClient.Run(agent, task, project(project), session, conversation, null);
    agent = requested.agent();
    task = requested.task();
    project = requested.project();
    session = requested.session();
    conversation = requested.conversation();
    var selectedAgent = agent;
    var selectedProject = project;
    return AsyncSupport.map(
        askAsync(
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
            StartedJob.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<StartedJob> runAsync(
      String agent, String task, String project, String session, String conversation) {
    return runAsync(agent, task, project, session, conversation, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Conversation> openConversationAsync(
      String project, Integer maxModelCalls, RequestOptions options) {

    project = Home.of(project).project();
    positive(maxModelCalls, "maxModelCalls");
    var selectedProject = project;
    return AsyncSupport.map(
        askAsync(
            "conversation.open",
            fields("project", project, "maxModelCalls", maxModelCalls),
            Conversation.class,
            options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedProject, result.project());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<Conversation> openConversationAsync(
      String project, Integer maxModelCalls) {
    return openConversationAsync(project, maxModelCalls, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<Conversation>> conversationsAsync(
      String project, RequestOptions options) {

    project = project(project);
    var selectedProject = project;
    return AsyncSupport.map(
        listAsync("conversation.list", fields("project", project), Conversation.class, options),
        asyncResult -> {
          var result = asyncResult;
          for (var conversation : result) selected(selectedProject, conversation.project());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<List<Conversation>> conversationsAsync(
      String project) {
    return conversationsAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Entries> chatAsync(
      String conversationId, Integer offset, Integer limit, RequestOptions options) {

    conversationId = ContractChecks.identity(conversationId, "conversation");
    ContractChecks.nonnegative(offset, "offset");
    positive(limit, "limit");
    var selectedConversationId = conversationId;
    return AsyncSupport.map(
        askAsync(
            "conversation.chat",
            fields("conversation", conversationId, "offset", offset, "limit", limit),
            Entries.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Entries> chatAsync(
      String conversationId, Integer offset, Integer limit) {
    return chatAsync(conversationId, offset, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Entries> trajectoryAsync(
      String conversationId, Integer offset, Integer limit, RequestOptions options) {

    conversationId = ContractChecks.identity(conversationId, "conversation");
    ContractChecks.nonnegative(offset, "offset");
    positive(limit, "limit");
    var selectedConversationId = conversationId;
    return AsyncSupport.map(
        askAsync(
            "conversation.trajectory",
            fields("conversation", conversationId, "offset", offset, "limit", limit),
            Entries.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Entries> trajectoryAsync(
      String conversationId, Integer offset, Integer limit) {
    return trajectoryAsync(conversationId, offset, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<LogHits> searchEntriesAsync(
      String project, String query, Integer offset, Integer limit, RequestOptions options) {

    project = project(project);
    query = ContractChecks.text(query, "query");
    ContractChecks.nonnegative(offset, "offset");
    positive(limit, "limit");
    var selectedProject = project;
    var selectedQuery = query;
    return AsyncSupport.map(
        askAsync(
            "conversation.search",
            fields("project", project, "q", query, "offset", offset, "limit", limit),
            LogHits.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<LogHits> searchEntriesAsync(
      String project, String query, Integer offset, Integer limit) {
    return searchEntriesAsync(project, query, offset, limit, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Context> contextAsync(
      String conversationId, String agent, RequestOptions options) {

    conversationId = ContractChecks.identity(conversationId, "conversation");
    agent = ContractChecks.optionalIdentity(agent, "agent");
    var selectedConversationId = conversationId;
    var selectedAgent = agent;
    return AsyncSupport.map(
        askAsync(
            "conversation.context",
            fields("conversation", conversationId, "agent", agent),
            Context.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Context> contextAsync(
      String conversationId, String agent) {
    return contextAsync(conversationId, agent, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<Seam>> compactionsAsync(
      String conversationId, RequestOptions options) {

    conversationId = ContractChecks.identity(conversationId, "conversation");
    var selectedConversationId = conversationId;
    return AsyncSupport.map(
        listAsync(
            "conversation.compactions",
            fields("conversation", conversationId),
            Seam.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<List<Seam>> compactionsAsync(String conversationId) {
    return compactionsAsync(conversationId, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<StartedJob> curateAsync(
      String project, Integer maxModelCalls, RequestOptions options) {

    project = project(project);
    positive(maxModelCalls, "maxModelCalls");
    var selectedProject = project;
    return AsyncSupport.map(
        askAsync(
            "agent.curate",
            fields("project", project, "maxModelCalls", maxModelCalls),
            StartedJob.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<StartedJob> curateAsync(
      String project, Integer maxModelCalls) {
    return curateAsync(project, maxModelCalls, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<StartedJob> askDocumentAsync(
      String documentId, String question, Integer maxModelCalls, RequestOptions options) {

    documentId = ContractChecks.identity(documentId, "document");
    question = ContractChecks.text(question, "question");
    positive(maxModelCalls, "maxModelCalls");
    var selectedDocumentId = documentId;
    var selectedQuestion = question;
    return AsyncSupport.map(
        askAsync(
            "document.ask",
            fields("document", documentId, "question", question, "maxModelCalls", maxModelCalls),
            StartedJob.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<StartedJob> askDocumentAsync(
      String documentId, String question, Integer maxModelCalls) {
    return askDocumentAsync(documentId, question, maxModelCalls, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<JobStatus> jobAsync(
      String id, RequestOptions options) {

    id = ContractChecks.identity(id, "job");
    var selectedId = id;
    return AsyncSupport.map(
        askAsync("job.status", fields("job", id), JobStatus.class, options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedId, result.id());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<JobStatus> jobAsync(String id) {
    return jobAsync(id, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<JobStatus> cancelJobAsync(
      String id, RequestOptions options) {

    id = ContractChecks.identity(id, "job");
    var selectedId = id;
    return AsyncSupport.map(
        askAsync("job.cancel", fields("job", id), JobStatus.class, options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedId, result.id());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<JobStatus> cancelJobAsync(String id) {
    return cancelJobAsync(id, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ProjectView> defineProjectAsync(
      String name, String workspace, List<String> exclusions, RequestOptions options) {

    name = Home.of(name).project();
    workspace = ContractChecks.pathText(workspace, "workspace");
    exclusions = paths(exclusions, "exclusions", false);
    var selectedName = name;
    var selectedWorkspace = workspace;
    return AsyncSupport.map(
        askAsync(
            "project.define",
            fields("name", name, "workspace", workspace, "exclusions", exclusions),
            ProjectView.class,
            options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedName, result.name());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ProjectView> defineProjectAsync(
      String name, String workspace, List<String> exclusions) {
    return defineProjectAsync(name, workspace, exclusions, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ProjectView> lendProjectAsync(
      String name, List<String> roots, RequestOptions options) {

    name = Home.of(name).project();
    roots = paths(roots, "roots", true);
    var selectedName = name;
    var selectedRoots = roots;
    return AsyncSupport.map(
        askAsync(
            "project.lend", fields("project", name, "roots", roots), ProjectView.class, options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedName, result.name());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ProjectView> lendProjectAsync(
      String name, List<String> roots) {
    return lendProjectAsync(name, roots, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ProjectView> unlendProjectAsync(
      String name, List<String> roots, RequestOptions options) {

    name = Home.of(name).project();
    roots = paths(roots, "roots", true);
    var selectedName = name;
    var selectedRoots = roots;
    return AsyncSupport.map(
        askAsync(
            "project.unlend", fields("project", name, "roots", roots), ProjectView.class, options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedName, result.name());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ProjectView> unlendProjectAsync(
      String name, List<String> roots) {
    return unlendProjectAsync(name, roots, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ProjectView> setProjectWorkspaceAsync(
      String name, String workspace, RequestOptions options) {

    name = Home.of(name).project();
    workspace = ContractChecks.pathText(workspace, "workspace");
    var selectedName = name;
    var selectedWorkspace = workspace;
    return AsyncSupport.map(
        askAsync(
            "project.workspace",
            fields("project", name, "workspace", workspace),
            ProjectView.class,
            options),
        asyncResult -> {
          var result = asyncResult;
          selected(selectedName, result.name());
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ProjectView> setProjectWorkspaceAsync(
      String name, String workspace) {
    return setProjectWorkspaceAsync(name, workspace, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Void> moveProjectAsync(
      String name, String to, RequestOptions options) {

    name = Home.of(name).project();
    to = Home.of(to).project();
    var selectedName = name;
    return AsyncSupport.map(
        replyAsync("project.move", fields("project", name, "to", to), options),
        asyncResult -> {
          return null;
        });
  }

  public java.util.concurrent.CompletionStage<Void> moveProjectAsync(String name, String to) {
    return moveProjectAsync(name, to, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Void> forgetProjectAsync(
      String name, RequestOptions options) {

    name = Home.of(name).project();
    var selectedName = name;
    return AsyncSupport.map(
        replyAsync("project.forget", fields("project", name), options),
        asyncResult -> {
          return null;
        });
  }

  public java.util.concurrent.CompletionStage<Void> forgetProjectAsync(String name) {
    return forgetProjectAsync(name, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<List<ProposalRow>> proposalsAsync(
      String project, RequestOptions options) {

    project = project(project);
    var selectedProject = project;
    return AsyncSupport.map(
        listAsync("proposal.list", fields("project", project), ProposalRow.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<List<ProposalRow>> proposalsAsync(String project) {
    return proposalsAsync(project, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<Resolution> resolveAsync(
      String id, boolean accept, String reason, String by, RequestOptions options) {

    id = ContractChecks.identity(id, "proposal");
    reason = ContractChecks.narrative(reason, "reason", 32768, false);
    by = ContractChecks.optionalIdentity(by, "by");
    var selectedId = id;
    return AsyncSupport.map(
        askAsync(
            "proposal.resolve",
            fields("proposal", id, "accept", accept, "reason", reason, "by", by),
            Resolution.class,
            options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<Resolution> resolveAsync(
      String id, boolean accept, String reason, String by) {
    return resolveAsync(id, accept, reason, by, null);
  }

  /** Explicit multipart HTTP exception, with bounded asynchronous completion. */
  public java.util.concurrent.CompletionStage<UploadedImage> uploadImageAsync(
      String project, String filename, byte[] bytes) {

    project = Home.of(project).project();
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
    var selectedProject = project;
    return HttpAsync.call(
        uploads.newCall(request.build()),
        response -> {
          if (!response.isSuccessful())
            throw new ServerError(response.code(), "image upload refused");
          if (response.body() == null) throw new IOException("image upload returned no receipt");
          var result =
              SdkJson.decode(JSON, JSON.readTree(response.body().string()), UploadedImage.class);
          selected(selectedProject, result.project());
          return result;
        });
  }
}
