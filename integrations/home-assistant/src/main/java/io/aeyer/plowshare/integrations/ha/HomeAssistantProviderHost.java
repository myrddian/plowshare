package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderConfiguration.Timing;
import io.aeyer.plowshare.sdk.Plowshare;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Standalone outbound provider supervision. The existing provider owns finite SDK/resource passes
 * and durable uncertainty; this host supplies scheduling, never retries or a second agent runtime.
 */
public final class HomeAssistantProviderHost implements HomeAssistantProviderLifecycle {
  /** Failed composition retains its exact cleanup owner, including an incompletely drained pass. */
  public static final class StartupException extends IOException {
    private final HomeAssistantProviderLifecycle owner;

    StartupException(Throwable failure, HomeAssistantProviderLifecycle owner) {
      super("Provider startup failed; preserve its retained cleanup owner", failure);
      this.owner = owner;
    }

    public HomeAssistantProviderLifecycle owner() {
      return owner;
    }
  }

  /** One logical provider. Implementations retain their journal until physical cleanup settles. */
  public interface Provider extends AutoCloseable {
    void reconcile() throws IOException;

    void renew() throws IOException;

    void poll() throws IOException;

    boolean available();

    void withdraw() throws IOException;

    @Override
    void close() throws IOException;
  }

  /** The shared HA and SDK owner, closed only after every borrowing provider has settled. */
  public interface Maintenance extends AutoCloseable {
    void maintain() throws IOException;

    @Override
    void close() throws IOException;
  }

