package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Indexed point lookup and atomic first-open under one transaction. A transaction-scoped advisory
 * lock avoids creating a losing root/allowance; the complete-key primary key remains the durable
 * uniqueness backstop. Hash collisions only serialize unrelated opens, never equate their keys.
 * Neither a miss nor an uncertain failure is retried. This repository never calls hooks.
 */
public final class JdbcConversationAliasRepository
    implements ConversationAliasRepository, AutoCloseable {
  private final JdbcTemplate jdbc;
  private final ConversationCreation conversations;
  private final TransactionTemplate transactions;
  private final ConversationAliasProperties properties;
  private final ScheduledExecutorService watchdogs;
  private final MeterRegistry metrics;
  private final Object lifecycle = new Object();
  private final Set<ConversationAliasSqlDeadline> active = ConcurrentHashMap.newKeySet();
  private boolean closed;

  public JdbcConversationAliasRepository(
      JdbcTemplate jdbc,
      ConversationCreation conversations,
      PlatformTransactionManager transactions,
      ConversationAliasProperties properties,
      MeterRegistry metrics) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.conversations = Objects.requireNonNull(conversations);
    this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions));
    // Scope validation executes before a first-open waiter obtains its advisory lock. Each
    // statement must take a fresh snapshot after that wait, so it can observe the winning alias.
    // Inheriting REPEATABLE_READ/SERIALIZABLE would retain the pre-wait miss and attempt a second
    // root. This repository owns its transaction (outer transactions are refused), so the choice
    // does not lower a caller's transaction isolation. Ordinary creation/current authority checks
    // participate in these same READ_COMMITTED statement snapshots and unchanged row-lock fences.
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.properties = Objects.requireNonNull(properties);
    this.metrics = Objects.requireNonNull(metrics);
    this.transactions.setTimeout((int) Math.max(1, properties.timeout().toSeconds()));
    this.watchdogs =
        Executors.newScheduledThreadPool(
            2, Thread.ofPlatform().daemon().name("conversation-alias-deadline-", 0).factory());
  }

  @Override
  public Opened open(Key key, Opening opening) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(opening);
    return transaction(
        "open",
        deadline -> {
          Scope scope = scope(key, deadline);
          query(
              "key_lock",
              deadline,
              () ->
                  jdbc.queryForObject(
                      "SELECT pg_advisory_xact_lock(?) IS NULL", Boolean.class, lockIdentity(key)));
          Optional<ConversationRecord> existing = target(key, scope, deadline);
          if (existing.isPresent()) return new Opened(existing.get(), false);

          // The ordinary owner retains admission and the only conversation/allowance INSERT recipe.
          // The project row's SHARE lock prevents rename/delete between resolved ID and its Home;
          // ProjectIds' ordinary name confirmation is thus a concurrency fence, not an alias cache.
          ConversationRecord opened =
              query(
                  "ordinary_create",
                  deadline,
                  () ->
                      conversations.open(
                          scope.home(), opening.budget(), opening.turnCap(), scope.owner()));
          requireTarget(opened, scope);
          int inserted =
              query(
                  "insert",
                  deadline,
                  () ->
                      jdbc.update(
                          "INSERT INTO conversation_id_map"
                              + "(project_id, owner_account_id, source, external_conversation_id, conversation_id)"
                              + " VALUES(?,?,?,?,?)",
                          key.projectId(),
                          key.account().value(),
                          key.source(),
                          key.externalConversation(),
                          opened.id()));
          if (inserted != 1) throw new IllegalStateException("Conversation alias was not retained");
          return new Opened(opened, true);
        });
  }

  @Override
  public Optional<ConversationRecord> find(Key key) {
    Objects.requireNonNull(key);
    return transaction("resolve", deadline -> target(key, scope(key, deadline), deadline));
  }

  private record Scope(Home home, String owner) {}

  private Scope scope(Key key, ConversationAliasSqlDeadline deadline) {
    List<Scope> selected =
        query(
            "scope",
            deadline,
            () ->
                jdbc.query(
                    """
        SELECT p.name, a.legacy_handle
          FROM projects p
          JOIN authority_scopes s ON s.project_id=p.id
          CROSS JOIN authority_accounts a
          JOIN admins u ON u.handle=a.legacy_handle
         WHERE p.id=? AND a.id=? AND a.builtin IS NULL AND u.enabled
           AND s.kind IN ('PROJECT','PERSONAL')
         FOR SHARE OF p,a,u
        """,
                    (row, position) -> new Scope(Home.of(row.getString(1)), row.getString(2)),
                    key.projectId(),
                    key.account().value()));
    if (selected.size() != 1)
      throw new CallerFault("Conversation alias namespace is unavailable or restricted");
    return selected.getFirst();
  }

  private Optional<ConversationRecord> target(
      Key key, Scope scope, ConversationAliasSqlDeadline deadline) {
    List<String> targets =
        query(
            "lookup",
            deadline,
            () ->
                jdbc.query(
                    "SELECT conversation_id FROM conversation_id_map"
                        + " WHERE project_id=? AND owner_account_id=? AND source=? AND external_conversation_id=?",
                    (row, position) -> row.getString(1),
                    key.projectId(),
                    key.account().value(),
                    key.source(),
                    key.externalConversation()));
    if (targets.isEmpty()) return Optional.empty();
    // The alias lookup reads only its retained relationship. The ordinary owner hydrates exactly
    // one canonical target snapshot, reused by the facade rather than discarded/refetched here.
    ConversationRecord selected =
        query(
            "ordinary_hydrate",
            deadline,
            () ->
                conversations
                    .find(targets.getFirst())
                    .orElseThrow(
                        () ->
                            new IllegalStateException("Conversation alias target is unavailable")));
    requireTarget(selected, scope);
    return Optional.of(selected);
  }

  private static void requireTarget(ConversationRecord selected, Scope scope) {
    if (!selected.home().equals(scope.home())
        || selected.origin() != Origin.TURN
        || selected.parentId() != null
        || selected.agent() != null
        || selected.lifecycle() == null
        || selected.budget() == null)
      throw new IllegalStateException(
          "Conversation alias target has incompatible scope or lifecycle");
  }

  private <T> T transaction(String operation, Function<ConversationAliasSqlDeadline, T> work) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Conversation aliases must own their commit boundary");
    long started = System.nanoTime();
    try {
      return ReadDeadline.within(
          properties.timeout(),
          () -> {
            ReadDeadline.check();
            long end = ReadDeadline.deadlineNanos(properties.timeout());
            var deadline = register(operation, end);
            try (deadline) {
              deadline.check();
              T result =
                  ArchiveUnavailableException.translating(
                      "resolve an external conversation",
                      () ->
                          transactions.execute(
                              status -> {
                                TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                      @Override
                                      public void afterCompletion(int completionStatus) {
                                        deadline.detach();
                                      }
                                    });
                                jdbc.execute(
                                    (ConnectionCallback<Void>)
                                        connection -> {
                                          deadline.attach(connection);
                                          return null;
                                        });
                                T selected = work.apply(deadline);
                                deadline.check();
                                return selected;
                              }));
              // This also fences a commit which finished after the caller's deadline. It is
              // uncertain
              // to that caller, not evidence that another open/run should be attempted
              // automatically.
              deadline.check();
              return Objects.requireNonNull(result);
            } catch (RuntimeException failed) {
              // Closing an expired socket can make rollback fail and replace the application's
              // original timeout. Preserve that known cancellation classification and uncertainty;
              // never relabel unrelated transaction/programming failures as a timeout.
              if (deadline.cancelledOrExpired() || cancelledApplication(failed))
                throw deadline.unavailable(failed);
              throw failed;
            } finally {
              active.remove(deadline);
            }
          });
    } finally {
      Timer.builder("plowshare.conversation.alias.sql.operation")
          .tag("operation", operation)
          .publishPercentileHistogram()
          .serviceLevelObjectives(
              Duration.ofMillis(500), Duration.ofSeconds(1), properties.timeout())
          .register(metrics)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }

  private static boolean cancelledApplication(RuntimeException failed) {
    if (!(failed instanceof TransactionSystemException transaction)) return false;
    Throwable application = transaction.getApplicationException();
    if (application instanceof ArchiveUnavailableException) return true;
    for (int depth = 0;
        application != null && depth < 16;
        depth++, application = application.getCause()) {
      if (application instanceof SQLException sql
          && ("57014".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) return true;
    }
    return false;
  }

  private ConversationAliasSqlDeadline register(String operation, long end) {
    synchronized (lifecycle) {
      if (closed)
        throw new ArchiveUnavailableException("Conversation alias storage is closed", null);
      var deadline =
          new ConversationAliasSqlDeadline(
              end,
              properties.queryTimeout(),
              watchdogs,
              () ->
                  metrics
                      .counter(
                          "plowshare.conversation.alias.sql.cancellations", "operation", operation)
                      .increment());
      active.add(deadline);
      return deadline;
    }
  }

  private <T> T query(String name, ConversationAliasSqlDeadline deadline, Supplier<T> work) {
    deadline.check();
    long started = System.nanoTime();
    var query = deadline.beginQuery();
    try {
      // All ordinary-owner statements in this transaction inherit this server-side ceiling too.
      // The watchdog additionally covers acquisition, materialization and commit on this path.
      String timeout = deadline.queryMillis() + "ms";
      jdbc.queryForObject(
          "SELECT set_config('statement_timeout', ?, true) || set_config('lock_timeout', ?, true)",
          String.class,
          timeout,
          timeout);
      T result = work.get();
      deadline.check();
      return result;
    } finally {
      query.close();
      long duration = System.nanoTime() - started;
      // A group includes timeout configuration and all execution/materialization in work. Ordinary
      // create/hydrate groups contain several owner statements. Their aggregate ceiling is stricter
      // than a fresh per-statement second; these meters are not individual-query measurements.
      Timer.builder("plowshare.conversation.alias.sql.group")
          .tag("group", name)
          .publishPercentileHistogram()
          .serviceLevelObjectives(Duration.ofMillis(500), Duration.ofSeconds(1))
          .register(metrics)
          .record(duration, TimeUnit.NANOSECONDS);
      if (duration >= Duration.ofMillis(500).toNanos())
        metrics
            .counter("plowshare.conversation.alias.sql.slow", "group", name, "threshold", "500ms")
            .increment();
      if (duration >= Duration.ofSeconds(1).toNanos())
        metrics
            .counter("plowshare.conversation.alias.sql.slow", "group", name, "threshold", "1s")
            .increment();
    }
  }

  private static long lockIdentity(Key key) {
    String text =
        key.projectId()
            + "\0"
            + key.account().value()
            + "\0"
            + key.source()
            + "\0"
            + key.externalConversation();
    try {
      return ByteBuffer.wrap(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)))
          .getLong();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
    }
  }

  @Override
  public void close() {
    List<ConversationAliasSqlDeadline> closing;
    synchronized (lifecycle) {
      if (closed) return;
      closed = true;
      closing = List.copyOf(active);
    }
    // Registration and the shutdown snapshot share one fence. Cancel every admitted scope before
    // removing scheduler alarms, including a caller still waiting to acquire its transaction.
    closing.forEach(ConversationAliasSqlDeadline::cancel);
    watchdogs.shutdownNow();
    try {
      if (!watchdogs.awaitTermination(500, TimeUnit.MILLISECONDS))
        throw new IllegalStateException("Conversation alias cancellation cleanup did not settle");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Conversation alias cancellation cleanup interrupted", interrupted);
    }
  }
}
