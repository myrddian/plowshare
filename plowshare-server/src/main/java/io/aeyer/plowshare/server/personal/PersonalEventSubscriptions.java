package io.aeyer.plowshare.server.personal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.aeyer.plowshare.protocol.ContainedProjects;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.ClientProjects;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Account-owned activation and inspection of Personal event subscriptions. */
public interface PersonalEventSubscriptions {
  View preview(String owner);

  /** Activates the exact previewed bytes against the expected current active revision. */
  View activate(String owner, Activation activation);

  record Activation(String revision, String expectedActiveRevision) {
    public Activation {
      revision = revisionValue(revision, false);
      expectedActiveRevision = revisionValue(expectedActiveRevision, true);
    }
  }

  record View(
      String revision, String activeRevision, List<Entry> subscriptions, List<Status> active) {
    public View {
      subscriptions = List.copyOf(subscriptions);
      active = List.copyOf(active);
    }
  }

  /** Fixed state and references only. Source text follows the owning inbox/job read gates. */
  record Status(String id, String state, String item, String event, String job, String detail) {}

  record Config(int version, List<Entry> subscriptions) {
    public Config {
      if (version != 1)
        throw new IllegalArgumentException("Use subscription configuration version 1");
      subscriptions = List.copyOf(subscriptions);
      if (subscriptions.size() > 8)
        throw new IllegalArgumentException("At most eight Personal subscriptions are allowed");
      var names = new HashSet<String>();
      for (var entry : subscriptions) {
        if (!names.add(entry.id())) throw new IllegalArgumentException("Duplicate subscription ID");
      }
    }
  }

  record Entry(String id, Source source, Handler handler, boolean paused) {
    public Entry {
      if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}"))
        throw new IllegalArgumentException("Choose a lowercase subscription ID");
      Objects.requireNonNull(source);
      Objects.requireNonNull(handler);
      if (handler instanceof MessageHandler
          && !(source instanceof MailboxSource || source instanceof ApplicationInboxSource))
        throw new IllegalArgumentException(
            "Message handlers require a Mailbox or Application inbox source");
    }
  }

  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "kind",
      visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = MailboxSource.class, name = "mailbox"),
    @JsonSubTypes.Type(value = RelaySource.class, name = "relay"),
    @JsonSubTypes.Type(value = ApplicationInboxSource.class, name = "application-inbox"),
    @JsonSubTypes.Type(
        value = LifecycleSource.class,
        name = "job",
        names = {"orchestration"}),
    @JsonSubTypes.Type(value = BoardSource.class, name = "board"),
    @JsonSubTypes.Type(value = InformationSource.class, name = "information")
  })
  sealed interface Source
      permits MailboxSource,
          RelaySource,
          ApplicationInboxSource,
          LifecycleSource,
          BoardSource,
          InformationSource {}

  record MailboxSource(String kind, String event, Schedule schedule, Outcome.Ending ending)
      implements Source {
    public MailboxSource {
      if (!"mailbox".equals(kind) || !"item.added".equals(event))
        throw new IllegalArgumentException("Choose the Mailbox item.added source");
      Objects.requireNonNull(schedule);
      Objects.requireNonNull(ending);
      if (ending == Outcome.Ending.AWAITING)
        throw new IllegalArgumentException("Choose a terminal schedule ending");
    }
  }

  record RelaySource(String kind, String project, String topic) implements Source {
    public RelaySource {
      if (!"relay".equals(kind)) throw new IllegalArgumentException("Choose the Relay source");
      ordinaryProject(project);
      topic = RelayPort.topic(topic);
    }
  }

  record ApplicationInboxSource(String kind, String project, String copy) implements Source {
    public ApplicationInboxSource {
      if (!"application-inbox".equals(kind)
          || !("project".equals(copy) || "attention".equals(copy)))
        throw new IllegalArgumentException(
            "Choose a project inbox item or personal attention copy");
      ordinaryProject(project);
    }
  }

  record LifecycleSource(String kind, String project, String event) implements Source {
    public LifecycleSource {
      if (!("job".equals(kind) || "orchestration".equals(kind)) || !"ended".equals(event))
        throw new IllegalArgumentException("Choose a job or orchestration ending");
      ordinaryProject(project);
    }
  }

  record BoardSource(String kind, String project, String event) implements Source {
    public BoardSource {
      if (!"board".equals(kind) || !("message.posted".equals(event) || "resolved".equals(event)))
        throw new IllegalArgumentException("Choose Board message.posted or resolved");
      ordinaryProject(project);
    }
  }

  record InformationSource(String kind, String project, String event) implements Source {
    public InformationSource {
      if (!"information".equals(kind) || !"report.finalised".equals(event))
        throw new IllegalArgumentException("Choose the information report.finalised event");
      ordinaryProject(project);
    }
  }

  private static void ordinaryProject(String project) {
    Home.of(project);
    if (project.startsWith("personal:")
        || project.startsWith("Personal:")
        || ClientProjects.privateProject(project)
        || ContainedProjects.name(project))
      throw new IllegalArgumentException("Choose an ordinary project for this source");
  }

  record Schedule(String project, String name) {
    public Schedule {
      Home.of(project);
      ScheduledWork.identity(name, "schedule name");
    }
  }

  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "kind",
      visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = SkillHandler.class, name = "skill"),
    @JsonSubTypes.Type(value = MessageHandler.class, name = "message")
  })
  sealed interface Handler permits SkillHandler, MessageHandler {}

  record SkillHandler(String kind, String agent, String name) implements Handler {
    public SkillHandler {
      if (!"skill".equals(kind)) throw new IllegalArgumentException("Choose a skill handler");
      agent = ScheduledWork.identity(agent, "agent");
      name = ScheduledWork.identity(name, "skill");
    }
  }

  record MessageHandler(String kind, String route) implements Handler {
    public MessageHandler {
      if (!"message".equals(kind)) throw new IllegalArgumentException("Choose a message handler");
      route = ScheduledWork.identity(route, "Personal bot route");
    }
  }

  private static String revisionValue(String value, boolean allowEmpty) {
    if (value == null || !(value.matches("[0-9a-f]{64}") || allowEmpty && value.isEmpty()))
      throw new IllegalArgumentException("Use the revision returned by subscription preview");
    return value;
  }
}
