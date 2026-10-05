package io.aeyer.plowshare.protocol;

import java.net.URI;
import java.time.Year;
import java.time.YearMonth;
import java.util.*;

/** Validated information commands. Caller identity and permissions always come from the server. */
public sealed interface InformationRequest {
  Selection selection();

  String operation();

  record Scope(String kind, String project, Boolean includeShared) {
    public Scope {
      if (!Set.of("personal", "project", "shared").contains(kind))
        throw new IllegalArgumentException("unsupported information scope");
      project = ContractValues.optionalIdentity(project, "scope project", 1024);
      if ("project".equals(kind) != (project != null))
        throw new IllegalArgumentException(
            "project scope requires a project; other scopes forbid it");
      if ("shared".equals(kind) && Boolean.TRUE.equals(includeShared))
        throw new IllegalArgumentException("shared scope cannot include another shared audience");
    }

    public static Scope personal() {
      return new Scope("personal", null, true);
    }
  }

  record Selection(Scope scope, String corpus, FacetFilter filter) {
    public Selection {
      Objects.requireNonNull(scope, "scope");
      if (corpus == null) corpus = "documents";
      if (!Set.of("code", "documents").contains(corpus))
        throw new IllegalArgumentException("unsupported information corpus");
    }

    public static Selection personal() {
      return new Selection(Scope.personal(), "documents", null);
    }
  }

  record FacetFilter(
      String kind,
      List<String> tags,
      List<String> autoTag,
      String tagGroup,
      String author,
      String documentAuthor,
      String when,
      String subtype,
      String search) {
    public FacetFilter {
      if (kind != null && !Set.of("source", "report").contains(kind))
        throw new IllegalArgumentException("kind must be source or report");
      tags = InformationRequest.tags(tags == null ? List.of() : tags);
      autoTag = InformationRequest.tags(autoTag == null ? List.of() : autoTag);
      tagGroup = tagGroup == null ? null : InformationRequest.tags(List.of(tagGroup)).getFirst();
      author = ContractValues.optionalIdentity(author, "author", 512);
      documentAuthor = ContractValues.optionalIdentity(documentAuthor, "documentAuthor", 512);
      search = ContractValues.optionalIdentity(search, "search", 512);
      if (subtype != null && !subtype.matches("[a-z][a-z0-9_]{0,127}"))
        throw new IllegalArgumentException("invalid subtype");
      if (when != null) {
        if (when.matches("[0-9]{4}") && !when.startsWith("0000")) Year.parse(when);
        else if (when.matches("[0-9]{4}-[0-9]{2}") && !when.startsWith("0000"))
          YearMonth.parse(when);
        else throw new IllegalArgumentException("when must be a positive UTC year or year-month");
      }
    }
  }

  record Reference(UUID revision, UUID acquisition) {
    public Reference {
      if ((revision == null) == (acquisition == null))
        throw new IllegalArgumentException("reference needs exactly one revision or acquisition");
    }
  }

  enum AdmissionAction {
    UPLOAD,
    REVISE,
    REPLACE
  }

  enum AcquisitionAction {
    ACQUIRE,
    REFRESH
  }

  enum BrowseAction {
    LIST,
    INVENTORY,
    ACQUISITIONS,
    MIGRATION_LIST
  }

  enum RevisionAction {
    FINALISE,
    SHARE,
    UNSHARE,
    WITHDRAW,
    EXCLUDE,
    UNEXCLUDE,
    RESTORE,
    DELETE
  }

  enum LinkAction {
    LINK,
    UNLINK
  }

  record Admission(
      AdmissionAction action,
      Selection selection,
      UUID requestId,
      String name,
      String text,
      UUID revision)
      implements InformationRequest {
    public Admission {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(requestId, "requestId");
      name = ContractValues.text(name, "name", 1024, action == AdmissionAction.UPLOAD);
      text = ContractValues.text(text, "text", 8388608, true);
      if ((action != AdmissionAction.UPLOAD) != (revision != null))
        throw new IllegalArgumentException(
            "revision required for revise/replace and forbidden for upload");
    }

    @Override
    public String operation() {
      return action.name().toLowerCase(Locale.ROOT);
    }
  }

