package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.files.FileStores;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.swarm.DispatcherPools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deployments use the same repository transaction, resource parsers and runtime policy. */
@Configuration
public class ApplicationDeploymentConfig {
  @Bean
  public ApplicationDeploymentStore applicationDeploymentStore(
      JdbcTemplate jdbc,
      UnitOfWork work,
      ServerProjects projects,
      io.aeyer.plowshare.server.events.ScheduleDefinitionStore schedules) {
    return new JdbcApplicationDeploymentStore(jdbc, work, projects, schedules);
  }

  @Bean
  public ApplicationPackageValidator applicationPackageValidator(
      ObjectProvider<AgentRegistry> boot,
      ObjectProvider<JobRuntime> runtime,
      DefinitionChecks checks,
      LlmDispatcher dispatcher,
      HookEngine hooks,
      io.aeyer.plowshare.server.relay.RelayRouteProgram relay) {
    return (project, root) ->
        new RuntimeApplicationPackageValidator(
                boot.getObject(),
                runtime.getObject().knownTools(),
                checks,
                new DispatcherPools(dispatcher),
                hooks,
                relay)
            .validate(project, root);
  }

  @Bean
  public ApplicationDeployments applicationDeployments(
      ApplicationDeploymentStore store,
      ProjectMembers members,
      ProjectWorkspaces projects,
      FileStores fileStores,
      ApplicationPackageValidator validator) {
    return new SourceApplicationDeployments(store, members, projects, fileStores, validator);
  }
}
