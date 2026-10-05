package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.SchedulingConfig;
import io.aeyer.plowshare.server.auth.AdminStore;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Exercises the real scheduled authorization sweep with both dedicated timers present. */
class SocketSchedulingTest {

  @Test
  void scheduled_revocation_uses_the_explicit_scheduler_and_stops_with_the_context()
      throws Exception {
    AdminStore accounts = mock(AdminStore.class);
    AtomicBoolean revoked = new AtomicBoolean();
    when(accounts.sessionCurrent("member", 1L)).thenAnswer(call -> !revoked.get());

    WebSocketSession socket = mock(WebSocketSession.class);
    when(socket.getAttributes())
        .thenReturn(Map.of("plowshare.sessionVersion", 1L, EventChannelHandler.HANDLE, "member"));
    AtomicReference<Thread> revocationThread = new AtomicReference<>();
    CountDownLatch closed = new CountDownLatch(1);
    doAnswer(
            call -> {
              revocationThread.set(Thread.currentThread());
              closed.countDown();
              return null;
            })
        .when(socket)
        .close(CloseStatus.POLICY_VIOLATION.withReason("Account session revoked"));

    AtomicReference<ScheduledThreadPoolExecutor> executor = new AtomicReference<>();
    new ApplicationContextRunner()
        .withUserConfiguration(
            SchedulingConfig.class, SocketAuthorization.class, DedicatedTimers.class)
        .withBean(AdminStore.class, () -> accounts)
        .run(
            context -> {
              TaskScheduler scheduler = context.getBean("taskScheduler", TaskScheduler.class);
              executor.set(
                  context
                      .getBean("taskScheduler", ThreadPoolTaskScheduler.class)
                      .getScheduledThreadPoolExecutor());
              AtomicReference<Thread> schedulerThread = new AtomicReference<>();
              scheduler
                  .schedule(() -> schedulerThread.set(Thread.currentThread()), Instant.now())
                  .get(5, TimeUnit.SECONDS);

              assertTrue(context.getBean(SocketAuthorization.class).attach(socket));
              revoked.set(true);
              assertTrue(
                  closed.await(5, TimeUnit.SECONDS), "scheduled sweep must close the socket");
              assertSame(schedulerThread.get(), revocationThread.get());
              assertTrue(
                  context.getBean("eventsTickerThread", ScheduledExecutorService.class)
                      != executor.get());
              assertTrue(
                  context.getBean("orchestrationStallsThread", ScheduledExecutorService.class)
                      != executor.get());
            });

    assertTrue(executor.get().isShutdown(), "closing the context must shut down its scheduler");
    assertTrue(executor.get().awaitTermination(5, TimeUnit.SECONDS));
  }

  @Configuration(proxyBeanMethods = false)
  static class DedicatedTimers {
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService eventsTickerThread() {
      return Executors.newSingleThreadScheduledExecutor();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService orchestrationStallsThread() {
      return Executors.newSingleThreadScheduledExecutor();
    }
  }
}