  record Acquisition(
      AcquisitionAction action,
      Selection selection,
      UUID requestId,
      String url,
      String name,
      UUID revision)
      implements InformationRequest {
    public Acquisition {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(requestId, "requestId");
      if (action == AcquisitionAction.ACQUIRE) {
        url = InformationRequest.url(url);
        if (revision != null) throw new IllegalArgumentException("acquire cannot name a revision");
      } else {
        Objects.requireNonNull(revision, "revision");
        if (url != null) throw new IllegalArgumentException("refresh uses its retained URL");
      }
      name = ContractValues.text(name, "name", 1024, false);
    }

    @Override
    public String operation() {
      return action == AcquisitionAction.ACQUIRE ? "acquire" : "refresh";
    }
  }

  record Browse(
      BrowseAction action, Selection selection, Integer limit, Integer offset, String kind)
      implements InformationRequest {
    public Browse {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(action, "action");
      page(limit, offset, 100);
      if (kind != null && !Set.of("source", "report").contains(kind))
        throw new IllegalArgumentException("invalid kind");
    }

    @Override
    public String operation() {
      return action == BrowseAction.MIGRATION_LIST
          ? "migration.list"
          : action.name().toLowerCase(Locale.ROOT);
    }
  }

  record Facets(Selection selection, String kind) implements InformationRequest {
    public Facets {
      Objects.requireNonNull(selection, "selection");
      if (kind != null && !Set.of("source", "report").contains(kind))
        throw new IllegalArgumentException("invalid kind");
    }

    @Override
    public String operation() {
      return "facets";
    }
  }

  record Status(Selection selection, UUID revision, UUID acquisition)
      implements InformationRequest {
    public Status {
      Objects.requireNonNull(selection, "selection");
      new Reference(revision, acquisition);
    }

    @Override
    public String operation() {
      return "status";
    }
  }

  record Await(Selection selection, List<Reference> sources, Integer waitMs)
      implements InformationRequest {
    public Await {
      Objects.requireNonNull(selection, "selection");
      sources = ContractValues.list(sources, "sources", 100);
      if (sources.isEmpty()) throw new IllegalArgumentException("sources cannot be empty");
      if (waitMs != null && (waitMs < 0 || waitMs > 30000))
        throw new IllegalArgumentException("waitMs must be 0..30000");
    }

    @Override
    public String operation() {
      return "await";
    }
  }

  record Read(Selection selection, UUID revision, Integer limit, Integer offset)
      implements InformationRequest {
    public Read {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      page(limit, offset, 32768);
    }

    @Override
    public String operation() {
      return "read";
    }
  }

  record Outline(Selection selection, UUID revision, Integer limit, Integer offset)
      implements InformationRequest {
    public Outline {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      page(limit, offset, 200);
    }

    @Override
    public String operation() {
      return "outline";
    }
  }

  record Symbols(Selection selection, UUID revision, String query, Integer limit, Integer offset)
      implements InformationRequest {
    public Symbols {
      Objects.requireNonNull(selection, "selection");
      query = ContractValues.text(query, "query", 128, true);
      page(limit, offset, 100);
    }

    @Override
    public String operation() {
      return "symbols";
    }
  }

  record Search(Selection selection, UUID revision, String query, Integer limit)
      implements InformationRequest {
    public Search {
      Objects.requireNonNull(selection, "selection");
      query = ContractValues.text(query, "query", 32768, true);
      page(limit, null, 100);
    }

    @Override
    public String operation() {
      return "search";
    }
  }

  record Rank(Selection selection, String query, Integer limit) implements InformationRequest {
    public Rank {
      Objects.requireNonNull(selection, "selection");
      query = ContractValues.text(query, "query", 32768, true);
      page(limit, null, 100);
    }

