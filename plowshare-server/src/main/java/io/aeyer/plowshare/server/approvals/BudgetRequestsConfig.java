package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.agents.BudgetRequests;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Messaging;
import io.aeyer.plowshare.server.agents.Pictures;
import io.aeyer.plowshare.server.agents.RunningConversations;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Budget consent uses existing approvals, execution owners, immutable authority and Relay wakes.
 */
@Configuration
public class BudgetRequestsConfig {
  @Bean
  public BudgetRequestRepository budgetRequestRepository(
      JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    return new JdbcBudgetRequestRepository(jdbc, new TransactionTemplate(transactions));
  }

  @Bean
  public BudgetRequests budgetRequests(
      BudgetRequestRepository repository,
      ActiveRunApprovals active,
      ApprovalDelivery delivery,
      RunApprovalStore approvals,
      ConversationStore conversations,
      ProjectStore projects,
      OperationAuthority operations,
      Callers callers,
      RunningConversations running,
      JobRuntime runtime,
      Turn turn,
      Runs runs,
      Pictures pictures,
      Inbox inbox,
      ObjectProvider<Messaging> messages,
      ObjectProvider<ConductorContinuation> conductors) {
    BudgetRequestAccess access =
        new BudgetRequestAccess() {
          @Override
          public long require(BudgetCheckpoint checkpoint, AllowanceId allowance) {
            callers.requireSession(checkpoint.session(), checkpoint.executor());
            String parent = null;
            ConversationRecord root = null;
            for (var frame : checkpoint.frames()) {
              var saved =
                  conversations
                      .find(frame.conversation())
                      .orElseThrow(
                          () -> new CallerFault("The original budget execution is unavailable"));
              if (root == null) root = saved;
              if (!Objects.equals(parent, saved.parentId()))
                throw new CallerFault("Budget execution ancestry changed");
              var retained =
                  operations
                      .conversation(frame.conversation())
                      .orElseThrow(
                          () -> new CallerFault("The original execution authority is unavailable"));
              if (!retained.agent().equals(frame.agent())
                  || !retained.budget().equals(allowance)
                  || !checkpoint.executor().equals(retained.admission().executor())
                  || !saved.home().equals(retained.admission().home()))
                throw new CallerFault("Budget continuation cannot replace its execution envelope");
              operations.require(retained.admission());
              parent = frame.conversation();
            }
            Objects.requireNonNull(root);
            if (root.lifecycle() == null
                || !root.lifecycle().acceptsWork()
                || root.home().isGlobal()
                || !(root.origin() == Origin.TURN
                    || root.origin() == Origin.MESSAGE
                    || root.origin() == Origin.SUBMISSION
                    || root.origin() == Origin.EVENT
                    || root.origin() == Origin.ORCHESTRATION))
              throw new CallerFault("This execution does not support agent budget continuation");
            callers.requireWork(root.home().project(), checkpoint.executor());
            return Objects.requireNonNull(projects.id(root.home().project()));
          }

          @Override
          public void requireRecovery(BudgetRequestRepository.Request request) {
            require(request.checkpoint(), request.allowance().id());
            repository.requireExecutable(request);
          }
        };
    BudgetRequestContinuations continuation =
        new BudgetRequestContinuations() {
          @Override
          public boolean busy(String root) {
            return turn.isSpeaking(root) || running.hasRunningJob(root);
          }

          @Override
          public boolean resume(BudgetRequestRepository.Request request) {
            RunApproval approval = approvals.find(request.approval()).orElseThrow();
            String text = RetainedBudgetRequests.decisionText(request);
            Messaging transport = messages.getIfAvailable();
            if (transport != null && transport.continueApproved(approval, text)) return true;
            var frame = request.checkpoint().frames().getFirst();
            // Route the root, not the leaf: runtime restores the complete retained delegation
            // stack.
            RunApproval rootApproval =
                new RunApproval(
                    approval.id(),
                    approval.projectId(),
                    approval.conversation(),
                    approval.conversation(),
                    approval.handle(),
                    frame.agent(),
                    approval.side(),
                    approval.argv(),
                    approval.cwd(),
                    approval.reason(),
                    approval.state(),
                    approval.scope(),
                    approval.prefix(),
                    approval.answeredBy(),
                    approval.answeredAt(),
                    approval.deliveredAt(),
                    approval.createdAt(),
                    approval.commands(),
                    approval.judged(),
                    approval.budget());
            if (conductors
                .getIfAvailable(() -> ConductorContinuation.NONE)
                .continueApproved(rootApproval, text)) return true;
            var root = conversations.find(request.root()).orElseThrow();
            if (root.origin() == Origin.TURN) {
              runs.start(
                  new Runs.Ask(
                      frame.agent(),
                      text,
                      null,
                      request.checkpoint().session(),
                      request.root(),
                      null,
                      null,
                      List.of(),
                      Speaker.approval(approval.id()),
                      request.checkpoint().executor()),
                  pictures);
            } else {
              runs.continueApproved(
                  request.root(),
                  frame.agent(),
                  text,
                  Speaker.approval(approval.id()),
                  outcome -> delivery.continuationEnded(rootApproval, outcome));
            }
            return true;
          }

          @Override
          public void denied(BudgetRequestRepository.Request request) {
            RunApproval approval = approvals.find(request.approval()).orElseThrow();
            Messaging transport = messages.getIfAvailable();
            if (transport != null
                && transport.budgetDenied(approval, RetainedBudgetRequests.decisionText(request)))
              return;
            inbox.notifyOnceFromLog(
                request.checkpoint().executor(),
                "approval",
                RetainedBudgetRequests.decisionText(request),
                request.root(),
                "budget-decision:" + request.approval());
          }

          @Override
          public void interrupted(BudgetRequestRepository.Request request, String text) {
            RunApproval approval = approvals.find(request.approval()).orElseThrow();
            Messaging transport = messages.getIfAvailable();
            if (transport != null && transport.budgetInterrupted(approval, text)) return;
            problem(request, text);
          }

          @Override
          public void problem(BudgetRequestRepository.Request request, String reason) {
            inbox.notifyOnceFromLog(
                request.checkpoint().executor(),
                "approval",
                reason,
                request.root(),
                "budget-recovery:" + request.approval());
          }
        };
    BudgetRequests requests =
        new RetainedBudgetRequests(repository, access, active, delivery::drain, continuation);
    runtime.useBudgetRequests(requests);
    runtime.useActiveRunApprovals(active);
    turn.useBudgetRequests(requests);
    return requests;
  }

  @Bean
  public BudgetRequestRecovery budgetRequestRecovery(BudgetRequests requests) {
    return new BudgetRequestRecovery(requests);
  }
}
