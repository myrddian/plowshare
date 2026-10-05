package io.aeyer.plowshare.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Owns the application-context scheduler used by {@code @Scheduled} methods. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

  /**
   * Explicitly selects a scheduler for annotation-driven tasks. Event ticks and orchestration stall
   * sweeps keep their dedicated executors so neither can delay socket revocation checks. Spring
   * initializes this scheduler and shuts it down when the application context closes.
   */
  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("scheduled-task-");
    return scheduler;
  }
}
