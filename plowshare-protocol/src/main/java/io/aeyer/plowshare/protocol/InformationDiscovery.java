package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cursor-based, read-only discovery. Scope identifiers select information; they grant no access.
 */
public final class InformationDiscovery {
  private InformationDiscovery() {}

  public record Scope(String id, String kind, String name, String project) {
    public Scope {
      scopeId(id);
      if (!List.of("PROJECT", "PERSONAL", "ACCOUNT_KNOWLEDGE", "CONTAINED", "GLOBAL_READ")
          .contains(kind)) throw new IllegalArgumentException("Invalid discovery scope kind");
      name = ContractValues.identity(name, "scope name", 1024);
      project = ContractValues.optionalIdentity(project, "project", 1024);
    }
  }

  public record Scopes(List<Scope> items, String nextCursor, boolean hasMore) {
    public Scopes {
      items = ContractValues.list(items, "items", 50);
      continuation(nextCursor, hasMore);
    }
  }

  /** Current heads only. Metadata remains source evidence; it is not a verified claim. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Item(
      UUID resource,
      UUID revision,
      String scope,
      String project,
      String sourceName,
      String title,
      String kind,
      String reportStatus,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
      List<String> tags,
      List<String> autoTag,
      String documentAuthor,
      String subtype,
      boolean contentIndexed) {
    public Item {
      Objects.requireNonNull(resource);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(createdAt);
      scopeId(scope);
      project = ContractValues.optionalIdentity(project, "project", 1024);
      sourceName = ContractValues.identity(sourceName, "sourceName", 4096);
      title = ContractValues.text(title, "title", 8192, false);
      if (!List.of("source", "report").contains(kind)
          || reportStatus != null
              && !List.of("draft", "final", "superseded").contains(reportStatus))
        throw new IllegalArgumentException("Invalid discovery resource kind/status");
      tags = ContractValues.list(tags, "tags", 32);
      autoTag = ContractValues.list(autoTag, "autoTag", 32);
      documentAuthor = ContractValues.text(documentAuthor, "documentAuthor", 512, false);
      subtype = ContractValues.identity(subtype, "subtype", 128);
    }
  }

  public record Page(List<String> scopes, List<Item> items, String nextCursor, boolean hasMore) {
    public Page {
      scopes = InformationDiscovery.scopes(scopes);
      items = ContractValues.list(items, "items", 50);
      continuation(nextCursor, hasMore);
    }
  }

  public record Facet(String value, long count) {
    public Facet {
      value = ContractValues.identity(value, "facet value", 512);
      if (count < 1) throw new IllegalArgumentException("Invalid facet count");
    }
  }

  public record Facets(
      List<String> scopes, String facet, List<Facet> items, String nextCursor, boolean hasMore) {
    public Facets {
      scopes = InformationDiscovery.scopes(scopes);
      items = ContractValues.list(items, "items", 50);
      continuation(nextCursor, hasMore);
    }
  }

  public record ScopesRequest(Integer limit, String cursor) {
    public ScopesRequest {
      page(limit, cursor);
    }
  }

  /** Search is lexical document discovery, ordered by creation time and revision UUID. */
  public record Query(
      List<String> scopes,
      InformationRequest.FacetFilter filter,
      String query,
      String facet,
      Integer limit,
      String cursor) {
    public Query {
      scopes = InformationDiscovery.scopes(scopes);
      page(limit, cursor);
      if (query != null) ContractValues.text(query, "query", 512, true);
      if (facet != null
          && !List.of("kind", "tags", "autoTag", "tagGroup", "documentAuthor", "subtype", "when")
              .contains(facet)) throw new IllegalArgumentException("unsupported discovery facet");
      if (filter != null && filter.search() != null)
        throw new IllegalArgumentException(
            "Use query on discovery.search rather than filter.search");
    }
  }

  public record Read(List<String> scopes, UUID revision, Integer offset, Integer limit) {
    public Read {
      scopes = InformationDiscovery.scopes(scopes);
      Objects.requireNonNull(revision, "revision");
      if (offset != null && offset < 0 || limit != null && (limit < 1 || limit > 32768))
        throw new IllegalArgumentException("invalid discovery read window");
    }
  }

  private static List<String> scopes(List<String> values) {
    values = ContractValues.list(values, "scopes", 32);
    if (values.isEmpty()) throw new IllegalArgumentException("Select 1–32 readable scopes");
    for (String value : values) {
      scopeId(value);
    }
    if (values.stream().distinct().count() != values.size())
      throw new IllegalArgumentException("Duplicate discovery scopes");
    return values.stream().sorted().toList();
  }

  private static void page(Integer limit, String cursor) {
    if (limit != null && (limit < 1 || limit > 50))
      throw new IllegalArgumentException("Discovery page limit must be between 1 and 50");
    if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,2048}\\.[A-Za-z0-9_-]{43}"))
      throw new IllegalArgumentException("Invalid discovery cursor");
  }

  private static void scopeId(String value) {
    if (!"global_read".equals(value)
        && (value == null || !UUID.fromString(value).toString().equals(value)))
      throw new IllegalArgumentException("Use exact discovered scope identifiers");
  }

  private static void continuation(String cursor, boolean more) {
    if (more != (cursor != null)) throw new IllegalArgumentException("Invalid page continuation");
  }
}
