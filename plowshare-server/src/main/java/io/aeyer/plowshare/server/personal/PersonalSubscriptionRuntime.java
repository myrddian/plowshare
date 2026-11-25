package io.aeyer.plowshare.server.personal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.EventRuns;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.agents.WorkCallers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.InboxItem;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.events.ScheduleDefinitions;
import io.aeyer.plowshare.server.events.SchedulePage;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.events.TriggerStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.RelayInformationEvents;
import io.aeyer.plowshare.server.messaging.MessageRefused;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import io.aeyer.plowshare.server.personal.PersonalSubscriptionStore.Binding;
import io.aeyer.plowshare.server.personal.PersonalSubscriptionStore.Key;
import io.aeyer.plowshare.server.personal.PersonalSubscriptionStore.SourceType;
import io.aeyer.plowshare.server.personal.PersonalSubscriptionStore.Spec;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayForwardingHistory;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayRetirementRepository;
import io.aeyer.plowshare.server.relay.RelayTopicMissingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns Personal configuration and the handoff from a retained event to a normal skill. */
final class PersonalSubscriptionRuntime implements PersonalEventSubscriptions {
  static final String TOPIC = "mailbox.item.added";
  private static final String PUBLISHER = "system.source";
  private static final int MAX_CONFIG_BYTES = 32_768;
  private static final int MAX_INPUT_BYTES = 32_768;
  private static final int SCAN_LIMIT = 32;
  private static final Duration START_RECOVERY = Duration.ofMinutes(1);
  private static final Duration RUNNING_REFRESH = Duration.ofSeconds(15);
  private static final Logger LOG = LoggerFactory.getLogger(PersonalSubscriptionRuntime.class);
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(
              DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
              DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
              DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
              DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    for (var shape :
        List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean))
      JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private final PersonalSpaces personal;
  private final PersonalSubscriptionStore store;
  private final Relay relay;
  private final RelayRetirementRepository retirement;
  private final RelayForwardingHistory history;
  private final int maxHops;
  private final ScheduleDefinitions definitions;
  private final ScheduleStore schedules;
  private final TriggerStore triggers;
  private final FiringStore firings;
  private final ProjectMembers members;
  private final ProjectWorkspaces projects;
  private final WorkCallers callers;
  private final SkillResolver skills;
  private final InboxStore inbox;
  private final PersonalNativeSources nativeSources;
  private final EventRuns jobs;
  private final Supplier<MessageService> messages;
  private final UnitOfWork transactions;
  private final SqlWorkBudget sql;
  private final Duration sqlCeiling;
  private Key after = new Key(0, "");

  PersonalSubscriptionRuntime(
      PersonalSpaces personal,
      PersonalSubscriptionStore store,
      Relay relay,
      RelayRetirementRepository retirement,
      RelayForwardingHistory history,
      int maxHops,
      ScheduleDefinitions definitions,
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings,
      ProjectMembers members,
      ProjectWorkspaces projects,
      WorkCallers callers,
      SkillResolver skills,
      InboxStore inbox,
      PersonalNativeSources nativeSources,
      EventRuns jobs,
      Supplier<MessageService> messages,
      UnitOfWork transactions,
      SqlWorkBudget sql,
      Duration sqlCeiling) {
    this.personal = personal;
    this.store = store;
    this.relay = relay;
    this.retirement = retirement;
    this.history = history;
    this.maxHops = maxHops;
    this.definitions = definitions;
    this.schedules = schedules;
    this.triggers = triggers;
    this.firings = firings;
    this.members = members;
    this.projects = projects;
    this.callers = callers;
    this.skills = skills;
    this.inbox = inbox;
    this.nativeSources = nativeSources;
    this.jobs = jobs;
    this.messages = messages;
    this.transactions = transactions;
    this.sql = sql;
    this.sqlCeiling = sqlCeiling;
  }

  private record Draft(String revision, Config config) {}

  private record Command(AgentDefinition agent, String mode, String hash) {}

  private record HandlerTarget(
      String agent, String skill, String mode, String hash, String route) {}

  private record Pending(UUID attempt, UUID claim) {}

  private record Admission(String event, String position, String relayInput) {}

  private record SkillInput(
      EventRuns.SubscriptionSource source, String argument, Runnable requireRecord) {
    SkillInput(EventRuns.SubscriptionSource source, String argument) {
      this(source, argument, () -> {});
    }
  }

  private record ResultInput(
      String kind,
      String subscription,
      String item,
      String schedule,
      String ending,
      String report) {}

  private record TopicInput(
      String kind,
      String subscription,
      String project,
      String topic,
      String event,
      String position,
      String publisher,
      String occurredAt,
      String text) {}

