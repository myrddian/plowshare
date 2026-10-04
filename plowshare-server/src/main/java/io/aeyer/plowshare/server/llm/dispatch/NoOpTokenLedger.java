package io.aeyer.plowshare.server.llm.dispatch;

import org.springframework.stereotype.Component;

/** Counts nothing, deliberately, and does not log about it. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "plowshare.llm.accounting",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
public final class NoOpTokenLedger implements TokenLedger {

  @Override
  public void record(LedgerEntry entry) {
    // Nothing to do. A log line here would be one per model call forever.
  }
}
