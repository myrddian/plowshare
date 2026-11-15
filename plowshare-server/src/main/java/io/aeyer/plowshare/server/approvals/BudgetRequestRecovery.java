package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.BudgetRequests;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Reconciles committed budget decisions after a restart or a busy execution owner. */
public final class BudgetRequestRecovery implements ApplicationListener<ApplicationReadyEvent> {
  private final BudgetRequests requests;
  private volatile boolean ready;

  public BudgetRequestRecovery(BudgetRequests requests) {
    this.requests = requests;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    ready = true;
    recover();
  }

  @Scheduled(fixedDelayString = "${plowshare.approvals.recovery-delay-ms:5000}")
  public void recover() {
    if (ready) requests.recover();
  }
}
