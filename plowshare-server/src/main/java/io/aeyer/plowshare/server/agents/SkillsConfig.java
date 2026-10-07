package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.images.ImageStore;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Skills attach to the ordinary runtime after its core beans have been built. */
@Configuration
public class SkillsConfig {
  /** Skill checks use the existing hook contract with each run's authorized file router. */
  @Bean
  public Function<ProviderRouter, Hooks> skillFileChecks(JobRuntime runtime) {
    Function<ProviderRouter, Hooks> checks = SkillFileHooks::new;
    runtime.useFileChecks(checks);
    return checks;
  }

  @Bean
  public ScopedFileRules scopedFileRules(
      AgentRules rules, io.aeyer.plowshare.server.files.RunProviders files, JobRuntime runtime) {
    ScopedFileRules scoped = new ScopedFileRules(rules, files);
    runtime.useFileRules(scoped);
    return scoped;
  }

  @Bean
  public SkillExecutions skillExecutions(JdbcTemplate jdbc) {
    return new JdbcSkillExecutionsRepository(jdbc);
  }

  @Bean
  public BoundCommands boundCommands(
      JdbcTemplate jdbc,
      SkillResolver skills,
      OrchestrationResolver orchestrations,
      Callers callers,
      SkillRuntime skillsRuntime,
      JobRuntime runtime) {
    BoundCommands commands =
        new BoundCommands(
            new JdbcCommandInvocationsRepository(jdbc),
            skills,
            orchestrations,
            callers,
            skillsRuntime);
    runtime.useBoundCommands(commands);
    return commands;
  }

  @Bean
  public SkillRuntime skillRuntime(
      SkillResolver skills,
      Callers callers,
      SkillExecutions executions,
      JobRuntime runtime,
      ImageStore images,
      io.aeyer.plowshare.server.archive.EntryStore entries,
      Compaction compaction) {
    SkillRuntime runner = new SkillRuntime(skills, callers, executions, runtime, images);
    runner.useContexts(new SkillContexts(entries, compaction, executions));
    runtime.useSkills(runner);
    return runner;
  }
}