  @Override
  public View preview(String owner) {
    long project = project(owner);
    Draft draft = draft(owner);
    return transaction(() -> view(project, draft));
  }

  @Override
  public View activate(String owner, Activation request) {
    long project = project(owner);
    Draft draft = draft(owner);
    if (!request.revision().equals(draft.revision()))
      throw new CallerFault("The Personal subscriptions file changed. Preview it again.");
    var desired =
        draft.config().subscriptions().stream()
            .map(entry -> resolve(owner, project, entry))
            .sorted(
                Comparator.comparingLong((Spec spec) -> sourceTopic(project, spec).projectId())
                    .thenComparing(spec -> sourceTopic(project, spec).name()))
            .toList();
    return transaction(
        () -> {
          store.lockApplicationInboxes(
              desired.stream()
                  .filter(spec -> spec.sourceType().applicationItem())
                  .map(PersonalSubscriptionRuntime::applicationProjectId)
                  .toList());
          store.lock(project);
          String current = store.revision(project);
          if (!current.equals(request.expectedActiveRevision())) {
            if (current.equals(draft.revision())) return view(project, draft);
            throw new CallerFault(
                "The active Personal subscription revision changed. Preview it again.");
          }
          verifyOwner(project, owner);
          var existing = new HashMap<String, Binding>();
          store.bindings(project).forEach(binding -> existing.put(binding.key().name(), binding));
          store.activate(project, draft.revision());
          var mailbox =
              desired.stream().anyMatch(spec -> spec.relayTopic() == null)
                  ? relay.registerTopic(
                      topic(project), RelayPayload.Kind.LIFECYCLE, Relay.Policy.systemDefault())
                  : null;
          if (!desired.isEmpty()) {
            Instant enabledAt = store.now();
            for (Spec spec : desired) {
              Binding prior = existing.get(spec.name());
              var sourceTopic =
                  spec.relayTopic() == null
                      ? Objects.requireNonNull(mailbox)
                      : requireTopic(owner, spec);
              if (prior == null) {
                UUID consumer = UUID.randomUUID();
                if (spec.relayTopic() == null)
                  relay.subscribe(key(project, consumer), Relay.Start.LATEST);
                store.save(
                    project,
                    consumer,
                    sourceTopic.generation(),
                    spec,
                    enabledAt,
                    spec.relayTopic() == null ? 0 : sourceTopic.lastPosition());
              } else {
                if (!prior.spec().sameSource(spec)
                    || !prior.topicGeneration().equals(sourceTopic.generation()))
                  throw new CallerFault(
                      "Use a new subscription ID for a changed source or topic generation");
                store.save(
                    project,
                    prior.consumer(),
                    prior.topicGeneration(),
                    spec,
                    prior.enabledAt(),
                    prior.relaySeen());
              }
            }
          }
          var keep = desired.stream().map(Spec::name).toList();
          for (Binding prior : existing.values()) {
            if (prior.spec().relayTopic() == null && !keep.contains(prior.key().name()))
              retirement.retireMailboxCursor(key(project, prior.consumer()));
          }
          store.remove(project, keep);
          return view(project, draft);
        });
  }

  private View view(long project, Draft draft) {
    return new View(
        draft.revision(),
        store.revision(project),
        draft.config().subscriptions(),
        store.bindings(project).stream().map(this::status).toList());
  }

  private Status status(Binding binding) {
    boolean mailbox = binding.spec().sourceType() == SourceType.MAILBOX;
    boolean item = mailbox || binding.spec().sourceType().applicationItem();
    String reference = mailbox ? binding.event() : visibleRelayEvent(binding);
    return new Status(
        binding.key().name(),
        binding.spec().paused() ? "paused" : binding.state(),
        item ? reference : null,
        item ? null : reference,
        binding.job(),
        binding.detail());
  }

  private String visibleRelayEvent(Binding binding) {
    if (binding.spec().relayTopic() == null || binding.event() == null) return null;
    try {
      requireSource(binding.owner(), binding.spec());
      return binding.event();
    } catch (CallerFault | NotFoundFault denied) {
      return null;
    }
  }

  private long project(String owner) {
    if (owner == null || ServiceCredentials.principal(owner))
      throw new CallerFault("Personal subscription activation requires the human owner");
    long project = personal.id(owner).orElseThrow(() -> new CallerFault("Set up Personal first"));
    verifyOwner(project, owner);
    return project;
  }

  private void verifyOwner(long project, String owner) {
    if (owner == null || !personal.owner(project).filter(owner::equals).isPresent())
      throw new CallerFault("The Personal owner is unavailable");
    callers.requireWork(PersonalSpaces.name(owner), owner);
  }

