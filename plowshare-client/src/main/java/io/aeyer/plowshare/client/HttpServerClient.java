package io.aeyer.plowshare.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.search.SearchPage;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@link ServerClient} over HTTP, which is the only way this process reaches the archive.
 *
 * <p>OkHttp and not Java's {@code HttpClient}: the server's {@code OpenAiEmbeddingClient} is
 * OkHttp, and one HTTP shape in the repo is one set of timeout and connection-pool behaviours to
 * reason about when something misbehaves.
 */
public final class HttpServerClient implements ServerClient {

  /**
   * How long to wait for the connection itself. Short, because the whole point of a separate value
   * here is that "nothing is listening" should be a fast, legible answer rather than something the
   * harness times out on and reports as a hung tool.
   *
   * <p>Package-visible rather than private because {@code Plowshare} waits on a WebSocket upgrade
   * to this same server and wants the same number for the same stated reason. Referenced and not
   * restated: a second five-second constant with a paraphrase of this paragraph beside it is how a
   * number becomes folklore.
   */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * How long to wait for the answer. Generous, because a recall makes the server embed the question
   * against a local model before it can query, and a model that has just been loaded takes seconds
   * to answer the first call. A read timeout shorter than that would present as an unreachable
   * server on exactly the first call of a session.
   */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

  private static final MediaType JSON = MediaType.get("application/json");

  /**
   * What an uploaded image's part declares. Not its real media type, and {@link #uploadImage} says
   * why: the server sniffs the bytes, so a type declared here is a claim nobody reads and somebody
   * might.
   */
  private static final MediaType OCTETS = MediaType.get("application/octet-stream");

  private static final TypeReference<List<IndexEntry>> INDEX =
      new TypeReference<List<IndexEntry>>() {};

  private static final TypeReference<List<Memory>> MEMORIES = new TypeReference<List<Memory>>() {};

  private static final TypeReference<List<ProposalRow>> PROPOSALS =
      new TypeReference<List<ProposalRow>>() {};

  private static final TypeReference<List<Seam>> SEAMS = new TypeReference<List<Seam>>() {};

  private static final TypeReference<List<Conversation>> CONVERSATIONS =
      new TypeReference<List<Conversation>>() {};

  /**
   * Enough of an error body to identify it, and not so much that a stack trace or an HTML error
   * page becomes the whole tool result the model reads.
   */
  private static final int MAX_ERROR_CHARS = 500;

  private final HttpUrl base;
  private final OkHttpClient http;
  private final ObjectMapper json;

  /** Share this client's authentication and network resources for operator usage reports. */
  public UsageSocket usageSocket() throws IOException {
    return new UsageSocket(http, base, json);
  }

  /** A one-shot WebSocket read; this method never uses the legacy HTTP reporting surface. */
  public JsonNode usage(String type, java.util.Map<String, ?> payload) throws IOException {
    try (UsageSocket usage = usageSocket()) {
      return usage.request(type, payload);
    }
  }

