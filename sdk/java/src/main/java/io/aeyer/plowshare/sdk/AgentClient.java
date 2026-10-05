package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.util.Objects;

/** Typed agent admission on an existing authenticated connection. */
public final class AgentClient {
  private final Plowshare connection;

  public AgentClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

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
    return connection.exchange(
        "agent.run", Objects.requireNonNull(request), ServerClient.StartedJob.class);
  }

  public ServerClient.JobStatus status(String job) throws IOException {
    var request = new Job(job);
    var result = connection.exchange("job.status", request, ServerClient.JobStatus.class);
    if (!request.job().equals(result.id())) throw new IOException("Foreign job status");
    return result;
  }
}
