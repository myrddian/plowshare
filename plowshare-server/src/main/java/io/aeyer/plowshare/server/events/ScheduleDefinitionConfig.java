package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.ProjectMessageRouting;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition only. Files and repositories expose typed contracts to scheduling logic. */
@Configuration
public class ScheduleDefinitionConfig {
  @Bean
  public ScheduleDefinitionStore scheduleDefinitionStore(
      JdbcTemplate jdbc,
      UnitOfWork work,
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings,
      ProjectionCaches caches) {
    return new JdbcScheduleDefinitionStore(
        jdbc,
        work,
        schedules,
        triggers,
        firings,
        caches.create("schedule-file-v1", Set.of(Domain.SCHEDULES, Domain.AUTHORITY)),
        caches.create("schedule-files-v1", Set.of(Domain.SCHEDULES, Domain.AUTHORITY)));
  }

  @Bean
  public ScheduleFiles scheduleFiles(
      DataLayout data,
      SessionChannel channel,
      PresenceRegistry presences,
      SessionRegistry sessions,
      ApplicationResources resources) {
    var files = new RegisteredScheduleFiles(data, channel, presences, sessions);
    files.useApplicationResources(resources);
    return files;
  }

  @Bean
  public ScheduleDefinitions.Authority scheduleActionAuthority(
      ScheduleFiles files,
      Callers callers,
      SkillResolver skills,
      ObjectProvider<OrchestrationResolver> orchestrations,
      ProjectMessageRouting routing,
      ObjectProvider<MessageService> messages) {
    return (source, definition) ->
        new ScheduleActionAuthority(
                files,
                callers,
                skills,
                orchestrations.getIfAvailable(),
                routing,
                messages::getIfAvailable)
            .validate(source, definition);
  }

  @Bean
  public ScheduleDefinitions scheduleDefinitions(
      ScheduleDefinitionStore store,
      ScheduleFiles files,
      ProjectStore projects,
      ProjectMembers members,
      ScheduleDefinitions.Authority authority,
      ApplicationResources resources) {
    return new FileScheduleDefinitions(
        store,
        files,
        projects,
        members,
        authority,
        new ApplicationScheduleControlAuthority(resources, members));
  }
}
