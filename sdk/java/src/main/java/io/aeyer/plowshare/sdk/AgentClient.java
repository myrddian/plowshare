package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.protocol.Home;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Typed agent admission on an existing authenticated connection. */
public final class AgentClient {
  private final Plowshare connection;

  public AgentClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** Explicit discovery scope; listing never infers a project from socket state or credentials. */
  public record ListQuery(String project) {
    public ListQuery {
      project = Home.of(ForumChecks.id(project)).project();
    }
  }

  /** Public discovery projection. Unexposed future definition fields are ignored deliberately. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Definition(
      @JsonProperty(required = true) String name,
      @JsonProperty(required = true) String description,
      @JsonProperty(required = true) boolean served,
      @JsonProperty(required = true) boolean bot,
      @JsonProperty(required = true) List<Command> commands) {
    public Definition {
      ForumChecks.id(name);
      ForumChecks.text(description, 32768, false);
      Objects.requireNonNull(description);
      commands = ForumChecks.list(commands, 10000);
      if (commands.stream().map(Command::command).distinct().count() != commands.size())
        throw new IllegalArgumentException("Duplicate served command");
    }
  }

  /** Complete served-command metadata; discovery never grants permission to execute a command. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Command(
      @JsonProperty(required = true) String command,
      @JsonProperty(required = true) List<String> aliases,
      @JsonProperty(required = true) String kind,
      @JsonProperty(required = true) String name,
      @JsonProperty(required = true) String description,
      @JsonProperty(required = true) String argumentHint,
      @JsonProperty(required = true) String executor,
      @JsonProperty(required = true) String mode,
      @JsonProperty(required = true) String tier,
      @JsonProperty(required = true) String hash,
      @JsonProperty(required = true) boolean agentVisible) {
    public Command {
      if (!Set.of("skill", "orchestration").contains(kind))
        throw new IllegalArgumentException("Invalid command kind");
      ForumChecks.id(name);
      if (!name.matches("[A-Za-z0-9_.-]+") || !Objects.equals(command, "/" + kind + ":" + name))
        throw new IllegalArgumentException("Inconsistent command identity");
      aliases = ForumChecks.ids(aliases, 32);
      if (aliases.stream().distinct().count() != aliases.size())
        throw new IllegalArgumentException("Duplicate command alias");
      ForumChecks.text(description, 32768, false);
      Objects.requireNonNull(description);
      ForumChecks.text(argumentHint, 4096, false);
      if (executor != null) ForumChecks.id(executor);
      if (mode != null && !Set.of("INHERITED", "SUMMARISED", "NEW", "DIRECT").contains(mode))
        throw new IllegalArgumentException("Invalid command context mode");
      if (!Set.of("GLOBAL", "SESSION", "PROJECT", "PERSONAL", "SHIPPED").contains(tier)
          || hash == null
          || !hash.matches("sha256:[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid command origin");
    }
  }

  /**
   * Lists the existing public agent.list projection. Server admission still checks current grants.
   */
  public List<Definition> list(String project) throws IOException {
    return list(new ListQuery(project));
  }

  public List<Definition> list(ListQuery query) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> listAsync(query, options));
  }

  public CompletionStage<List<Definition>> listAsync(String project) {
    return listAsync(new ListQuery(project), null);
  }

  public CompletionStage<List<Definition>> listAsync(ListQuery query) {
    return listAsync(query, null);
  }

  public CompletionStage<List<Definition>> listAsync(ListQuery query, RequestOptions options) {
    Objects.requireNonNull(query);
    return AsyncSupport.map(
        connection.exchangeAsync("agent.list", query, Definition[].class, options),
        rows -> {
          var result = List.of(Objects.requireNonNull(rows));
          if (result.size() > 10000
              || result.stream().map(Definition::name).distinct().count() != result.size())
            throw new IOException("Invalid agent catalog bound or duplicate definition");
          return result;
        });
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
      if (project != null) project = Home.of(project).project();
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
  public CompletionStage<ServerClient.StartedJob> runAsync(Run request, RequestOptions options) {

    return AsyncSupport.map(
        connection.exchangeAsync(
            "agent.run", Objects.requireNonNull(request), ServerClient.StartedJob.class, options),
        asyncResult -> {
          return asyncResult;
        });
  }

  public CompletionStage<ServerClient.StartedJob> runAsync(Run request) {
    return runAsync(request, null);
  }

  /** Asynchronous typed counterpart; cancellation never cancels remote work. */
  public CompletionStage<ServerClient.JobStatus> statusAsync(String job, RequestOptions options) {

    var request = new Job(job);
    return AsyncSupport.map(
        connection.exchangeAsync("job.status", request, ServerClient.JobStatus.class, options),
        asyncResult -> {
          var result = asyncResult;
          if (!request.job().equals(result.id())) throw new IOException("Foreign job status");
          return result;
        });
  }

  public CompletionStage<ServerClient.JobStatus> statusAsync(String job) {
    return statusAsync(job, null);
  }
}
