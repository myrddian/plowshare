package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;

/** Model judgments are retained as judgments. Valid references are not proof of claim support. */
public record InformationReportDetails(
    List<String> objectives,
    List<Finding> findings,
    List<Review> reviews,
    List<String> scopeChanges) {
  public record Finding(
      String id,
      String objective,
      String claim,
      List<UUID> support,
      List<UUID> counterEvidence,
      String rationale,
      String verdict) {}

  public record Review(String stage, String outcome, String text) {}

  public static InformationReportDetails from(Map<String, Object> args) {
    var objectives = strings(args.get("objectives"), "objectives");
    var changes = strings(args.get("scopeChanges"), "scopeChanges");
    var findings = new ArrayList<Finding>();
    var ids = new HashSet<String>();
    for (var value : objects(args.get("findings"), "findings")) {
      String id = text(value, "id"),
          objective = text(value, "objective"),
          verdict = text(value, "verdict");
      if (!ids.add(id) || !objectives.contains(objective))
        throw new CallerFault("each finding needs a unique id and a supplied objective");
      if (!Set.of("holds", "weakened", "refuted", "not_checked").contains(verdict))
        throw new CallerFault("finding verdict must be holds, weakened, refuted or not_checked");
      findings.add(
          new Finding(
              id,
              objective,
              text(value, "claim"),
              uuids(value.get("support"), "support"),
              uuids(value.get("counterEvidence"), "counterEvidence"),
              text(value, "rationale"),
              verdict));
    }
    var reviews = new ArrayList<Review>();
    for (var value : objects(args.get("reviews"), "reviews"))
      reviews.add(new Review(text(value, "stage"), text(value, "outcome"), text(value, "text")));
    return new InformationReportDetails(
        List.copyOf(objectives), List.copyOf(findings), List.copyOf(reviews), List.copyOf(changes));
  }

  public List<UUID> evidence() {
    var result = new LinkedHashSet<UUID>();
    for (var finding : findings) {
      result.addAll(finding.support());
      result.addAll(finding.counterEvidence());
    }
    return List.copyOf(result);
  }

  private static String text(Map<?, ?> values, String key) {
    if (values.get(key) instanceof String text && !text.isBlank() && text.length() <= 32768)
      return text;
    throw new CallerFault(key + " must be nonblank text within 32768 characters");
  }

  private static List<String> strings(Object value, String field) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list))
      throw new CallerFault(field + " must be a list of strings");
    return list.stream()
        .map(
            item -> {
              if (!(item instanceof String text) || text.isBlank() || text.length() > 32768)
                throw new CallerFault(field + " must contain bounded nonblank strings");
              return text;
            })
        .distinct()
        .toList();
  }

  private static List<UUID> uuids(Object value, String field) {
    return strings(value, field).stream()
        .map(
            text -> {
              try {
                return UUID.fromString(text);
              } catch (IllegalArgumentException invalid) {
                throw new CallerFault(field + " must contain evidence UUIDs");
              }
            })
        .toList();
  }

  private static List<Map<?, ?>> objects(Object value, String field) {
    // The catalogue enforces a total metadata byte budget. Counts are not a
    // correctness constraint: longer runs legitimately retain more reviews.
    if (value == null) return List.of();
    if (!(value instanceof List<?> list))
      throw new CallerFault(field + " must be a list of records");
    return list.stream()
        .<Map<?, ?>>map(
            item -> {
              if (!(item instanceof Map<?, ?> record))
                throw new CallerFault(field + " must contain objects");
              return (Map<?, ?>) record;
            })
        .toList();
  }
}
