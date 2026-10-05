package io.aeyer.plowshare.server.events;

import java.util.List;
import java.util.Optional;

/**
 * Specialist persistence contract. SQL, row decoding and guarded transitions belong to its JDBC
 * implementation.
 */
public interface TriggerStore {

  TriggerRecord define(TriggerRecord t);

  List<TriggerRecord> list();

  Optional<TriggerRecord> find(String name);

  List<TriggerRecord> listening(String event);

  void pause(String name, boolean paused, String handle);

  void forget(String name, String handle);
}
