package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.access.InferenceCalls;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.GlobalAgentDefinitions;
import io.aeyer.plowshare.server.agents.GlobalAgentSnapshots;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.auth.AccountAdministrationRepository;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.personal.PersonalSpaceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Composes private policy storage and current personal bot resolution; neither grants execution.
 */
@Configuration
public class ApprovalPolicyConfiguration {
  @Bean
  public ApprovalPolicyRepository approvalPolicyRepository(
      DataLayout data, @Value("${plowshare.approvals.policy-io-budget:PT2S}") Duration budget) {
    return new FileApprovalPolicyRepository(
        owner -> {
          if (!data.keepsAnything())
            throw new IllegalStateException("Approval policies require configured server storage");
          // This is deliberately outside all workspace trees and source-editing APIs. The UI
          // exposes
          // it as Personal settings; ordinary file writes are not policy-management operations.
          return data.root().resolve("personal-approval-policies").resolve(policyKey(owner));
        },
        budget);
  }

  private static String policyKey(String owner) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  @Bean
  public AvailableApprovalBots availableApprovalBots(
      ObjectProvider<DefinitionResolver> definitions, PersonalSpaceRepository personal) {
    return (owner, name) -> {
      long project =
          personal.id(owner).orElseThrow(() -> new CallerFault("Personal space is unavailable"));
      var resolver = definitions.getIfAvailable();
      if (resolver == null) throw new CallerFault("Personal bots are unavailable");
      var definition =
          resolver.forCaller(new DefinitionResolver.Caller(project, null, owner)).get(name);
      if (!definition.bot() || definition.nativeOnly())
        throw new CallerFault("Select an available personal model bot");
      return definition;
    };
  }

  @Bean
  public ApprovalPolicies approvalPolicies(
      ApprovalPolicyRepository policies,
      AccountAdministrationRepository accounts,
      AvailableApprovalBots bots) {
    return new PersonalApprovalPolicies(policies, accounts, bots);
  }

  @Bean(destroyMethod = "close")
  public DispatchReviewInferences approvalReviewInferences(
      ObjectProvider<LlmDispatcher> dispatcher,
      InferenceCalls calls,
      @Value("${plowshare.approvals.review-capacity:64}") int capacity,
      @Value("${plowshare.approvals.review-cleanup-budget:PT2S}") Duration cleanup) {
    return new DispatchReviewInferences(
        (request, cancelled) ->
            dispatcher.getObject().stream(request, Deltas.DISCARDING, cancelled),
        calls,
        capacity,
        cleanup);
  }

  @Bean
  public HumanApprovalHistory humanApprovalHistory(
      JdbcTemplate jdbc, @Value("${plowshare.approvals.history-lifetime:P30D}") Duration lifetime) {
    return new JdbcHumanApprovalHistory(jdbc, Instant::now, lifetime);
  }

  @Bean
  public ApprovalReviewSessions approvalReviewSessions(
      ApprovalPolicyRepository policies,
      AvailableApprovalBots bots,
      HumanApprovalHistory history,
      ReviewInferenceFactory inference,
      ObjectProvider<GlobalAgentDefinitions> globals,
      ObjectProvider<AgentRegistry> agents,
      @Value("${plowshare.approvals.review-budget:PT60S}") Duration budget) {
    return new PersonalApprovalReviewSessions(
        policies,
        bots,
        inference,
        () -> GlobalAgentSnapshots.supply(globals, agents).get().get("security_reviewer"),
        budget,
        history);
  }

  @Bean
  public SmartInitializingSingleton installApprovalReviewSessions(
      ObjectProvider<JobRuntime> runtime, ApprovalReviewSessions sessions) {
    return () -> {
      JobRuntime selected = runtime.getIfAvailable();
      if (selected != null) selected.useApprovalReviewSessions(sessions);
    };
  }
}
