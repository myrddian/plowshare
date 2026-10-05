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
    if (groups != null) groups = InformationTagGroups.from(groups, tags);
    if (author == null) {
      if (authorSource != null || authorEvidence != null)
        throw new IllegalArgumentException("Absent author cannot carry attribution");
    } else if (author.isBlank()
        || author.length() > 256
        || author.codePoints().anyMatch(Character::isISOControl)
        || author.indexOf('\u2028') >= 0
        || author.indexOf('\u2029') >= 0
        || !java.util.Set.of("person", "organisation")
            .contains(authorSource == null ? "" : authorSource)
        || authorEvidence == null
        || authorEvidence.isBlank()
        || authorEvidence.length() > 512
        || authorEvidence.indexOf('\0') >= 0
        || !authorEvidence.contains(author))
      throw new IllegalArgumentException("Invalid bibliographic attribution");
  }

  public InformationMetadata(
      List<String> tags, String author, String authorSource, String authorEvidence) {
    this(tags, author, authorSource, authorEvidence, null);
  }

  /** Conversion at the model/checkpoint boundary; services receive only this validated value. */
  public static InformationMetadata from(Object raw, String retained) {
    return InformationMetadataCodec.convert(raw, retained);
  }
}
