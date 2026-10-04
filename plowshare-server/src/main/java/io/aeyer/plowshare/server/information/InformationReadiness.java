package io.aeyer.plowshare.server.information;

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

  public static List<Source> sources(Object value) {
    if (!(value instanceof List<?> rows) || rows.isEmpty() || rows.size() > 100)
      throw new CallerFault("readiness sources must contain 1..100 source references");
    var sources = new LinkedHashSet<Source>();
    for (Object row : rows) {
      if (!(row instanceof Map<?, ?> fields))
        throw new CallerFault("each fence source must be an object");
      sources.add(new Source(id(fields, "revision"), id(fields, "acquisition")));
    }
    return List.copyOf(sources);
  }

  private static UUID id(Map<?, ?> fields, String name) {
    Object value = fields.get(name);
    if (value == null) return null;
    if (!(value instanceof String text)) throw new CallerFault(name + " must be a UUID");
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault(name + " must be a UUID");
    }
  }

  public static Map<String, Object> await(
      InformationCatalogue catalogue,
      InformationContext context,
      List<Source> sources,
      int waitMs) {
    if (waitMs < 0 || waitMs > 30000)
      throw new CallerFault("readiness waitMs must be between 0 and 30000");
    long deadline = System.nanoTime() + waitMs * 1_000_000L;
    while (true) {
      var snapshot = snapshot(catalogue, context, sources);
      if (Boolean.TRUE.equals(snapshot.get("complete")) || System.nanoTime() >= deadline)
        return snapshot;
      try {
        Thread.sleep(Math.min(1000, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
        snapshot.put("interrupted", true);
        return snapshot;
      }
    }
  }

  static Map<String, Object> snapshot(
      InformationCatalogue catalogue, InformationContext context, List<Source> sources) {
    var outcomes = new ArrayList<Map<String, Object>>();
    int settled = 0, ready = 0;
    for (Source source : new LinkedHashSet<>(sources)) {
      var outcome = outcome(catalogue, context, source);
      outcomes.add(outcome);
      if (!"pending".equals(outcome.get("state"))) settled++;
      if ("ready".equals(outcome.get("state"))) ready++;
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("expected", outcomes.size());
    result.put("settled", settled);
    result.put("ready", ready);
    result.put("pending", outcomes.size() - settled);
    result.put("complete", settled == outcomes.size());
    result.put("outcomes", outcomes);
    return result;
  }

  private static Map<String, Object> outcome(
      InformationCatalogue catalogue, InformationContext context, Source source) {
    var result = new LinkedHashMap<String, Object>();
    UUID revision = source.revision();
    if (source.acquisition() != null) result.put("acquisition", source.acquisition());
    else result.put("revision", revision);
    try {
      if (source.acquisition() != null) {
        var ticket = catalogue.acquisitionStatus(context, source.acquisition());
        result.put("acquisition_state", ticket.get("state"));
        copy(ticket, result, "attempt", "error");
        String state = String.valueOf(ticket.get("state"));
        if (!state.equals("succeeded")) {
          result.put(
              "state",
              List.of("failed", "blocked", "cancelled").contains(state) ? state : "pending");
          return result;
        }
        Object retained = ticket.get("revision_id");
        if (retained == null) {
          result.put("state", "unavailable");
          result.put("error", "Acquisition retained no revision");
          return result;
        }
        revision = retained instanceof UUID id ? id : UUID.fromString(retained.toString());
        result.put("revision", revision);
      }
      catalogue.requireReadable(context, revision);
      var status = catalogue.status(context, revision);
      result.put("generation", status.get("generation"));
      copy(status, result, "source_uri");
      var steps = (List<?>) status.getOrDefault("steps", List.of());
      for (Object value : steps)
        if (value instanceof Map<?, ?> step
            && "extract".equals(step.get("stage"))
            && step.get("generation") instanceof Number generation
            && status.get("generation") instanceof Number current
            && generation.longValue() == current.longValue()) {
          String state = String.valueOf(step.get("state"));
          result.put("extraction_state", state);
          result.put(
              "state",
              List.of("ready", "failed", "blocked", "cancelled", "skipped").contains(state)
                  ? state
                  : "pending");
          for (String field : List.of("attempt", "error", "started_at", "finished_at"))
            if (step.get(field) != null) result.put(field, step.get(field));
          return result;
        }
      result.put("state", "unavailable");
      result.put("error", "No extraction stage in the current generation");
    } catch (NotFoundFault unavailable) {
      // No foreign source metadata, and no false claim that its background job failed.
      result
          .keySet()
          .retainAll(source.acquisition() == null ? Set.of("revision") : Set.of("acquisition"));
      result.put("state", "unavailable");
      result.put("error", "Source is unavailable in this information scope");
    }
    return result;
  }

  private static void copy(Map<String, Object> from, Map<String, Object> to, String... fields) {
    for (String field : fields) if (from.get(field) != null) to.put(field, from.get(field));
  }
}
