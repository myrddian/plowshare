package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Typed forum operations. Membership, tool grants and close permission are checked by the server.
 * Retain mutation request UUIDs before submission; receipts never authorize a retry on their own.
 */
public final class BoardClient {
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TopicsQuery(String project, Integer offset, Integer limit) {
    public TopicsQuery {
      ForumChecks.optional(project);
      ForumChecks.page(offset, limit);
    }
  }

  public record TopicQuery(String topic) {
    public TopicQuery {
      ForumChecks.id(topic);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Open(
      String project,
      String swarm,
      String title,
      String label,
      String body,
      String requestId,
      Integer maxModelCalls) {
    public Open {
      ForumChecks.id(project);
      if (swarm != null) ForumChecks.name(swarm);
      ForumChecks.text(title, 32768, true);
      ForumChecks.text(label, 32768, true);
      ForumChecks.text(body, 16000, true);
      ForumChecks.uuid(requestId);
      ForumChecks.budget(maxModelCalls);
    }
  }

  public record Retry(String project, String topic, String member, String requestId, int maxTurns) {
    public Retry {
      ForumChecks.id(project);
      ForumChecks.id(topic);
      ForumChecks.id(member, 128);
      ForumChecks.uuid(requestId);
      ForumChecks.positive(maxTurns, 1);
    }
  }

  public record Post(String project, String topic, String body, String requestId) {
    public Post {
      ForumChecks.id(project);
      ForumChecks.id(topic);
      ForumChecks.text(body, 16000, true);
      ForumChecks.uuid(requestId);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Close(
      String project, String topic, String body, List<String> cites, String requestId) {
    public Close {
      ForumChecks.id(project);
      ForumChecks.id(topic);
      if (!topic.matches("bdt_[A-Za-z0-9]+"))
        throw new IllegalArgumentException("Invalid board topic");
      ForumChecks.text(body, 16000, true);
      if (cites != null) {
        cites = ForumChecks.ids(cites, 200);
        if (cites.stream().anyMatch(id -> !id.matches("bdm_[A-Za-z0-9]+")))
          throw new IllegalArgumentException("Invalid citation");
      }
      ForumChecks.uuid(requestId);
    }
  }

  public record TopUp(String topic, int maxModelCalls) {
    public TopUp {
      ForumChecks.id(topic);
      ForumChecks.positive(maxModelCalls, 2);
    }
  }

  /**
   * Retained participant selection belongs to the root and its subtopics, independently of live
   * files.
   */
  public record Topic(
      String id,
      String project,
      @JsonProperty(required = true) String parent,
      String root,
      int depth,
      String title,
      String label,
      String account,
      String openerKind,
      String opener,
      @JsonProperty(required = true) String originConversation,
      String state,
      @JsonProperty(required = true) String resolution,
      @JsonProperty(required = true) Long potTotal,
      @JsonProperty(required = true) Long potSpent,
      @JsonProperty(required = true) Long reserve,
      @JsonProperty(required = true) String quietNotifiedAt,
      Instant openedAt,
      @JsonProperty(required = true) Instant closedAt,
      @JsonProperty(required = true) SwarmClient.Selection swarm) {
    public Topic {
      ForumChecks.id(id);
      ForumChecks.id(project);
      ForumChecks.optional(parent);
      ForumChecks.id(root);
      ForumChecks.count(depth);
      ForumChecks.text(title, 32768, true);
      ForumChecks.text(label, 32768, true);
      ForumChecks.id(account);
      ForumChecks.id(openerKind);
      ForumChecks.id(opener);
      ForumChecks.optional(originConversation);
      ForumChecks.id(state);
      ForumChecks.optional(resolution);
      ForumChecks.count(potTotal);
      ForumChecks.count(potSpent);
      ForumChecks.count(reserve);
      ContractChecks.timestamp(quietNotifiedAt, "quietNotifiedAt");
      Objects.requireNonNull(openedAt);
    }
  }

  public record Message(
      String id,
      String topic,
      @JsonProperty(required = true) String replyTo,
      String authorKind,
      String author,
      @JsonProperty(required = true) String conversation,
      @JsonProperty(required = true) Long entry,
      String kind,
      @JsonProperty(required = true) String title,
      String body,
      boolean alert,
      List<String> mentions,
      Instant postedAt) {
    public Message {
      ForumChecks.id(id);
      ForumChecks.id(topic);
      ForumChecks.optional(replyTo);
      ForumChecks.id(authorKind);
      ForumChecks.id(author);
      ForumChecks.optional(conversation);
      ForumChecks.count(entry);
      ForumChecks.id(kind);
      if (title != null) ForumChecks.text(title, 512, false);
      ForumChecks.text(body, 32768, false);
      Objects.requireNonNull(body);
      mentions = ForumChecks.ids(mentions, 10000);
      Objects.requireNonNull(postedAt);
    }
  }

  public record Seat(
      String topic,
      String occupant,
      String conversation,
      boolean passed,
      @JsonProperty(required = true) String failedEnding,
      int silentWakes,
      @JsonProperty(required = true) String seenThrough,
      int alertsUsed) {
    public Seat {
      ForumChecks.id(topic);
      ForumChecks.id(occupant);
      ForumChecks.id(conversation);
      ForumChecks.optional(failedEnding);
      ForumChecks.optional(seenThrough);
      ForumChecks.count(silentWakes);
      ForumChecks.count(alertsUsed);
    }
  }

  public record SeatView(
      Seat seat,
      String state,
      @JsonProperty(required = true) String job,
      @JsonProperty(required = true) String reason,
      @JsonProperty(required = true) Integer position,
      @JsonProperty(required = true) Long waitedMillis,
      boolean overdue) {
    public SeatView {
      Objects.requireNonNull(seat);
      ForumChecks.id(state);
      ForumChecks.optional(job);
      if (reason != null) ForumChecks.text(reason, 32768, false);
      if (position != null) ForumChecks.count(position);
      ForumChecks.count(waitedMillis);
    }
  }

  public record Decision(
      String request,
      boolean approved,
      String reason,
      @JsonProperty(required = true) String child) {
    public Decision {
      ForumChecks.id(request);
      ForumChecks.text(reason, 32768, false);
      ForumChecks.optional(child);
    }
  }

  public record Summary(Topic topic, int messages, int documents) {
    public Summary {
      Objects.requireNonNull(topic);
      ForumChecks.count(messages);
      ForumChecks.count(documents);
    }
  }

  public record Topics(List<Summary> topics, boolean more, int offset) {
    public Topics {
      topics = ForumChecks.list(topics, 200);
      ForumChecks.count(offset);
    }
  }

  public record Detail(
      Topic topic,
      Topic root,
      List<Message> messages,
      List<SeatView> seats,
      List<Decision> decisions) {
    public Detail {
      Objects.requireNonNull(topic);
      Objects.requireNonNull(root);
      messages = ForumChecks.list(messages, 10000);
      seats = ForumChecks.list(seats, 10000);
      decisions = ForumChecks.list(decisions, 10000);
      if (!topic.root().equals(root.id())
          || !topic.project().equals(root.project())
          || !Objects.equals(topic.swarm(), root.swarm())
          || messages.stream().anyMatch(m -> !m.topic().equals(topic.id()))
          || seats.stream().anyMatch(s -> !s.seat().topic().equals(topic.id())))
        throw new IllegalArgumentException("Foreign board detail");
    }
  }

  public record Opened(String requestId, Topic topic, Message message) {
    public Opened {
      ForumChecks.uuid(requestId);
      Objects.requireNonNull(topic);
      Objects.requireNonNull(message);
      if (topic.swarm() == null || !topic.id().equals(message.topic()))
        throw new IllegalArgumentException("Invalid opened topic");
    }
  }

  public record Retried(String requestId, String member, int maxTurns, Message message) {
    public Retried {
      ForumChecks.uuid(requestId);
      ForumChecks.id(member, 128);
      ForumChecks.positive(maxTurns, 1);
      Objects.requireNonNull(message);
    }
  }

  public record Posted(String requestId, Message message) {
    public Posted {
      ForumChecks.uuid(requestId);
      Objects.requireNonNull(message);
    }
  }

  public record Closed(String requestId, Topic topic, Message message) {
    public Closed {
      ForumChecks.uuid(requestId);
      Objects.requireNonNull(topic);
      Objects.requireNonNull(message);
      if (!topic.id().equals(message.topic()))
        throw new IllegalArgumentException("Foreign close message");
    }
  }

  private final Plowshare connection;

  public BoardClient(Plowshare connection) {
    this.connection = Objects.requireNonNull(connection);
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Topics topics(TopicsQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> topicsAsync(request, options));
  }

  public CompletionStage<Topics> topicsAsync(TopicsQuery request) {
    return topicsAsync(request, null);
  }

  public CompletionStage<Topics> topicsAsync(TopicsQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("board.topics", request, Topics.class, options),
        result -> {
          if (!(result.offset() == (request.offset() == null ? 0 : request.offset())
              && result.topics().size() <= (request.limit() == null ? 200 : request.limit())
              && (request.project() == null
                  || result.topics().stream()
                      .allMatch(s -> request.project().equals(s.topic().project())))))
            throw new IOException("Foreign or inconsistent board.topics response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Detail messages(TopicQuery request) throws IOException {
    return AsyncSupport.await(
        connection.defaultOptions(), options -> messagesAsync(request, options));
  }

  public CompletionStage<Detail> messagesAsync(TopicQuery request) {
    return messagesAsync(request, null);
  }

  public CompletionStage<Detail> messagesAsync(TopicQuery request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("board.messages", request, Detail.class, options),
        result -> {
          if (!(request.topic().equals(result.topic().id())))
            throw new IOException("Foreign or inconsistent board.messages response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Opened open(Open request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> openAsync(request, options));
  }

  public CompletionStage<Opened> openAsync(Open request) {
    return openAsync(request, null);
  }

  public CompletionStage<Opened> openAsync(Open request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("board.open", request, Opened.class, options),
        result -> {
          if (!(request.requestId().equals(result.requestId())
              && request.project().equals(result.topic().project())
              && (request.swarm() == null
                  || request.swarm().equals(result.topic().swarm().name()))))
            throw new IOException("Foreign or inconsistent board.open response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Retried retry(Retry request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> retryAsync(request, options));
  }

  public CompletionStage<Retried> retryAsync(Retry request) {
    return retryAsync(request, null);
  }

  public CompletionStage<Retried> retryAsync(Retry request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("board.retry", request, Retried.class, options),
        result -> {
          if (!(request.requestId().equals(result.requestId())
              && request.topic().equals(result.message().topic())
              && request.member().equals(result.member())
              && request.maxTurns() == result.maxTurns()))
            throw new IOException("Foreign or inconsistent board.retry response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Posted post(Post request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> postAsync(request, options));
  }

  public CompletionStage<Posted> postAsync(Post request) {
    return postAsync(request, null);
  }

  public CompletionStage<Posted> postAsync(Post request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("board.post", request, Posted.class, options),
        result -> {
          if (!(request.requestId().equals(result.requestId())
              && request.topic().equals(result.message().topic())))
            throw new IOException("Foreign or inconsistent board.post response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Closed close(Close request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> closeAsync(request, options));
  }

  public CompletionStage<Closed> closeAsync(Close request) {
    return closeAsync(request, null);
  }

  public CompletionStage<Closed> closeAsync(Close request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("board.close", request, Closed.class, options),
        result -> {
          if (!(request.requestId().equals(result.requestId())
              && request.topic().equals(result.topic().id())
              && request.project().equals(result.topic().project())))
            throw new IOException("Foreign or inconsistent board.close response");
          return result;
        });
  }

  /** One bounded operation; an uncertain mutation is never replayed automatically. */
  public Topic topUp(TopUp request) throws IOException {
    return AsyncSupport.await(connection.defaultOptions(), options -> topUpAsync(request, options));
  }

  public CompletionStage<Topic> topUpAsync(TopUp request) {
    return topUpAsync(request, null);
  }

  public CompletionStage<Topic> topUpAsync(TopUp request, RequestOptions options) {
    Objects.requireNonNull(request);
    return AsyncSupport.map(
        connection.exchangeAsync("board.topup", request, Topic.class, options),
        result -> {
          if (!(request.topic().equals(result.id())))
            throw new IOException("Foreign or inconsistent board.topup response");
          return result;
        });
  }
}
