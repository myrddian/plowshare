package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.*;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.harness.*;
import io.aeyer.plowshare.server.hooks.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/**
 * Durable URL intake. A failed fetch is inspectable and retryable; a denied post gate retains its
 * bytes.
 */
public final class InformationAcquisitions implements AutoCloseable, UsageAware {
  private UsageOwners usageOwners = UsageOwners.NONE;

  @Override
  public void useUsageOwners(UsageOwners source) {
    usageOwners = java.util.Objects.requireNonNull(source);
  }

  private final AcquisitionRepository repository;
  private final UnitOfWork work;
  private final InformationAccess access;
  private final InformationCatalogue catalogue;
  private final InformationJobs inputs;
  private final PageFetcher fetcher;
  private final IntSupplier allowance;
  private final ConversationStore conversations;
  private final LogStages logStages;
  private final Hooks configured;
  private final Harness harness;
  private ScheduledExecutorService worker;

  public InformationAcquisitions(
      AcquisitionRepository repository,
      UnitOfWork work,
      InformationAccess access,
      InformationCatalogue catalogue,
      PageFetcher fetcher,
      IntSupplier allowance,
      ConversationStore conversations,
      LogStages logStages,
      Hooks configured,
      Harness harness,
      InformationJobs inputs) {
    this.inputs = inputs;
    this.repository = Objects.requireNonNull(repository);
    this.work = work;
    this.access = access;
    this.catalogue = catalogue;
    this.fetcher = fetcher;
    this.allowance = allowance;
    this.conversations = conversations;
    this.logStages = logStages;
    this.configured = configured;
    this.harness = harness;
  }

