package io.aeyer.plowshare.server.information;

import java.time.*;
import java.util.*;

/**
 * Repository SQL construction for typed metadata criteria. Caller values remain bound parameters.
 */
public final class InformationFacetSql {
  private InformationFacetSql() {}

  private static String readyMetadata(String revision) {
    return "EXISTS(SELECT 1 FROM information_steps tag_step WHERE tag_step.revision_id="
        + revision
        + ".id AND tag_step.generation="
        + revision
        + ".generation AND tag_step.stage='autoTag' AND tag_step.state='ready')";
  }

  public static String readyTags(String revision) {
    revisionAlias(revision);
    return "CASE WHEN "
        + readyMetadata(revision)
        + " THEN "
        + revision
        + ".auto_tag ELSE '[]'::jsonb END";
  }

  public static String documentAuthor(String revision, String resource) {
    aliases(revision, resource);
    return "coalesce(CASE WHEN "
        + readyMetadata(revision)
        + " THEN "
        + revision
        + ".document_author END,"
        + resource
        + ".owner_handle)";
  }

  public static String documentAuthorSource(String revision) {
    revisionAlias(revision);
    return "coalesce(CASE WHEN "
        + readyMetadata(revision)
        + " THEN "
        + revision
        + ".document_author_source END,'account')";
  }

  public static InformationSql.Filter facets(
      InformationFacets facets, String revision, String resource) {
    aliases(revision, resource);
    var sql = new StringBuilder("true");
    var args = new ArrayList<Object>();
    for (var entry : values(facets).entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      switch (key) {
        case "tags", "autoTag" -> {
          if (((List<?>) value).isEmpty()) continue;
          sql.append(" AND (")
              .append(key.equals("tags") ? resource + ".tags" : readyTags(revision))
              .append(")")
              .append(" @> CAST(? AS jsonb)");
          args.add(InformationJson.json(value));
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
          sql.append(" AND jsonb_exists(").append(visibleGroups(revision, resource)).append(",?)");
          args.add(value);
        }
        case "when" -> {
          var period = InformationFacets.period((String) value);
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
    return new InformationSql.Filter(sql.toString(), args);
  }

  private static Map<String, Object> values(InformationFacets facets) {
    var values = new LinkedHashMap<String, Object>();
    if (facets.kind() != null) values.put("kind", facets.kind());
    if (!facets.tags().isEmpty()) values.put("tags", facets.tags());
    if (!facets.autoTag().isEmpty()) values.put("autoTag", facets.autoTag());
    if (facets.tagGroup() != null) values.put("tagGroup", facets.tagGroup());
    if (facets.author() != null) values.put("author", facets.author());
    if (facets.documentAuthor() != null) values.put("documentAuthor", facets.documentAuthor());
    if (facets.when() != null) values.put("when", facets.when());
    if (facets.subtype() != null) values.put("subtype", facets.subtype());
    if (facets.search() != null) values.put("search", facets.search());
    return values;
  }

  public static String visibleTags(String revision, String resource) {
    aliases(revision, resource);
    return "information_visible_tags(" + resource + ".tags," + readyTags(revision) + ")";
  }

  public static String visibleGroups(String revision, String resource) {
    aliases(revision, resource);
    String tags = visibleTags(revision, resource);
    String automatic =
        "CASE WHEN "
            + revision
            + ".tag_groups_input_tags="
            + tags
            + " AND EXISTS(SELECT 1 FROM information_steps group_step WHERE group_step.revision_id="
            + revision
            + ".id AND group_step.generation="
            + revision
            + ".generation AND group_step.stage='tagGroups' AND group_step.state='ready') THEN "
            + revision
            + ".auto_tag_groups ELSE '{}'::jsonb END";
    return "information_visible_tag_groups(CASE WHEN "
        + resource
        + ".tag_groups_manual THEN "
        + resource
        + ".tag_groups ELSE "
        + automatic
        + " END,"
        + tags
        + ")";
  }

  /** Alias names are the fixed vocabulary of the specialist information/document repositories. */
  private static void revisionAlias(String value) {
    if (!Set.of("r", "facet_revision").contains(value == null ? "" : value))
      throw new IllegalArgumentException("Unknown internal revision alias");
  }

  private static void aliases(String revision, String resource) {
    revisionAlias(revision);
    if (!Set.of("q", "facet_resource").contains(resource == null ? "" : resource))
      throw new IllegalArgumentException("Unknown internal resource alias");
  }
}
