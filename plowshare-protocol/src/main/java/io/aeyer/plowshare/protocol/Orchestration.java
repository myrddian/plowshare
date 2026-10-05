package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Caller-facing orchestration contracts. Identity and authority remain server-owned. */
public final class Orchestration {
  private Orchestration() {}

  private static String id(String value) {
    return ContractValues.identity(value, "id", 1024);
  }

  private static String optionalId(String value) {
    return ContractValues.optionalIdentity(value, "identity", 1024);
  }

  private static String text(String value) {
    return ContractValues.text(value, "text", 1048576, false);
  }

  private static String state(String value) {
    if (!Set.of("running", "asking", "waiting", "finished", "failed", "capped", "cancelled")
        .contains(value)) throw new IllegalArgumentException("invalid orchestration state");
    return value;
  }

  private static String tier(String value) {
    if (!Set.of("global", "personal", "project", "session", "shipped").contains(value))
      throw new IllegalArgumentException("invalid definition tier");
    return value;
  }

  public record Start(
      String agent,
      String definition,
      String request,
      String context,
      String project,
      UUID requestId) {
    public Start {
      agent = id(agent);
      definition = id(definition);
      project = optionalId(project);
      request = ContractValues.text(request, "request", 1048576, true);
      context = text(context);
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  public record Receipt(UUID requestId) {
    public Receipt {
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  /** A stable key identifies one explicit resume, including after uncertain delivery. */
  public record Resume(String id, UUID requestId) {
    public Resume {
      id = Orchestration.id(id);
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  public record Reference(String id) {
    public Reference {
      id = Orchestration.id(id);
    }
  }

  public record ListedQuery(String project, String state, Integer limit) {
    public ListedQuery {
      project = optionalId(project);
      if (state != null) state = Orchestration.state(state);
      if (limit != null && (limit < 1 || limit > 200))
        throw new IllegalArgumentException("limit must be 1..200");
    }
  }

  public record DefinitionQuery(String project) {
    public DefinitionQuery {
      project = optionalId(project);
    }
  }

  public record Answer(String id, String answer, List<Choice> choices) {
    public Answer {
      id = Orchestration.id(id);
      answer = text(answer);
      if (choices != null) {
        choices = uniqueChoices(choices);
        if (choices.isEmpty()) throw new IllegalArgumentException("choices cannot be empty");
      }
      if (choices == null && (answer == null || answer.isBlank()))
        throw new IllegalArgumentException("answer needs text or choices");
    }
  }

  public record Started(String id, String state, UUID requestId) {
    public Started {
      id = Orchestration.id(id);
      state = Orchestration.state(state);
      Objects.requireNonNull(requestId, "requestId");
    }
  }

  public record Changed(String id, String state) {
    public Changed {
      id = Orchestration.id(id);
      state = Orchestration.state(state);
    }
  }

  public record StageView(String id, String doneWhen, List<String> mayReturnTo) {
    public StageView {
      id = Orchestration.id(id);
      doneWhen = ContractValues.text(doneWhen, "doneWhen", 32768, false);
      mayReturnTo =
          ContractValues.list(mayReturnTo, "return stages", 1000).stream()
              .map(Orchestration::id)
              .toList();
    }
  }

  public record DefinitionView(
      String name,
      String description,
      String tier,
      List<StageView> stages,
      List<String> triggers,
      boolean served,
      String withheld) {
    public DefinitionView {
      name = id(name);
      description = text(description);
      withheld = text(withheld);
      stages = ContractValues.list(stages, "stages", 1000);
      triggers =
          ContractValues.list(triggers, "triggers", 1000).stream()
              .map(Orchestration::text)
              .toList();
      if (served) {
        tier = Orchestration.tier(tier);
        if (withheld != null)
          throw new IllegalArgumentException("served definition cannot be withheld");
      } else if (tier != null
          || !stages.isEmpty()
          || !triggers.isEmpty()
          || withheld == null
          || withheld.isBlank())
        throw new IllegalArgumentException("withheld definition must carry its reason only");
    }
  }

  public record Definitions(List<DefinitionView> definitions) {
    public Definitions {
      definitions = ContractValues.list(definitions, "definitions", 10000);
    }
  }

  public record Listed(List<RunView> orchestrations) {
    public Listed {
      orchestrations = ContractValues.list(orchestrations, "orchestrations", 200);
    }
  }

  public record RunView(
      String id,
      String definition,
      String tier,
      String project,
      String state,
      String pendingCap,
      String result,
      String failure,
      int returnsUsed,
      int maxReturns,
      int nudges,
      int restarts,
      String callerAgent,
      String callerConversation,
      String conductorConversation,
      String parent,
      int depth,
      String waitingFor,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant endedAt,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant stalledSince) {
    public RunView {
      id = Orchestration.id(id);
      definition = Orchestration.id(definition);
      tier = Orchestration.tier(tier);
      project = optionalId(project);
      state = Orchestration.state(state);
      pendingCap = optionalId(pendingCap);
      result = text(result);
      failure = text(failure);
      callerAgent = optionalId(callerAgent);
      callerConversation = optionalId(callerConversation);
      conductorConversation = Orchestration.id(conductorConversation);
      parent = optionalId(parent);
      waitingFor = optionalId(waitingFor);
      if (returnsUsed < 0 || maxReturns < 0 || nudges < 0 || restarts < 0 || depth < 0)
        throw new IllegalArgumentException("invalid orchestration counters");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  public record ChildView(String id, String state) {
    public ChildView {
      id = Orchestration.id(id);
      state = Orchestration.state(state);
    }
  }

  public record Todo(
      String id,
      String parent,
      int position,
      String text,
      String status,
      String summary,
      boolean locked,
      String stage,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt) {
    public Todo {
      id = Orchestration.id(id);
      parent = optionalId(parent);
      text = ContractValues.text(text, "todo text", 1048576, true);
      summary = Orchestration.text(summary);
      stage = optionalId(stage);
      Objects.requireNonNull(updatedAt, "updatedAt");
      if (position < 0 || !Set.of("pending", "in_progress", "done", "dropped").contains(status))
        throw new IllegalArgumentException("invalid todo state");
    }
  }

  public record MessageView(
      String id,
      String kind,
      String text,
      String author,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant deliveredAt,
      String capKind,
      Structure structure) {
    public MessageView {
      id = Orchestration.id(id);
      text = ContractValues.text(text, "message text", 1048576, true);
      author = Orchestration.id(author);
      Objects.requireNonNull(createdAt, "createdAt");
      capKind = optionalId(capKind);
      if (!Set.of("question", "answer").contains(kind))
        throw new IllegalArgumentException("invalid message kind");
      if (structure != null && ("question".equals(kind) != (structure.questions() != null)))
        throw new IllegalArgumentException("structure differs from message kind");
    }
  }

  public record Status(
      RunView orchestration,
      List<Todo> todos,
      List<MessageView> messages,
      List<ChildView> children) {
    public Status {
      Objects.requireNonNull(orchestration, "orchestration");
      todos = ContractValues.list(todos, "todos", 10000);
      messages = ContractValues.list(messages, "messages", 10000);
      children = ContractValues.list(children, "children", 10000);
    }
  }

  /** Exactly one question or answer shape; install details are allowed only on a question. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Structure(
      String lead,
      List<Question> questions,
      List<Choice> choices,
      String name,
      String path,
      String text,
      String sha256) {
    public Structure {
      if ((questions == null) == (choices == null))
        throw new IllegalArgumentException("structure needs questions or choices");
      if (questions != null) {
        lead = ContractValues.text(lead, "lead", 1048576, true);
        questions = ContractValues.list(questions, "questions", 4);
        if (questions.isEmpty()
            || questions.stream().map(Question::header).distinct().count() != questions.size())
          throw new IllegalArgumentException("question headers must be unique");
      } else {
        choices = uniqueChoices(choices);
        if (lead != null) throw new IllegalArgumentException("answer cannot have a lead");
      }
      if (name != null || path != null || text != null || sha256 != null) {
        if (questions == null) throw new IllegalArgumentException("install requires questions");
        name = ContractValues.identity(name, "draft name", 256);
        path = ContractValues.identity(path, "draft path", 8192);
        text = ContractValues.text(text, "draft text", 1048576, true);
        if (sha256 == null || !sha256.matches("sha256:[a-f0-9]{64}"))
          throw new IllegalArgumentException("invalid draft hash");
      }
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Option(String label, String description, String preview) {
    public Option {
      label = ContractValues.identity(label, "option label", 60);
      description = ContractValues.text(description, "option description", 300, true).strip();
      preview = ContractValues.text(preview, "preview", 4096, false);
    }
  }

  public record Question(
      String header,
      String question,
      @JsonProperty(defaultValue = "false") boolean multi,
      List<Option> options) {
    public Question {
      header = ContractValues.identity(header, "header", 12);
      question = ContractValues.text(question, "question", 1048576, true).strip();
      options = ContractValues.list(options, "options", 4);
      if (options.size() < 2
          || options.stream().map(Option::label).distinct().count() != options.size())
        throw new IllegalArgumentException("question needs 2..4 distinct options");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Choice(String header, List<String> chosen, String other, String note) {
    public Choice {
      header = ContractValues.identity(header, "header", 12);
      chosen =
          chosen == null
              ? List.of()
              : ContractValues.list(chosen, "chosen", 4).stream()
                  .map(label -> ContractValues.identity(label, "chosen label", 60))
                  .toList();
      if (chosen.stream().distinct().count() != chosen.size())
        throw new IllegalArgumentException("chosen labels must be unique");
      other = free(other);
      note = free(note);
    }
  }

  private static String free(String value) {
    value = ContractValues.text(value, "choice text", 2000, false);
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static List<Choice> uniqueChoices(List<Choice> choices) {
    choices = ContractValues.list(choices, "choices", 4);
    if (choices.stream().map(Choice::header).distinct().count() != choices.size())
      throw new IllegalArgumentException("choice headers must be unique");
    return choices;
  }
}
