package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import java.io.IOException;
import java.util.*;

/**
 * Durable integration plans. Transactions preserve ordering, lineage and uncertain-delivery
 * evidence.
 */
public interface IntegrationJournal {
  /** Validate the complete retained inventory before any startup/recovery side effect. */
  void verifyDtos() throws IOException;

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