  private Draft draft(String owner) {
    try {
      var root =
          personal
              .workspace(PersonalSpaces.name(owner))
              .orElseThrow(() -> new CallerFault("The Personal workspace is unavailable"))
              .toRealPath();
      var directory = root.resolve("Resources");
      var file = directory.resolve("subscriptions.json");
      if (Files.isSymbolicLink(directory) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new CallerFault("Create a regular Resources/subscriptions.json file in Personal");
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes(MAX_CONFIG_BYTES + 1);
        if (bytes.length > MAX_CONFIG_BYTES)
          throw new CallerFault("The Personal subscriptions file exceeds 32 KiB");
        Config config = JSON.readValue(bytes, Config.class);
        if (config == null)
          throw new CallerFault("The Personal subscriptions file must be an object");
        return new Draft(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), config);
      }
    } catch (IOException invalid) {
      throw new CallerFault(
          "The Personal subscriptions file could not be read as valid version 1 JSON");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private Spec resolve(String owner, long personalProject, Entry entry) {
    HandlerTarget target =
        switch (entry.handler()) {
          case SkillHandler skill -> {
            var command = command(owner, skill.agent(), skill.name());
            yield new HandlerTarget(
                skill.agent(), skill.name(), command.mode(), command.hash(), null);
          }
          case MessageHandler message -> {
            messaging().requirePersonalAlertRoute(owner, message.route());
            yield new HandlerTarget(null, null, null, null, message.route());
          }
        };
    return switch (entry.source()) {
      case MailboxSource source -> {
        String project = PersonalSpaces.resolveAddress(source.schedule().project(), owner);
        requireRead(owner, project);
        String schedule = schedule(owner, project, source.schedule().name());
        yield new Spec(
            entry.id(),
            project,
            schedule,
            source.ending().name(),
            null,
            SourceType.MAILBOX,
            null,
            target.agent(),
            target.skill(),
            target.mode(),
            target.hash(),
            target.route(),
            entry.paused());
      }
      case RelaySource source -> {
        long project = readableTopicProject(owner, source.project());
        yield new Spec(
            entry.id(),
            source.project(),
            null,
            null,
            new Relay.TopicKey(project, source.topic()),
            SourceType.RELAY,
            null,
            target.agent(),
            target.skill(),
            target.mode(),
            target.hash(),
            target.route(),
            entry.paused());
      }
      case ApplicationInboxSource source -> {
        long project = nativeSources.applicationProject(owner, source.project());
        boolean copy = source.copy().equals("attention");
        yield topicSpec(
            entry,
            target,
            source.project(),
            copy ? SourceType.ATTENTION_COPY : SourceType.APPLICATION_INBOX,
            new Relay.TopicKey(
                copy ? personalProject : project,
                copy
                    ? PersonalNativeSources.ATTENTION_TOPIC
                    : PersonalNativeSources.APPLICATION_TOPIC),
            copy ? project : null);
      }
      case LifecycleSource source -> {
        long project = readableTopicProject(owner, source.project());
        boolean job = source.kind().equals("job");
        yield topicSpec(
            entry,
            target,
            source.project(),
            job ? SourceType.JOB : SourceType.ORCHESTRATION,
            new Relay.TopicKey(
                project,
                job ? PersonalNativeSources.JOB_TOPIC : PersonalNativeSources.ORCHESTRATION_TOPIC),
            null);
      }
      case BoardSource source -> {
        long project = readableTopicProject(owner, source.project());
        yield topicSpec(
            entry,
            target,
            source.project(),
            SourceType.BOARD,
            new Relay.TopicKey(project, "board." + source.event()),
            null);
      }
      case InformationSource source -> {
        long project = readableTopicProject(owner, source.project());
        yield topicSpec(
            entry,
            target,
            source.project(),
            SourceType.INFORMATION,
            RelayInformationEvents.subscriptionTopic(project),
            null);
      }
    };
  }

  private static Spec topicSpec(
      Entry entry,
      HandlerTarget target,
      String project,
      SourceType type,
      Relay.TopicKey topic,
      Long attentionProject) {
    return new Spec(
        entry.id(),
        project,
        null,
        null,
        topic,
        type,
        attentionProject,
        target.agent(),
        target.skill(),
        target.mode(),
        target.hash(),
        target.route(),
        entry.paused());
  }

  private static long applicationProjectId(Spec spec) {
    return spec.attentionProject() == null
        ? spec.relayTopic().projectId()
        : spec.attentionProject();
  }

  private static Relay.TopicKey sourceTopic(long project, Spec spec) {
    return spec.relayTopic() == null ? topic(project) : spec.relayTopic();
  }

  private void requireRead(String owner, String project) {
    PersonalSpaces.requireOwn(project, owner);
    if (!members.mayUse(project, owner)) throw new CallerFault("Source project is unavailable");
  }

  private long readableTopicProject(String owner, String project) {
    requireRead(owner, project);
    Long id = projects.id(project);
    if (id == null) throw new CallerFault("Source project is unavailable");
    return id;
  }

  private void requireSource(String owner, Spec spec) {
    if (spec.sourceType() == SourceType.MAILBOX) {
      requireRead(owner, spec.sourceProject());
      return;
    }
    // An issued attention copy is the recipient's own Mailbox item.
    if (spec.sourceType() == SourceType.ATTENTION_COPY) return;
    if (spec.sourceType() == SourceType.RELAY
        && RelayInformationEvents.ownsTopic(spec.relayTopic()))
      throw new CallerFault("Use the typed information selector for this topic");
    long source =
        spec.sourceType() == SourceType.APPLICATION_INBOX
            ? nativeSources.applicationProject(owner, spec.sourceProject())
            : readableTopicProject(owner, spec.sourceProject());
    if (source != spec.relayTopic().projectId())
      throw new CallerFault("The configured source project was replaced");
  }

  private Relay.Topic requireTopic(String owner, Spec spec) {
    requireSource(owner, spec);
    if (spec.sourceType().nativeTopic())
      return relay.registerTopic(
          spec.relayTopic(),
          spec.sourceType() == SourceType.INFORMATION
              ? RelayPayload.Kind.TEXT
              : RelayPayload.Kind.LIFECYCLE,
          Relay.Policy.systemDefault());
    Relay.Topic topic;
    try {
      topic = relay.topic(spec.relayTopic());
    } catch (RelayTopicMissingException missing) {
      throw new CallerFault("Choose an existing readable Relay topic");
    }
    if (topic.kind() != RelayPayload.Kind.TEXT && topic.kind() != RelayPayload.Kind.EMPTY)
      throw new CallerFault("Choose a Relay topic with TEXT or EMPTY payloads");
    return topic;
  }

  private String schedule(String owner, String project, String name) {
    var exact = definitions.managed(name, owner);
    if (exact.isPresent()) return mailboxSchedule(exact.get(), project);
    var legacy = schedules.find(name).filter(schedule -> schedule.definedBy().equals(owner));
    if (legacy.isPresent()) {
      var schedule = legacy.get();
      if (triggers.listening(schedule.emits()).stream()
          .anyMatch(
              trigger ->
                  owner.equals(trigger.definedBy())
                      && trigger.conversation() == null
                      && Objects.equals(
                          project,
                          trigger.project() == null
                              ? PersonalSpaces.name(owner)
                              : trigger.project()))) return name;
    }
    // Personal schedule inspection uses the owner's existing unqualified listing.
    String scope = project.equals(PersonalSpaces.name(owner)) ? null : project;
    var matches = new ArrayList<ScheduledWork.File>();
    String cursor = null;
    for (int page = 0; page < 16; page++) {
      var files = definitions.page(owner, scope, new SchedulePage(cursor, 100));
      files.stream()
          .filter(file -> Objects.equals(project, file.project()) && name.equals(file.name()))
          .forEach(matches::add);
      if (matches.size() > 1)
        throw new CallerFault("The schedule name is ambiguous in this project");
      if (files.size() < 100) {
        if (matches.size() == 1)
          return definitions
              .managed(matches.getFirst().internalName(), owner)
              .map(file -> mailboxSchedule(file, project))
              .orElseThrow(() -> new CallerFault("The Mailbox schedule is unavailable"));
        throw new CallerFault("No Mailbox schedule matches the configured project and name");
      }
      cursor = files.getLast().internalName();
    }
    throw new CallerFault("Use the exact runtime schedule name for this project");
  }

  private static String mailboxSchedule(ScheduledWork.File file, String project) {
    if (project.equals(file.project())
        && file.definition() != null
        && file.definition().target().kind().equals("mailbox")) return file.internalName();
    throw new CallerFault("No Mailbox schedule matches the configured project and name");
  }

  private Command command(String owner, String agentName, String skillName) {
    var access = callers.callerFor(PersonalSpaces.name(owner), null, owner);
    var agent = callers.requireAgent(agentName, access);
    if (agent.isSystem() || agent.nativeOnly() || ScriptProgram.isScript(agent.prompt()))
      throw new CallerFault("Choose a Personal agent that supports bound skills");
    var skill = skills.forCaller(access).granted(agent).get(skillName);
    if (skill == null)
      throw new CallerFault("The Personal agent is not granted the configured skill");
    var definition = skill.definition();
    return new Command(
        agent, definition.mode() == null ? "DIRECT" : definition.mode().name(), definition.hash());
  }

  /** A bounded, round-robin pass. PostgreSQL fences other workers racing the same subscription. */
  void process() {
    var batch = sql.within(sqlCeiling, () -> store.workAfter(after, SCAN_LIMIT));
    after = batch.size() < SCAN_LIMIT ? new Key(0, "") : batch.getLast().key();
    for (Binding binding : batch) {
      try {
        process(binding);
      } catch (RuntimeException failure) {
        LOG.warn(
            "Personal subscription pass failed for project {}", binding.key().project(), failure);
      }
    }
  }

  private void process(Binding binding) {
    if (binding.state().equals("starting")) {
      if (binding.updatedAt().plus(START_RECOVERY).isBefore(Instant.now())) {
        UUID claim = UUID.randomUUID();
        boolean reclaimed =
            transaction(
                () -> {
                  store.lock(binding.key().project());
                  return store.reclaim(binding, claim);
                });
        // A job cannot start before its ID is linked by this claim. A newer claim fences a
        // worker that stalled before that point, even if its Java thread is still alive.
        if (reclaimed) start(binding, new Pending(binding.attempt(), claim));
      }
      return;
    }
    if (binding.state().equals("running") || binding.state().equals("uncertain")) {
      if (binding.jobEnding() != null) {
        settle(binding, "completed", binding.jobEnding());
      } else if (binding.state().equals("running")) {
        refreshRunning(binding);
      }
      return;
    }
    if (binding.spec().relayTopic() == null && !mailboxReadable(binding)) return;
    Pending pending;
    try {
      pending = transaction(() -> admit(binding));
    } catch (RelayTopicMissingException missing) {
      settle(binding, "blocked", "Source topic unavailable. Activate a new subscription ID.");
      return;
    }
    if (pending != null) start(binding, pending);
  }

  private void refreshRunning(Binding binding) {
    Instant now = Instant.now();
    if (binding.updatedAt().plus(RUNNING_REFRESH).isAfter(now)) return;
    transaction(
        () -> {
          if (jobs.subscriptionJobActive(binding.job())) store.renewRunning(binding);
          else if (binding.updatedAt().plus(START_RECOVERY).isBefore(now))
            store.unconfirmed(binding);
          return null;
        });
  }

  private Pending admit(Binding snapshot) {
    store.lock(snapshot.key().project());
    var current = store.find(snapshot.key()).filter(snapshot::equals);
    if (current.isEmpty()) return null;
    if (snapshot.spec().relayTopic() != null) return admitProjectTopic(snapshot);
    var selected = key(snapshot.key().project(), snapshot.consumer());
    if (!snapshot.topicGeneration().equals(relay.topic(selected.topic()).generation())) {
      store.settle(snapshot, "blocked", "Mailbox topic replaced. Activate a new subscription ID.");
      return null;
    }
    var read = relay.read(selected, 1);
    if (read.gap().isPresent()) {
      store.settle(
          snapshot,
          "blocked",
          "Mailbox events expired. Activate a new subscription ID to start from now.");
      return null;
    }
    if (read.publications().isEmpty()) return null;
    var input = read.publications().getFirst();
    var notice = matching(snapshot, input);
    Pending pending = null;
    if (notice != null) {
      pending = prepare(snapshot, input, new Admission(notice.subject(), notice.related(), null));
      if (pending == null) {
        store.settle(
            snapshot,
            "source-unavailable",
            "The Mailbox event's effect budget is unavailable or exhausted.");
      }
    }
    relay.advanceSeen(selected, input.position());
    return pending;
  }

  private Pending admitProjectTopic(Binding snapshot) {
    // Check authority under the owner lock, before loading bytes or recording a disposition.
    try {
      requireBindingAccess(snapshot);
    } catch (CallerFault | NotFoundFault denied) {
      store.settle(
          snapshot, "blocked", "Source or Personal access unavailable. Reactivate after review.");
      return null;
    }
    var read =
        relay.follow(
            snapshot.spec().relayTopic(), snapshot.topicGeneration(), snapshot.relaySeen());
    if (!snapshot.topicGeneration().equals(read.topic().generation())) {
      store.settle(snapshot, "blocked", "Source topic replaced. Activate a new subscription ID.");
      return null;
    }
    if (snapshot.relaySeen() < read.topic().expiredThrough()) {
      store.settle(
          snapshot,
          "blocked",
          "Relay events expired. Activate a new subscription ID to start from now.");
      return null;
    }
    if (read.next().isEmpty()) return null;
    var next = read.next().get();
    if (snapshot.spec().sourceType() == SourceType.INFORMATION)
      return admitInformation(snapshot, next);
    if (snapshot.spec().sourceType().nativeTopic()) return admitNative(snapshot, next);
    Pending pending = null;
    String detail = "The Relay publication exceeds the bound skill input limit.";
    if (next.publication().isPresent()) {
      var publication = next.publication().get();
      var event = publication.event();
      String text = event.payload() instanceof RelayPayload.Text body ? body.text() : null;
      var input =
          new TopicInput(
              "relay.publication",
              snapshot.key().name(),
              snapshot.spec().sourceProject(),
              read.topic().key().name(),
              event.eventId(),
              Long.toString(next.position()),
              event.publisher(),
              event.occurredAt().toString(),
              text);
      var argument = argument(input);
      if (argument.isPresent()) {
        pending =
            prepare(
                snapshot,
                publication,
                new Admission(event.eventId(), Long.toString(next.position()), argument.get()));
        detail = "The Relay publication's effect budget is unavailable or exhausted.";
      }
    }
    store.advanceRelay(
        snapshot,
        next.position(),
        pending == null ? next.eventId() : null,
        pending == null ? detail : null);
    return pending;
  }

  private Pending admitNative(Binding binding, Relay.Followed next) {
    var publication = next.publication().orElse(null);
    if (publication == null
        || !PUBLISHER.equals(publication.event().publisher())
        || !publication.event().occurredAt().isAfter(binding.enabledAt())
        || !(publication.event().payload() instanceof RelayPayload.Lifecycle notice)
        || !notice.source().equals(binding.spec().relayTopic().name())
        || (binding.spec().sourceType().applicationItem()
            && !Long.toString(applicationProjectId(binding.spec())).equals(notice.context()))) {
      store.advanceRelay(binding, next.position(), null, null);
      return null;
    }
    var reference =
        new PersonalNativeSources.Reference(
            publication.event().eventId(),
            notice.subject(),
            notice.state(),
            Objects.toString(
                PersonalNativeSources.BOARD_POST_TOPIC.equals(notice.source())
                    ? notice.context()
                    : notice.related(),
                ""),
            publication.event().occurredAt().toString());
    String selected =
        binding.spec().sourceType().applicationItem() ? notice.subject() : reference.event();
    return retainNative(binding, next, publication, selected, reference);
  }

  private Pending admitInformation(Binding binding, Relay.Followed next) {
    var publication = next.publication().orElse(null);
    Optional<RelayInformationEvents.Finalisation> notice;
    try {
      notice =
          publication == null
              ? Optional.empty()
              : RelayInformationEvents.subscriptionFinalisation(publication);
    } catch (IllegalArgumentException invalid) {
      store.advanceRelay(binding, next.position(), next.eventId(), "Invalid information event.");
      return null;
    }
    if (notice.isEmpty()) {
      store.advanceRelay(binding, next.position(), null, null);
      return null;
    }
    var finalised = notice.get();
    var reference =
        new PersonalNativeSources.InformationReference(
            next.eventId(),
            finalised.revision(),
            finalised.generation(),
            publication.event().occurredAt().toString());
    return retainNative(binding, next, publication, reference.event(), reference);
  }

  private Pending retainNative(
      Binding binding,
      Relay.Followed next,
      Relay.Publication publication,
      String selected,
      Record reference) {
    Pending pending =
        prepare(
            binding,
            publication,
            new Admission(selected, Long.toString(next.position()), encode(reference)));
    store.advanceRelay(
        binding,
        next.position(),
        pending == null ? selected : null,
        pending == null ? "The native event's effect budget is unavailable or exhausted." : null);
    return pending;
  }

  private Pending prepare(Binding binding, Relay.Publication publication, Admission admission) {
    var cause =
        history
            .causation(publication, maxHops)
            .filter(value -> value.depth() >= 0 && value.depth() < maxHops);
    if (cause.isEmpty()) return null;
    var pending = new Pending(UUID.randomUUID(), UUID.randomUUID());
    store.prepare(
        binding,
        admission.event(),
        admission.position(),
        admission.relayInput(),
        cause.get().next(publication.event().eventId(), maxHops),
        pending.attempt(),
        pending.claim());
    return pending;
  }

  private boolean mailboxReadable(Binding binding) {
    try {
      requireBindingAccess(binding);
      return true;
    } catch (CallerFault | NotFoundFault denied) {
      settle(binding, "blocked", "Source or Personal access unavailable. Reactivate after review.");
      return false;
    }
  }

  private void requireBindingAccess(Binding binding) {
    verifyOwner(binding.key().project(), binding.owner());
    requireSource(binding.owner(), binding.spec());
  }

  private static RelayPayload.Lifecycle matching(Binding binding, Relay.Publication input) {
    if (!PUBLISHER.equals(input.event().publisher())
        || !input.event().occurredAt().isAfter(binding.enabledAt())
        || !(input.event().payload() instanceof RelayPayload.Lifecycle notice)
        || !TOPIC.equals(notice.source())
        || notice.related() == null
        || !binding.spec().schedule().equals(notice.context())
        || !binding.spec().ending().equals(notice.state())) return null;
    return notice;
  }

  private void start(Binding binding, Pending pending) {
    Binding starting =
        sql.within(sqlCeiling, () -> store.find(binding.key()))
            .filter(
                value ->
                    pending.attempt().equals(value.attempt())
                        && pending.claim().equals(value.claim())
                        && value.state().equals("starting"))
            .orElse(null);
    if (starting == null || starting.spec().paused()) return;
    if (!binding.spec().equals(starting.spec())) {
      settle(starting, "source-unavailable", "The subscription changed before dispatch.");
      return;
    }
    if (starting.spec().messageRoute() != null) {
      startMessage(starting);
      return;
    }
    try {
      requireStartSource(starting);
    } catch (CallerFault | NotFoundFault denied) {
      settle(starting, "blocked", "Source access or topic unavailable. Review the subscription.");
      return;
    }
    try {
      var command = command(binding.owner(), binding.spec().agent(), binding.spec().skill());
      if (!starting.spec().skillHash().equals(command.hash())) {
        settle(starting, "blocked", "The skill changed. Preview and reactivate the subscription.");
        return;
      }
      SkillInput input = skillInput(starting);
      var action =
          new ScheduledWork.Action(
              "skill",
              command.agent().name(),
              starting.spec().skill(),
              input.argument(),
              command.mode());
      jobs.submitSubscriptionSkill(
          command.agent(),
          action.utterance(),
          Home.of(PersonalSpaces.name(binding.owner())),
          TurnCap.of(command.agent().maxTurns()),
          binding.owner(),
          Speaker.relay(pending.attempt()),
          starting.cause(),
          new EventRuns.SubscriptionSkill(
              input.source(),
              starting.spec().skill(),
              command.hash(),
              () -> {
                requireStartSource(starting);
                input.requireRecord().run();
              },
              job -> {
                requireStartSource(starting);
                input.requireRecord().run();
                if (!transaction(
                    () -> {
                      store.lock(binding.key().project());
                      return store.started(starting, job);
                    }))
                  throw new CallerFault("The subscription changed before the job could start");
              }));
    } catch (CallerFault | NotFoundFault denied) {
      settle(starting, "source-unavailable", "The source or handler is no longer available.");
    } // A failed unlinked start can be reclaimed. Linked jobs are reconciled without replay.
  }

  private MessageService messaging() {
    var service = messages.get();
    if (service == null) throw new CallerFault("Messaging is unavailable");
    return service;
  }

  private void startMessage(Binding starting) {
    try {
      transaction(
          () -> {
            store.lock(starting.key().project());
            if (store.find(starting.key()).filter(starting::equals).isEmpty()) return null;
            requireStartSource(starting);
            if (starting.spec().sourceType() == SourceType.MAILBOX) {
              requireScheduleOccurrence(starting);
              if (!inbox.canSeeScheduledResult(
                  starting.owner(), starting.event(), starting.position()))
                throw new CallerFault("The exact Mailbox result is unavailable");
            } else {
              String retained =
                  store
                      .relayInput(starting)
                      .orElseThrow(() -> new CallerFault("The admitted inbox item is unavailable"));
              nativeSources.requireApplicationAlert(
                  starting, reference(retained, PersonalNativeSources.Reference.class));
            }
            String message =
                messaging()
                    .sendPersonalAlert(
                        starting.owner(),
                        starting.spec().messageRoute(),
                        new MessageService.PersonalAlert(
                            starting.event(),
                            starting.spec().sourceType() == SourceType.MAILBOX
                                ? starting.position()
                                : null),
                        starting.cause());
            // The transport receipt and disposition commit together. Retry keeps this same item
            // key.
            store.settle(starting, "completed", "Queued message " + message);
            return null;
          });
    } catch (CallerFault | NotFoundFault denied) {
      if (denied instanceof MessageRefused refusal
          && refusal.code() == MessageRefused.Code.QUEUE_FULL)
        return; // Existing starting-claim recovery retries when the recipient has capacity.
      settle(starting, "source-unavailable", "The inbox item or bot route is unavailable.");
    }
  }

  private void requireStartSource(Binding binding) {
    requireBindingAccess(binding);
    if (binding.spec().relayTopic() != null) {
      try {
        if (!binding
            .topicGeneration()
            .equals(relay.topic(binding.spec().relayTopic()).generation()))
          throw new CallerFault("The source topic was replaced");
      } catch (RelayTopicMissingException missing) {
        throw new CallerFault("The source topic is unavailable");
      }
    }
  }

  private SkillInput skillInput(Binding binding) {
    if (binding.spec().relayTopic() != null) {
      String argument =
          sql.within(sqlCeiling, () -> store.relayInput(binding))
              .orElseThrow(() -> new CallerFault("The admitted Relay publication is unavailable"));
      if (binding.spec().sourceType().nativeTopic()) {
        var input =
            binding.spec().sourceType() == SourceType.INFORMATION
                ? nativeSources.information(
                    binding, reference(argument, PersonalNativeSources.InformationReference.class))
                : nativeSources.read(
                    binding, reference(argument, PersonalNativeSources.Reference.class));
        return new SkillInput(
            input.source(),
            argument(input.evidence())
                .orElseThrow(
                    () -> new CallerFault("The selected result exceeds the skill input limit")),
            input.requireRecord());
      }
      return new SkillInput(
          new EventRuns.ProjectSource(binding.spec().relayTopic().projectId()), argument);
    }
    var item = sourceItem(binding);
    String argument =
        argument(
                new ResultInput(
                    "mailbox.result",
                    binding.key().name(),
                    item.id(),
                    binding.spec().schedule(),
                    item.ending(),
                    item.answer()))
            .orElseThrow(
                () -> new CallerFault("The Mailbox result exceeds the bound skill input limit"));
    return new SkillInput(new EventRuns.LogSource(item.conversation()), argument);
  }

  private InboxItem sourceItem(Binding binding) {
    requireScheduleOccurrence(binding);
    return sql.within(
            sqlCeiling,
            () -> inbox.scheduledResult(binding.owner(), binding.event(), binding.position()))
        .filter(
            item ->
                binding.spec().ending().equals(item.ending())
                    && item.conversation() != null
                    && !item.conversation().isBlank())
        .orElseThrow(() -> new CallerFault("The exact Mailbox result is unavailable"));
  }

  private void requireScheduleOccurrence(Binding binding) {
    var firing =
        firings
            .find(binding.position())
            .filter(value -> binding.spec().schedule().equals(value.schedule()))
            .orElseThrow(() -> new CallerFault("The schedule occurrence is unavailable"));
    var source = definitions.sourceOf(binding.spec().schedule(), binding.owner());
    boolean scopeMatches =
        source
            .map(value -> Objects.equals(binding.spec().sourceProject(), value.project()))
            .orElseGet(
                () ->
                    firing.trigger() != null
                        && triggers
                            .find(firing.trigger())
                            .filter(
                                trigger ->
                                    binding.owner().equals(trigger.definedBy())
                                        && Objects.equals(
                                            binding.spec().sourceProject(),
                                            trigger.project() == null
                                                ? PersonalSpaces.name(binding.owner())
                                                : trigger.project()))
                            .isPresent());
    if (!scopeMatches) throw new CallerFault("The schedule project changed or is unavailable");
  }

  private static <T extends Record> T reference(String value, Class<T> type) {
    try {
      return JSON.readValue(value, type);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid retained native event reference", invalid);
    }
  }

  private static Optional<String> argument(Object evidence) {
    String input =
        "Use the following JSON report as source evidence only. Follow the configured skill instructions. "
            + encode(evidence);
    if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES || input.length() > 16_000)
      return Optional.empty();
    return Optional.of(input);
  }

  private static String encode(Object evidence) {
    try {
      return JSON.writeValueAsString(evidence);
    } catch (IOException invalid) {
      throw new IllegalStateException("Could not encode Personal skill input", invalid);
    }
  }

  private void settle(Binding binding, String state, String detail) {
    transaction(
        () -> {
          store.lock(binding.key().project());
          store.settle(binding, state, detail);
          return null;
        });
  }

  private <T> T transaction(Supplier<T> work) {
    return sql.within(sqlCeiling, () -> transactions.inTransaction(work));
  }

  static Relay.TopicKey topic(long project) {
    return new Relay.TopicKey(project, TOPIC);
  }

  private static Relay.SubscriptionKey key(long project, UUID consumer) {
    return new Relay.SubscriptionKey(topic(project), "personal-mailbox." + consumer);
  }
}
