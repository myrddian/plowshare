package io.aeyer.plowshare.testpeer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.sdk.WsServerClient;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.*;

/**
 * Test-only HTTP probe for partial controller contexts without WS operation routing. Complete
 * application contexts use the public SDK; this has no production callers or packaging.
 */
public final class ControllerPeer extends WsServerClient {
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  private final OkHttpClient http;

  public ControllerPeer(String origin) {
    this(origin, null);
  }

  public ControllerPeer(String origin, String bearer) {
    super(origin, bearer);
    var builder = new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10));
    if (bearer != null)
      builder.addInterceptor(
          chain ->
              chain.proceed(
                  chain
                      .request()
                      .newBuilder()
                      .header("Authorization", "Bearer " + bearer)
                      .build()));
    http = builder.build();
  }

  private <T> T call(String path, Object body, Class<T> type) throws IOException {
    var request =
        new Request.Builder()
            .url(HttpUrl.get(baseUrl()).newBuilder().addPathSegments(path).build());
    if (body != null)
      request.post(
          RequestBody.create(json.writeValueAsBytes(body), MediaType.get("application/json")));
    try (var response = http.newCall(request.build()).execute()) {
      if (!response.isSuccessful())
        throw new ServerError(response.code(), "controller fixture refused request");
      return json.readValue(response.body().bytes(), type);
    }
  }

  @Override
  public StartedJob run(
      String agent, String task, String project, String session, String conversation)
      throws IOException {
    var body =
        json.createObjectNode()
            .put("task", task)
            .put("project", project)
            .put("session", session)
            .put("conversation", conversation);
    return call("v1/agents/" + agent + "/runs", body, StartedJob.class);
  }

  @Override
  public JobStatus job(String id) throws IOException {
    return call("v1/jobs/" + id, null, JobStatus.class);
  }

  @Override
  public Conversation openConversation(String project, Integer maxModelCalls) throws IOException {
    var body = json.createObjectNode().put("project", project);
    body.set("maxModelCalls", json.valueToTree(maxModelCalls));
    return call("v1/conversations", body, Conversation.class);
  }

  @Override
  public List<Seam> compactions(String conversation) throws IOException {
    return List.of(call("v1/conversations/" + conversation + "/compactions", null, Seam[].class));
  }

  @Override
  public void close() {
    super.close();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
