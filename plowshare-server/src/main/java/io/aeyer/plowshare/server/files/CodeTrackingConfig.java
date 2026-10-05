package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires persistent monitoring after the runtime and definition resolver have finished booting. */
@Configuration
public class CodeTrackingConfig {
  @Bean
  public CodeWorkspaceStore codeWorkspaceStore(
      JdbcTemplate jdbc,
      UnitOfWork work,
      @Value("${plowshare.code-tracking.interval:30s}") String interval,
      @Value("${plowshare.code-tracking.capacity:64}") int capacity) {
    return new JdbcCodeWorkspaceStore(
        jdbc,
        work,
        Clock.systemUTC(),
        org.springframework.boot.convert.DurationStyle.detectAndParse(interval),
        capacity);
  }

  @Bean(destroyMethod = "close")
  public CodeWorkspaceMonitor codeWorkspaceMonitor(
      CodeWorkspaceStore store,
      ObjectProvider<Callers> callers,
      ObjectProvider<RunProviders> files,
      @Value("${plowshare.code-tracking.enabled:true}") boolean enabled) {
    return new CodeWorkspaceMonitor(
        store,
        scope -> {
          var access = callers.getObject();
          if (!scope.home().isGlobal())
            access.requireProject(scope.home().project(), scope.owner());
          else {
            if (scope.session() == null)
              throw new WorkspaceRefusedException(
                  "global code tracking needs an owned live session");
            access.requireSession(scope.session(), scope.owner());
          }
          var definition =
              access.readAgent(
                  scope.agent(),
                  access.callerFor(
                      scope.home().isGlobal() ? null : scope.home().project(), scope.session()));
          if (!definition.tools().contains(CodeMapTool.NAME)
              || definition.scopes().stream().noneMatch(grant -> grant.allows(Mode.READ)))
            throw new WorkspaceRefusedException(
                "code tracking's current definition has no code-map read grant");
          // Definition scopes never survive as stored authority. Resolve them again at each routing
          // call too.
          return new ProviderRouter(
              home -> {
                if (!home.equals(scope.home()))
                  throw new WorkspaceRefusedException("code tracking scope changed");
                if (!home.isGlobal()) access.requireProject(home.project(), scope.owner());
                else access.requireSession(scope.session(), scope.owner());
                var current =
                    access.readAgent(
                        scope.agent(),
                        access.callerFor(home.isGlobal() ? null : home.project(), scope.session()));
                if (!current.tools().contains(CodeMapTool.NAME))
                  throw new WorkspaceRefusedException("code map is no longer granted");
                return files
                    .getObject()
                    .forRun(home, current.scopes(), scope.session(), scope.owner());
              });
        },
        enabled);
  }

  @Bean
  @Lazy
  public CodeWorkspaceIndex codeWorkspaceIndex(
      CodeWorkspaceStore store,
      io.aeyer.plowshare.server.information.InformationCatalogue catalogue,
      io.aeyer.plowshare.server.information.InformationLifecycle lifecycle) {
    return new CodeWorkspaceIndex(store, catalogue, lifecycle);
  }

  @Bean
  public SmartInitializingSingleton codeTrackingBinding(
      CodeWorkspaceMonitor monitor,
      JobRuntime runtime,
      ObjectProvider<CodeWorkspaceIndex> indexes) {
    return () -> {
      monitor.indexing(indexes::getObject);
      runtime.useCodeMonitor(monitor);
      monitor.start();
    };
  }
}
