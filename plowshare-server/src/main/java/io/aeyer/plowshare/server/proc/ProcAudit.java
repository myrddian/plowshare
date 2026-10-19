package io.aeyer.plowshare.server.proc;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.ArrayList;
import java.util.Objects;

/** Coordinates typed evidence. Suspected repetition is evidence for inspection, not a verdict. */
public final class ProcAudit {
  private final ProcRepository repository;
  private final ProcRuntime runtime;

  public ProcAudit(ProcRepository repository, ProcRuntime runtime) {
    this.repository = Objects.requireNonNull(repository);
    this.runtime = Objects.requireNonNull(runtime);
  }

  public ProcViews.Conversations list(String account, ProcQuery query) {
    var page = repository.conversations(account, query, runtime.snapshot());
    return new ProcViews.Conversations(
        page.snapshotAt(),
        page.selection(),
        page.conversations(),
        page.total(),
        page.cursor(),
        notes(page.coverage()));
  }

  public ProcViews.Queues queues(String account, ProcQuery query) {
    var queues = repository.queues(account, query);
    return new ProcViews.Queues(
        queues.snapshotAt(),
        queues.selection(),
        queues.queues(),
        queues.summaries(),
        query.fleet() ? runtime.pools() : java.util.List.of(),
        queues.cursor(),
        notes(queues.coverage()));
  }

  public ProcViews.Calls calls(String account, ProcQuery query) {
    var page = repository.calls(account, query);
    return new ProcViews.Calls(
        page.snapshotAt(), page.selection(), page.calls(), page.cursor(), notes(page.coverage()));
  }

  private java.util.List<String> notes(java.util.List<String> coverage) {
    return java.util.stream.Stream.concat(coverage.stream(), runtime.notes().stream()).toList();
  }

  /** Page retained tool receipts without arguments or result content. */
  public ProcViews.Tools tools(String account, ProcQuery query) {
    var rows = repository.tools(account, query);
    var page = rows.stream().limit(query.limit()).toList();
    return new ProcViews.Tools(
        java.time.Instant.now(),
        query,
        page,
        rows.size() > query.limit() ? page.getLast().id() : null,
        java.util.List.of(
            "Retained transcript tool metadata only; a recorded result is not proof of success.",
            "A missing result means unknown, not proof that a provider is still executing.",
            "Arguments and results are not returned; external destination metadata may be unavailable."));
  }

  /** Inspect a selected subtree; each section has independent, explicit pagination. */
  public ProcViews.Inspection inspect(String account, ProcQuery query) {
    if (query.conversation() == null && query.root() == null && query.causation() == null)
      throw new CallerFault("PROC inspect needs a conversation, root or causation selector");
    var conversations = list(account, query);
    if (conversations.total() == 0)
      throw new CallerFault("No non-archived conversation is available in this selection");
    var signals = new ArrayList<String>();
    for (var conversation : conversations.conversations()) {
      if (conversation.repeatedCalls() >= 3)
        signals.add(
            conversation.id()
                + ": repeated agent/operation/model calls; inspect iteration progress and attempts");
      if (conversation.causationDepth() != null && conversation.causationDepth() >= 3)
        signals.add(conversation.id() + ": causal chain depth " + conversation.causationDepth());
    }
    var tools = repository.tools(account, query.page(null));
    return new ProcViews.Inspection(
        conversations,
        queues(account, query.page(null)),
        calls(account, query.page(null)),
        tools.stream().limit(query.limit()).toList(),
        tools.size() > query.limit(),
        signals);
  }
}