  public AcquisitionRepository.Status submit(
      InformationContext context, UUID request, String url, String name, String session) {
    access.requireWork(context);
    if (context.selection().scope() == InformationContext.Scope.SHARED)
      throw new CallerFault("acquire into a personal or project namespace");
    if (request == null || url == null || url.isBlank() || name == null || name.isBlank())
      throw new CallerFault("acquisition needs requestId, URL and source name");
    int total = allowance.getAsInt();
    if (total < 1) throw new CallerFault("processing allowance must be positive");
    String corpus =
        context.corpus() == InformationContext.Corpus.CODE
                || io.aeyer.plowshare.protocol.DocumentType.classify(name, null).isCode()
            ? "code"
            : "documents";
    String identity = json(Arrays.asList(context.selection(), url, name));
    String fingerprint =
        InformationCatalogue.sha256(
            (corpus.equals("code") ? identity + corpus : identity)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID id =
        work.inTransaction(
            () ->
                repository.submit(
                    context, request, fingerprint, url, name, session, total, corpus));
    return status(context, id);
  }

  private AcquisitionRepository.Ticket owned(InformationContext context, UUID ticket) {
    access.requireSelection(context);
    var row = repository.owned(context.account(), ticket);
    if (!Objects.equals(context.selection().project(), row.project())
        || context.selection().scope() == InformationContext.Scope.SHARED)
      throw new NotFoundFault("select the acquisition's original namespace");
    return row;
  }

  public AcquisitionRepository.Status status(InformationContext context, UUID ticket) {
    return owned(context, ticket).status();
  }

  public List<AcquisitionRepository.Status> list(
      InformationContext context, int limit, int offset) {
    access.requireSelection(context);
    if (context.selection().scope() == InformationContext.Scope.SHARED) return List.of();
    if (limit < 1 || limit > 100 || offset < 0)
      throw new CallerFault("acquisition list needs limit 1..100 and nonnegative offset");
    return repository.list(
        context.account(),
        context.selection().project(),
        context.corpus() == InformationContext.Corpus.CODE ? "code" : "documents",
        limit,
        offset);
  }

  public void retry(InformationContext context, UUID ticket) {
    owned(context, ticket);
    repository.retry(ticket, context.account());
  }

  public synchronized void start() {
    if (worker != null) return;
    worker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("information-acquisition").factory());
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            drainOne();
          } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(InformationAcquisitions.class)
                .warn("information acquisition queue could not be serviced", failure);
          }
        },
        0,
        1,
        TimeUnit.SECONDS);
  }

  public boolean drainOne() {
    var row = work.inTransaction(() -> repository.claim()).orElse(null);
    if (row == null) return false;
    HarnessRun run = harness.begin();
    try {
      String account = row.account(), project = row.project();
      var context =
          access
              .resolve(
                  account,
                  project == null
                      ? InformationContext.Selection.personal()
                      : InformationAccess.projectSelection(account, project))
              .withCorpus(
                  io.aeyer.plowshare.server.information.InformationContext.Corpus.valueOf(
                      row.status().corpus().toUpperCase(Locale.ROOT)));
      access.requireWork(context);
      String log = row.log();
      if (log == null) {
        Home home = project == null ? Home.global() : Home.of(project);
        log =
            conversations
                .log(
                    Origin.SUBMISSION,
                    home,
                    "document_pipeline",
                    null,
                    Budget.of(row.status().allowanceTotal()),
                    account,
                    null)
                .id();
        String opened = log;
        work.inTransaction(
            () -> {
              fence(row);
              repository.opened(row, opened);
              return null;
            });
        logStages.opened(
            new LogStages.LogOpened(
                log,
                Origin.SUBMISSION,
                home,
                "document_pipeline",
                false,
                null,
                row.session(),
                null));
      }
      var hookContext =
          HookContext.forLog("submission", "document_pipeline", false, project, log)
              .withUsage(usageOwners.conversation(log, 0, UsageAttribution.Operation.HOOK_MODEL))
              .about(
                  new HookContext.Document(
                      "acquire",
                      null,
                      null,
                      1,
                      "acquire",
                      row.status().attempt(),
                      row.status().url()));
      Hooks hooks = Hooks.chain(run.forModel(null), configured);
      var shown = new StageShown("acquire", "acquire", 0, 1);
      Gate pre = hooks.stagePre(hookContext, new StageStart(shown, null, null));
      work.inTransaction(
          () -> {
            fence(row);
            repository.preGate(row, pre);
            return null;
          });
      if (pre.isDenied()) {
        finish(row, "blocked", pre.denied());
        return true;
      }
      UUID revision = row.status().revisionId();
      String processingLog = log;
      if (revision == null) {
        var fetched = fetcher.fetch(row.status().url());
        if (!fetched.isOk())
          throw new CallerFault(
              "acquisition failed: " + fetched.failure() + ": " + fetched.message());
        if (fetched.sourceBytes() == null)
          throw new CallerFault("fetch provider returned no original response bytes");
        revision =
            work.inTransaction(
                () -> {
                  fence(row);
                  access.requireWork(context);
                  var admitted =
                      catalogue.acquired(
                          context,
                          row.requestId(),
                          row.status().sourceName(),
                          fetched.sourceBytes(),
                          fetched.mediaType(),
                          fetched.finalUrl(),
                          row.session(),
                          row.status().allowanceTotal());
                  inputs.bind(processingLog, context, List.of(admitted.revision()));
                  repository.admitted(row, admitted.revision());
                  return admitted.revision();
                });
      }
      catalogue.requireReadable(context, revision);
      inputs.requireLog(log, account);
      var retained = repository.retained(revision);
      Gate post =
          hooks.stagePost(
              hookContext.about(
                  new HookContext.Document(
                      "acquire",
                      retained.resource().toString(),
                      revision.toString(),
                      1,
                      "acquire",
                      row.status().attempt(),
                      retained.sourceUri())),
              new StageDone(shown, "original response bytes retained", null));
      work.inTransaction(
          () -> {
            fence(row);
            repository.postGate(row, post);
            return null;
          });
      finish(row, post.isDenied() ? "blocked" : "succeeded", post.denied());
    } catch (InformationLifecycle.StaleLease invalidated) {
      // A newer attempt owns the ticket. Its predecessor cannot admit bytes or publish success.
    } catch (RuntimeException failure) {
      finish(
          row,
          "failed",
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    } finally {
      var records = run.finish();
      repository.finishRecords(row, records);
    }
    return true;
  }

  private void fence(AcquisitionRepository.Ticket row) {
    repository.fence(row);
  }

  private void finish(AcquisitionRepository.Ticket row, String state, String error) {
    repository.finish(row, state, error);
  }

  private static String json(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  @Override
  public synchronized void close() {
    if (worker != null) worker.shutdownNow();
  }
}
