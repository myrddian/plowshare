package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.llm.dispatch.InferenceBinding;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retained allowance and single-attempt evidence. All claim methods join the owner's transaction.
 */
@Repository
public class JdbcQueryEmbeddingRepository implements QueryEmbeddingRepository {
  private final JdbcTemplate jdbc;
  private final AllowanceRepository allowances;

  public JdbcQueryEmbeddingRepository(JdbcTemplate jdbc) {
    this(jdbc, new JdbcAllowanceRepository(jdbc));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public JdbcQueryEmbeddingRepository(JdbcTemplate jdbc, AllowanceRepository allowances) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.allowances = Objects.requireNonNull(allowances);
  }

  public Pin pin(InformationContext context) {
    var rows =
        jdbc.query(
            "SELECT a.id,CASE WHEN ?='project' THEN information_namespace_home('project:' || ?) WHEN ?='personal' THEN information_namespace_home('account:' || a.legacy_handle) WHEN ?='account' THEN ?::uuid ELSE ?::uuid END FROM authority_accounts a WHERE a.legacy_handle=? AND information_global_read_member(?)",
            (r, n) -> new Pin(r.getObject(1, UUID.class), r.getObject(2, UUID.class)),
            scope(context),
            context.selection().project(),
            scope(context),
            scope(context),
            io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL_KNOWLEDGE.value(),
            io.aeyer.plowshare.server.access.BuiltInIdentities.ADMINISTRATION.value(),
            context.account(),
            context.account());
    if (rows.size() != 1 || rows.getFirst().scope() == null) throw denied();
    return rows.getFirst();
  }

  private static String scope(InformationContext context) {
    return context.selection().scope().name().toLowerCase(Locale.ROOT);
  }

  private static CallerFault denied() {
    return new CallerFault("Semantic query is outside its live read authority");
  }

  public void require(InformationContext context, Pin expected, UUID target) {
    if (!pin(context).equals(expected)) throw denied();
    if (context.selection().scope() == InformationContext.Scope.PROJECT
        && !Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT information_project_readable((SELECT project_id FROM authority_scopes WHERE id=?),?)",
                Boolean.class,
                expected.scope(),
                context.account()))) throw denied();
    if (target != null) {
      var filter = InformationSql.read(context, "r");
      var args = new ArrayList<Object>();
      args.add(target);
      args.addAll(filter.arguments());
      args.add(context.corpus().documentType());
      if (!Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM information_revisions r WHERE r.id=? AND "
                  + filter.sql()
                  + " AND r.document_type=?)",
              Boolean.class,
              args.toArray()))) throw denied();
    }
  }

  public Source open(Pin pin, String fingerprint, String invocation, Integer limit) {
    if (limit != null && limit < 0)
      throw new IllegalArgumentException(
          "Semantic search allowance must be nonnegative or unlimited");
    if (Integer.valueOf(0).equals(limit))
      throw new CallerFault(
          "Semantic search inference is disabled by plowshare.embeddings.search-budget=0");
    return openSource(pin, fingerprint, invocation, limit, "QUERY");
  }

  @Override
  public Source openWrite(Pin pin, String fingerprint, String invocation, Integer limit) {
    if (limit != null && limit < 0) throw new IllegalArgumentException("Invalid write allowance");
    return openSource(pin, fingerprint, invocation, limit, "MEMORY_WRITE");
  }

  private Source openSource(
      Pin pin, String fingerprint, String invocation, Integer limit, String consumer) {
    var source = new Source(UUID.randomUUID(), UUID.randomUUID(), limit);
    int count =
        jdbc.update(
            "INSERT INTO embedding_queries(id,budget_id,account_id,scope_id,fingerprint,invocation,call_limit,consumer_kind) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(account_id,invocation) DO NOTHING",
            source.id(),
            source.budget(),
            pin.account(),
            pin.scope(),
            fingerprint,
            invocation,
            limit,
            consumer);
    if (count != 1)
      throw new IllegalStateException(
          "Query invocation already admitted; reconcile without replay");
    return source;
  }

  public Budget.Claim claim(Source source) {
    // Serialize closure with the last claim. The lock ends before any provider IO.
    TransactionTemplate transaction =
        new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    return transaction.execute(
        status -> {
          if (jdbc.query(
                      "SELECT id FROM embedding_queries WHERE id=? AND budget_id=? AND NOT closed AND call_limit IS NOT DISTINCT FROM ?::integer FOR UPDATE",
                      (r, n) -> r.getObject(1, UUID.class),
                      source.id(),
                      source.budget(),
                      source.limit())
                  .size()
              != 1) throw new IllegalStateException("Query source closed or changed");
          return allowances
              .claim(new AllowanceId(source.budget()), 0)
              .orElseThrow(() -> new IllegalStateException("Query allowance exhausted"));
        });
  }

  public void bind(Source source, InferenceBinding binding, String taskHash) {
    if (jdbc.update(
            "UPDATE inference_admissions SET query_id=?,query_task_hash=? WHERE id=? AND budget_id=? AND query_id IS NULL",
            source.id(),
            taskHash,
            binding.id(),
            source.budget())
        != 1) throw new IllegalStateException("Query claim binding refused");
  }

  public void close(Source source) {
    if (jdbc.update(
            "UPDATE embedding_queries SET closed=true WHERE id=? AND budget_id=?",
            source.id(),
            source.budget())
        != 1) throw new IllegalStateException("Missing query source");
  }

  public void lockRead(InformationContext context, Pin pin, UUID target) {
    // Root audience/reference changes take the exclusive side of this existing fence.
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0)) IS NULL",
        Boolean.class);
    jdbc.queryForObject(
        "SELECT information_lock_scope(?,?) IS NULL",
        Boolean.class,
        context.account(),
        pin.scope());
    // Short result assembly must not race withdrawal or input/projection edits. No provider IO
    // occurs here. SHARE allows concurrent readers and follows authority-before-resource ordering.
    jdbc.execute(
        "LOCK TABLE information_resources,information_revisions,information_inputs IN SHARE MODE");
    require(context, pin, target);
  }
}