  /**
   * The legacy adapter opens a socket for this new family; uncertain mutations are never replayed.
   */
  @Override
  public Object information(String operation, java.util.Map<String, Object> payload)
      throws IOException {
    if (!io.aeyer.plowshare.protocol.frames.InformationOperations.ALL.contains(operation))
      throw new IllegalArgumentException("unknown information operation");
    var answer = new java.util.concurrent.CompletableFuture<JsonNode>();
    String id = java.util.UUID.randomUUID().toString();
    String frame =
        json.writeValueAsString(
            new io.aeyer.plowshare.protocol.frames.Envelope(
                id,
                "information." + operation,
                io.aeyer.plowshare.protocol.frames.Envelope.CURRENT_VERSION,
                payload));
    HttpUrl url =
        base.newBuilder()
            .addPathSegments("v1/events")
            .addQueryParameter("session", java.util.UUID.randomUUID().toString())
            .build();
    var socket =
        http.newWebSocket(
            new Request.Builder().url(url).build(),
            new okhttp3.WebSocketListener() {
              @Override
              public void onOpen(okhttp3.WebSocket socket, Response response) {
                if (!socket.send(frame))
                  answer.completeExceptionally(
                      new IOException("information socket refused the request"));
              }

              @Override
              public void onMessage(okhttp3.WebSocket socket, String text) {
                try {
                  var envelope = json.readTree(text);
                  if (!id.equals(envelope.path("id").asText())) return;
                  if (!io.aeyer.plowshare.protocol.frames.Envelope.CURRENT_VERSION.equals(
                      envelope.path("protocol_version").asText()))
                    throw new IOException(
                        "information response used an unsupported protocol version");
                  answer.complete(envelope.path("payload"));
                } catch (IOException invalid) {
                  answer.completeExceptionally(invalid);
                }
              }

              @Override
              public void onFailure(
                  okhttp3.WebSocket socket, Throwable failure, Response response) {
                answer.completeExceptionally(
                    new IOException(
                        "information socket failed; mutation outcome may be uncertain; keep requestId",
                        failure));
              }

              @Override
              public void onClosed(okhttp3.WebSocket socket, int code, String reason) {
                answer.completeExceptionally(
                    new IOException("information socket closed before its answer; keep requestId"));
              }

              @Override
              public void onClosing(okhttp3.WebSocket socket, int code, String reason) {
                socket.close(code, reason);
              }
            });
    try {
      JsonNode result = answer.get(60, java.util.concurrent.TimeUnit.SECONDS);
      String code = result.path("code").asText();
      if (!code.equals("OK") && !code.equals("ACCEPTED"))
        throw new ServerError(
            422, result.path("said").asText("information request refused: " + code));
      return json.convertValue(result.get("payload"), Object.class);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new IOException("information request interrupted; keep requestId", stopped);
    } catch (java.util.concurrent.TimeoutException timedOut) {
      throw new IOException(
          "information response timed out; keep requestId to reconcile uncertain delivery",
          timedOut);
    } catch (java.util.concurrent.ExecutionException failed) {
      throw new IOException(failed.getCause().getMessage(), failed.getCause());
    } finally {
      socket.close(1000, "request complete");
    }
  }

  /**
   * A client that presents no credential.
   *
   * <p>Kept, and not deprecated, because it is the right constructor for a server with {@code
   * plowshare.auth.enabled: false} and for every test that points this at a {@code MockWebServer}.
   * Against a server with the gate on it gets a 401 from every call, reported by {@link
   * ServerError} with the server's own one-word body — which is a legible failure and not a silent
   * one.
   */
  public HttpServerClient(String baseUrl) {
    this(baseUrl, null);
  }

  /**
   * @param baseUrl where the server is
   * @param token an access token to present as {@code Authorization: Bearer}, or null for none.
   *     <b>Never logged and never put in a URL</b>: the server's own {@code Tokens} javadoc argues
   *     why a credential this process only ever presents is still a credential, and a query string
   *     reaches access logs and referrers
   */
  public HttpServerClient(String baseUrl, String token) {
    HttpUrl parsed = HttpUrl.parse(baseUrl);
    if (parsed == null) {
      // Thrown at construction rather than at the first call: a
      // mistyped base URL is a configuration error, and discovering it
      // on the model's first memory_recall makes it look like the
      // archive is empty.
      throw new IllegalArgumentException("not a usable server URL: " + baseUrl);
    }
    this.base = parsed;
    OkHttpClient.Builder building =
        new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(READ_TIMEOUT)
            .writeTimeout(CONNECT_TIMEOUT);
    if (token != null && !token.isBlank()) {
      // An interceptor rather than a line in each of the four request
      // builders below: a method added to this class later cannot forget
      // to authenticate, and forgetting would present as one endpoint
      // 401ing while the rest work.
      String header = "Bearer " + token.trim();
      building.addInterceptor(
          chain ->
              chain.proceed(chain.request().newBuilder().header("Authorization", header).build()));
    }
    this.http = building.build();
    // findAndRegisterModules picks up jackson-datatype-jsr310 from the
    // classpath; without it every Memory fails to bind on its Instant
    // fields, and the failure names a date, not a missing module.
    this.json =
        new ObjectMapper()
            .findAndRegisterModules()
            // A server that grows a field must not break a client that has
            // not been rebuilt: the two halves ship separately, and the
            // failure mode of the strict setting is every memory tool
            // erroring at once over a key nobody reads. Spring Boot's own
            // ObjectMapper on the server side is configured the same way.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  @Override
  public String baseUrl() {
    return base.toString();
  }

  @Override
  public WriteResult write(String project, MemoryProposal proposal) throws IOException {
    ObjectNode body = json.createObjectNode();
    // Written explicitly rather than left out when null: the server reads a
    // null project as the global tier, and an omitted key means the same
    // thing there — but sending it makes the request self-describing in a
    // capture, which is where these get debugged.
    body.set("project", project == null ? body.nullNode() : body.textNode(project));
    body.set("proposal", json.valueToTree(proposal));

    return post(url("v1", "memories"), body, WriteResult.class);
  }

  @Override
  public Navigation navigateMemory(String project, String question) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("project", project);
    body.put("question", question);
    return post(url("v1", "memories", "navigate"), body, Navigation.class);
  }

