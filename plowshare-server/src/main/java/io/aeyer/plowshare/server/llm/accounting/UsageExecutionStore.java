package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AttributedTranscript;
import io.aeyer.plowshare.server.agents.RunUsage;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable run ownership, independent of jobs and inference calls. A parent that makes no model call
 * still has an execution. Snapshots survive source retention and resumption uses the same run.
 */
public final class UsageExecutionStore implements RunUsage {
  private static final int MAX_DEPTH = 128;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transaction;

  public UsageExecutionStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper mapper) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.mapper = mapper;
    this.transaction = new TransactionTemplate(transactions);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Transcript start(Home home, Transcript transcript, String agent, String account) {
    if (transcript.usage().status() != UsageAttribution.Status.LEGACY_UNATTRIBUTED) {
      if (account != null && !Objects.equals(account, transcript.usage().accountHandle())) {
        throw new LlmException("inference accounting cannot change an admitted account");
      }
      return transcript;
    }
    var spoken = transcript.spokenIn();
    if (spoken == null || !spoken.conversationId().equals(transcript.conversationId())) {
      throw new LlmException(
          "inference accounting requires a durable conversation and turn identity");
    }
    try {
      UsageAttribution owner =
          transaction.execute(ignored -> resolve(home, transcript, spoken, agent, account));
      transcript.accounted(owner);
      return new AttributedTranscript(transcript, owner, transcript.parentUsage());
    } catch (RuntimeException failure) {
      // Do not expose SQL parameters, row contents, or credential-bearing exception text.
      throw new LlmException("inference accounting could not resolve immutable run ownership");
    }
  }

  /** Read the execution owning source material, rather than the unrelated trigger's run. */
  public UsageAttribution source(String id, int turn, UsageAttribution.Operation operation) {
    return transaction.execute(
        ignored -> {
          Integer latest =
              turn == 0
                  ? jdbc.queryForObject(
                      "SELECT max(turn_ordinal) FROM inference_run_ownership WHERE conversation_id=?",
                      Integer.class,
                      id)
                  : turn;
          UsageAttribution saved = latest == null ? null : read(id, latest);
          if (saved != null) {
            return saved.withOperation(operation);
          }
          Conversation c = conversation(id);
          var owner =
              c.account() == null
                  ? UsageAttribution.system(c.projectId(), operation)
                  : c.projectId() == null
                      ? UsageAttribution.global(c.account(), operation)
                      : UsageAttribution.project(c.account(), c.projectId(), operation);
          return owner.withExecution(
              conversations(c),
              UsageLineage.NONE,
              orchestrations(id, UsageLineage.NONE),
              c.agent(),
              (long) turn,
              null);
        });
  }

  private UsageAttribution resolve(
      Home home, Transcript transcript, Transcript.Spoken spoken, String agent, String account) {
    Conversation conversation = conversation(spoken.conversationId());
    require(
        Objects.equals(home.project(), conversation.projectName()),
        "run home differs from its log");
    if (account != null && conversation.account() != null) {
      require(account.equals(conversation.account()), "run account differs from its log");
    }
    UsageAttribution saved = read(spoken.conversationId(), spoken.turnOrdinal());
    if (saved == null && transcript.continuingTurn() != null) {
      saved = read(spoken.conversationId(), transcript.continuingTurn());
    }
    int startingTurn =
        transcript.continuingTurn() == null ? spoken.turnOrdinal() : transcript.continuingTurn();
    UsageAttribution owner;
    if (saved != null) {
      require(
          saved.conversations().equals(conversations(conversation)),
          "execution cannot change lineage");
      if (transcript.parentUsage().status() != UsageAttribution.Status.LEGACY_UNATTRIBUTED) {
        require(
            Objects.equals(conversation.parent(), transcript.parentUsage().conversations().id()),
            "execution cannot change parent");
      }
      require(Objects.equals(saved.agentName(), agent), "execution cannot change agent");
      require(
          account == null || Objects.equals(account, saved.accountHandle()),
          "execution cannot change actor");
      require(
          Objects.equals(saved.projectId(), conversation.projectId()),
          "execution cannot change project");
      require(
          conversation.account() == null
              || Objects.equals(saved.accountHandle(), conversation.account()),
          "execution cannot change account");
      owner =
          saved.withExecution(
              saved.conversations(),
              saved.runs(),
              saved.orchestrations(),
              agent,
              (long) spoken.turnOrdinal(),
              null);
    } else {
      var lineage = conversations(conversation);
      var parent = transcript.parentUsage();
      UsageLineage runs;
      UsageLineage orchestration;
      if (conversation.parent() == null) {
        require(
            parent.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED,
            "root cannot name a parent");
        runs = UsageLineage.root(runId(spoken.conversationId(), startingTurn));
        orchestration = UsageLineage.NONE;
      } else {
        if (parent.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED) {
          parent = historicalParent(conversation, new HashSet<>());
        }
        require(
            conversation.parent().equals(parent.conversations().id()),
            "delegation parent differs from log");
        require(
            Objects.equals(conversation.projectId(), parent.projectId()),
            "delegation cannot change project");
        require(
            Objects.equals(
                conversation.account() != null ? conversation.account() : account,
                parent.accountHandle()),
            "delegation cannot change account");
        require(
            lineage.equals(parent.conversations().child(conversation.id())),
            "delegation lineage differs from log");
        runs = parent.runs().child(runId(spoken.conversationId(), startingTurn));
        orchestration = parent.orchestrations();
      }
      orchestration = orchestrations(conversation.id(), orchestration);
      String handle = conversation.account() != null ? conversation.account() : account;
      var operation =
          parent.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
              ? conversation.agent() != null && conversation.agent().startsWith("document_")
                  ? UsageAttribution.Operation.DOCUMENT_SUMMARY
                  : UsageAttribution.Operation.AGENT_CHAT
              : parent.operation();
      var identity =
          handle == null
              ? UsageAttribution.system(conversation.projectId(), operation)
              : conversation.projectId() == null
                  ? UsageAttribution.global(handle, operation)
                  : UsageAttribution.project(handle, conversation.projectId(), operation);
      owner =
          identity.withExecution(
              lineage, runs, orchestration, agent, (long) spoken.turnOrdinal(), null);
    }
    if (transcript.continuingTurn() != null
        && read(spoken.conversationId(), startingTurn) == null) {
      register(
          new Transcript.Spoken(spoken.conversationId(), startingTurn),
          owner.withExecution(
              owner.conversations(),
              owner.runs(),
              owner.orchestrations(),
              agent,
              (long) startingTurn,
              null));
    }
    register(spoken, owner);
    return owner;
  }

  /** Recover an older delegation's actual calling turn from its persisted tool-call reference. */
  private UsageAttribution historicalParent(Conversation child, HashSet<String> visiting) {
    require(visiting.add(child.id()) && visiting.size() <= MAX_DEPTH, "invalid execution ancestry");
    require(child.openedBy() != null, "delegation has no durable caller reference");
    List<Integer> turns =
        jdbc.queryForList(
            """
                SELECT DISTINCT turn_ordinal FROM entries
                WHERE conversation_id = ? AND (recorded_at IS NULL OR recorded_at <= ?)
                  AND tool_calls @> jsonb_build_array(jsonb_build_object('id', CAST(? AS text)))
                """,
            Integer.class,
            child.parent(),
            child.createdAt(),
            child.openedBy());
    require(turns.size() == 1, "delegation caller is missing or ambiguous");
    int turn = turns.getFirst();
    var saved = read(child.parent(), turn);
    if (saved != null) {
      return saved;
    }
    var parent = conversation(child.parent());
    var preceding = parent.parent() == null ? null : historicalParent(parent, visiting);
    UsageLineage runs =
        preceding == null
            ? UsageLineage.root(runId(parent.id(), turn))
            : preceding.runs().child(runId(parent.id(), turn));
    UsageLineage orchestration = preceding == null ? UsageLineage.NONE : preceding.orchestrations();
    var identity =
        parent.account() == null
            ? UsageAttribution.system(parent.projectId(), UsageAttribution.Operation.AGENT_CHAT)
            : parent.projectId() == null
                ? UsageAttribution.global(parent.account(), UsageAttribution.Operation.AGENT_CHAT)
                : UsageAttribution.project(
                    parent.account(), parent.projectId(), UsageAttribution.Operation.AGENT_CHAT);
    return identity.withExecution(
        conversations(parent),
        runs,
        orchestrations(parent.id(), orchestration),
        parent.agent(),
        (long) turn,
        null);
  }

  private UsageLineage conversations(Conversation current) {
    var ancestors = new ArrayList<String>();
    var seen = new HashSet<String>();
    seen.add(current.id());
    String next = current.parent();
    while (next != null) {
      require(seen.add(next) && seen.size() <= MAX_DEPTH, "invalid conversation ancestry");
      var parent = conversation(next);
      require(
          Objects.equals(current.account(), parent.account()),
          "conversation tree has different owners");
      require(
          Objects.equals(current.projectId(), parent.projectId()),
          "conversation tree has different projects");
      ancestors.add(next);
      next = parent.parent();
    }
    return new UsageLineage(current.id(), ancestors);
  }

  private UsageLineage orchestrations(String conversation, UsageLineage inherited) {
    List<String> found =
        jdbc.queryForList(
            "SELECT id FROM orchestrations WHERE conductor_conversation = ?",
            String.class,
            conversation);
    if (found.isEmpty()) {
      return inherited;
    }
    String id = found.getFirst();
    var path = new ArrayList<String>();
    var seen = new HashSet<String>();
    seen.add(id);
    String next =
        jdbc.queryForObject("SELECT parent FROM orchestrations WHERE id = ?", String.class, id);
    while (next != null) {
      require(seen.add(next) && seen.size() <= MAX_DEPTH, "invalid orchestration ancestry");
      path.add(next);
      next =
          jdbc.queryForObject("SELECT parent FROM orchestrations WHERE id = ?", String.class, next);
    }
    return new UsageLineage(id, path);
  }

  private Conversation conversation(String id) {
    List<Conversation> rows =
        jdbc.query(
            """
                SELECT c.id, c.parent_id, c.project_id, p.name AS project_name, c.owner_handle,
                       c.agent, c.opened_by_call, c.created_at
                FROM conversations c LEFT JOIN projects p ON p.id = c.project_id WHERE c.id = ?
                """,
            UsageExecutionStore::conversation,
            id);
    require(rows.size() == 1, "conversation missing");
    return rows.getFirst();
  }

  private static Conversation conversation(ResultSet row, int index) throws SQLException {
    return new Conversation(
        row.getString("id"),
        row.getString("parent_id"),
        row.getString("project_id"),
        row.getString("project_name"),
        row.getString("owner_handle"),
        row.getString("agent"),
        row.getString("opened_by_call"),
        row.getObject("created_at", OffsetDateTime.class));
  }

  private UsageAttribution read(String conversation, int turn) {
    var rows =
        jdbc.queryForList(
            "SELECT attribution::text FROM inference_run_ownership WHERE conversation_id = ? AND turn_ordinal = ?",
            String.class,
            conversation,
            turn);
    if (rows.isEmpty()) {
      return null;
    }
    try {
      return mapper.readValue(rows.getFirst(), UsageAttribution.class);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("invalid saved ownership");
    }
  }

  private void register(Transcript.Spoken spoken, UsageAttribution owner) {
    String json;
    try {
      json = mapper.writeValueAsString(owner);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("invalid ownership");
    }
    jdbc.update(
        """
                INSERT INTO inference_run_ownership(conversation_id, turn_ordinal, attribution)
                VALUES (?, ?, ?::jsonb) ON CONFLICT (conversation_id, turn_ordinal) DO NOTHING
                """,
        spoken.conversationId(),
        spoken.turnOrdinal(),
        json);
    require(
        owner.equals(read(spoken.conversationId(), spoken.turnOrdinal())),
        "conflicting execution ownership");
  }

  private static String runId(String conversation, int turn) {
    return "run:" + conversation + ":" + turn;
  }

  private static void require(boolean condition, String reason) {
    if (!condition) {
      throw new IllegalStateException(reason);
    }
  }

  private record Conversation(
      String id,
      String parent,
      String projectId,
      String projectName,
      String account,
      String agent,
      String openedBy,
      OffsetDateTime createdAt) {}
}
