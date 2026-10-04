package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.harness.*;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gates a prepared, validated database transition before making its new information visible. No
 * model/network/hook runs in a database transaction. Approved receipts resume without refiring
 * hooks.
 */
public final class InformationWriteGates implements UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;
  private final InformationAccess access;
  private final InformationJobs inputs;
  private final ConversationStore conversations;
  private final LogStages logs;
  private final Hooks configured;
  private final Harness harness;
  private final Clock clock;

  public InformationWriteGates(
      JdbcTemplate jdbc,
      UnitOfWork work,
      InformationAccess access,
      InformationJobs inputs,
      ConversationStore conversations,
      LogStages logs,
      Hooks configured,
      Harness harness,
      Clock clock) {
    this.jdbc = jdbc;
    this.work = work;
    this.access = access;
    this.inputs = inputs;
    this.conversations = conversations;
    this.logs = logs;
    this.configured = configured;
    this.harness = harness;
    this.clock = clock;
  }

  public <T> T execute(
      InformationContext context,
      UUID request,
      String operation,
      Object identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<T> transition) {
    access.requireSelection(context);
    if (request == null) throw new CallerFault("stage transition needs a stable requestId");
    String fingerprint =
        InformationCatalogue.sha256(
            json(Arrays.asList(operation, context.selection(), identity))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var receipt =
        work.inTransaction(
            () -> {
              jdbc.queryForObject(
                  "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
                  Boolean.class,
                  context.account() + ":write-gates:" + request);
              var prior =
                  jdbc.queryForList(
                      "SELECT * FROM information_write_gates WHERE account=? AND request_id=? FOR UPDATE",
                      context.account(),
                      request);
              if (!prior.isEmpty()) {
                var row = prior.getFirst();
                if (!fingerprint.equals(row.get("fingerprint")))
                  throw new CallerFault(
                      "requestId was already used for a different prepared transition");
                if (row.get("state").equals("completed")) return row;
                if (row.get("state").equals("blocked"))
                  throw new CallerFault((String) row.get("error"));
                if (((OffsetDateTime) row.get("lease_until")).toInstant().isAfter(clock.instant()))
                  throw new CallerFault(
                      "transition is in progress; reconcile with the same requestId");
                UUID token = UUID.randomUUID();
                jdbc.update(
                    "UPDATE information_write_gates SET token=?,lease_until=? WHERE account=? AND request_id=?",
                    token,
                    expiry(),
                    context.account(),
                    request);
                var claimed = new LinkedHashMap<>(row);
                claimed.put("token", token);
                return claimed;
              }
              UUID token = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO information_write_gates(account,request_id,fingerprint,operation,token,lease_until) VALUES(?,?,?,?,?,?)",
                  context.account(),
                  request,
                  fingerprint,
                  operation,
                  token,
                  expiry());
              return jdbc.queryForMap(
                  "SELECT * FROM information_write_gates WHERE account=? AND request_id=?",
                  context.account(),
                  request);
            });
    if (receipt.get("state").equals("completed"))
      return decode(receipt.get("response"), resultType);
    HarnessRun run = harness.begin();
    try {
      if (!receipt.get("state").equals("approved")) {
        String log = (String) receipt.get("log_id");
        if (log == null) {
          String project = context.selection().project();
          Home home = project == null ? Home.global() : Home.of(project);
          log =
              conversations
                  .log(
                      Origin.SUBMISSION,
                      home,
                      "document_pipeline",
                      null,
                      Budget.of(1),
                      context.account(),
                      null)
                  .id();
          inputs.bind(log, context, sources);
          logs.opened(
              new LogStages.LogOpened(
                  log, Origin.SUBMISSION, home, "document_pipeline", false, null, session, null));
          String opened = log;
          work.inTransaction(
              () -> {
                fence(context, request, receipt);
                jdbc.update(
                    "UPDATE information_write_gates SET log_id=? WHERE account=? AND request_id=?",
                    opened,
                    context.account(),
                    request);
                return null;
              });
        }
        inputs.requireLog(log, context.account());
        var hookContext =
            HookContext.forLog(
                    "submission", "document_pipeline", false, context.selection().project(), log)
                .withUsage(usageOwners.conversation(log, 0, UsageAttribution.Operation.HOOK_MODEL))
                .about(
                    new HookContext.Document(
                        operation,
                        null,
                        sources.isEmpty() ? null : sources.getFirst().toString(),
                        1,
                        operation,
                        1));
        Hooks chain = Hooks.chain(run.forModel(null), configured);
        var shown = new StageShown(operation, operation, 0, 1);
        Gate pre = chain.stagePre(hookContext, new StageStart(shown, null, null));
        record(context, request, receipt, "pre_gate", pre);
        if (pre.isDenied()) throw new CallerFault(pre.denied());
        Gate post =
            chain.stagePost(
                hookContext,
                new StageDone(shown, "validated information transition prepared", null));
        record(context, request, receipt, "post_gate", post);
        if (post.isDenied()) throw new CallerFault(post.denied());
        work.inTransaction(
            () -> {
              fence(context, request, receipt);
              inputs.requireLog(openedLog(context, request), context.account());
              jdbc.update(
                  "UPDATE information_write_gates SET state='approved' WHERE account=? AND request_id=?",
                  context.account(),
                  request);
              return null;
            });
      }
      return work.inTransaction(
          () -> {
            fence(context, request, receipt);
            access.requireSelection(context);
            inputs.requireLog(openedLog(context, request), context.account());
            T result = transition.get();
            jdbc.update(
                "UPDATE information_write_gates SET state='completed',response=CAST(? AS jsonb) WHERE account=? AND request_id=?",
                json(result),
                context.account(),
                request);
            return result;
          });
    } catch (RuntimeException failure) {
      jdbc.update(
          "UPDATE information_write_gates SET state='blocked',error=? WHERE account=? AND request_id=? AND token=? AND state<>'completed'",
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
          context.account(),
          request,
          receipt.get("token"));
      throw failure;
    } finally {
      record(context, request, receipt, "finish_records", run.finish());
    }
  }

  private String openedLog(InformationContext context, UUID request) {
    return jdbc.queryForObject(
        "SELECT log_id FROM information_write_gates WHERE account=? AND request_id=?",
        String.class,
        context.account(),
        request);
  }

  private void record(
      InformationContext context,
      UUID request,
      Map<String, Object> receipt,
      String column,
      Object value) {
    // Column names are fixed internal call sites, never caller input.
    jdbc.update(
        "UPDATE information_write_gates SET "
            + column
            + "=CAST(? AS jsonb) WHERE account=? AND request_id=? AND token=?",
        json(value),
        context.account(),
        request,
        receipt.get("token"));
  }

  private void fence(InformationContext context, UUID request, Map<String, Object> receipt) {
    if (jdbc.queryForList(
            "SELECT request_id FROM information_write_gates WHERE account=? AND request_id=? AND token=? AND lease_until>=? FOR UPDATE",
            context.account(),
            request,
            receipt.get("token"),
            now())
        .isEmpty())
      throw new CallerFault("transition lease expired; reconcile with the same requestId");
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  private OffsetDateTime expiry() {
    return clock.instant().plusSeconds(300).atOffset(ZoneOffset.UTC);
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private static <T> T decode(Object value, Class<T> type) {
    try {
      return JSON.readValue(value.toString(), type);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }
}
