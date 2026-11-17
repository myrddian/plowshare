package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.CatalogueConfirmed;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.CatalogueIntent;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.Delivery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.sdk.RelayTools;
import io.aeyer.plowshare.sdk.RequestCancellation;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bounded local host for the public SDK provider, not another durable agent runtime. Each serial
 * pass owns one budget across SDK requests, journal operations and resource calls. A timed-out
 * physical pass retains its slot and exclusive journal; another pass cannot run around it.
 */
public final class HomeAssistantToolProvider implements AutoCloseable {
  public record Configuration(
      RelayTools.Binding binding,
      String prefix,
      int leaseSeconds,
      Duration requestTimeout,
      Duration cleanupTimeout) {
    public Configuration(RelayTools.Binding binding, String prefix, int leaseSeconds) {
      this(binding, prefix, leaseSeconds, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    public Configuration {
      Objects.requireNonNull(binding);
      HomeAssistantReadTools.prefix(prefix);
      if (leaseSeconds < 1 || leaseSeconds > 300)
        throw new IllegalArgumentException("HA provider lease must be 1..300 seconds");
      checked(requestTimeout);
      checked(cleanupTimeout);
    }

    private static void checked(Duration value) {
      Objects.requireNonNull(value);
      if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(30)) > 0)
        throw new IllegalArgumentException(
            "HA provider budget must be positive and at most 30 seconds");
    }
  }

  /** Operator-installed Application descriptor; installation is independent of agent grants. */
  public record Installation(
      int version, List<RelayTools.Declaration> bindings, List<ProviderPermission> providers) {
    public Installation {
      bindings = List.copyOf(bindings);
      providers = List.copyOf(providers);
      if (version != 1 || !bindings.isEmpty() || providers.isEmpty() || providers.size() > 32)
        throw new IllegalArgumentException("Invalid HA Application provider installation");
    }
  }

