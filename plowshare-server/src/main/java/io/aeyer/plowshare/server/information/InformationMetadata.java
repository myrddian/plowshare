package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Map;

/** Model suggestions are accepted only when their attribution quotes the retained source. */
public record InformationMetadata(
    List<String> tags,
    String author,
    String authorSource,
    String authorEvidence,
    Map<String, List<String>> groups) {
  public InformationMetadata {
    tags = InformationFacets.tags(tags);
  }

  public InformationMetadata(
      List<String> tags, String author, String authorSource, String authorEvidence) {
    this(tags, author, authorSource, authorEvidence, null);
  }

  public static InformationMetadata from(Object raw, String retained) {
    // Old paid tag checkpoints remain reusable; they contain no bibliographic attribution.
    if (raw instanceof List<?>)
      return new InformationMetadata(InformationFacets.tags(raw), null, null, null);
    if (!(raw instanceof Map<?, ?> fields))
      throw new IllegalStateException("information metadata must be a JSON object");
    var tags = InformationFacets.tags(fields.get("autoTag"));
    var groups =
        fields.containsKey("tagGroups")
            ? InformationTagGroups.from(fields.get("tagGroups"), tags)
            : null;
    var person = candidate(fields.get("documentAuthor"), retained);
    if (person != null)
      return new InformationMetadata(tags, person.name(), "person", person.evidence(), groups);
    var organisation = candidate(fields.get("documentOrganisation"), retained);
    return organisation == null
        ? new InformationMetadata(tags, null, null, null, groups)
        : new InformationMetadata(
            tags, organisation.name(), "organisation", organisation.evidence(), groups);
  }

  private record Candidate(String name, String evidence) {}

  private static Candidate candidate(Object raw, String retained) {
    if (!(raw instanceof Map<?, ?> fields)
        || !Boolean.TRUE.equals(fields.get("certain"))
        || !(fields.get("name") instanceof String name)
        || !(fields.get("evidence") instanceof String evidence)) return null;
    name = name.strip();
    evidence = evidence.strip();
    if (name.isEmpty()
        || name.length() > 256
        || evidence.isEmpty()
        || evidence.length() > 512
        || name.codePoints().anyMatch(Character::isISOControl)
        || !evidence.contains(name)
        || retained == null
        || !retained.contains(evidence)) return null;
    return new Candidate(name, evidence);
  }
}
