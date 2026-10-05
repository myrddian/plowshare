package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.*;
import java.util.*;

/** Sanitized metadata selection. No raw payload or SQL is carried into catalogue policy. */
public record InformationFacets(
    String kind,
    List<String> tags,
    List<String> autoTag,
    String tagGroup,
    String author,
    String documentAuthor,
    String when,
    String subtype,
    String search) {
  public static final InformationFacets NONE =
      new InformationFacets(null, List.of(), List.of(), null, null, null, null, null, null);
  public static final List<String> NAMES =
      List.of("kind", "tags", "autoTag", "tagGroup", "author", "documentAuthor", "when", "subtype");

  public InformationFacets {
    kind = text(kind, "kind");
    if (kind != null && !List.of("source", "report").contains(kind))
      throw new CallerFault("kind must be source or report");
    tags = tags == null ? List.of() : tags(tags);
    autoTag = autoTag == null ? List.of() : tags(autoTag);
    tagGroup = text(tagGroup, "tagGroup");
    if (tagGroup != null) tagGroup = tags(List.of(tagGroup)).getFirst();
    author = text(author, "author");
    documentAuthor = text(documentAuthor, "documentAuthor");
    when = text(when, "when");
    if (when != null) period(when);
    subtype = text(subtype, "subtype");
    if (subtype != null && !subtype.matches("[a-z][a-z0-9_]*"))
      throw new CallerFault("invalid subtype facet");
    search = text(search, "search");
  }

  private static String text(String value, String field) {
    if (value == null) return null;
    if (value.isBlank()
        || value.length() > 512
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new CallerFault(field + " must be bounded nonblank text");
    return value.strip();
  }

  public boolean empty() {
    return equals(NONE);
  }

  public static List<String> tags(Object raw) {
    if (!(raw instanceof List<?> list) || list.size() > 32)
      throw new CallerFault("tags must be an array of at most 32 strings");
    var result = new TreeSet<String>();
    for (Object item : list) {
      if (!(item instanceof String tag)) throw new CallerFault("tags must contain strings");
      tag = tag.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
      if (tag.isEmpty() || tag.length() > 64 || tag.codePoints().anyMatch(Character::isISOControl))
        throw new CallerFault("each tag must have 1..64 printable characters");
      result.add(tag);
    }
    return List.copyOf(result);
  }

  public static LocalDate[] period(String value) {
    try {
      if (value.startsWith("0000")) throw new CallerFault("when must use a positive UTC year");
      if (value.matches("[0-9]{4}")) {
        var start = LocalDate.of(Integer.parseInt(value), 1, 1);
        return new LocalDate[] {start, start.plusYears(1)};
      }
      if (value.matches("[0-9]{4}-[0-9]{2}")) {
        var start = YearMonth.parse(value).atDay(1);
        return new LocalDate[] {start, start.plusMonths(1)};
      }
    } catch (DateTimeException invalid) {
    }
    throw new CallerFault("when must be a valid UTC year or year-month");
  }
}
