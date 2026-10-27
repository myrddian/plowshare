package io.aeyer.plowshare.server.proc;

import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobStore;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Existing stores and live handles remain the authorities; PROC only reads them. */
@Configuration
public class ProcConfiguration {
  @Bean
  public ProcRepository procRepository(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      io.aeyer.plowshare.server.cache.ProjectionCaches caches,
      io.aeyer.plowshare.server.reads.ReadAdmission admission,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    return new CachedProcRepository(
        new JdbcProcRepository(jdbc, transactions),
        caches.create(
            "proc-queues-v1",
            java.util.Set.of(
                io.aeyer.plowshare.server.cache.ProjectionVersions.Domain.QUEUES,
                io.aeyer.plowshare.server.cache.ProjectionVersions.Domain.PROGRESS,
                io.aeyer.plowshare.server.cache.ProjectionVersions.Domain.DOCUMENTS,
                io.aeyer.plowshare.server.cache.ProjectionVersions.Domain.SEARCH,
                io.aeyer.plowshare.server.cache.ProjectionVersions.Domain.TRANSCRIPTS,
                io.aeyer.plowshare.server.cache.ProjectionVersions.Domain.AUTHORITY)),
        admission,
        json);
  }

  @Bean
  public ProcRuntime procRuntime(
      JobStore jobs,
      io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher dispatcher,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.llm.accounting.AccountingJournal>
          journals,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.llm.accounting.AccountingRecorder>
          recorders,
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.llm.accounting.AccountingProjector>
          projectors) {
    return new ProcRuntime() {
      public java.util.List<String> notes() {
        var notes = new java.util.ArrayList<String>();
        var journal = journals.getIfAvailable();
        notes.add(
            journal == null
                ? "Accounting capture disabled; absence of calls does not imply absence of inference."
                : "Accounting capture enabled; journal pending events "
                    + journal.health().pendingEvents()
                    + "; journal status "
                    + journal.health().problem().name());
        var recorder = recorders.getIfAvailable();
        if (recorder != null)
          notes.add(
              "Buffered terminal events "
                  + recorder.health().bufferedTerminalEvents()
                  + "; lost terminal events "
                  + recorder.health().lostTerminalEvents()
                  + "; projection conflict "
                  + recorder.health().projectionConflict());
        var projector = projectors.getIfAvailable();
        if (projector != null)
          notes.add(
              "Projection status "
                  + projector.health().problem().name()
                  + "; last success "
                  + projector.health().lastSuccessAt());
        return java.util.List.copyOf(notes);
      }

      public ProcRepository.Live snapshot() {
        return new ProcRepository.Live(
            Instant.now(),
            jobs.jobs().stream()
                .filter(job -> job.state() == Job.State.RUNNING)
                .map(Job::conversation)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
      }

      public java.util.List<ProcViews.Pool> pools() {
        return dispatcher.pools().stream()
            .flatMap(
                pool ->
                    java.util.Arrays.stream(io.aeyer.plowshare.server.llm.dispatch.Lane.values())
                        .map(
                            lane -> {
                              var occupancy = pool.occupancy(lane);
                              return new ProcViews.Pool(
                                  pool.name(),
                                  lane.name(),
                                  occupancy.queued(),
                                  occupancy.running(),
                                  occupancy.capacity());
                            }))
            .toList();
      }
    };
  }

  @Bean
  public ProcAudit procAudit(ProcRepository repository, ProcRuntime runtime) {
    return new ProcAudit(repository, runtime);
  }
}
