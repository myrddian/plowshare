package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Repository-private facet persistence shared by catalogue revisions and navigation heads.
 *
 * <p>Materialization is a correctness and work boundary: all dimensions, the graph and the total
 * consume the same authorized candidate snapshot. Only the owning repositories supply selection
 * predicates; caller values remain bound once. This does not retain an authorization decision
 * beyond the statement or replace the repositories' existing access predicates.
 */
final class JdbcInformationFacets {
  enum Candidates {
    REVISIONS(
        " FROM information_revision_allowances r JOIN information_resources q ON q.id=r.resource_id"),
    HEADS(
        " FROM information_resources q JOIN information_revision_allowances r ON r.id=information_navigation_head(q.id)");

    private final String from;

    Candidates(String from) {
      this.from = from;
    }
  }

  /** Column and function identifiers are fixed here, never derived from a request's facet names. */
  private enum Dimension {
    KIND("kind", "c.kind"),
    TAGS("tags", "c.tags", Expansion.ARRAY),
    AUTO_TAG("autoTag", "c.automatic_tags", Expansion.ARRAY),
    TAG_GROUP("tagGroup", "c.tag_groups", Expansion.OBJECT_KEYS),
    AUTHOR("author", "c.author"),
    DOCUMENT_AUTHOR("documentAuthor", "c.document_author"),
    WHEN("when", "c.period"),
    SUBTYPE("subtype", "c.subtype"),
    CONTENT_TYPE("contentType", "c.content_type"),
    CONTENT_TYPE_VERSION("contentTypeVersion", "c.content_type_version");

    private final String name;
    private final String column;
    private final Expansion expansion;

    Dimension(String name, String column) {
      this(name, column, Expansion.SCALAR);
    }

    Dimension(String name, String column, Expansion expansion) {
      this.name = name;
      this.column = column;
      this.expansion = expansion;
    }

    private boolean typed() {
      return this == CONTENT_TYPE || this == CONTENT_TYPE_VERSION;
    }

    private static Dimension read(String name) throws SQLException {
      for (var dimension : values()) {
        if (dimension.name.equals(name)) return dimension;
      }
      throw new SQLException("Unknown information facet dimension");
    }
  }

  private enum Expansion {
    SCALAR,
    ARRAY,
    OBJECT_KEYS
  }

  private sealed interface Aggregate permits Total, Count, Edge {}

  private record Total(long value) implements Aggregate {
    private Total {
      if (value < 0) throw new IllegalArgumentException("Negative facet total");
    }
  }

  private record Count(Dimension dimension, Information.FacetCount value) implements Aggregate {}

  private record Edge(Information.TagEdge value) implements Aggregate {}

  private final JdbcTemplate jdbc;
  private final ProjectionCache<Information.Facets> cache;

  JdbcInformationFacets(JdbcTemplate jdbc) {
    this(jdbc, ProjectionCache.direct());
  }