  public record ProviderPermission(
      String provider, String account, String prefix, int leaseSeconds) {
    public ProviderPermission {
      if (provider == null
          || provider.length() > 48
          || !provider.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"))
        throw new IllegalArgumentException("Invalid HA logical provider");
      RelayPort.identity(account);
      HomeAssistantReadTools.prefix(prefix);
      if (leaseSeconds < 1 || leaseSeconds > 300)
        throw new IllegalArgumentException("Invalid HA provider lease");
    }
  }

  private enum Operation {
    POLL,
    RECONCILE,
    RENEW,
    WITHDRAW
  }

  private static final class Pass<T> {
    final Operation operation;
    final OperationBudget budget;
    OperationBudget stageBudget;
    Delivery delivery;
    RelayPort.Batch batch;
    final RequestCancellation cancellation = new RequestCancellation();
    final CompletableFuture<T> reply = new CompletableFuture<>();
    Thread thread;
    boolean cancelled;
    boolean cancellationRequested;
    boolean cancellationDone = true;
    boolean workerDone;
    T value;
    Exception failure;

    Pass(Operation operation, Duration timeout) {
      this.operation = operation;
      budget = OperationBudget.start(timeout);
      stageBudget = budget;
    }
  }

  @FunctionalInterface
  private interface Work<T> {
    T run() throws IOException;
  }

  private final Configuration configuration;
  private final HomeAssistantToolHandlers handlers;
  private final HomeAssistantToolTransport transport;
  private final HomeAssistantProviderJournal journal;
  private final RelayTools.Provider provider;
  private final Supplier<Instant> wallClock;
  private final ThreadPoolExecutor worker;
  private final ThreadPoolExecutor canceller;
  private final Object lifecycle = new Object();
  private Pass<?> active;
  private volatile boolean closed;
  private volatile boolean catalogueUncertain;
  private volatile IOException closeFailure;
  private volatile CatalogueConfirmed catalogue;

  /** Compose with an already configured/authenticated SDK connection; its owner closes it. */
  public static HomeAssistantToolProvider open(
      Configuration configuration,
      Plowshare connection,
      HomeAssistantToolHandlers handlers,
      Path journalDirectory,
      String ownerFingerprint)
      throws IOException {
    String identity = journalIdentity(configuration, handlers);
    var journal =
        new HomeAssistantToolJournal(
            journalDirectory, identity, journalOwner(configuration, ownerFingerprint));
    try {
      return new HomeAssistantToolProvider(
          configuration,
          handlers,
          new HomeAssistantToolTransport.Sdk(new RelayClient(connection)),
          journal,
          Instant::now);
    } catch (IOException | RuntimeException failure) {
      try {
        journal.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /**
   * Private-journal owner fingerprint. Explicit origins are validated and credentials are hashed,
   * never stored/logged. Use the actual HA token, policy identity and SDK authenticated principal;
   * a changed HA credential/policy requires a separate journal and old-receipt reconciliation.
   */
  public static String ownerFingerprint(
      String serverOrigin,
      String homeOrigin,
      String homeToken,
      String policyIdentity,
      RelayTools.Binding binding) {
    origin(serverOrigin);
    origin(homeOrigin);
    IntegrationContracts.identity(policyIdentity, 256);
    if (homeToken == null
        || homeToken.isBlank()
        || homeToken.length() > 16384
        || homeToken.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("A valid HA credential is required");
    Objects.requireNonNull(binding);
    return RelayPort.hash(
        serverOrigin
            + "\0"
            + homeOrigin
            + "\0"
            + homeToken
            + "\0"
            + policyIdentity
            + "\0"
            + binding.project()
            + "\0"
            + binding.provider()
            + "\0"
            + binding.account());
  }

  private static void origin(String source) {
    URI uri;
    if (source == null)
      throw new IllegalArgumentException("An explicit HTTP(S) origin is required");
    try {
      uri = URI.create(source);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("An explicit HTTP(S) origin is required");
    }
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !List.of("", "/").contains(uri.getPath())
        || uri.getPort() == 0
        || uri.getPort() > 65535)
      throw new IllegalArgumentException("An explicit HTTP(S) origin is required");
  }

  public static String journalIdentity(
      Configuration configuration, HomeAssistantToolHandlers handlers) {
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(handlers);
    List<RelayTools.Declaration> tools = List.copyOf(handlers.declarations());
    if (tools.isEmpty()
        || tools.size() > 128
        || tools.stream().map(RelayTools.Declaration::name).distinct().count() != tools.size()
        || tools.stream().anyMatch(value -> !value.name().startsWith(configuration.prefix())))
      throw new IllegalArgumentException(
          "HA tool catalogue does not match its configured prefix/bounds");
    return RelayPort.hash(RelayTools.deploymentConfig(configuration.binding(), tools));
  }

  /** Bind lease/budget configuration as well as the SDK's declaration fingerprint. */
  public static String journalOwner(Configuration configuration, String ownerFingerprint) {
    Objects.requireNonNull(configuration);
    if (ownerFingerprint == null || !ownerFingerprint.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("A private HA provider owner fingerprint is required");
    return RelayPort.hash(
        ownerFingerprint
            + "\0"
            + configuration.binding().project()
            + "\0"
            + configuration.binding().provider()
            + "\0"
            + configuration.binding().account()
            + "\0"
            + configuration.prefix()
            + "\0"
            + configuration.leaseSeconds()
            + "\0"
            + configuration.requestTimeout()
            + "\0"
            + configuration.cleanupTimeout());
  }

  public static Installation installation(Configuration configuration) {
    Objects.requireNonNull(configuration);
    return new Installation(
        1,
        List.of(),
        List.of(
            new ProviderPermission(
                configuration.binding().provider(),
                configuration.binding().account(),
                configuration.prefix(),
                configuration.leaseSeconds())));
  }

  HomeAssistantToolProvider(
      Configuration configuration,
      HomeAssistantToolHandlers handlers,
      HomeAssistantToolTransport transport,
      HomeAssistantProviderJournal journal,
      Supplier<Instant> wallClock)
      throws IOException {
    this(
        configuration,
        handlers,
        transport,
        journal,
        wallClock,
        Thread.ofPlatform().daemon().name("ha-tool-provider-", 0).factory());
  }

  HomeAssistantToolProvider(
      Configuration configuration,
      HomeAssistantToolHandlers handlers,
      HomeAssistantToolTransport transport,
      HomeAssistantProviderJournal journal,
      Supplier<Instant> wallClock,
      ThreadFactory workerThreads)
      throws IOException {
    this.configuration = Objects.requireNonNull(configuration);
    this.handlers = Objects.requireNonNull(handlers);
    this.transport = Objects.requireNonNull(transport);
    this.journal = Objects.requireNonNull(journal);
    this.wallClock = Objects.requireNonNull(wallClock);
    if (!journalIdentity(configuration, handlers).equals(journal.identity()))
      throw new IOException("HA journal belongs to another catalogue");
    catalogue = journal.confirmed().orElse(null);
    Optional<CatalogueIntent> retained = journal.publishing();
    catalogueUncertain = retained.isPresent();
    retained.ifPresent(
        intent -> {
          if (!intent.request().project().equals(configuration.binding().project())
              || !intent.request().topic().equals(catalogueTopic()))
            throw new IllegalArgumentException("Foreign retained HA catalogue intent");
        });
    if (catalogue != null
        && (!catalogue.receipt().project().equals(configuration.binding().project())
            || !catalogue.receipt().topic().equals(catalogueTopic())))
      throw new IOException("Foreign retained HA catalogue confirmation");
    // At most one admitted pass and one close task may be queued. Cancellation has its own
    // single physical owner: Thread.interrupt can invoke a blocking NIO interruptor, so it must
    // never run on an expired caller or under the host admission lock.
    worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2),
            Objects.requireNonNull(workerThreads));
    canceller =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().name("ha-tool-cancellation-", 0).factory());
    List<RelayTools.RegisteredTool> registered =
        handlers.declarations().stream()
            .map(
                declaration ->
                    new RelayTools.RegisteredTool(
                        declaration,
                        call -> {
                          Pass<?> pass = current();
                          Duration remaining =
                              Duration.between(wallClock.get(), Instant.parse(call.deadline()));
                          if (remaining.isZero() || remaining.isNegative())
                            return CompletableFuture.completedFuture(
                                new RelayTools.Result(
                                    RelayTools.State.REJECTED,
                                    "HA tool invocation expired before execution."));
                          RelayTools.Result result = handlers.execute(call, pass.stageBudget);
                          current();
                          return CompletableFuture.completedFuture(result);
                        }))
            .toList();
    provider =
        new RelayTools.Provider(ports(), configuration.binding(), registered, boundedJournal());
  }

  /** One poll is admitted only under an exact locally confirmed, unexpired catalogue lease. */
  public int poll() throws IOException {
    return run(
        Operation.POLL,
        () -> {
          if (journal.publishing().isPresent() || !catalogueAvailable())
            throw new IOException(
                "HA catalogue is unavailable; publish or reconcile its lease explicitly");
          return provider.poll();
        });
  }

  /**
   * Fresh publication identity is durably intercepted before SDK transmission. No automatic
   * renewal.
   */
  public void renewCatalogue() throws IOException {
    run(
        Operation.RENEW,
        () -> {
          provider.publishCatalog(UUID.randomUUID().toString());
          return Boolean.TRUE;
        });
  }

  public void withdrawCatalogue() throws IOException {
    run(
        Operation.WITHDRAW,
        () -> {
          provider.withdrawCatalog(UUID.randomUUID().toString());
          return Boolean.TRUE;
        });
  }

  /** Only retained reads resolve uncertain catalogue/results; absence never authorizes replay. */
  public int reconcile() throws IOException {
    return run(Operation.RECONCILE, () -> reconcileCatalogue() + provider.reconcile());
  }

  /**
   * Informational only; server registry/agent scopes remain the authority at invocation admission.
   */
  public boolean catalogueAvailable() {
    var selected = catalogue;
    return !closed
        && !catalogueUncertain
        && selected != null
        && !selected.withdrawal()
        && wallClock
            .get()
            .isBefore(selected.receipt().publishedAt().plusSeconds(configuration.leaseSeconds()));
  }

  private <T> T run(Operation operation, Work<T> work) throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new IOException("HA provider caller is interrupted before admission");
    Pass<T> pass = new Pass<>(operation, configuration.requestTimeout());
    synchronized (lifecycle) {
      if (closed) throw new IOException("HA tool provider is closed");
      if (active != null)
        throw new IOException(
            "HA tool provider still owns a physical pass; reconcile after settlement");
      active = pass;
      try {
        worker.execute(() -> execute(pass, work));
      } catch (RejectedExecutionException unavailable) {
        active = null;
        throw new IOException("HA provider worker is unavailable");
      }
    }
    try {
      T result = pass.reply.get(pass.budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
      pass.budget.remaining();
      return result;
    } catch (InterruptedException interrupted) {
      cancel(pass);
      Thread.currentThread().interrupt();
      throw new IOException("HA provider pass interrupted; retained work may be uncertain");
    } catch (TimeoutException expired) {
      cancel(pass);
      throw new IOException(
          "HA provider pass expired; its physical work remains owned until settlement");
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof IOException io) throw io;
      throw new IOException("HA provider pass failed", failed.getCause());
    } catch (IOException expired) {
      cancel(pass);
      throw expired;
    }
  }

  private <T> void execute(Pass<T> pass, Work<T> work) {
    synchronized (lifecycle) {
      pass.thread = Thread.currentThread();
    }
    T result = null;
    Exception failure = null;
    boolean terminal = false;
    try {
      current();
      result = Objects.requireNonNull(work.run());
      current();
      terminal = true;
    } catch (Exception failed) {
      failure = failed;
      terminal = true;
    } finally {
      // SDK cancellation callbacks run only on the physical owner, never under the admission lock
      // or on the expired caller's return path. Local cancellation does not cancel remote work.
      try {
        pass.cancellation.cancel();
      } catch (RuntimeException failed) {
        if (failure == null) failure = failed;
      }
      Runnable answer;
      synchronized (lifecycle) {
        pass.value = result;
        pass.failure =
            terminal
                ? failure
                : new IOException(
                    "HA provider physical pass failed unexpectedly; retain its receipts");
        pass.workerDone = true;
        answer = retire(pass);
      }
      if (answer != null) answer.run();
    }
  }

  private void cancel(Pass<?> pass) {
    synchronized (lifecycle) {
      cancelLocked(pass);
    }
  }

  private void cancelLocked(Pass<?> pass) {
    if (active != pass || pass.cancellationRequested) return;
    pass.cancelled = true;
    pass.cancellationRequested = true;
    pass.cancellationDone = false;
    try {
      canceller.execute(() -> cancelPhysical(pass));
    } catch (RejectedExecutionException failed) {
      // Keep ownership rather than claiming an undispatched cancellation settled. This should be
      // unreachable under the single-pass/one-cancellation invariant; shutdown reports the fault.
      closeFailure =
          new IOException(
              "HA provider cancellation owner is unavailable; retain journal ownership");
    }
  }

  private void cancelPhysical(Pass<?> pass) {
    try {
      pass.cancellation.cancel();
      Thread target;
      synchronized (lifecycle) {
        target = active == pass && !pass.workerDone ? pass.thread : null;
      }
      if (target != null) target.interrupt();
    } finally {
      Runnable answer;
      synchronized (lifecycle) {
        pass.cancellationDone = true;
        answer = retire(pass);
        lifecycle.notifyAll();
      }
      if (answer != null) answer.run();
    }
  }

  /** Called under lifecycle; completion callbacks themselves always run outside that lock. */
  private Runnable retire(Pass<?> pass) {
    if (active != pass || !pass.workerDone || !pass.cancellationDone) return null;
    active = null;
    lifecycle.notifyAll();
    return () -> answer(pass);
  }

  private static <T> void answer(Pass<T> pass) {
    if (pass.failure != null) pass.reply.completeExceptionally(pass.failure);
    else if (pass.cancelled)
      pass.reply.completeExceptionally(
          new IOException("HA provider pass was cancelled; retained work may be uncertain"));
    else pass.reply.complete(pass.value);
  }

  private Pass<?> current() throws IOException {
    Pass<?> pass = currentOuter();
    pass.stageBudget.remaining();
    return pass;
  }

  private Pass<?> currentOuter() throws IOException {
    Pass<?> pass;
    synchronized (lifecycle) {
      pass = active;
      if (closed
          || pass == null
          || pass.thread != Thread.currentThread()
          || pass.cancelled
          || Thread.currentThread().isInterrupted())
        throw new IOException("HA provider pass is unavailable or cancelled");
    }
    pass.budget.remaining();
    return pass;
  }

  private RelayTools.Journal boundedJournal() {
    return new RelayTools.Journal() {
      @Override
      public String identity() {
        return journal.identity();
      }

      @Override
      public List<RelayTools.Receipt> all() throws IOException {
        current();
        var result = journal.all();
        current();
        return result;
      }

      @Override
      public void save(RelayTools.Receipt receipt) throws IOException {
        current();
        journal.save(receipt);
        Pass<?> pass = current();
        if (receipt.phase() == RelayTools.Phase.EXECUTING) {
          if (pass.delivery == null || !pass.delivery.invocationId().equals(receipt.invocationId()))
            throw new IOException("HA execution intent has no validated broker delivery");
          journal.captureDelivery(pass.delivery);
        }
        current();
      }
    };
  }

  private RelayTools.Ports ports() {
    return new RelayTools.Ports() {
      @Override
      public RelayPort.Batch consume(RelayPort.Consume request) throws IOException {
        Pass<?> pass = currentOuter();
        pass.stageBudget = pass.budget;
        pass.delivery = null;
        pass.batch = null;
        var result = transport.consume(request, pass.stageBudget, pass.cancellation);
        current();
        if (!result.project().equals(request.project())
            || !result.topic().equals(request.topic())
            || !result.group().equals(request.group())
            || !result.consumerId().equals(request.consumerId())
            || result.events().size() > 1) throw new IOException("Foreign HA tool batch");
        if (result.events().size() == 1
            && "tool-runtime".equals(result.events().getFirst().publisher())
            && "TEXT".equals(result.events().getFirst().payload().kind())) {
          // The SDK does its full authority/schema/identity validation before persisting intent.
          // Decode once here as well because its public Provider API has no pre-ack deadline hook:
          // the already-admitted call's earlier deadline must also bound journal/ack/publication.
          RelayTools.Call call = RelayTools.decodeCall(result.events().getFirst().payload().text());
          var event = result.events().getFirst();
          if (!event.eventId().equals(call.invocationId())
              || !Objects.equals(event.correlationId(), call.invocationId())
              || !result.through().equals(event.position())
              || !request
                  .topic()
                  .equals("tool." + call.provider() + "." + call.tool() + ".request"))
            throw new IOException("Foreign HA tool request coordinates");
          pass.delivery =
              new Delivery(
                  call.invocationId(), request.topic(), result.generation(), event.position());
          journal.checkDelivery(pass.delivery);
          current();
          pass.batch = result;
          Duration remaining = Duration.between(wallClock.get(), Instant.parse(call.deadline()));
          if (!remaining.isZero() && !remaining.isNegative())
            pass.stageBudget = pass.budget.capped(remaining);
          // Already expired work still needs a bounded rejection/duplicate acknowledgement;
          // the SDK never invokes its handler when that original deadline has passed.
        }
        return result;
      }

      @Override
      public RelayPort.Acknowledged acknowledge(RelayPort.Ack request) throws IOException {
        Pass<?> pass = current();
        var batch = pass.batch;
        if (batch == null
            || pass.delivery == null
            || !request.project().equals(batch.project())
            || !request.topic().equals(batch.topic())
            || !request.group().equals(batch.group())
            || !request.consumerId().equals(batch.consumerId())
            || !request.batchId().equals(batch.batchId())
            || !request.fence().equals(batch.fence())
            || request.expiredThrough() != null)
          throw new IOException("Foreign HA acknowledgement request");
        var result = transport.acknowledge(request, pass.stageBudget, pass.cancellation);
        current();
        if (!result.project().equals(request.project())
            || !result.topic().equals(request.topic())
            || !result.group().equals(request.group())
            || !result.batchId().equals(request.batchId())
            || result.gap()
            || !result.through().equals(batch.through()))
          throw new IOException("Foreign HA acknowledgement boundary");
        journal.acknowledged(pass.delivery);
        current();
        return result;
      }

      @Override
      public RelayPort.Published publish(RelayPort.Publish request) throws IOException {
        Pass<?> pass = current();
        boolean metadata = request.topic().equals(catalogueTopic());
        if (metadata) {
          if (pass.operation != Operation.RENEW && pass.operation != Operation.WITHDRAW)
            throw new IOException("Unexpected HA catalogue publication");
          catalogueUncertain = true;
          journal.beginCatalogue(request, pass.operation == Operation.WITHDRAW);
          current();
        }
        RelayPort.Published result;
        try {
          result = transport.publish(request, pass.stageBudget, pass.cancellation);
        } catch (Plowshare.TransportException failed) {
          if (metadata && failed.delivery() == Plowshare.Delivery.NOT_SUBMITTED) {
            current();
            journal.notSubmittedCatalogue(request.requestId());
            catalogueUncertain = false;
            current();
          }
          throw failed;
        }
        current();
        if (metadata) {
          journal.confirmCatalogue(result);
          catalogue = journal.confirmed().orElseThrow();
          catalogueUncertain = false;
          current();
        }
        return result;
      }

      @Override
      public RelayLog.Page log(RelayLog.Query request) throws IOException {
        Pass<?> pass = current();
        var result = transport.log(request, pass.stageBudget, pass.cancellation);
        current();
        return result;
      }
    };
  }

  private int reconcileCatalogue() throws IOException {
    Optional<CatalogueIntent> retained = journal.publishing();
    if (retained.isEmpty()) return 0;
    var intent = retained.orElseThrow();
    String after = "0";
    for (int page = 0; page < 100; page++) {
      Pass<?> pass = current();
      var result =
          transport.log(
              new RelayLog.Query(
                  configuration.binding().project(), false, catalogueTopic(), after, 100),
              pass.stageBudget,
              pass.cancellation);
      current();
      if (!result.scope().project().equals(configuration.binding().project())
          || result.scope().system()
          || !result.topic().name().equals(catalogueTopic())
          || !result.after().equals(after)) throw new IOException("Foreign HA catalogue log");
      for (var event : result.events())
        if (event.eventId().equals(intent.request().requestId())) {
          if (!event.publisher().equals(RelayPort.publisher(configuration.binding().account()))
              || !event.payload().kind().equals("TEXT")
              || !event.payload().text().equals(intent.request().text())
              || !event.occurredAt().equals(intent.request().occurredAt())
              || event.correlationId() != null
              || event.causationId() != null)
            throw new IOException("Conflicting HA catalogue publication");
          journal.confirmCatalogue(
              new RelayPort.Published(
                  event.eventId(),
                  configuration.binding().project(),
                  catalogueTopic(),
                  event.position(),
                  event.publishedAt()));
          catalogue = journal.confirmed().orElseThrow();
          catalogueUncertain = false;
          current();
          return 1;
        }
      if (result.next().equals(result.topic().through())) return 0;
      after = result.next();
    }
    throw new IOException("HA catalogue reconciliation exceeds bounded inspection; retain intent");
  }

  private String catalogueTopic() {
    return "tool." + configuration.binding().provider() + ".catalog";
  }

  @Override
  public void close() throws IOException {
    OperationBudget cleanup = OperationBudget.start(configuration.cleanupTimeout());
    synchronized (lifecycle) {
      if (active != null && active.thread == Thread.currentThread())
        throw new IOException("HA provider cannot join its own physical pass during cleanup");
      if (!closed) {
        closed = true;
        if (active != null) cancelLocked(active);
        try {
          worker.execute(this::closeJournalAfterSettlement);
        } catch (RejectedExecutionException unavailable) {
          closeFailure = new IOException("HA provider cleanup was not scheduled");
        }
        worker.shutdown();
        canceller.shutdown();
      }
    }
    try {
      if (!worker.awaitTermination(cleanup.remaining().toNanos(), TimeUnit.NANOSECONDS)
          || !canceller.awaitTermination(cleanup.remaining().toNanos(), TimeUnit.NANOSECONDS))
        throw new IOException(
            "HA provider cleanup is incomplete; retain its journal owner until physical settlement");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("HA provider cleanup interrupted; retain journal ownership");
    }
    if (closeFailure != null) throw closeFailure;
  }

  private void closeJournalAfterSettlement() {
    boolean interrupted = false;
    synchronized (lifecycle) {
      while (active != null) {
        try {
          lifecycle.wait();
        } catch (InterruptedException cancellation) {
          interrupted = true;
        }
      }
    }
    try {
      journal.close();
    } catch (IOException failed) {
      closeFailure = failed;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