  @Override
  public StartedJob digestMemory(String project) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("project", project);
    return post(url("v1", "memories", "digest"), body, StartedJob.class);
  }

  @Override
  public SearchPage search(String query, int pageSize, int max, int page) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("query", query);
    body.put("pageSize", pageSize);
    body.put("max", max);
    body.put("page", page);
    return post(url("v1", "search"), body, SearchPage.class);
  }

  @Override
  public FetchWindow fetch(String pageUrl, int offset) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("url", pageUrl);
    body.put("offset", offset);
    return post(url("v1", "fetch"), body, FetchWindow.class);
  }

  @Override
  public Recall recall(String project, String question, Integer limit) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.set("project", project == null ? body.nullNode() : body.textNode(project));
    body.put("question", question);
    body.set("limit", limit == null ? body.nullNode() : body.numberNode(limit));

    // The response echoes the question and the limit alongside the hits and
    // the unsearchable count; only two of those are the client's business,
    // and reading the tree rather than binding a record avoids a second copy
    // of the server's RecallResponse shape living over here.
    JsonNode response = post(url("v1", "memories", "recall"), body, JsonNode.class);
    // asInt() on a missing node is 0, and that is the reading that matters:
    // a client built against an older server, which sends no such field,
    // reports a complete answer rather than an archive nobody can search.
    return new Recall(
        json.convertValue(response.path("memories"), MEMORIES),
        response.path("unsearchable").asInt());
  }

  @Override
  public Memory read(String id) throws IOException {
    return get(url("v1", "memories", id), Memory.class);
  }

  @Override
  public List<IndexEntry> index(String project) throws IOException {
    HttpUrl.Builder url = url("v1", "memories", "index").newBuilder();
    if (project != null) {
      // Omitted, not sent empty, when there is no project: the server
      // reads an absent parameter as global and refuses a blank one, and
      // this is the one place a null could turn into "" by accident.
      url.addQueryParameter("project", project);
    }
    return get(url.build(), INDEX);
  }

  @Override
  public DocumentSearch searchDocuments(String query, Integer limit) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("query", query);
    body.set("limit", limit == null ? body.nullNode() : body.numberNode(limit));

    // Bound rather than read off the tree, unlike `recall` one method up.
    // That one takes the tree because only two of the response's four fields
    // are the client's business; here every field is, and the shape has
    // nine of them per hit -- reading them out by name would be a second
    // spelling of DocumentHit that nothing compares against the first.
    return post(url("v1", "documents", "search"), body, DocumentSearch.class);
  }

  @Override
  public Retrieved retrieve(String query, String documentId, Integer limit) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("query", query);
    // A null document is sent as an explicit null rather than omitted, which
    // is `searchDocuments`' treatment of its own limit and the right one
    // here for a stronger reason: absent and null mean the same thing to the
    // server -- the whole corpus -- and sending the field says the caller
    // chose the corpus rather than forgot the document.
    body.set("document", documentId == null ? body.nullNode() : body.textNode(documentId));
    body.set("limit", limit == null ? body.nullNode() : body.numberNode(limit));

    return post(url("v1", "documents", "retrieve"), body, Retrieved.class);
  }

  @Override
  public DocumentPage listDocuments(String naming, Integer limit, Integer offset)
      throws IOException {
    HttpUrl.Builder url = url("v1", "documents").newBuilder();
    // Omitted rather than sent empty, as `citations` does with its two
    // filters: the server reads an absent `q` as the whole corpus and would
    // have to invent a meaning for a blank one.
    if (naming != null) {
      url.addQueryParameter("q", naming);
    }
    if (limit != null) {
      url.addQueryParameter("limit", String.valueOf(limit));
    }
    if (offset != null) {
      url.addQueryParameter("offset", String.valueOf(offset));
    }
    return get(url.build(), DocumentPage.class);
  }

  @Override
  public DocumentOutline describeDocument(String documentId) throws IOException {
    return get(url("v1", "documents", documentId), DocumentOutline.class);
  }

  @Override
  public Ranking rankDocuments(String query, Integer limit) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("query", query);
    body.set("limit", limit == null ? body.nullNode() : body.numberNode(limit));

    return post(url("v1", "documents", "rank"), body, Ranking.class);
  }

  @Override
  public Stance documentStance(String documentId, String claim) throws IOException {
    ObjectNode body = json.createObjectNode();
    // `claim` and not `query`, which every other read on this resource calls
    // the same field. The server negates this one, so the difference in the
    // name is the difference in what belongs there.
    body.put("claim", claim);

    return post(url("v1", "documents", documentId, "stance"), body, Stance.class);
  }

  @Override
  public Citations citations(String conversationId, String documentId, Integer limit)
      throws IOException {
    HttpUrl.Builder url = url("v1", "documents", "citations").newBuilder();
    // Omitted rather than sent empty when there is none, as `searchEntries`
    // does with its project: the server reads an absent parameter as "the
    // whole corpus" and would have to invent a meaning for a blank one.
    if (conversationId != null) {
      url.addQueryParameter("conversation", conversationId);
    }
    if (documentId != null) {
      url.addQueryParameter("document", documentId);
    }
    if (limit != null) {
      url.addQueryParameter("limit", String.valueOf(limit));
    }
    return get(url.build(), Citations.class);
  }

  // --- plumbing ------------------------------------------------------------

  @Override
  public StartedJob run(
      String agent, String task, String project, String session, String conversation)
      throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("task", task);
    body.set("project", project == null ? body.nullNode() : body.textNode(project));
    // Explicit null rather than an omitted key, for the reason the project
    // above is written that way: the request is self-describing in a capture,
    // which is where these get debugged. The server reads a missing key and
    // an explicit null identically and refuses a blank one.
    body.set("session", session == null ? body.nullNode() : body.textNode(session));
    body.set("conversation", conversation == null ? body.nullNode() : body.textNode(conversation));

    return post(url("v1", "agents", agent, "runs"), body, StartedJob.class);
  }

  /**
   * {@inheritDoc}
   *
   * <h2>Multipart, because that is the only body the route has</h2>
   *
   * <p>{@code POST /v1/images} takes one shape and deliberately offers no JSON-with-base64
   * alternative, so that there is one place its cap is counted and one thing a refusal is about.
   * This is the first multipart request in this class and it is written with OkHttp's own builder
   * rather than by hand: a boundary assembled here would be a second implementation of a format
   * whose failure mode is a 400 nobody can read.
   *
   * <p><b>The bytes are the only part that has to be here.</b> {@code project} and {@code name} are
   * sent only when they are something, because the route reads an absent {@code project} as the
   * global tier and an absent {@code name} as "use the part's filename" — while a <em>blank</em>
   * name is a 400 on purpose. Sending an empty string for either would turn two ordinary omissions
   * into a refusal.
   *
   * <p>The media type on the part is {@code application/octet-stream} and it is deliberately not
   * the image's: nothing on the far side reads it. {@code ImageFormat} sniffs the bytes precisely
   * so that a declared type cannot be wrong, and a client that declared one here would be inviting
   * a reader to trust it.
   */
  @Override
  public UploadedImage uploadImage(String project, String filename, byte[] bytes)
      throws IOException {
    MultipartBody.Builder parts =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // The part's own filename, which is what the server records when
            // no `name` field is sent. Never null: OkHttp writes no filename
            // parameter for a null, and Spring then binds the part with a
            // null original filename, which the route would store as an
            // image nobody can recognise in a directory of img_ files.
            .addFormDataPart(
                "file",
                filename == null || filename.isBlank() ? "image" : filename,
                RequestBody.create(bytes, OCTETS));
    if (project != null && !project.isBlank()) {
      parts.addFormDataPart("project", project);
    }
    if (filename != null && !filename.isBlank()) {
      parts.addFormDataPart("name", filename);
    }
    Request request = new Request.Builder().url(url("v1", "images")).post(parts.build()).build();
    return send(request, node -> json.treeToValue(node, UploadedImage.class));
  }

  @Override
  public Conversation openConversation(String project, Integer maxModelCalls) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.set("project", project == null ? body.nullNode() : body.textNode(project));
    // Sent as a null when it is one rather than omitted. The server reads the
    // two identically — ConversationController takes its configured default
    // for `maxModelCalls() == null` however the key arrived — so what this
    // chooses between is two ways of saying the same thing, and an explicit
    // null is the one that shows in a capture: a body with the key and no
    // number is a caller that named no allowance, where a body missing the
    // key is indistinguishable from one this client built wrong.
    //
    // This comment used to say the null was for the sake of the server's
    // refusal. There is no refusal now; there is a default. There is still
    // no number to fill in here — ServerClient.openConversation says why.
    body.set(
        "maxModelCalls", maxModelCalls == null ? body.nullNode() : body.numberNode(maxModelCalls));

    return post(url("v1", "conversations"), body, Conversation.class);
  }

  @Override
  public List<Seam> compactions(String conversationId) throws IOException {
    return get(url("v1", "conversations", conversationId, "compactions"), SEAMS);
  }

  @Override
  public List<Conversation> conversations(String project) throws IOException {
    HttpUrl.Builder url = url("v1", "conversations").newBuilder();
    if (project != null) {
      // Omitted rather than sent empty when there is none, exactly as in
      // index above: the server reads an absent parameter as global and
      // refuses a blank one.
      url.addQueryParameter("project", project);
    }
    return get(url.build(), CONVERSATIONS);
  }

  @Override
  public Entries chat(String conversationId, Integer offset, Integer limit) throws IOException {
    return get(
        paged(url("v1", "conversations", conversationId, "chat"), offset, limit), Entries.class);
  }

  @Override
  public Entries trajectory(String conversationId, Integer offset, Integer limit)
      throws IOException {
    return get(
        paged(url("v1", "conversations", conversationId, "trajectory"), offset, limit),
        Entries.class);
  }

  @Override
  public LogHits searchEntries(String project, String query, Integer offset, Integer limit)
      throws IOException {
    HttpUrl.Builder url = paged(url("v1", "entries", "search"), offset, limit).newBuilder();
    url.addQueryParameter("q", query);
    if (project != null) {
      // Omitted rather than sent empty when there is none, as in
      // conversations above: the server reads an absent parameter as the
      // global tier and refuses a blank one.
      url.addQueryParameter("project", project);
    }
    return get(url.build(), LogHits.class);
  }

  @Override
  public Context context(String conversationId, String agent) throws IOException {
    HttpUrl.Builder url = url("v1", "conversations", conversationId, "context").newBuilder();
    if (agent != null) {
      url.addQueryParameter("agent", agent);
    }
    return get(url.build(), Context.class);
  }

  /**
   * The two paging parameters, omitted when the caller had no opinion.
   *
   * <p>Absent and zero are different requests for {@code limit} — one asks for the server's own
   * number and the other is refused as a page that could hold nothing — so a null must not become a
   * 0 here. {@code offset} would survive the substitution and is written the same way anyway,
   * because a reader checking one of the two against the server's rule should not have to work out
   * why the other is different.
   */
  private static HttpUrl paged(HttpUrl base, Integer offset, Integer limit) {
    HttpUrl.Builder url = base.newBuilder();
    if (offset != null) {
      url.addQueryParameter("offset", offset.toString());
    }
    if (limit != null) {
      url.addQueryParameter("limit", limit.toString());
    }
    return url.build();
  }

  @Override
  public StartedJob curate(String project, Integer maxModelCalls) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("project", project);
    body.set(
        "maxModelCalls", maxModelCalls == null ? body.nullNode() : body.numberNode(maxModelCalls));

    return post(url("v1", "curate"), body, StartedJob.class);
  }

  @Override
  public StartedJob askDocument(String documentId, String question, Integer maxModelCalls)
      throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("question", question);
    body.set(
        "maxModelCalls", maxModelCalls == null ? body.nullNode() : body.numberNode(maxModelCalls));

    return post(url("v1", "documents", documentId, "ask"), body, StartedJob.class);
  }

  @Override
  public JobStatus job(String id) throws IOException {
    return get(url("v1", "jobs", id), JobStatus.class);
  }

  @Override
  public JobStatus cancelJob(String id) throws IOException {
    return post(url("v1", "jobs", id, "cancel"), json.createObjectNode(), JobStatus.class);
  }

  @Override
  public ProjectView defineProject(String name, String workspace, List<String> exclusions)
      throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("name", name);
    body.put("workspace", workspace);
    body.set("exclusions", json.valueToTree(exclusions));

    return post(url("v1", "projects"), body, ProjectView.class);
  }

  @Override
  public ProjectView lendProject(String name, List<String> roots) throws IOException {
    return post(url("v1", "projects", name, "lend"), rootsBody(roots), ProjectView.class);
  }

  @Override
  public ProjectView unlendProject(String name, List<String> roots) throws IOException {
    return post(url("v1", "projects", name, "unlend"), rootsBody(roots), ProjectView.class);
  }

  /** The body both lending verbs send, which is why there is one of it. */
  private ObjectNode rootsBody(List<String> roots) {
    ObjectNode body = json.createObjectNode();
    body.set("roots", json.valueToTree(roots));
    return body;
  }

  @Override
  public ProjectView setProjectWorkspace(String name, String workspace) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("workspace", workspace);

    return post(url("v1", "projects", name, "workspace"), body, ProjectView.class);
  }

  @Override
  public void moveProject(String name, String to) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("to", to);

    // The project being moved is a path segment and the name it is getting
    // is a body field, matching the endpoint: one addresses a project that
    // exists, the other says what to call it. `addPathSegment` percent-
    // encodes, so a name holding a slash cannot walk this onto another
    // endpoint -- which is the whole of what
    // a_project_name_with_a_slash_cannot_reach_another_endpoint measures,
    // and it measures it on every project-addressing call for the reason
    // that test gives.
    postExpectingNothing(url("v1", "projects", name, "move"), body);
  }

  @Override
  public void forgetProject(String name) throws IOException {
    delete(url("v1", "projects", name));
  }

  @Override
  public List<ProposalRow> proposals(String project) throws IOException {
    HttpUrl.Builder url = url("v1", "proposals").newBuilder();
    if (project != null) {
      url.addQueryParameter("project", project);
    }
    return get(url.build(), PROPOSALS);
  }

  @Override
  public Resolution resolve(String id, boolean accept, String reason, String by)
      throws IOException {
    ObjectNode body = json.createObjectNode();
    body.put("accept", accept);
    body.set("reason", reason == null ? body.nullNode() : body.textNode(reason));
    body.put("by", by);

    return post(url("v1", "proposals", id, "resolve"), body, Resolution.class);
  }

  private HttpUrl url(String... segments) {
    HttpUrl.Builder builder = base.newBuilder();
    for (String segment : segments) {
      // addPathSegment, singular: it percent-encodes, so an id or a
      // project name containing a slash cannot walk the request onto a
      // different endpoint.
      builder.addPathSegment(segment);
    }
    return builder.build();
  }

  private <T> T post(HttpUrl url, JsonNode body, Class<T> type) throws IOException {
    Request request =
        new Request.Builder()
            .url(url)
            .post(RequestBody.create(json.writeValueAsString(body), JSON))
            .build();
    return send(request, node -> json.treeToValue(node, type));
  }

  /**
   * A post whose answer is a 204, which is {@link #delete}'s case with a body going out.
   *
   * <p>Its own method for exactly {@link #delete}'s reason and not a {@code Void} through {@link
   * #post}: {@link #send} refuses a blank body, so a {@code post(..., Void.class)} against an
   * endpoint that correctly answers 204 would report a server that failed without saying so.
   */
  private void postExpectingNothing(HttpUrl url, JsonNode body) throws IOException {
    Request request =
        new Request.Builder()
            .url(url)
            .post(RequestBody.create(json.writeValueAsString(body), JSON))
            .build();
    OkHttpClient transport =
        request.url().encodedPath().equals("/v1/memories/navigate")
            ? http.newBuilder().readTimeout(Duration.ofMinutes(30)).build()
            : http;
    try (Response response = transport.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new ServerError(response.code(), detail(bodyText(response)));
      }
    }
  }

  /**
   * One exchange with nothing to bind.
   *
   * <p>Its own method rather than a {@code Void} through {@link #send}, because that one refuses a
   * blank body — rightly, since every other endpoint here promises JSON and an empty answer from
   * one of them is a server that failed without saying so. A 204 is the opposite: the absence of a
   * body <em>is</em> the answer, and there is no leash left to describe.
   */
  private void delete(HttpUrl url) throws IOException {
    Request request = new Request.Builder().url(url).delete().build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new ServerError(response.code(), detail(bodyText(response)));
      }
    }
  }

  private <T> T get(HttpUrl url, Class<T> type) throws IOException {
    return send(new Request.Builder().url(url).get().build(), node -> json.treeToValue(node, type));
  }

  private <T> T get(HttpUrl url, TypeReference<T> type) throws IOException {
    return send(
        new Request.Builder().url(url).get().build(), node -> json.convertValue(node, type));
  }

  /**
   * One exchange: send, check the status, parse the body.
   *
   * <p>The status is checked before the body is bound, so a 404's error document never gets bound
   * onto a {@link Memory} and handed back as a memory with every field null.
   */
  private <T> T send(Request request, Bind<T> bind) throws IOException {
    try (Response response = http.newCall(request).execute()) {
      String text = bodyText(response);
      if (!response.isSuccessful()) {
        throw new ServerError(response.code(), detail(text));
      }
      if (text.isBlank()) {
        throw new ServerError(
            response.code(),
            "an empty body where "
                + request.url().encodedPath()
                + " should have"
                + " answered with JSON");
      }
      return bind.apply(json.readTree(text));
    }
  }

  private static String bodyText(Response response) throws IOException {
    ResponseBody body = response.body();
    return body == null ? "" : body.string();
  }

  /**
   * The part of an error body worth showing the caller.
   *
   * <p>{@code ApiExceptionHandler} answers with {@code {"error":..., "detail":...}}, and the detail
   * is the sentence that names the offending field or id. Anything else — a container's HTML 502, a
   * stack trace — is passed through truncated rather than dropped: a message that says only "500"
   * tells the reader nothing about which server produced it.
   */
  private String detail(String text) {
    if (text.isBlank()) {
      return "(no body)";
    }
    try {
      JsonNode node = json.readTree(text);
      if (node.path("detail").isTextual()) {
        return node.get("detail").asText();
      }
      if (node.path("message").isTextual()) {
        return node.get("message").asText();
      }
    } catch (IOException notJson) {
      // Deliberately ignored: this is the fallback path, and failing to
      // parse an error body must not replace the server's complaint with
      // a parser's complaint about it.
    }
    return text.length() > MAX_ERROR_CHARS ? text.substring(0, MAX_ERROR_CHARS) + "…" : text;
  }

  /** Binding a parsed body, which can fail the same way any read can. */
  @FunctionalInterface
  private interface Bind<T> {
    T apply(JsonNode node) throws IOException;
  }
}