  JdbcInformationFacets(JdbcTemplate jdbc, ProjectionCache<Information.Facets> cache) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.cache = Objects.requireNonNull(cache);
  }

  Information.Facets read(
      InformationSql.Filter selected, Candidates candidates, boolean typedView) {
    return load(selected, candidates, typedView, null);
  }

  /**
   * The owning repository supplies a complete selection key; its service retains live selection
   * admission. Complete document/authority version fences cover candidate-set and policy changes,
   * so a warm hit needs no discarded per-candidate read. Loads stay on the caller's transaction.
   */
  Information.Facets read(
      InformationSql.Filter selected,
      Candidates candidates,
      boolean typedView,
      SnapshotCache.Key key) {
    if (!cache.enabled()) return load(selected, candidates, typedView, null);
    long maximum = cache.maxWeight() <= 4096 ? 0 : (cache.maxWeight() - 4096) / 8;
    return cache.read(
        key,
        maximum > 0,
        () -> load(selected, candidates, typedView, maximum),
        () -> load(selected, candidates, typedView, null),
        JdbcInformationFacets::weight);
  }

  private Information.Facets load(
      InformationSql.Filter selected, Candidates candidates, boolean typedView, Long maximumBytes) {
    var dimensions =
        java.util.Arrays.stream(Dimension.values())
            .filter(dimension -> typedView || !dimension.typed())
            .toList();
    String sql = statement(selected, candidates, dimensions, typedView);
    var arguments = new ArrayList<Object>(selected.arguments());
    if (maximumBytes != null) {
      // The existing candidate CTE still runs once. Bound only its already limited aggregate rows;
      // an oversized result returns a scalar sentinel with no category/value text to hydrate.
      sql =
          "WITH facet_rows AS MATERIALIZED ("
              + sql
              + "), facet_bounds AS (SELECT COALESCE(sum("
              + "256::bigint+COALESCE(octet_length(facet_name),0)+COALESCE(octet_length(category),0)"
              + "+COALESCE(octet_length(value),0)),0) AS projection_bytes FROM facet_rows)"
              + " SELECT facet_rows.*,facet_bounds.projection_bytes FROM facet_bounds LEFT JOIN facet_rows"
              + " ON facet_bounds.projection_bytes<=?"
              + " ORDER BY row_kind,facet_name,result_count DESC,category,value";
      arguments.add(maximumBytes);
    }
    var rows =
        jdbc.query(
            sql,
            (row, index) -> {
              if (maximumBytes != null && row.getLong("projection_bytes") > maximumBytes)
                throw new ProjectionCache.Bypass();
              return aggregate(row, index);
            },
            arguments.toArray());
    var values = new LinkedHashMap<Dimension, List<Information.FacetCount>>();
    dimensions.forEach(dimension -> values.put(dimension, new ArrayList<>()));
    var edges = new ArrayList<Information.TagEdge>();
    Long total = null;
    for (var row : rows) {
      switch (row) {
        case Total count -> {
          if (total != null) throw new IllegalStateException("Duplicate facet total");
          total = count.value();
        }
        case Count count -> {
          var dimension = values.get(count.dimension());
          if (dimension == null || dimension.size() == 101)
            throw new IllegalStateException("Unexpected facet count projection");
          dimension.add(count.value());
        }
        case Edge edge -> {
          if (edges.size() == 1001) throw new IllegalStateException("Unbounded facet graph");
          edges.add(edge.value());
        }
      }
    }
    if (total == null) throw new IllegalStateException("Missing facet total");
    var counts = new LinkedHashMap<String, List<Information.FacetCount>>();
    var more = new LinkedHashMap<String, Boolean>();
    values.forEach(
        (dimension, entries) -> {
          counts.put(dimension.name, entries.stream().limit(100).toList());
          more.put(dimension.name, entries.size() > 100);
        });
    return new Information.Facets(
        total,
        counts,
        more,
        new Information.TagGraph(edges.stream().limit(1000).toList(), edges.size() > 1000));
  }

  private static long weight(Information.Facets facets) {
    long weight = 4096;
    for (var dimension : facets.facets().entrySet()) {
      weight += 256L + 4L * dimension.getKey().length();
      for (var count : dimension.getValue()) weight += 128L + 4L * count.value().length();
    }
    for (var edge : facets.tagGraph().edges())
      weight += 192L + 4L * (edge.category().length() + edge.tag().length());
    return weight;
  }

  private static String statement(
      InformationSql.Filter selected,
      Candidates candidates,
      List<Dimension> dimensions,
      boolean typedView) {
    String projection =
        "SELECT q.kind,q.owner_handle AS author,r.document_subtype AS subtype,"
            + "to_char(r.created_at AT TIME ZONE 'UTC','YYYY-MM') AS period,q.tags,"
            + InformationFacetSql.readyTags("r")
            + " AS automatic_tags,"
            + InformationFacetSql.documentAuthor("r", "q")
            + " AS document_author,"
            + InformationFacetSql.visibleGroups("r", "q")
            + " AS tag_groups";
    if (typedView)
      projection +=
          ",facet_type.type_id::text AS content_type,facet_binding.type_version::text AS content_type_version";
    String from = candidates.from;
    if (typedView)
      from +=
          " LEFT JOIN information_revision_content facet_binding ON facet_binding.revision_id=r.id AND NOT facet_binding.redacted"
              + " LEFT JOIN information_content_type_versions facet_type ON facet_type.id=facet_binding.type_version";
    var sql =
        new StringBuilder("WITH candidates AS MATERIALIZED (")
            .append(projection)
            .append(from)
            .append(" WHERE ")
            .append(selected.sql())
            .append(
                ") SELECT 0 AS row_kind,NULL::text AS facet_name,NULL::text AS category,"
                    + "NULL::text AS value,count(*) AS result_count FROM candidates");
    for (var dimension : dimensions) {
      sql.append(" UNION ALL SELECT 1,'")
          .append(dimension.name)
          .append(
              "',NULL::text,value,result_count FROM (SELECT value,count(*) AS result_count FROM (")
          .append(dimensionValues(dimension))
          .append(") expanded");
      // Catalogue historically counts JSON null array elements; navigation omits them. Keep that
      // distinction even though ordinary validated tag writes contain strings only.
      if (candidates == Candidates.HEADS || dimension.expansion != Expansion.ARRAY)
        sql.append(" WHERE value IS NOT NULL");
      sql.append(" GROUP BY value ORDER BY result_count DESC,value LIMIT 101) counted");
    }
    sql.append(
        " UNION ALL SELECT 2,NULL::text,category,value,result_count FROM ("
            + "SELECT g.category,t.value,count(*) AS result_count FROM candidates c"
            + " CROSS JOIN LATERAL jsonb_each(c.tag_groups) g(category,tags)"
            + " CROSS JOIN LATERAL jsonb_array_elements_text(g.tags) t(value)"
            + " GROUP BY g.category,t.value ORDER BY result_count DESC,g.category,t.value LIMIT 1001) graph"
            // UNION ALL/parallel append do not preserve branch ordering. The final order keeps
            // PostgreSQL's value collation and the graph's count/group/tag tie-breakers intact.
            + " ORDER BY row_kind,facet_name,result_count DESC,category,value");
    return sql.toString();
  }

  private static String dimensionValues(Dimension dimension) {
    return switch (dimension.expansion) {
      case SCALAR -> "SELECT " + dimension.column + " AS value FROM candidates c";
      case ARRAY ->
          "SELECT item.value FROM candidates c CROSS JOIN LATERAL jsonb_array_elements_text("
              + dimension.column
              + ") item(value)";
      case OBJECT_KEYS ->
          "SELECT item.value FROM candidates c CROSS JOIN LATERAL jsonb_object_keys("
              + dimension.column
              + ") item(value)";
    };
  }

  private static Aggregate aggregate(ResultSet row, int index) throws SQLException {
    return switch (row.getInt("row_kind")) {
      case 0 -> new Total(row.getLong("result_count"));
      case 1 ->
          new Count(
              Dimension.read(row.getString("facet_name")),
              new Information.FacetCount(row.getString("value"), row.getLong("result_count")));
      case 2 ->
          new Edge(
              new Information.TagEdge(
                  row.getString("category"), row.getString("value"), row.getLong("result_count")));
      default -> throw new SQLException("Unknown information facet aggregate");
    };
  }
}