    @Override
    public String operation() {
      return "rank";
    }
  }

  record Ask(Selection selection, UUID revision, String question, Integer maxModelCalls)
      implements InformationRequest {
    public Ask {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      question = ContractValues.text(question, "question", 1048576, true);
      if (maxModelCalls != null && maxModelCalls < 1)
        throw new IllegalArgumentException("positive model allowance required");
    }

    @Override
    public String operation() {
      return "ask";
    }
  }

  record EvidenceRecord(
      Selection selection,
      UUID revision,
      UUID requestId,
      int start,
      int end,
      String quote,
      String locator)
      implements InformationRequest {
    public EvidenceRecord {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
      quote = ContractValues.text(quote, "quote", 32768, true);
      locator = ContractValues.identity(locator, "locator", 1024);
      if (start < 0 || end < start || end - start != quote.length())
        throw new IllegalArgumentException("invalid evidence span");
    }

    @Override
    public String operation() {
      return "evidence.record";
    }
  }

  record EvidenceRead(Selection selection, UUID evidence) implements InformationRequest {
    public EvidenceRead {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public String operation() {
      return "evidence.read";
    }
  }

  record ReportRecord(
      Selection selection,
      UUID requestId,
      String name,
      String text,
      List<UUID> inputs,
      List<UUID> evidence,
      UUID feedback,
      InformationReportDetails details)
      implements InformationRequest {
    public ReportRecord {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(requestId, "requestId");
      name = ContractValues.text(name, "name", 1024, true);
      text = ContractValues.text(text, "text", 8388608, true);
      inputs = ContractValues.list(inputs == null ? List.of() : inputs, "inputs", 10000);
      evidence = ContractValues.list(evidence == null ? List.of() : evidence, "evidence", 10000);
      if (details == null) details = InformationReportDetails.empty();
    }

    @Override
    public String operation() {
      return "record.report";
    }
  }

  record RevisionChange(RevisionAction action, Selection selection, UUID revision, UUID requestId)
      implements InformationRequest {
    public RevisionChange {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
    }

    @Override
    public String operation() {
      return action.name().toLowerCase(Locale.ROOT);
    }
  }

  record Link(
      LinkAction action,
      Selection selection,
      UUID revision,
      UUID requestId,
      String collectionProject)
      implements InformationRequest {
    public Link {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
      collectionProject = ContractValues.identity(collectionProject, "collectionProject", 1024);
    }

    @Override
    public String operation() {
      return action.name().toLowerCase(Locale.ROOT);
    }
  }

  record Tags(Selection selection, UUID revision, UUID requestId, List<String> tags)
      implements InformationRequest {
    public Tags {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
      tags = InformationRequest.tags(tags);
    }

    @Override
    public String operation() {
      return "tags";
    }
  }

  record TagGroups(
      Selection selection, UUID revision, UUID requestId, Map<String, List<String>> groups)
      implements InformationRequest {
    public TagGroups {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
      if (groups != null) {
        if (groups.size() > 32) throw new IllegalArgumentException("too many tag groups");
        var checked = new LinkedHashMap<String, List<String>>();
        groups.forEach(
            (key, values) -> {
              String name = InformationRequest.tags(List.of(key)).getFirst();
              if (checked.putIfAbsent(name, InformationRequest.tags(values)) != null)
                throw new IllegalArgumentException("duplicate normalized group");
            });
        groups = Map.copyOf(checked);
      }
    }

    @Override
    public String operation() {
      return "tags.groups";
    }
  }

  record Retry(Selection selection, UUID revision, UUID acquisition, UUID requestId)
      implements InformationRequest {
    public Retry {
      Objects.requireNonNull(selection, "selection");
      new Reference(revision, acquisition);
      if (revision != null) Objects.requireNonNull(requestId, "requestId");
    }

    @Override
    public String operation() {
      return "retry";
    }
  }

