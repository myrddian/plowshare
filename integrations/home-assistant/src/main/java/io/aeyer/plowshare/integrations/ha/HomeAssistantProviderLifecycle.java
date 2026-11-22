package io.aeyer.plowshare.integrations.ha;

import java.io.IOException;
import java.time.Duration;

/** Operator process lifecycle; stop intake without manufacturing a replacement external effect. */
public interface HomeAssistantProviderLifecycle extends AutoCloseable {
  /** Supervise resource-owned finite passes until requested stop or an explicit failure. */
  void run() throws IOException, InterruptedException;

  /** Wake an idle supervisor; an already accepted pass retains its existing finite owner. */
  void requestStop();

  /** Finite process shutdown grace, including the active pass and each resource's cleanup. */
  Duration shutdownGrace();

  /** Observe completion of cleanup; false never establishes that remote effects were aborted. */
  boolean awaitClosed(Duration timeout) throws InterruptedException;

  /** Preserve journals and report incomplete physical cleanup instead of replacing their owners. */
  @Override
  void close() throws IOException;
}
