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
      ApplicationIdentities identities,
      io.aeyer.plowshare.server.acting.ActingRepository acting,
      ContainmentFence containment) {
    return new JdbcApplicationDeploymentStore(
        jdbc, work, projects, schedules, identities, acting, containment);
  }

  /**
   * The live-child fence activation asks under the project row lock. It holds the containment read
   * and nothing else, which is what makes a refusal the only thing it can do.
   */
  @Bean
  public ContainmentFence containmentFence(ContainedApplicationRepository records) {
    return new SourceContainmentFence(records);
  }

  /**
   * The live staging claims a sweep must not collect through. One instance, shared by the creating
   * service and the collector: two would be two sets and the guard would not hold.
   */
  @Bean
  public ContainedStaging containedStaging() {
    return new InProcessContainedStaging();
  }

  /**
   * The staged-source collector. It is constructed with the containment configuration a server
   * actually has: without one it has no root and sweeps nothing, rather than guessing a path.
   */
  @Bean
  public ContainedSourceCollector containedSourceCollector(
      ContainedApplicationRepository records,
      UnitOfWork work,
      ContainedStaging staging,
      ObjectProvider<ContainmentConfig> containment) {
    var config = containment.getIfAvailable(ContainmentConfig::absent);
    return new ContainedSourceCollector(records, work, config, staging, config.sweepGrace());
  }

  /**
   * The collector's production trigger: startup reconciliation plus a repeating sweep.
   *
   * <p>Conditional on the same property {@code ContainmentConfig} is conditional on, and on the
   * property rather than on that bean because a property condition does not depend on
   * configuration-class ordering. That is not merely tidy — it is what keeps a boot timer out of
   * every Spring test context in this tree, which is the objection {@code Retention} raised against
   * scheduling a byte-deleting sweep at all. A server that has not configured nesting does not
   * contain this bean, so there is nothing to fire.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "plowshare.containment",
      name = "root")
  public ContainedCollectionSweeps containedCollectionSweeps(ContainedSourceCollector collector) {
    return new ContainedCollectionSweeps(collector);
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

  /**
   * The child path takes neither {@code ProjectMembers} nor {@code FileStores}, which is how it
   * holds no server-administrator check and no destination FileStore {@code MANAGER} check: there
   * is no collaborator here through which either could be made. {@code ContainmentConfig} is
   * optional for the same reason its bounds are — a server that has not configured nesting is a
   * normal server, and it then refuses every creation rather than defaulting a root.
   */
  @Bean
  public ContainedApplications containedApplications(
      ContainedApplicationRepository records,
      ProjectWorkspaces projects,
      ApplicationPackageValidator validator,
      io.aeyer.plowshare.server.access.OperationAuthority authority,
      ContainedPrincipals principals,
      io.aeyer.plowshare.server.allowances.AllowanceRepository allowances,
      UnitOfWork work,
      ContainedStaging staging,
      ObjectProvider<ContainmentConfig> containment) {
    return new SourceContainedApplications(
        records,
        projects,
        validator,
        authority,
        principals,
        allowances,
        work,
        containment.getIfAvailable(ContainmentConfig::absent),
        staging);
  }
}