  record Rebuild(Selection selection, UUID revision, UUID requestId, String stage)
      implements InformationRequest {
    public Rebuild {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
      if (!Set.of(
              "extract", "derive", "embed", "summarise", "summary_embed", "autoTag", "tagGroups")
          .contains(stage)) throw new IllegalArgumentException("invalid processing stage");
    }

    @Override
    public String operation() {
      return "rebuild";
    }
  }

  record Allowance(Selection selection, UUID revision, UUID requestId, int maxModelCalls)
      implements InformationRequest {
    public Allowance {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
      if (maxModelCalls < 1)
        throw new IllegalArgumentException("positive model allowance required");
    }

    @Override
    public String operation() {
      return "allowance";
    }
  }

  record Events(Selection selection, Long after, Integer limit) implements InformationRequest {
    public Events {
      Objects.requireNonNull(selection, "selection");
      if (after != null && after < 0)
        throw new IllegalArgumentException("event cursor must be nonnegative");
      page(limit, null, 100);
    }

    @Override
    public String operation() {
      return "events";
    }
  }

  record MigrationInspect(
      Selection selection, String payload, String reason, Integer limit, Integer offset)
      implements InformationRequest {
    public MigrationInspect {
      Objects.requireNonNull(selection, "selection");
      payload = ContractValues.identity(payload, "payload", 1024);
      reason = ContractValues.text(reason, "reason", 4096, true);
      page(limit, offset, 100);
    }

    @Override
    public String operation() {
      return "migration.inspect";
    }
  }

  record MigrationRelease(
      Selection selection,
      String payload,
      String owner,
      List<UUID> inputs,
      String reason,
      UUID requestId)
      implements InformationRequest {
    public MigrationRelease {
      Objects.requireNonNull(selection, "selection");
      payload = ContractValues.identity(payload, "payload", 1024);
      owner = ContractValues.identity(owner, "owner", 256);
      reason = ContractValues.text(reason, "reason", 4096, true);
      Objects.requireNonNull(requestId, "requestId");
      inputs = ContractValues.list(inputs == null ? List.of() : inputs, "inputs", 10000);
    }

    @Override
    public String operation() {
      return "migration.release";
    }
  }

  record MigrationAdopt(
      Selection selection,
      UUID revision,
      String owner,
      String visibility,
      String collectionProject,
      String reason,
      UUID requestId)
      implements InformationRequest {
    public MigrationAdopt {
      Objects.requireNonNull(selection, "selection");
      Objects.requireNonNull(revision, "revision");
      Objects.requireNonNull(requestId, "requestId");
      owner = ContractValues.identity(owner, "owner", 256);
      reason = ContractValues.text(reason, "reason", 4096, true);
      collectionProject =
          ContractValues.optionalIdentity(collectionProject, "collectionProject", 1024);
      if (!Set.of("personal", "project", "shared").contains(visibility)
          || "project".equals(visibility) != (collectionProject != null))
        throw new IllegalArgumentException("invalid adoption destination");
    }

    @Override
    public String operation() {
      return "migration.adopt";
    }
  }

  private static void page(Integer limit, Integer offset, int maximum) {
    if (limit != null && (limit < 1 || limit > maximum) || offset != null && offset < 0)
      throw new IllegalArgumentException("invalid page bounds");
  }

  private static String url(String value) {
    value = ContractValues.identity(value, "url", 8192);
    URI uri = URI.create(value);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null)
      throw new IllegalArgumentException("URL must use HTTP(S) without credentials or fragment");
    return uri.toASCIIString();
  }

  private static List<String> tags(List<String> values) {
    return ContractValues.list(values, "tags", 32).stream()
        .map(
            value -> {
              value =
                  ContractValues.identity(value, "tag", 64)
                      .toLowerCase(Locale.ROOT)
                      .replaceAll("\\s+", " ");
              if (value.length() > 64)
                throw new IllegalArgumentException("tag exceeds 64 characters");
              return value;
            })
        .distinct()
        .sorted()
        .toList();
  }
}
