package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.util.Objects;

/** Typed agent admission on an existing authenticated connection. */
public final class AgentClient {
  private final Plowshare connection;

  public AgentClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /**
   * A project is required for fresh work; an existing conversation supplies its own scope instead.
   * Personal clients must select their Personal project explicitly, never inherit it from a bearer.
   */
  public record Run(
      String agent,
      String task,
      String project,
      String session,
      String conversation,
      Boolean newConversation) {
    public Run {
      agent = ContractChecks.identity(agent, "agent");
      task = ContractChecks.text(task, "task");
      if (conversation == null && project == null)
        throw new IllegalArgumentException(
            "Choose an explicit project or an existing conversation");
      if (conversation != null && project != null)
        throw new IllegalArgumentException("conversation owns its project; omit project");
      if (project != null) project = io.aeyer.plowshare.protocol.Home.of(project).project();
      if (session != null) session = ContractChecks.identity(session, "session");
      if (conversation != null)
        conversation = ContractChecks.identity(conversation, "conversation");
      if (Boolean.TRUE.equals(newConversation) && conversation != null)
        throw new IllegalArgumentException("newConversation cannot reuse a conversation");
    }
  }

  private record Job(String job) {
    private Job {
      job = ContractChecks.identity(job, "job");
    }
  }

  public ServerClient.StartedJob run(Run request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> runAsync(request, options));
  }

  public ServerClient.JobStatus status(String job) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> statusAsync(job, options));
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ServerClient.StartedJob> runAsync(
      Run request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "agent.run", Objects.requireNonNull(request), ServerClient.StartedJob.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public java.util.concurrent.CompletionStage<ServerClient.StartedJob> runAsync(Run request) {
    return runAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public java.util.concurrent.CompletionStage<ServerClient.JobStatus> statusAsync(
      String job, RequestOptions options) {

    var request = new Job(job);
    return AsyncSupport.map(
        connection.exchangeAsync("job.status", request, ServerClient.JobStatus.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (!request.job().equals(result.id())) throw new IOException("Foreign job status");
          return result;
        });
  }

  public java.util.concurrent.CompletionStage<ServerClient.JobStatus> statusAsync(String job) {
    return statusAsync(job, null);
  }
}
