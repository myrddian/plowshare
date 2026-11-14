package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Read-only swarm type discovery and bounded current scheduling inspection. */
public final class SwarmClient {
  public record StatusQuery() {}

  public record TypesQuery(String project) {
    public TypesQuery {
      ForumChecks.id(project);
    }
  }

  public record Selection(
      String name,
      String revision,
      String description,
      List<String> members,
      @JsonProperty(required = true) Integer budget) {
    public Selection {
      ForumChecks.name(name);
      if (revision == null || !revision.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid swarm revision");
      ForumChecks.text(description, 4096, false);
      Objects.requireNonNull(description);
      members = SwarmClient.members(members, false);
      ForumChecks.budget(budget);
    }
  }

  public record Type(
      String name,
      List<String> members,
      @JsonProperty(required = true) Integer budget,
      Map<String, String> refused,
      String origin,
      @JsonProperty(required = true) Selection selection) {
    public Type {
      ForumChecks.name(name);
      members = SwarmClient.members(members, true);
      ForumChecks.budget(budget);
      Objects.requireNonNull(refused);
      if (refused.size() > 64) throw new IllegalArgumentException("Too many refusals");
      refused.forEach(
          (member, reason) -> {
            ForumChecks.id(member, 128);
            ForumChecks.text(reason, 4096, false);
            Objects.requireNonNull(reason);
          });
      refused = Map.copyOf(refused);
      ForumChecks.id(origin, 1024);
      if (selection == null
          ? !members.isEmpty()
          : !selection.name().equals(name)
              || !selection.members().equals(members)
              || !Objects.equals(selection.budget(), budget))
        throw new IllegalArgumentException("Inconsistent swarm selection");
    }
  }

  public record Types(String project, List<Type> types) {
    public Types {
      ForumChecks.id(project);
      types = ForumChecks.list(types, 64);
      if (types.stream().map(Type::name).distinct().count() != types.size())
        throw new IllegalArgumentException("Duplicate swarm type");
    }
  }

  public record Pool(String pool, int slots, int used) {
    public Pool {
      ForumChecks.id(pool);
      ForumChecks.count(slots);
      ForumChecks.count(used);
    }
  }

  public record Ready(
      String topic,
      String member,
      String specifier,
      int position,
      long waitedMillis,
      boolean overdue) {
    public Ready {
      ForumChecks.id(topic);
      ForumChecks.id(member);
      ForumChecks.id(specifier);
      ForumChecks.count(position);
      ForumChecks.count(waitedMillis);
    }
  }

  public record Status(
      List<Pool> pools,
      List<Ready> ready,
      List<BoardClient.Summary> topics,
      List<BoardClient.SeatView> seats,
      boolean more) {
    public Status {
      pools = ForumChecks.list(pools, 10000);
      ready = ForumChecks.list(ready, 10000);
      topics = ForumChecks.list(topics, 10000);
      seats = ForumChecks.list(seats, 10000);
    }
  }

  private static List<String> members(List<String> values, boolean emptyAllowed) {
    var result = ForumChecks.list(values, 64);
    result.forEach(v -> ForumChecks.id(v, 128));
    if (!emptyAllowed && result.isEmpty() || new HashSet<>(result).size() != result.size())
      throw new IllegalArgumentException("Invalid swarm members");
    return result;
  }

  private final Plowshare connection;

  public SwarmClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Types types(TypesQuery request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> typesAsync(request, options));
  }

  public CompletionStage<Types> typesAsync(TypesQuery request) {
    return typesAsync(request, null);
  }

  public CompletionStage<Types> typesAsync(TypesQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("swarm.types", request, Types.class, options),
        result -> {
          if (!(request.project().equals(result.project())))
            throw new IOException("Foreign or inconsistent swarm.types response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Status status(StatusQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> statusAsync(request, options));
  }

  public CompletionStage<Status> statusAsync(StatusQuery request) {
    return statusAsync(request, null);
  }

  public CompletionStage<Status> statusAsync(StatusQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("swarm.status", request, Status.class, options),
        result -> {
          if (!(true)) throw new IOException("Foreign or inconsistent swarm.status response");
          return result;
        });
  }
}
