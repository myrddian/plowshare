package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link Harness}: component-scanned, on {@code HooksConfig}'s own terms — this package sits
 * under {@code io.aeyer.plowshare.server}, which {@code PlowshareServerApplication} scans.
 */
@Configuration
@EnableConfigurationProperties(HarnessProperties.class)
public class HarnessConfig {

  /**
   * Every profile validated against every registered harness hook, at boot.
   *
   * <p>Taken as an {@link ObjectProvider} rather than a hard {@code List<HarnessHookFactory>}: a
   * context that leaves out the factory beans (a test slice) has none, and Spring may refuse to
   * inject an empty list where a concrete list is asked for. {@code orderedStream().toList()} is
   * empty for a context with none, which is exactly {@link Harness#NONE}'s own shape with no
   * profiles configured.
   */
  @Bean
  public Harness harness(
      HarnessProperties properties,
      LlmProperties llm,
      ObjectProvider<HarnessHookFactory> factories) {
    return new Harness(
        io.aeyer.plowshare.server.harness.HarnessConfiguration.decode(
            properties, Harness.assignments(llm.getPools()), factories.orderedStream().toList()));
  }

  /**
   * {@code harness:stuck}. The registry is taken lazily and read per consult, so an operator's edit
   * to {@code stuck_advisor.md} reaches the next consult, and a missing file fails that consult
   * rather than the boot — the agent is not REQUIRED.
   */
  @Bean(destroyMethod = "close")
  public StuckTrapFactory stuckTrap(
      LlmDispatcher dispatcher, ObjectProvider<AgentRegistry> agents) {
    return new StuckTrapFactory(dispatcher, () -> agents.getObject().get(StuckTrapFactory.ADVISOR));
  }

  /**
   * The call validator every run consults about a call written as text (spec
   * 2026-09-28-call-failures §5). The registry is taken lazily and read per consult, on {@link
   * #stuckTrap}'s reason: an edit to {@code call_validator.md} reaches the next consult, and a
   * missing file fails that consult -- a warning, as without a validator -- not the boot.
   */
  @Bean(destroyMethod = "close")
  public ModelCallValidator callValidator(
      LlmDispatcher dispatcher, ObjectProvider<AgentRegistry> agents) {
    return new ModelCallValidator(
        dispatcher, () -> agents.getObject().get(ModelCallValidator.AGENT));
  }

  /**
   * The acceptance checker (spec 2026-10-01): the agent an orchestration's {@code checker:} names,
   * resolved per pass from the boot registry so an edit to its file reaches the next one, and run
   * by the harness as an agent of its own — its read-only file tools, its own model-call allowance
   * from its file, nothing spent from the run's budget, and the run's session so it reads the files
   * where they are. An ending that is not its answer is no answer: the caller then has nothing to
   * say at plan time, and asks the person at the end. The runtime and the registry are taken
   * lazily, on {@link #stuckTrap}'s reason.
   */
  @Bean
  public ModelAcceptanceChecker acceptanceChecker(
      ObjectProvider<AgentRegistry> agents,
      ObjectProvider<JobRuntime> runtime,
      ObjectProvider<io.aeyer.plowshare.server.agents.Compaction> logs) {
    return new ModelAcceptanceChecker(
        name -> agents.getObject().find(name),
        new ModelAcceptanceChecker.Runner() {
          @Override
          public String run(
              io.aeyer.plowshare.server.agents.AgentDefinition checker,
              String task,
              io.aeyer.plowshare.protocol.Home home,
              String session) {
            return run(
                checker,
                task,
                home,
                session,
                io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
          }

          @Override
          public String run(
              io.aeyer.plowshare.server.agents.AgentDefinition checker,
              String task,
              io.aeyer.plowshare.protocol.Home home,
              String session,
              io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
            Budget budget = Budget.of(checker.maxModelCalls());
            var log = logs.getIfAvailable();
            var trace =
                log == null
                    ? io.aeyer.plowshare.server.agents.Transcript.NONE
                    : log.logFor(
                        io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
                        home,
                        checker,
                        null,
                        budget,
                        io.aeyer.plowshare.server.agents.Speaker.harness(),
                        owner.accountHandle());
            if (owner.status()
                != io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Status
                    .LEGACY_UNATTRIBUTED) {
              trace.accounted(owner);
              trace =
                  new io.aeyer.plowshare.server.agents.AttributedTranscript(
                      trace,
                      owner,
                      io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
            }
            Outcome outcome =
                runtime
                    .getObject()
                    .run(
                        checker,
                        task,
                        home,
                        budget,
                        () -> false,
                        session,
                        io.aeyer.plowshare.server.agents.JobWatch.UNWATCHED,
                        trace);
            trace.closed(task, outcome);
            if (outcome.ending() != Outcome.Ending.ANSWERED) {
              throw new IllegalStateException(
                  "it ended "
                      + outcome.ending()
                      + (outcome.text() == null ? "" : ": " + outcome.text()));
            }
            return outcome.text();
          }
        });
  }

  /**
   * The command judge (V67), resolved per consult like the call validator — and a missing file
   * fails that consult, which asks the person, not the boot.
   */
  @Bean(destroyMethod = "close")
  public ModelCommandJudge commandJudge(
      LlmDispatcher dispatcher, ObjectProvider<AgentRegistry> agents) {
    return new ModelCommandJudge(dispatcher, () -> agents.getObject().get(ModelCommandJudge.AGENT));
  }
}
