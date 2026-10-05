package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.util.*;

/**
 * A readiness fence counts durable outcomes, never fetch receipts or transient event deliveries.
 */
public final class InformationReadiness {
  private InformationReadiness() {}

  public record Source(UUID revision, UUID acquisition) {
    public Source {
      if ((revision == null) == (acquisition == null))
        throw new CallerFault("each fence source needs exactly one revision or acquisition");
    }
  }

  public static Information.Readiness await(
      InformationCatalogue catalogue,
      InformationContext context,
      List<Source> sources,
      int waitMs) {
    if (waitMs < 0 || waitMs > 30000)
      throw new CallerFault("readiness waitMs must be between 0 and 30000");
    long deadline = System.nanoTime() + waitMs * 1_000_000L;
    while (true) {
      var snapshot = snapshot(catalogue, context, sources);
      if (snapshot.complete() || System.nanoTime() >= deadline) return snapshot;
      try {
        Thread.sleep(Math.min(1000, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        return snapshot.interruptedObserver();
      }
    }
  }

  static Information.Readiness snapshot(
      InformationCatalogue catalogue, InformationContext context, List<Source> sources) {
    if (sources == null || sources.isEmpty() || sources.size() > 100)
      throw new CallerFault("readiness sources must contain 1..100 references");
    var outcomes =
        new LinkedHashSet<>(sources)
            .stream().map(source -> outcome(catalogue, context, source)).toList();
    int settled = (int) outcomes.stream().filter(value -> !"pending".equals(value.state())).count();
    int ready = (int) outcomes.stream().filter(value -> "ready".equals(value.state())).count();
    return new Information.Readiness(
        outcomes.size(),
        settled,
        ready,
        outcomes.size() - settled,
        settled == outcomes.size(),
        outcomes,
        null);
  }

  private static Information.ReadinessOutcome outcome(
      InformationCatalogue catalogue, InformationContext context, Source source) {
    UUID revision = source.revision();
    String acquisitionState = null;
    try {
      if (source.acquisition() != null) {
        var ticket = catalogue.acquisitionStatus(context, source.acquisition());
        acquisitionState = ticket.state();
        if (!acquisitionState.equals("succeeded"))
          return new Information.ReadinessOutcome(
              null,
              source.acquisition(),
              acquisitionState,
              null,
              List.of("failed", "blocked").contains(acquisitionState)
                  ? acquisitionState
                  : "pending",
              ticket.attempt(),
              ticket.error(),
              null,
              null,
              null,
              null);
        revision = ticket.revisionId();
        if (revision == null)
          return new Information.ReadinessOutcome(
              null,
              source.acquisition(),
              acquisitionState,
              null,
              "unavailable",
              ticket.attempt(),
              "Acquisition retained no revision",
              null,
              null,
              null,
              null);
      }
      catalogue.requireReadable(context, revision);
      var status = catalogue.status(context, revision);
      for (var step : status.steps())
        if (step.stage().equals("extract") && step.generation() == status.generation())
          return new Information.ReadinessOutcome(
              revision,
              source.acquisition(),
              acquisitionState,
              step.state(),
              List.of("ready", "failed", "blocked", "cancelled", "skipped").contains(step.state())
                  ? step.state()
                  : "pending",
              step.attempt(),
              step.error(),
              status.generation(),
              status.sourceUri(),
              step.startedAt(),
              step.finishedAt());
      return new Information.ReadinessOutcome(
          revision,
          source.acquisition(),
          acquisitionState,
          null,
          "unavailable",
          null,
          "No extraction stage in the current generation",
          status.generation(),
          status.sourceUri(),
          null,
          null);
    } catch (NotFoundFault unavailable) {
      // A scope reduction must not disclose a foreign ticket's metadata or claim its job failed.
      return new Information.ReadinessOutcome(
          source.revision(),
          source.acquisition(),
          null,
          null,
          "unavailable",
          null,
          "Source is unavailable in this information scope",
          null,
          null,
          null,
          null);
    }
  }
}
