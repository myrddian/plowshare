package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.Main;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** HA executable entry point; only the explicit provider flag selects the named-tool host. */
public final class HomeAssistantMain {
  private HomeAssistantMain() {}

  @FunctionalInterface
  interface Launcher {
    HomeAssistantProviderLifecycle open(
        HomeAssistantProviderConfiguration configuration,
        Consumer<HomeAssistantProviderLifecycle> retainOwner)
        throws IOException;
  }

  /** Process lifecycle boundary; a retained hook keeps an incompletely drained exact owner. */
  interface Hooks {
    Thread register(Runnable stop);

    void remove(Thread hook);
  }

  private static final Hooks PROCESS_HOOKS =
      new Hooks() {
        @Override
        public Thread register(Runnable stop) {
          Thread hook = new Thread(stop, "ha-provider-shutdown");
          Runtime.getRuntime().addShutdownHook(hook);
          return hook;
        }

        @Override
        public void remove(Thread hook) {
          try {
            Runtime.getRuntime().removeShutdownHook(hook);
          } catch (IllegalStateException stopping) {
            // JVM shutdown already owns this hook; its bounded observation remains authoritative.
          }
        }
      };

  public static void main(String[] arguments) throws Exception {
    if (arguments.length == 0 || !arguments[0].equals("--provider")) {
      Main.main(arguments);
      return;
    }
    int result =
        runProvider(
            arguments,
            System.out,
            System.err,
            (configuration, retainOwner) ->
                HomeAssistantProviderHost.open(configuration, System::getenv, retainOwner));
    // Do not forcibly terminate a retained physical cleanup owner. An incomplete owner remains
    // referenced by its shutdown hook and resources until explicit process shutdown observes it.
    // This static exception still yields a nonzero command outcome without exposing a dependency
    // exception's potentially private configuration/provider data.
    if (result != 0)
      throw new IOException("Outbound provider command failed; preserve retained journals");
  }

  static int runProvider(
      String[] arguments, PrintStream out, PrintStream error, Launcher launcher) {
    return runProvider(arguments, out, error, launcher, PROCESS_HOOKS);
  }

  static int runProvider(
      String[] arguments, PrintStream out, PrintStream error, Launcher launcher, Hooks hooks) {
    boolean check = arguments.length == 3 && arguments[1].equals("--check");
    if (!(check || arguments.length == 2 && !arguments[1].startsWith("--"))
        || !arguments[0].equals("--provider")) {
      error.println("Usage: plowshare-integration-home-assistant --provider [--check] CONFIG");
      return 2;
    }
    try {
      var configuration =
          HomeAssistantProviderConfiguration.read(Path.of(arguments[check ? 2 : 1]));
      if (check) {
        out.println(
            "Outbound provider configuration is valid; no credentials, connections or journals opened.");
        return 0;
      }
      var retained = new AtomicReference<HomeAssistantProviderLifecycle>();
      var stopping = new AtomicBoolean();
      // A failed hook registration must precede every resource open. The composition callback
      // publishes its exact owner before the first socket/journal, including partial startup.
      Thread shutdown =
          hooks.register(
              () -> {
                stopping.set(true);
                var owner = retained.get();
                if (owner != null) stopOriginalOwner(owner, error);
              });
      try {
        if (stopping.get()) return 1;
        var host =
            launcher.open(
                configuration,
                owner -> {
                  if (!retained.compareAndSet(null, owner))
                    throw new IllegalStateException(
                        "Provider launcher published more than one owner");
                  if (stopping.get()) owner.requestStop();
                });
        if (retained.get() != host)
          throw new HomeAssistantProviderHost.StartupException(
              new IOException("Provider launcher did not retain its exact owner"), host);
        try (host) {
          host.run();
        }
      } catch (HomeAssistantProviderHost.StartupException failure) {
        retained.compareAndSet(null, failure.owner());
        throw failure;
      } finally {
        var owner = retained.get();
        if (owner == null || finished(owner)) hooks.remove(shutdown);
        else
          error.println(
              "Provider retains an unfinished cleanup owner; inspect before process restart.");
      }
      return 0;
    } catch (HomeAssistantProviderHost.StartupException failure) {
      error.println(
          "Provider startup failed; preserve journals and the original cleanup owner for inspection.");
      return 1;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      error.println(
          "Provider stopped by interruption; preserve retained journals before restarting.");
      return 130;
    } catch (IOException | RuntimeException failure) {
      // Dependency/parser messages can include supplied configuration or provider data. The
      // operator gets a bounded failure status; private journals retain exact recovery identity.
      error.println(
          "Outbound provider stopped; check configuration and credentials, and preserve journals for inspection.");
      return 1;
    }
  }

  private static boolean finished(HomeAssistantProviderLifecycle owner) {
    boolean interrupted = Thread.interrupted();
    try {
      return owner.awaitClosed(Duration.ofNanos(1));
    } catch (InterruptedException interruption) {
      interrupted = true;
      return false;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  static void stopOriginalOwner(HomeAssistantProviderLifecycle owner, PrintStream error) {
    owner.requestStop();
    try {
      if (owner.awaitClosed(owner.shutdownGrace())) return;
      // This repeats cleanup observation on the same owner, never a provider invocation, catalogue
      // mutation or SDK reconnect. Host.close suppresses withdrawal after an uncertain failure.
      owner.close();
      if (!finished(owner))
        error.println(
            "Provider cleanup remains incomplete; retained receipts do not prove remote abort.");
    } catch (IOException | RuntimeException failure) {
      error.println(
          "Provider cleanup remains incomplete; preserve journals and inspect uncertainty.");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      error.println("Provider cleanup observation interrupted; preserve its retained owner.");
    }
  }
}
