package io.aeyer.plowshare.protocol;

import java.util.List;
import java.util.Objects;

/** Ordered tool result references. Bytes, URLs and caller-selected storage scopes are forbidden. */
public final class ToolContent {
  public static final String PROFILE = "content/1";

  private ToolContent() {}

  public enum Kind {
    TEXT,
    IMAGE
  }

  public enum Use {
    OBSERVATION,
    OUTPUT,
    BOTH
  }

  /** An IMAGE refers to one immutable admission slot in the enclosing result's invocation. */
  public record Part(Kind kind, String text, String imageId, Integer ordinal, Use use) {
    public Part {
      Objects.requireNonNull(kind);
      if (kind == Kind.TEXT) {
        if (text == null
            || text.length() > 16384
            || text.indexOf('\0') >= 0
            || imageId != null
            || ordinal != null
            || use != null) throw new IllegalArgumentException("Invalid tool text part");
      } else if (text != null
          || imageId == null
          || !imageId.matches("img_[0-9a-f]{32}")
          || ordinal == null
          || ordinal < 0
          || ordinal > 15
          || use == null) throw new IllegalArgumentException("Invalid tool image reference");
    }

    public static Part text(String text) {
      return new Part(Kind.TEXT, text, null, null, null);
    }
  }

  public record Body(String version, List<Part> parts) {
    public Body {
      if (!PROFILE.equals(version))
        throw new IllegalArgumentException("Invalid tool content version");
      parts = List.copyOf(parts);
      if (parts.isEmpty() || parts.size() > 16)
        throw new IllegalArgumentException("Invalid tool part count");
      int images = 0, length = 0;
      var slots = new java.util.HashSet<Integer>();
      var words = new StringBuilder();
      for (var part : parts) {
        if (part.kind() == Kind.TEXT) {
          length += part.text().length();
          words.append(part.text());
        } else {
          images++;
          if (!slots.add(part.ordinal()))
            throw new IllegalArgumentException("Duplicate image admission slot");
        }
      }
      if (images > 4 || length > 16384 || images == 0 && words.toString().isBlank())
        throw new IllegalArgumentException("Invalid tool content allowance");
    }

    /** Exact textual projection; image-only results legitimately return an empty string. */
    public String text() {
      var text = new StringBuilder();
      parts.stream().filter(p -> p.kind() == Kind.TEXT).forEach(p -> text.append(p.text()));
      return text.toString();
    }

    public List<Part> images() {
      return parts.stream().filter(p -> p.kind() == Kind.IMAGE).toList();
    }
  }
}
