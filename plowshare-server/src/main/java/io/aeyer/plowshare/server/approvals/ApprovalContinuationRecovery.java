package io.aeyer.plowshare.server.approvals;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Reconciles committed consent decisions if the answer handler or its process was interrupted. */
public final class ApprovalContinuationRecovery
    implements ApplicationListener<ApplicationReadyEvent> {
  private final RunApprovalContinuations continuations;
  private volatile boolean ready;

  public ApprovalContinuationRecovery(RunApprovalContinuations continuations) {
    this.continuations = continuations;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent ready) {
    this.ready = true;
    recover();
  }

  @Scheduled(fixedDelayString = "${plowshare.approvals.recovery-delay-ms:5000}")
  public void recover() {
    if (ready) continuations.recover();
  }
}
