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
      io.aeyer.plowshare.server.events.ScheduleDefinitionStore schedules,
      ApplicationIdentities identities) {
    return new JdbcApplicationDeploymentStore(jdbc, work, projects, schedules, identities);
  }

  @Bean
  public ApplicationIdentities applicationIdentities(
      ApplicationIdentityRepository repository,
      io.aeyer.plowshare.server.auth.ServiceAccountRepository services,
      io.aeyer.plowshare.server.auth.AdminStore accounts,
      ProjectMembers members,
      ProjectWorkspaces projects,
      UnitOfWork work,
      org.springframework.context.ApplicationEventPublisher events) {
    return new OwnedApplicationIdentities(
        repository, services, accounts, members, projects, work, events);
  }

  @Bean
  public ApplicationPackageValidator applicationPackageValidator(
      ObjectProvider<AgentRegistry> boot,
      ObjectProvider<GlobalAgentDefinitions> globals,
      ObjectProvider<JobRuntime> runtime,
      DefinitionChecks checks,
      LlmDispatcher dispatcher,
      HookEngine hooks,
      io.aeyer.plowshare.server.relay.RelayRouteProgram relay,
      ScopedTools scopedTools) {
    return (project, root) -> {
      var names = new java.util.TreeSet<>(runtime.getObject().knownTools());
      names.addAll(scopedTools.stagedNames(project, root, names));
      new RuntimeApplicationPackageValidator(
              GlobalAgentSnapshots.supply(globals, boot).get(),
              names,
              checks,
              new DispatcherPools(dispatcher),
              hooks,
              relay)
          .validate(project, root);
    };
  }

  @Bean
  public ApplicationDeployments applicationDeployments(
      ApplicationDeploymentStore store,
      ProjectMembers members,
      ProjectWorkspaces projects,
      FileStores fileStores,
      ApplicationPackageValidator validator,
      ApplicationIdentities identities) {
    return new SourceApplicationDeployments(
        store, members, projects, fileStores, validator, identities);
  }
}
