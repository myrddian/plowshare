package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Composition with existing execution, source resolution and immutable admission owners. */
@Configuration
public class WorkspacePauseConfig {
  @Bean
  public WorkspacePauseRepository workspacePauseRepository(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions,
      WorkspaceRunProperties properties) {
    return new JdbcWorkspacePauseRepository(
        jdbc,
        new TransactionTemplate(transactions),
        properties.getSqlTimeout(),
        properties.getOwnerLease());
  }

  @Bean
  public Recovery workspacePauseRecovery(
      WorkspaceRuns workspaces,
      WorkspacePauseRepository repository,
      ObjectProvider<Runs> runs,
      ObjectProvider<Turn> turns,
      ObjectProvider<Callers> callers,
      OperationAuthority operations,
      ConversationStore conversations) {
    workspaces.usePersistence(
        repository,
        new WorkspaceRuns.RecoveryOwner() {
          @Override
          public boolean busy(String root) {
            return turns.getObject().isSpeaking(root);
          }

          @Override
          public void require(WorkspacePauseRepository.Snapshot snapshot) {
            var checkpoint = snapshot.checkpoint();
            callers.getObject().requireSession(checkpoint.session(), checkpoint.executor());
            var root =
                conversations
                    .find(snapshot.root())
                    .orElseThrow(() -> new CallerFault("Original execution unavailable"));
            if (!(root.origin() == Origin.TURN
                || root.origin() == Origin.SUBMISSION
                || root.origin() == Origin.EVENT))
              throw new CallerFault("This execution requires its owning workflow to restore it");
            String parent = null;
            for (var frame : checkpoint.frames()) {
              var row =
                  conversations
                      .find(frame.conversation())
                      .orElseThrow(() -> new CallerFault("Original execution unavailable"));
              var retained =
                  operations
                      .conversation(frame.conversation())
                      .orElseThrow(() -> new CallerFault("Original admission unavailable"));
              if (!Objects.equals(parent, row.parentId())
                  || !retained.agent().equals(frame.agent())
                  || !retained.budget().value().toString().equals(snapshot.allowance())
                  || !retained.admission().executor().equals(checkpoint.executor())
                  || !retained.admission().home().equals(row.home())
                  || row.lifecycle() == null
                  || !row.lifecycle().acceptsWork())
                throw new CallerFault(
                    "Workspace recovery cannot replace its original execution envelope");
              operations.require(retained.admission());
              parent = frame.conversation();
            }
          }

          @Override
          public void resume(BudgetCheckpoint checkpoint) {
            turns.getObject().useWorkspaceContinuations(workspaces);
            runs.getObject().continueWorkspace(checkpoint);
          }

          @Override
          public Origin origin(String conversation) {
            return conversations.find(conversation).orElseThrow().origin();
          }
        });
    return new Recovery(workspaces, turns);
  }

  /** Recover only after the execution owners are initialized. Each scan has a bounded page. */
  public static final class Recovery implements ApplicationListener<ApplicationReadyEvent> {
    private final WorkspaceRuns workspaces;
    private final ObjectProvider<Turn> turns;
    private volatile boolean ready;

    Recovery(WorkspaceRuns workspaces, ObjectProvider<Turn> turns) {
      this.workspaces = workspaces;
      this.turns = turns;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
      turns.getObject().useWorkspaceContinuations(workspaces);
      ready = true;
      recover();
    }

    @Scheduled(fixedDelayString = "${plowshare.jobs.workspace.recovery-delay-ms:1000}")
    public void recover() {
      if (ready) workspaces.recover();
    }
  }
}
