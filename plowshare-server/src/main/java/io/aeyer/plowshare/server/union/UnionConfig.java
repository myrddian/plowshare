package io.aeyer.plowshare.server.union;

import io.aeyer.plowshare.server.agents.RunEnds;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import java.time.Instant;
import java.util.List;
import org.eclipse.jgit.http.server.GitServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Union projects: the store, the hubs, the gate, the git endpoint. Spec 2026-09-14. */
@Configuration
public class UnionConfig {

  private static final Logger log = LoggerFactory.getLogger(UnionConfig.class);

  @Bean
  public UnionStore unionStore(JdbcTemplate jdbc) {
    return new UnionStore(jdbc);
  }

  @Bean
  public Hubs hubs(DataLayout layout, ProjectStore projects) {
    return new Hubs(layout, projects::id);
  }

  /** Also a {@code SessionCloseListener}: {@code FileChannelConfig} collects every one. */
  @Bean
  public UnionGate unionGate(Hubs hubs, ApplicationEventPublisher events) {
    return new UnionGate(
        hubs,
        events::publishEvent,
        Instant::now,
        UnionGate.WRITE_PATIENCE,
        UnionGate.SYNC_PATIENCE);
  }

  /** A run that ends in a union's project commits what it wrote to the server's copy. */
  @Bean
  public RunEnds unionRunEnds(UnionStore unions, UnionGate gate) {
    return (runId, agent, home) -> {
      if (home == null || home.isGlobal()) {
        return;
      }
      if (unions.find(home.project()).filter(UnionStore.Union::enabled).isPresent()) {
        gate.runEnded(home.project(), runId, agent);
      }
    };
  }

  @Bean
  public UnionRouting unionRouting(UnionStore unions, Hubs hubs, UnionGate gate) {
    return new UnionRouting(unions, hubs, gate);
  }

  @Bean
  public ServletRegistrationBean<GitServlet> unionHub(
      Hubs hubs,
      UnionGate gate,
      UnionStore unions,
      io.aeyer.plowshare.server.archive.ProjectMembers members,
      @Value("${plowshare.union.max-file-bytes:5242880}") long maxFileBytes,
      @Value("${plowshare.union.max-push-bytes:2147483648}") long maxPushBytes) {
    GitServlet servlet =
        HubServlet.build(
            hubs,
            gate,
            project -> unions.find(project).map(UnionStore.Union::syncHidden).orElse(List.of()),
            maxFileBytes,
            maxPushBytes,
            members::requireRole);
    ServletRegistrationBean<GitServlet> registration =
        new ServletRegistrationBean<>(servlet, HubServlet.MAPPING);
    registration.setName("unionHub");
    return registration;
  }

  /**
   * A server that stopped mid-run left writes in some tree; commit them before the mirror serves.
   */
  @Bean
  public ApplicationRunner unionRecovery(UnionStore unions, UnionGate gate) {
    return args -> {
      for (UnionStore.Union union : unions.enabled()) {
        try {
          gate.runEnded(union.name(), "(server restarted)", Hub.SERVER_AUTHOR);
        } catch (RuntimeException failed) {
          log.warn("could not commit the tree of union '{}' at startup", union.name(), failed);
        }
      }
    };
  }
}
