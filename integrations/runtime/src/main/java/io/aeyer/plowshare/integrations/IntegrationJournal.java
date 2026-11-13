package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.integrations.IntegrationContracts.BindingState;
import io.aeyer.plowshare.integrations.IntegrationContracts.Entry;
import io.aeyer.plowshare.integrations.IntegrationContracts.EventEnvelope;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable integration plans. Transactions preserve ordering, lineage and uncertain-delivery
 * evidence.
 */
public interface IntegrationJournal {
  /** Validate the complete retained inventory before any startup/recovery side effect. */
  void verifyDtos() throws IOException;

  /** Inspection remains available; active startup cannot adopt or replay unfinished old work. */
  default void requireRequestCutover() throws IOException {
    for (var entry : entries()) {
      var record = entry.getValue();
      if (record.kind().equals("outgoing")
          && (!record.status().equals("DONE") || "UNKNOWN".equals(record.state())))
        throw new IOException(
            "Unfinished legacy outgoing evidence requires operator reconciliation");
    }
  }

  Optional<RelayRequests.Checkpoint> relayCheckpoint(String binding) throws IOException;

  /** Captured event records and their input cursor become durable in the same atomic snapshot. */
  void captureRelay(
      String binding,
      RelayRequests.Checkpoint expected,
      RelayRequests.Checkpoint next,
      Map<String, Entry> records)
      throws IOException;

  List<Map.Entry<String, Entry>> entries() throws IOException;

  BindingState bindingState(String binding) throws IOException;

  void writeEntry(String id, Entry record) throws IOException;

  void commitEntries(Map<String, Entry> records, String binding, BindingState state)
      throws IOException;

  void enqueueEntry(
      String id, Entry record, Configuration.QueuePolicy queue, Configuration.Retention retention)
      throws IOException;

  Optional<Entry> captureEntry(
      String id,
      Entry candidate,
      boolean afterAdapterEvents,
      Configuration.Binding binding,
      EventEnvelope causalEvent)
      throws IOException;

  boolean admitTimer(
      String id, Entry timer, String binding, BindingState expected, BindingState next)
      throws IOException;

  void completeActionEntry(
      String id,
      Entry record,
      Configuration.Binding binding,
      IntegrationAdapter.Result result,
      long now)
      throws IOException;

  boolean known(String id);

  void expireCauses(long now) throws IOException;

  void maintain(Configuration.Retention retention) throws IOException;

  void checkCauseCapacity(Configuration.Binding binding, long now) throws IOException;
}
