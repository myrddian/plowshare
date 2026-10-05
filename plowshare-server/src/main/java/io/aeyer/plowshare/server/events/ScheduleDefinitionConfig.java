package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.board.*;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.session.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
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
      FiringStore firings) {
    return new JdbcScheduleDefinitionStore(jdbc, work, schedules, triggers, firings);
  }

  @Bean
  public ScheduleFiles scheduleFiles(
      DataLayout data,
      SessionChannel channel,
      PresenceRegistry presences,
      SessionRegistry sessions) {
    return new RegisteredScheduleFiles(data, channel, presences, sessions);
  }

  @Bean
  public ScheduleDefinitions.Authority scheduleActionAuthority(
      ScheduleFiles files,
      Callers callers,
      SkillResolver skills,
      ObjectProvider<OrchestrationResolver> orchestrations,
      ProjectMessageRouting routing,
      ObjectProvider<BoardMessaging> messages) {
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
      ScheduleDefinitions.Authority authority) {
    return new FileScheduleDefinitions(store, files, projects, members, authority);
  }
}