  private final Maintenance maintenance;
  private final List<Provider> providers;
  private final Timing timing;
  private final int configuredProviders;
  private final LongSupplier clock;
  private final CountDownLatch stop = new CountDownLatch(1);
  private final CountDownLatch closed = new CountDownLatch(1);
  private final ExecutorService cleanupOwner =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("ha-provider-shared-cleanup-", 0).factory());
  private CompletableFuture<Void> sharedCleanup;
  private Thread sharedCleanupThread;
  private final AtomicBoolean working = new AtomicBoolean();
  private boolean started;
  private boolean failed;
  private volatile boolean closing;
  private volatile boolean starting;
  private long renewalAt;

  public HomeAssistantProviderHost(
      Maintenance maintenance, List<Provider> providers, Timing timing, LongSupplier clock) {
    this.maintenance = Objects.requireNonNull(maintenance);
    this.providers = List.copyOf(providers);
    if (this.providers.isEmpty() || this.providers.size() > 2)
      throw new IllegalArgumentException(
          "One read provider and optional control provider required");
    this.timing = Objects.requireNonNull(timing);
    configuredProviders = this.providers.size();
    this.clock = Objects.requireNonNull(clock);
  }

  private HomeAssistantProviderHost(
      Maintenance maintenance, Timing timing, int configuredProviders) {
    this.maintenance = maintenance;
    providers = new ArrayList<>();
    this.timing = timing;
    this.configuredProviders = configuredProviders;
    starting = true;
    clock = System::nanoTime;
  }

  /** Mutable only while the private composition root opens its resources. */
  private static final class SharedResources implements Maintenance {
    private final HomeAssistantAdapter adapter;
    private Plowshare sdk;

    SharedResources(HomeAssistantAdapter adapter) {
      this.adapter = adapter;
    }

    @Override
    public void maintain() throws IOException {
      adapter.maintain();
    }

    @Override
    public void close() {
      adapter.close();
      if (sdk != null) sdk.close();
    }
  }

  /**
   * Composition root. Resolve every credential and validate path aliases before transports or
   * journals open. One authenticated SDK socket serves both logical providers under the shared
   * execution principal required by the public Application provider contract.
   */
  public static HomeAssistantProviderHost open(
      HomeAssistantProviderConfiguration configuration, Function<String, String> environment)
      throws IOException {
    return open(configuration, environment, ignored -> {});
  }

  /** Publish the exact process owner before opening the first transport or journal. */
  public static HomeAssistantProviderHost open(
      HomeAssistantProviderConfiguration configuration,
      Function<String, String> environment,
      Consumer<HomeAssistantProviderLifecycle> retainOwner)
      throws IOException {
    Objects.requireNonNull(configuration);
    Objects.requireNonNull(environment);
    String haToken = credential(environment.apply(configuration.homeAssistant().tokenEnv()));
    String token = credential(environment.apply(configuration.read().tokenEnv()));
    requirePrivatePaths(configuration);
    var timing = configuration.timing();
    var adapter =
        new HomeAssistantAdapter(
            configuration.homeAssistant(),
            haToken,
            timing.connectTimeout(),
            timing.requestTimeout(),
            timing.cleanupTimeout());
    var resourcesOwner = new SharedResources(adapter);
    var host =
        new HomeAssistantProviderHost(
            resourcesOwner, timing, configuration.control().isPresent() ? 2 : 1);
    try {
      Objects.requireNonNull(retainOwner).accept(host);
      if (host.stop.getCount() == 0) return host;
      adapter.start(ignored -> {});
      if (host.stop.getCount() == 0) return host;
      var source = adapter.resourceSource(configuration.binding());
      var policy =
          HomeAssistantDiscoveryPolicy.fromSettings(
              source.binding(), configuration.policyRevision(), configuration.homeAssistant());
      var resources = new HomeAssistantResourceCatalog(source, policy, timing.metadataMaxAge());
      var sdk = Plowshare.connect(configuration.plowshare(), token, timing.connectTimeout(), null);
      resourcesOwner.sdk = sdk;
      if (host.stop.getCount() == 0) return host;
      var read = configuration.read().runtime(timing);
      var readTools =
          new HomeAssistantReadTools(
              source.binding(), read.prefix(), resources, resources, timing.requestTimeout());
      host.providers.add(
          openProvider(configuration, configuration.read(), sdk, readTools, haToken));
      if (host.stop.getCount() != 0 && configuration.control().isPresent()) {
        var selected = configuration.control().orElseThrow();
        var control = selected.provider().runtime(timing);
        var controlPolicy =
            new HomeAssistantActionPolicy(source.binding(), configuration.selectedActions());
        var tools =
            new HomeAssistantActionTools(
                source.binding(),
                control,
                read,
                resources,
                resources,
                controlPolicy,
                adapter.actionExecutor(configuration.binding()));
        host.providers.add(openProvider(configuration, selected.provider(), sdk, tools, haToken));
      }
      return host;
    } catch (IOException | RuntimeException failure) {
      // No catalogue is published during composition. Reverse-order cleanup must still retain
      // shared transport ownership if an earlier provider cannot settle its local physical owner.
      host.failed = true;
      host.starting = false;
      var retained = new StartupException(failure, host);
      try {
        host.close();
      } catch (IOException cleanup) {
        retained.addSuppressed(cleanup);
      }
      throw retained;
    } finally {
      // No journal can be appended after close is admitted. The process hook may only close after
      // this sole factory owner returns or releases ownership into its own failure cleanup.
      host.starting = false;
    }
  }

  private static Provider openProvider(
      HomeAssistantProviderConfiguration configuration,
      HomeAssistantProviderConfiguration.Provider selected,
      Plowshare sdk,
      HomeAssistantToolHandlers handlers,
      String haToken)
      throws IOException {
    var runtime = selected.runtime(configuration.timing());
    String owner =
        HomeAssistantToolProvider.ownerFingerprint(
            configuration.plowshare(),
            configuration.homeAssistant().endpoint(),
            haToken,
            configuration.policyRevision(),
            runtime.binding());
    var provider =
        HomeAssistantToolProvider.open(runtime, sdk, handlers, selected.journal(), owner);
    return new Provider() {
      @Override
      public void reconcile() throws IOException {
        provider.reconcile();
      }

      @Override
      public void renew() throws IOException {
        provider.renewCatalogue();
      }

      @Override
      public void poll() throws IOException {
        provider.poll();
      }

      @Override
      public boolean available() {
        return provider.catalogueAvailable();
      }

      @Override
      public void withdraw() throws IOException {
        provider.withdrawCatalogue();
      }

      @Override
      public void close() throws IOException {
        provider.close();
      }
    };
  }

  static void requirePrivatePaths(HomeAssistantProviderConfiguration configuration)
      throws IOException {
    requireNoSymlink(configuration.read().journal());
    if (configuration.control().isPresent())
      requireNoSymlink(configuration.control().orElseThrow().provider().journal());
  }

  private static void requireNoSymlink(Path journal) throws IOException {
    // Existing ancestor aliases can otherwise turn two different configured paths into the same
    // journal. Refuse conservatively before either owner creates/locks its directory. The journal's
    // own atomic-file/lock checks remain the authoritative defense against a concurrent
    // replacement.
    for (Path selected = journal; selected != null; selected = selected.getParent())
      if (Files.exists(selected, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(selected))
        throw new IOException("Provider journal path contains an unsupported symbolic link");
  }

  private static String credential(String value) throws IOException {
    if (value == null
        || value.isBlank()
        || value.length() > 16384
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IOException("A configured provider credential is missing or invalid");
    return value;
  }

  /** One serial cycle; separate provider pass budgets remain owned by their existing APIs. */
  public void cycle() throws IOException {
    if (starting) throw new IOException("Provider startup still owns its resources");
    if (!working.compareAndSet(false, true))
      throw new IOException("Provider supervisor pass is occupied");
    try {
      if (closing || stop.getCount() == 0) throw new IOException("Provider intake is stopped");
      if (!started) {
        started = true;
        for (Provider provider : providers) {
          if (stop.getCount() == 0) return;
          provider.reconcile();
          if (stop.getCount() == 0) return;
          // A zero reconciliation count is not proof of non-submission. The provider's existing
          // journal refuses this renewal if its original catalogue intent remains unresolved.
          provider.renew();
        }
        renewalAt = clock.getAsLong() + timing.renewEvery().toNanos();
      }
      if (stop.getCount() == 0) return;
      maintenance.maintain();
      if (stop.getCount() == 0) return;
      if (clock.getAsLong() - renewalAt >= 0) {
        for (Provider provider : providers) {
          if (stop.getCount() == 0) return;
          provider.renew();
        }
        renewalAt = clock.getAsLong() + timing.renewEvery().toNanos();
      }
      for (Provider provider : providers) {
        if (stop.getCount() == 0) return;
        if (!provider.available()) throw new IOException("Provider catalogue lease is unavailable");
        provider.poll();
      }
    } catch (IOException | RuntimeException failure) {
      failed = true;
      requestStop();
      throw failure;
    } finally {
      working.set(false);
    }
  }

  @Override
  public void run() throws IOException, InterruptedException {
    try {
      while (stop.getCount() != 0) {
        cycle();
        stop.await(timing.pollInterval().toNanos(), TimeUnit.NANOSECONDS);
      }
    } catch (InterruptedException interrupted) {
      failed = true;
      requestStop();
      Thread.currentThread().interrupt();
      throw interrupted;
    }
  }

  @Override
  public void requestStop() {
    stop.countDown();
  }

  @Override
  public Duration shutdownGrace() {
    // Stop is checked between every owner operation: only the current maintenance/provider pass
    // may remain, not a fresh complete cycle. Normal withdrawal uses one pass per provider;
    // journal drains and the shared SDK/HA close each retain their separate cleanup budget.
    Duration active =
        timing.connectTimeout().compareTo(timing.requestTimeout()) > 0
            ? timing.connectTimeout()
            : timing.requestTimeout();
    return active
        .plus(timing.requestTimeout().multipliedBy(configuredProviders))
        .plus(timing.cleanupTimeout().multipliedBy(configuredProviders + 1L));
  }

  @Override
  public boolean awaitClosed(Duration timeout) throws InterruptedException {
    if (timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Finite positive shutdown grace required");
    return closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      if (closed.getCount() == 0) return;
      if (Thread.currentThread() == sharedCleanupThread)
        throw new IOException("Shared provider cleanup cannot join its own physical owner");
      if (closing) throw new IOException("Provider cleanup already has an active owner");
      if (starting || working.get())
        throw new IOException("Stop and settle the provider supervisor before cleanup");
      closing = true;
    }
    requestStop();
    // Cleanup joins need to run even when the owner was interrupted. Restore the original flag;
    // do not reinterpret cancellation as proof that any SDK/native operation did not happen.
    boolean interrupted = Thread.interrupted();
    IOException problem = null;
    try {
      if (started && !failed) {
        for (int index = providers.size() - 1; index >= 0; index--) {
          Provider provider = providers.get(index);
          if (provider.available()) {
            try {
              provider.withdraw();
            } catch (IOException | RuntimeException failure) {
              problem =
                  new IOException(
                      "Provider withdrawal failed; preserve its original intent", failure);
              failed = true;
              break;
            }
          }
        }
      }
      IOException cleanup =
          new IOException("Provider cleanup is incomplete; retain its journal owner");
      boolean settled = closeProviders(providers, cleanup);
      if (!settled) {
        failed = true;
        if (problem == null) problem = cleanup;
        else problem.addSuppressed(cleanup);
      } else {
        try {
          closeShared();
        } catch (IOException failure) {
          if (problem == null) problem = failure;
          else problem.addSuppressed(failure);
        }
      }
    } finally {
      synchronized (this) {
        closing = false;
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (problem != null) {
      failed = true;
      throw problem;
    }
  }

  private void closeShared() throws IOException {
    if (sharedCleanup == null) {
      sharedCleanup = new CompletableFuture<>();
      cleanupOwner.execute(
          () -> {
            sharedCleanupThread = Thread.currentThread();
            try {
              maintenance.close();
              closed.countDown();
              sharedCleanup.complete(null);
            } catch (IOException | RuntimeException failure) {
              sharedCleanup.completeExceptionally(failure);
            }
          });
      cleanupOwner.shutdown();
    }
    try {
      sharedCleanup.get(timing.cleanupTimeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException(
          "Shared provider cleanup interrupted; its physical owner remains retained");
    } catch (TimeoutException timeout) {
      throw new IOException(
          "Shared provider cleanup incomplete; its original physical owner remains retained");
    } catch (ExecutionException failure) {
      throw new IOException(
          "Shared provider cleanup failed; retain its original owner", failure.getCause());
    }
  }

  private static boolean closeProviders(List<Provider> providers, Throwable failure) {
    boolean settled = true;
    for (int index = providers.size() - 1; index >= 0; index--) {
      try {
        providers.get(index).close();
      } catch (IOException | RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
        settled = false;
      }
    }
    return settled;
  }
}
