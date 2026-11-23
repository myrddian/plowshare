package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderConfiguration.Timing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeAssistantProviderHostTest {
  @TempDir Path directory;
  private static final Timing TIMING =
      new Timing(
          Duration.ofSeconds(20),
          Duration.ofSeconds(2),
          Duration.ofMillis(100),
          Duration.ofMillis(10),
          Duration.ofSeconds(10),
          Duration.ofSeconds(30));

  private static final class Provider implements HomeAssistantProviderHost.Provider {
    final List<String> events;
    final String name;
    boolean available;
    boolean failPoll;
    boolean failRenew;
    boolean failClose;
    boolean failWithdraw;

    Provider(String name, List<String> events) {
      this.name = name;
      this.events = events;
    }

    @Override
    public void reconcile() {
      events.add(name + ":reconcile");
    }

    @Override
    public void renew() throws IOException {
      events.add(name + ":renew");
      if (failRenew) throw new IOException("retained publication remains uncertain");
      available = true;
    }

    @Override
    public void poll() throws IOException {
      events.add(name + ":poll");
      if (failPoll) throw new IOException("submitted result remains uncertain");
    }

    @Override
    public boolean available() {
      return available;
    }

    @Override
    public void withdraw() {
      events.add(name + ":withdraw");
      if (failWithdraw) throw new IllegalStateException("private unknown withdrawal");
      available = false;
    }

    @Override
    public void close() throws IOException {
      events.add(name + ":close");
      assertFalse(Thread.currentThread().isInterrupted());
      if (failClose) throw new IOException("physical owner remains occupied");
    }
  }

  private static HomeAssistantProviderHost.Maintenance maintenance(List<String> events) {
    return new HomeAssistantProviderHost.Maintenance() {
      @Override
      public void maintain() {
        events.add("maintain");
      }

      @Override
      public void close() {
        events.add("shared:close");
      }
    };
  }

  @Test
  void startupReconcilesBeforeOnePublicationAndScheduledRenewalIsNotAReplay() throws Exception {
    List<String> events = new ArrayList<>();
    var read = new Provider("read", events);
    var control = new Provider("control", events);
    AtomicLong clock = new AtomicLong();
    try (var host =
        new HomeAssistantProviderHost(
            maintenance(events), List.of(read, control), TIMING, clock::get)) {
      host.cycle();
      host.cycle();
      assertEquals(1, events.stream().filter("read:renew"::equals).count());
      assertEquals(
          List.of("read:reconcile", "read:renew", "control:reconcile", "control:renew"),
          events.subList(0, 4));
      clock.set(Duration.ofSeconds(11).toNanos());
      host.cycle();
      assertEquals(2, events.stream().filter("read:renew"::equals).count());
      assertEquals(3, events.stream().filter("control:poll"::equals).count());
    }
    assertEquals(
        List.of("control:withdraw", "read:withdraw", "control:close", "read:close", "shared:close"),
        events.subList(events.size() - 5, events.size()));
  }

  @Test
  void uncertainPollStopsIntakeWithoutAnotherPublicationWithdrawalOrEffect() throws Exception {
    List<String> events = new ArrayList<>();
    var read = new Provider("read", events);
    read.failPoll = true;
    var host = new HomeAssistantProviderHost(maintenance(events), List.of(read), TIMING, () -> 0);
    assertThrows(IOException.class, host::cycle);
    assertThrows(IOException.class, host::cycle);
    host.close();
    assertEquals(1, events.stream().filter("read:renew"::equals).count());
    assertEquals(1, events.stream().filter("read:poll"::equals).count());
    assertFalse(events.contains("read:withdraw"));
    assertTrue(events.contains("shared:close"));
  }

  @Test
  void unresolvedCatalogueCannotBecomeAFreshPublicationRetry() throws Exception {
    List<String> events = new ArrayList<>();
    var read = new Provider("read", events);
    read.failRenew = true;
    var host = new HomeAssistantProviderHost(maintenance(events), List.of(read), TIMING, () -> 0);
    assertThrows(IOException.class, host::cycle);
    host.close();
    assertEquals(List.of("read:reconcile", "read:renew", "read:close", "shared:close"), events);
  }

  @Test
  void incompleteProviderCleanupKeepsSharedTransportAndAllowsOnlyTheSameOwnerToDrain()
      throws Exception {
    List<String> events = new ArrayList<>();
    var read = new Provider("read", events);
    var host = new HomeAssistantProviderHost(maintenance(events), List.of(read), TIMING, () -> 0);
    host.cycle();
    read.failClose = true;
    assertThrows(IOException.class, host::close);
    assertFalse(events.contains("shared:close"));
    assertFalse(host.awaitClosed(Duration.ofMillis(1)));
    read.failClose = false;
    host.close();
    assertTrue(host.awaitClosed(Duration.ofMillis(1)));
    assertEquals(1, events.stream().filter("read:withdraw"::equals).count());
    assertEquals(1, events.stream().filter("shared:close"::equals).count());
  }

  @Test
  void capabilityIdentityLockRemainsOwnedUntilBorrowingProviderPhysicallySettles()
      throws Exception {
    var identityPath = directory.toRealPath().resolve("identities");
    var store =
        new HomeAssistantCapabilityIdentityStore(identityPath, "a".repeat(64), "b".repeat(64));
    var read = new Provider("read", new ArrayList<>());
    var shared =
        new HomeAssistantProviderHost.Maintenance() {
          @Override
          public void maintain() {}

          @Override
          public void close() throws IOException {
            store.close();
          }
        };
    var host = new HomeAssistantProviderHost(shared, List.of(read), TIMING, () -> 0);
    try {
      host.cycle();
      read.failClose = true;
      assertThrows(IOException.class, host::close);
      assertThrows(
          IOException.class,
          () ->
              new HomeAssistantCapabilityIdentityStore(
                  identityPath, "a".repeat(64), "b".repeat(64)));
      read.failClose = false;
      host.close();
      try (var reopened =
          new HomeAssistantCapabilityIdentityStore(identityPath, "a".repeat(64), "b".repeat(64))) {
        assertEquals("a".repeat(64), reopened.state().owner());
        assertTrue(Files.isRegularFile(identityPath.resolve("identities.json")));
      }
    } finally {
      read.failClose = false;
      host.close();
    }
  }

  @Test
  void sharedCleanupHasOneRetainedPhysicalTaskAfterCallerExpiry() throws Exception {
    List<String> events = new ArrayList<>();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger closes = new AtomicInteger();
    var read = new Provider("read", events);
    var host =
        new HomeAssistantProviderHost(
            new HomeAssistantProviderHost.Maintenance() {
              @Override
              public void maintain() {}

              @Override
              public void close() throws IOException {
                closes.incrementAndGet();
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IOException("cleanup interrupted");
                }
              }
            },
            List.of(read),
            TIMING,
            () -> 0);
    try {
      assertThrows(IOException.class, host::close);
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertFalse(host.awaitClosed(Duration.ofMillis(1)));
      assertThrows(IOException.class, host::close);
      assertEquals(1, closes.get());
    } finally {
      release.countDown();
    }
    assertTrue(host.awaitClosed(Duration.ofSeconds(1)));
    host.close();
    assertEquals(1, closes.get());
  }

  @Test
  void cleanupRetainsTheCallersInterruptWithoutAbandoningItsOwnedDrain() throws Exception {
    List<String> events = new ArrayList<>();
    var host =
        new HomeAssistantProviderHost(
            maintenance(events), List.of(new Provider("read", events)), TIMING, () -> 0);
    Thread.currentThread().interrupt();
    try {
      host.close();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(List.of("read:close", "shared:close"), events);
  }

  @Test
  void stopDuringLongMaintenanceKeepsItsOwnerAndSkipsSubsequentPolls() throws Exception {
    List<String> events = new ArrayList<>();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var read = new Provider("read", events);
    var host =
        new HomeAssistantProviderHost(
            new HomeAssistantProviderHost.Maintenance() {
              @Override
              public void maintain() throws IOException {
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IOException(interrupted);
                }
              }

              @Override
              public void close() {
                events.add("shared:close");
              }
            },
            List.of(read),
            TIMING,
            () -> 0);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var cycle =
          workers.submit(
              () -> {
                host.cycle();
                return true;
              });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      host.requestStop();
      assertThrows(IOException.class, host::close);
      assertFalse(cycle.isDone());
      assertEquals(Duration.ofMillis(22200), host.shutdownGrace());
      release.countDown();
      assertTrue(cycle.get(1, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
    host.close();
    assertFalse(events.contains("read:poll"));
    assertTrue(events.contains("read:withdraw"));
  }

  @Test
  void publishedStartupOwnerCannotBeClosedOrRunBeforeFactoryReleasesItsResources()
      throws Exception {
    Path privateDirectory = directory.toRealPath();
    var configuration =
        HomeAssistantProviderConfiguration.read(
            HomeAssistantProviderConfigurationTest.write(
                privateDirectory, HomeAssistantProviderConfigurationTest.fixture()));
    var retained = new AtomicReference<HomeAssistantProviderLifecycle>();
    var host =
        HomeAssistantProviderHost.open(
            configuration,
            ignored -> "fixture-token",
            owner -> {
              retained.set(owner);
              assertEquals(Duration.ofSeconds(30), owner.shutdownGrace());
              assertThrows(IOException.class, owner::close);
              assertThrows(IOException.class, owner::run);
              owner.requestStop();
            });
    assertTrue(host == retained.get());
    assertFalse(Files.exists(privateDirectory.resolve("private")));
    host.close();
    assertTrue(host.awaitClosed(Duration.ofMillis(1)));
    assertFalse(Files.exists(privateDirectory.resolve("private")));
  }

  @Test
  void runtimeWithdrawalFailureCannotPreventCleanupOrPermitAnotherPublication() throws Exception {
    List<String> events = new ArrayList<>();
    var read = new Provider("read", events);
    read.failWithdraw = true;
    var host = new HomeAssistantProviderHost(maintenance(events), List.of(read), TIMING, () -> 0);
    host.cycle();
    assertThrows(IOException.class, host::close);
    host.close();
    assertEquals(1, events.stream().filter("read:withdraw"::equals).count());
    assertTrue(events.contains("shared:close"));
  }
}
