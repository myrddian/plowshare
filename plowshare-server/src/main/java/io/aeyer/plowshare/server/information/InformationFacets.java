package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.*;
import java.util.*;

/** Intersect metadata before paging or retrieval. Caller values are always SQL parameters. */
public record InformationFacets(Map<String, Object> values) {
  private static final ObjectMapper JSON = new ObjectMapper();
  public static final InformationFacets NONE = new InformationFacets(Map.of());

  private static String readyMetadata(String revision) {
    return "EXISTS(SELECT 1 FROM information_steps tag_step WHERE tag_step.revision_id="
        + revision
        + ".id AND tag_step.generation="
        + revision
        + ".generation AND tag_step.stage='autoTag' AND tag_step.state='ready')";
  }

  public static String readyTags(String revision) {
    return "CASE WHEN "
        + readyMetadata(revision)
        + " THEN "
        + revision
        + ".auto_tag ELSE '[]'::jsonb END";
  }

  public static String documentAuthor(String revision, String resource) {
    return "coalesce(CASE WHEN "
        + readyMetadata(revision)
        + " THEN "
        + revision
        + ".document_author END,"
        + resource
        + ".owner_handle)";
  }

  public static String documentAuthorSource(String revision) {
    return "coalesce(CASE WHEN "
        + readyMetadata(revision)
        + " THEN "
        + revision
        + ".document_author_source END,'account')";
  }

  public static final List<String> NAMES =
      List.of("kind", "tags", "autoTag", "tagGroup", "author", "documentAuthor", "when", "subtype");

  public InformationFacets {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public static InformationFacets from(Object raw) {
    if (raw == null) return NONE;
    if (!(raw instanceof Map<?, ?> fields)) throw new CallerFault("filter must be an object");
    var values = new LinkedHashMap<String, Object>();
    for (var field : fields.entrySet()) {
      String key = String.valueOf(field.getKey());
      if (!NAMES.contains(key) && !key.equals("search"))
        throw new CallerFault("unknown information facet: " + key);
      if (key.equals("tags") || key.equals("autoTag")) {
        values.put(key, tags(field.getValue()));
        continue;
      }
      if (!(field.getValue() instanceof String value) || value.isBlank() || value.length() > 512)
        throw new CallerFault(key + " must be bounded nonblank text");
      value = value.strip();
      if (key.equals("tagGroup")) value = tags(List.of(value)).getFirst();
      if (key.equals("kind") && !List.of("source", "report").contains(value))
        throw new CallerFault("kind must be source or report");
      if (key.equals("subtype") && !value.matches("[a-z][a-z0-9_]*"))
        throw new CallerFault("invalid subtype facet");
      if (key.equals("when")) period(value);
      values.put(key, value);
    }
    return new InformationFacets(values);
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

  private static LocalDate[] period(String value) {
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

  public InformationAccess.ReadFilter sql(String revision, String resource) {
    var sql = new StringBuilder("true");
    var args = new ArrayList<Object>();
    for (var entry : values.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      switch (key) {
        case "tags", "autoTag" -> {
          if (((List<?>) value).isEmpty()) continue;
          sql.append(" AND (")
              .append(key.equals("tags") ? resource + ".tags" : readyTags(revision))
              .append(")")
              .append(" @> CAST(? AS jsonb)");
          args.add(json(value));
        }
        case "kind", "author", "subtype" -> {
          sql.append(" AND ")
              .append(
                  switch (key) {
                    case "kind" -> resource + ".kind";
                    case "author" -> resource + ".owner_handle";
                    default -> revision + ".document_subtype";
                  })
              .append("=?");
          args.add(value);
        }
        case "documentAuthor" -> {
          sql.append(" AND ").append(documentAuthor(revision, resource)).append("=?");
          args.add(value);
        }
        case "tagGroup" -> {
          sql.append(" AND jsonb_exists(")
              .append(InformationTagGroups.groups(revision, resource))
              .append(",?)");
          args.add(value);
        }
        case "when" -> {
          var period = period((String) value);
          sql.append(" AND ")
              .append(revision)
              .append(".created_at>=? AND ")
              .append(revision)
              .append(".created_at<?");
          for (var date : period) args.add(date.atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        case "search" -> {
          sql.append(" AND to_tsvector('simple',coalesce(")
              .append(revision)
              .append(".title,'') || ' ' || ")
              .append(resource)
              .append(".source_name || ' ' || coalesce(")
              .append(revision)
              .append(".extracted_text,'') || ' ' || ")
              .append(resource)
              .append(".tags::text || ' ' || ")
              .append("(")
              .append(readyTags(revision))
              .append(")::text) @@ websearch_to_tsquery('simple',?)");
          args.add(value);
        }
        default -> throw new IllegalStateException("unvalidated facet");
      }
    }
    return new InformationAccess.ReadFilter(sql.toString(), args);
  }

  public static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  public static Map<String, Object> metadata(Map<String, Object> row) {
    for (String key : List.of("tags", "autoTag"))
      if (row.get(key) != null && !(row.get(key) instanceof List<?>)) {
        try {
          row.put(key, JSON.readValue(row.get(key).toString(), List.class));
        } catch (java.io.IOException invalid) {
          throw new IllegalStateException("invalid stored tags", invalid);
        }
      }
    if (row.get("tagGroups") != null && !(row.get("tagGroups") instanceof Map<?, ?>)) {
      try {
        row.put("tagGroups", JSON.readValue(row.get("tagGroups").toString(), Map.class));
      } catch (java.io.IOException invalid) {
        throw new IllegalStateException("invalid stored tag groups", invalid);
      }
    }
    return row;
  }
}
