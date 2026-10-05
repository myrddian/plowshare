package io.aeyer.plowshare.protocol;

import java.util.*;

/** Retained model judgments; valid evidence references are not proof of claim support. */
public record InformationReportDetails(
    List<String> objectives,
    List<Finding> findings,
    List<Review> reviews,
    List<String> scopeChanges) {
  public InformationReportDetails {
    objectives = texts(objectives == null ? List.of() : objectives, "objectives");
    findings = List.copyOf(findings == null ? List.of() : findings);
    reviews = List.copyOf(reviews == null ? List.of() : reviews);
    scopeChanges = texts(scopeChanges == null ? List.of() : scopeChanges, "scopeChanges");
    if (findings.size() > 10000 || reviews.size() > 10000)
      throw new IllegalArgumentException("report metadata exceeds 10000 records");
    var ids = new HashSet<String>();
    for (var finding : findings)
      if (!ids.add(finding.id()) || !objectives.contains(finding.objective()))
        throw new IllegalArgumentException("each finding needs a unique id and supplied objective");
  }

  public record Finding(
      String id,
      String objective,
      String claim,
      List<UUID> support,
      List<UUID> counterEvidence,
      String rationale,
      String verdict) {
    public Finding {
      id = text(id);
      objective = text(objective);
      claim = text(claim);
      rationale = text(rationale);
      support = List.copyOf(support == null ? List.of() : support);
      counterEvidence = List.copyOf(counterEvidence == null ? List.of() : counterEvidence);
      if (support.size() > 10000 || counterEvidence.size() > 10000)
        throw new IllegalArgumentException("too many evidence references");
      if (!Set.of("holds", "weakened", "refuted", "not_checked").contains(verdict))
        throw new IllegalArgumentException("invalid finding verdict");
    }
  }

  public record Review(String stage, String outcome, String text) {
    public Review {
      stage = InformationReportDetails.text(stage);
      outcome = InformationReportDetails.text(outcome);
      text = InformationReportDetails.text(text);
    }
  }

  public static InformationReportDetails empty() {
    return new InformationReportDetails(List.of(), List.of(), List.of(), List.of());
  }

  public List<UUID> evidence() {
    var result = new LinkedHashSet<UUID>();
    for (var finding : findings) {
      result.addAll(finding.support());
      result.addAll(finding.counterEvidence());
    }
    return List.copyOf(result);
  }

  private static List<String> texts(List<String> values, String field) {
    if (values.size() > 10000) throw new IllegalArgumentException(field + " exceeds 10000 entries");
    return values.stream().map(InformationReportDetails::text).distinct().toList();
  }

  private static String text(String value) {
    if (value == null || value.isBlank() || value.length() > 32768 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("report text must be nonblank within 32768 characters");
    return value;
  }
}
