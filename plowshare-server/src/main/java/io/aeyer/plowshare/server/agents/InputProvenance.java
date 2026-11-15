package io.aeyer.plowshare.server.agents;

import java.util.List;
import java.util.Objects;

/**
 * A supplied-context span in a recorded input, with declared evidence identities. Offsets are
 * zero-based Unicode code points, end-exclusive, so Java and PostgreSQL agree. References describe
 * provenance, never permission to read the original evidence or proof of its truth.
 */
public record InputProvenance(int start, int end, List<Reference> sources) {
  public record Reference(String id, String revision) {
    public Reference {
      id = identity(id);
      revision = identity(revision);
    }

    private static String identity(String value) {
      if (value == null
          || value.isBlank()
          || value.length() > 256
          || value
              .codePoints()
              .anyMatch(
                  c ->
                      Character.isWhitespace(c)
                          || Character.isSpaceChar(c)
                          || Character.isISOControl(c)))
        throw new IllegalArgumentException("Invalid supplied evidence identity");
      return value;
    }
  }

  public InputProvenance {
    if (start < 0 || end <= start) throw new IllegalArgumentException("Invalid context span");
    sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    if (sources.size() > 1024) throw new IllegalArgumentException("Too many evidence references");
  }

  public void validate(String content) {
    if (end > content.codePointCount(0, content.length()))
      throw new IllegalArgumentException("Supplied context exceeds its recorded input");
  }

  public String evidenceText(String content) {
    validate(content);
    return content.substring(0, content.offsetByCodePoints(0, start))
        + "\n\n"
        + content.substring(content.offsetByCodePoints(0, end));
  }
}
