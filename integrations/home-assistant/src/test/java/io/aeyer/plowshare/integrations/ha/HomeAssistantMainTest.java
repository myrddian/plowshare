package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeAssistantMainTest {
  @TempDir Path directory;

  private static final class Hooks implements HomeAssistantMain.Hooks {
    Runnable retained;
    boolean removed;
    boolean failRegister;

    @Override
    public Thread register(Runnable stop) {
      if (failRegister) throw new IllegalStateException("process is already stopping");
      retained = stop;
      return new Thread(stop);
    }

    @Override
    public void remove(Thread hook) {
      removed = true;
    }
  }

  private static HomeAssistantMain.Launcher launcher(Host host) {
    return (configuration, retainOwner) -> {
      retainOwner.accept(host);
      return host;
    };
  }

  private static final class Host implements HomeAssistantProviderLifecycle {
    int runs;
    int closes;
    int stops;
    boolean failClose;
    boolean finished;
    boolean interrupt;
    boolean runtimeClose;

    @Override
    public void run() throws InterruptedException {
      runs++;
      if (interrupt) throw new InterruptedException("fixture interrupt");
    }

    @Override
    public void requestStop() {
      stops++;
    }

    @Override
    public Duration shutdownGrace() {
      return Duration.ofMillis(100);
    }

    @Override
    public boolean awaitClosed(Duration timeout) {
      assertFalse(timeout.isNegative() || timeout.isZero());
      return finished;
    }

    @Override
    public void close() throws IOException {
      closes++;
      if (runtimeClose) throw new IllegalStateException("private cleanup payload");
      if (failClose) throw new IOException("private provider payload must not be logged");
      finished = true;
    }
  }

  @Test
  void offlineCheckNeverLaunchesCredentialOrJournalOwners() throws Exception {
    Path config =
        HomeAssistantProviderConfigurationTest.write(
            directory, HomeAssistantProviderConfigurationTest.fixture());
    var output = new ByteArrayOutputStream();
    AtomicInteger launches = new AtomicInteger();
    int result =
        HomeAssistantMain.runProvider(
            new String[] {"--provider", "--check", config.toString()},
            new PrintStream(output),
            new PrintStream(output),
            (value, retainOwner) -> {
              launches.incrementAndGet();
              return new Host();
            },
            new Hooks());
    assertEquals(0, result);
    assertEquals(0, launches.get());
    assertFalse(Files.exists(directory.resolve("private")));
    assertTrue(
        output
            .toString(StandardCharsets.UTF_8)
            .contains("no credentials, connections or journals opened"));
  }

  @Test
  void invalidProviderFlagsAndInvalidConfigCannotFallBackOrExposeSuppliedValues() throws Exception {
    var output = new ByteArrayOutputStream();
    AtomicInteger launches = new AtomicInteger();
    HomeAssistantMain.Launcher launcher =
        (value, retainOwner) -> {
          launches.incrementAndGet();
          return new Host();
        };
    for (String[] arguments :
        new String[][] {
          {"--provider"}, {"--provider", "--unknown"}, {"--provider", "--check", "unused", "extra"}
        }) {
      assertEquals(
          2,
          HomeAssistantMain.runProvider(
              arguments, new PrintStream(output), new PrintStream(output), launcher, new Hooks()));
    }
    Path config = directory.resolve("private-configuration.json");
    Files.writeString(config, "{\"secret-fixture-value\":true}");
    assertEquals(
        1,
        HomeAssistantMain.runProvider(
            new String[] {"--provider", "--check", config.toString()},
            new PrintStream(output),
            new PrintStream(output),
            launcher,
            new Hooks()));
    assertEquals(0, launches.get());
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("secret-fixture-value"));
    assertFalse(output.toString(StandardCharsets.UTF_8).contains(directory.toString()));
  }

  @Test
  void normalClosureRemovesHookOnlyAfterPhysicalCleanup() throws Exception {
    Path config =
        HomeAssistantProviderConfigurationTest.write(
            directory, HomeAssistantProviderConfigurationTest.fixture());
    var output = new ByteArrayOutputStream();
    var hooks = new Hooks();
    var host = new Host();
    assertEquals(
        0,
        HomeAssistantMain.runProvider(
            new String[] {"--provider", config.toString()},
            new PrintStream(output),
            new PrintStream(output),
            launcher(host),
            hooks));
    assertEquals(1, host.runs);
    assertEquals(1, host.closes);
    assertTrue(hooks.removed);
  }

  @Test
  void incompleteCloseRetainsHookAndRetriesOnlyTheSameCleanupOwner() throws Exception {
    Path config =
        HomeAssistantProviderConfigurationTest.write(
            directory, HomeAssistantProviderConfigurationTest.fixture());
    var output = new ByteArrayOutputStream();
    var hooks = new Hooks();
    var host = new Host();
    host.failClose = true;
    assertEquals(
        1,
        HomeAssistantMain.runProvider(
            new String[] {"--provider", config.toString()},
            new PrintStream(output),
            new PrintStream(output),
            launcher(host),
            hooks));
    assertFalse(hooks.removed);
    assertEquals(1, host.closes);
    host.failClose = false;
    hooks.retained.run();
    assertEquals(1, host.runs);
    assertEquals(2, host.closes);
    assertEquals(1, host.stops);
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("private provider payload"));
  }

  @Test
  void partialStartupRetainsItsTypedExactOwnerForBoundedProcessShutdown() throws Exception {
    Path config =
        HomeAssistantProviderConfigurationTest.write(
            directory, HomeAssistantProviderConfigurationTest.fixture());
    var output = new ByteArrayOutputStream();
    var hooks = new Hooks();
    var host = new Host();
    host.failClose = true;
    assertEquals(
        1,
        HomeAssistantMain.runProvider(
            new String[] {"--provider", config.toString()},
            new PrintStream(output),
            new PrintStream(output),
            (value, retainOwner) -> {
              retainOwner.accept(host);
              try {
                host.close();
              } catch (IOException failure) {
                throw new HomeAssistantProviderHost.StartupException(failure, host);
              }
              return host;
            },
            hooks));
    assertFalse(hooks.removed);
    assertEquals(0, host.runs);
    assertEquals(1, host.closes);
    host.failClose = false;
    hooks.retained.run();
    assertEquals(2, host.closes);
    assertTrue(host.finished);
  }

  @Test
  void interruptionRemainsVisibleAfterOwnedCleanupAndHookRemoval() throws Exception {
    Path config =
        HomeAssistantProviderConfigurationTest.write(
            directory, HomeAssistantProviderConfigurationTest.fixture());
    var output = new ByteArrayOutputStream();
    var hooks = new Hooks();
    var host = new Host();
    host.interrupt = true;
    try {
      assertEquals(
          130,
          HomeAssistantMain.runProvider(
              new String[] {"--provider", config.toString()},
              new PrintStream(output),
              new PrintStream(output),
              launcher(host),
              hooks));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(hooks.removed);
      assertEquals(1, host.closes);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void hookRegistrationFailureHappensBeforeOpeningAnyResource() throws Exception {
    Path config =
        HomeAssistantProviderConfigurationTest.write(
            directory, HomeAssistantProviderConfigurationTest.fixture());
    var output = new ByteArrayOutputStream();
    var hooks = new Hooks();
    hooks.failRegister = true;
    AtomicInteger launches = new AtomicInteger();
    assertEquals(
        1,
        HomeAssistantMain.runProvider(
            new String[] {"--provider", config.toString()},
            new PrintStream(output),
            new PrintStream(output),
            (value, retainOwner) -> {
              launches.incrementAndGet();
              return new Host();
            },
            hooks));
    assertEquals(0, launches.get());
    assertFalse(Files.exists(directory.resolve("private")));
  }

  @Test
  void unexpectedCleanupRuntimeFailureStaysBoundedAndDoesNotExposeDependencyData() {
    var output = new ByteArrayOutputStream();
    var host = new Host();
    host.runtimeClose = true;
    HomeAssistantMain.stopOriginalOwner(host, new PrintStream(output));
    assertEquals(1, host.closes);
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("private cleanup payload"));
    assertTrue(output.toString(StandardCharsets.UTF_8).contains("cleanup remains incomplete"));
  }
}
