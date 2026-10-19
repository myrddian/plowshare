package io.aeyer.plowshare.server.proc;

import java.time.Instant;
import java.util.List;

/**
 * Reads retained execution metadata without starting work or changing queue state. Every read
 * rechecks current account, project membership and explicit fleet authority, including pagination.
 */
public interface ProcRepository {
  ProcViews.Conversations conversations(String account, ProcQuery query, Live live);

  ProcViews.Queues queues(String account, ProcQuery query);

  ProcViews.Calls calls(String account, ProcQuery query);

  /** Transcript tool receipts expose names and completion evidence, never arguments or output. */
  List<ProcViews.Tool> tools(String account, ProcQuery query);

  /** Local runtime evidence is a separate snapshot from the durable database projection. */
  record Live(Instant at, List<String> runningConversations) {
    public Live {
      runningConversations = List.copyOf(runningConversations);
    }
  }
}
