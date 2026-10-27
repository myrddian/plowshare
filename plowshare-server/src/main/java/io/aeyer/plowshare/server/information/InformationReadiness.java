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
    return await(catalogue, context, sources, waitMs, () -> {});
  }

  /** Observer validity is rechecked between short reads; waiting owns no transaction/DB permit. */
  public static Information.Readiness await(
      InformationCatalogue catalogue,
      InformationContext context,
      List<Source> sources,
      int waitMs,
      Runnable validity) {
    if (waitMs < 0 || waitMs > 30000)
      throw new CallerFault("readiness waitMs must be between 0 and 30000");
    long deadline = System.nanoTime() + waitMs * 1_000_000L;
    while (true) {
      validity.run();
      var snapshot = snapshot(catalogue, context, sources);
      validity.run();
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
    var selected = new LinkedHashSet<>(sources);
    var acquisitions = new java.util.LinkedHashMap<UUID, AcquisitionRepository.Status>();
    for (var source : selected) {
      if (source.acquisition() == null || acquisitions.containsKey(source.acquisition())) continue;
      try {
        acquisitions.put(
            source.acquisition(), catalogue.acquisitionStatus(context, source.acquisition()));
      } catch (NotFoundFault unavailable) {
        acquisitions.put(source.acquisition(), null);
      }
    }
    var revisions = new LinkedHashSet<UUID>();
    for (var source : selected) {
      if (source.revision() != null) revisions.add(source.revision());
      else {
        var ticket = acquisitions.get(source.acquisition());
        if (ticket != null && "succeeded".equals(ticket.state()) && ticket.revisionId() != null)
          revisions.add(ticket.revisionId());
      }
    }
    var states =
        revisions.isEmpty()
            ? java.util.Map.<UUID, InformationCatalogueRepository.ExtractionState>of()
            : catalogue.extractionStates(context, List.copyOf(revisions));
    var outcomes = selected.stream().map(source -> outcome(source, acquisitions, states)).toList();
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
      Source source,
      java.util.Map<UUID, AcquisitionRepository.Status> acquisitions,
      java.util.Map<UUID, InformationCatalogueRepository.ExtractionState> states) {
    UUID revision = source.revision();
    String acquisitionState = null;
    try {
      if (source.acquisition() != null) {
        var ticket = acquisitions.get(source.acquisition());
        if (ticket == null) throw new NotFoundFault("Unavailable acquisition");
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
      var status = states.get(revision);
      if (status == null) throw new NotFoundFault("Unavailable revision");
      var step = status.extraction();
      if (step != null)
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
