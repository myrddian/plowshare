package io.aeyer.plowshare.protocol;

import java.net.URI;
import java.time.Year;
import java.time.YearMonth;
import java.util.*;

/** Validated information commands. Caller identity and permissions always come from the server. */
public sealed interface InformationRequest {
  Selection selection();

  String operation();

  record InterchangeCapabilities(Selection selection) implements InformationRequest {
    public InterchangeCapabilities {
      interchangeSelection(selection);
    }

    public String operation() {
      return "interchange.capabilities";
    }
  }

  record InterchangeExport(Selection selection, List<UUID> revisions)
      implements InformationRequest {
    public InterchangeExport {
      interchangeSelection(selection);
      revisions = List.copyOf(revisions);
      if (revisions.isEmpty()
          || revisions.size() > InformationInterchange.MAX_DOCUMENTS
          || new HashSet<>(revisions).size() != revisions.size())
        throw new IllegalArgumentException("Select 1–16 distinct exact revisions");
    }

    public String operation() {
      return "interchange.export";
    }
  }

  record InterchangeReadable(Selection selection, UUID revision) implements InformationRequest {
    public InterchangeReadable {
      interchangeSelection(selection);
      Objects.requireNonNull(revision);
    }

    public String operation() {
      return "interchange.readable";
    }
  }

  record InterchangeImport(
      Selection selection, UUID requestId, InformationInterchange.ImportPlan plan)
      implements InformationRequest {
    public InterchangeImport {
      interchangeSelection(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(plan);
      if (selection.scope().kind().equals("global_read"))
        throw new IllegalArgumentException("Import requires an owned destination scope");
    }

    public String operation() {
      return "interchange.import";
    }
  }

  record InterchangeOrigin(Selection selection, UUID revision) implements InformationRequest {
    public InterchangeOrigin {
      interchangeSelection(selection);
      Objects.requireNonNull(revision);
    }

    public String operation() {
      return "interchange.origin";
    }
  }

  private static void interchangeSelection(Selection selection) {
    Objects.requireNonNull(selection);
    if (!selection.corpus().equals("documents") || selection.filter() != null)
      throw new IllegalArgumentException("Interchange selects exact document revisions");
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record Scope(String kind, String project, Boolean includeGlobal) {
    public Scope {
      if (!Set.of("personal", "project", "global_read").contains(kind))
        throw new IllegalArgumentException("unsupported information scope");
      project = ContractValues.optionalIdentity(project, "scope project", 1024);
      if ("project".equals(kind) != (project != null))
        throw new IllegalArgumentException(
            "project scope requires a project; other scopes forbid it");
      if ("global_read".equals(kind) && includeGlobal != null)
        throw new IllegalArgumentException("global_read scope does not take includeGlobal");
    }

    public static Scope personal() {
      return new Scope("personal", null, true);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record Selection(Scope scope, String corpus, FacetFilter filter, String view) {
    public Selection(Scope scope, String corpus, FacetFilter filter) {
      this(scope, corpus, filter, null);
    }

    public Selection {
      Objects.requireNonNull(scope, "scope");
      if (view != null && !InformationContent.VIEW.equals(view))
        throw new IllegalArgumentException("unsupported information view");
      if (filter != null
          && (filter.contentType() != null
              || filter.contentTypeVersion() != null
              || filter.untyped() != null)
          && !InformationContent.VIEW.equals(view))
        throw new IllegalArgumentException("type filters require supporting view");
      if (corpus == null) corpus = "documents";
      if (!Set.of("code", "documents").contains(corpus))
        throw new IllegalArgumentException("unsupported information corpus");
    }

    public static Selection personal() {
      return new Selection(Scope.personal(), "documents", null);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record FacetFilter(
      String kind,
      List<String> tags,
      List<String> autoTag,
      String tagGroup,
      String author,
      String documentAuthor,
      String when,
      String subtype,
      String search,
      UUID contentType,
      UUID contentTypeVersion,
      Boolean untyped) {
    public FacetFilter(
        String kind,
        List<String> tags,
        List<String> autoTag,
        String tagGroup,
        String author,
        String documentAuthor,
        String when,
        String subtype,
        String search) {
      this(
          kind,
          tags,
          autoTag,
          tagGroup,
          author,
          documentAuthor,
          when,
          subtype,
          search,
          null,
          null,
          null);
    }

    public FacetFilter {
      if (Boolean.TRUE.equals(untyped) && (contentType != null || contentTypeVersion != null))
        throw new IllegalArgumentException("untyped conflicts with type filters");
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
      UUID revision,
      UUID expectedRevision,
      InformationContent.Typing typing)
      implements InformationRequest {
    public Admission(
        AdmissionAction action,
        Selection selection,
        UUID requestId,
        String name,
        String text,
        UUID revision) {
      this(action, selection, requestId, name, text, revision, null, null);
    }

    public Admission {
      authored(selection, expectedRevision, typing);
      if (typing != null
          && action != AdmissionAction.UPLOAD
          && !Objects.equals(expectedRevision, revision))
        throw new IllegalArgumentException("expectedRevision must match revised revision");
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
      UUID revision,
      UUID expectedRevision,
      InformationContent.Typing typing)
      implements InformationRequest {
    public Acquisition(
        AcquisitionAction action,
        Selection selection,
        UUID requestId,
        String url,
        String name,
        UUID revision) {
      this(action, selection, requestId, url, name, revision, null, null);
    }

    public Acquisition {
      authored(selection, expectedRevision, typing);
      if (action == AcquisitionAction.ACQUIRE && typing != null
          || typing != null && !Objects.equals(expectedRevision, revision))
        throw new IllegalArgumentException(
            "typed acquisition only refreshes its expected revision");
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
      InformationReportDetails details,
      UUID expectedRevision,
      InformationContent.Typing typing)
      implements InformationRequest {
    public ReportRecord(
        Selection selection,
        UUID requestId,
        String name,
        String text,
        List<UUID> inputs,
        List<UUID> evidence,
        UUID feedback,
        InformationReportDetails details) {
      this(selection, requestId, name, text, inputs, evidence, feedback, details, null, null);
    }

    public ReportRecord {
      authored(selection, expectedRevision, typing);
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

  record ContentCapabilities(Selection selection) implements InformationRequest {
    public ContentCapabilities {
      Objects.requireNonNull(selection);
    }

    @Override
    public String operation() {
      return "types.capabilities";
    }
  }

  record ContentRead(Selection selection, UUID revision) implements InformationRequest {
    public ContentRead {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(revision);
    }

    @Override
    public String operation() {
      return "content.get";
    }
  }

  record ContentRevise(
      Selection selection, UUID requestId, UUID expectedRevision, InformationContent.Typing typing)
      implements InformationRequest {
    public ContentRevise {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(expectedRevision);
      Objects.requireNonNull(typing);
    }

    @Override
    public String operation() {
      return "content.revise";
    }
  }

  record TypeList(Selection selection, Boolean drafts, Integer limit, Integer offset)
      implements InformationRequest {
    public TypeList {
      Objects.requireNonNull(selection);
      page(limit, offset, 100);
    }

    @Override
    public String operation() {
      return "types.list";
    }
  }

  record TypeRead(Selection selection, UUID version, UUID attachedRevision, Boolean publishedOnly)
      implements InformationRequest {
    public TypeRead(Selection selection, UUID version, UUID attachedRevision) {
      this(selection, version, attachedRevision, null);
    }

    public TypeRead {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(version);
    }

    @Override
    public String operation() {
      return "types.get";
    }
  }

  record TypeDraft(
      Selection selection,
      UUID requestId,
      UUID type,
      Long expectedControlRevision,
      String slug,
      String label,
      String description,
      SchemaDefinition schema,
      SchemaValue.ObjectValue presentation)
      implements InformationRequest {
    public TypeDraft {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(requestId);
      if (type == null) {
        InformationContent.slug(slug);
        label = InformationContent.text(label, "label", 120, true);
        description = InformationContent.text(description, "description", 4096, false);
      } else if (slug != null || label != null || description != null)
        throw new IllegalArgumentException("existing type identity is immutable");
      DocumentMetadata.definition(schema);
      presentation =
          DocumentMetadata.presentation(
              schema, presentation == null ? DocumentMetadata.empty() : presentation);
      if ((type != null) != (expectedControlRevision != null)
          || expectedControlRevision != null && expectedControlRevision < 1)
        throw new IllegalArgumentException("existing type needs expected control revision");
    }

    @Override
    public String operation() {
      return "types.version.create";
    }
  }

  record TypeTransition(
      Selection selection,
      UUID requestId,
      UUID version,
      long expectedControlRevision,
      boolean activate)
      implements InformationRequest {
    public TypeTransition {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(version);
      if (expectedControlRevision < 1)
        throw new IllegalArgumentException("positive control revision required");
    }

    @Override
    public String operation() {
      return activate ? "types.activate" : "types.deprecate";
    }
  }

  record TypeValidate(
      Selection selection,
      UUID version,
      UUID attachedRevision,
      SchemaValue.ObjectValue metadata,
      Boolean publishedOnly)
      implements InformationRequest {
    public TypeValidate(
        Selection selection,
        UUID version,
        UUID attachedRevision,
        SchemaValue.ObjectValue metadata) {
      this(selection, version, attachedRevision, metadata, null);
    }

    public TypeValidate {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(version);
      metadata = DocumentMetadata.instance(metadata);
    }

    @Override
    public String operation() {
      return "types.validate";
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

  /** Guarded user-tag replacement; tags are resource state, independent of revision heads. */
  record TagsReplace(
      Selection selection,
      UUID revision,
      UUID requestId,
      List<String> tags,
      Long expectedTagsVersion)
      implements InformationRequest {
    public TagsReplace {
      Objects.requireNonNull(selection);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(expectedTagsVersion);
      if (expectedTagsVersion < 0 || expectedTagsVersion > 9007199254740991L)
        throw new IllegalArgumentException("invalid expected tag version");
      tags = InformationRequest.tags(tags);
    }

    public String operation() {
      return "tags.replace";
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

  record Retry(
      Selection selection, UUID revision, UUID acquisition, UUID requestId, List<String> stages)
      implements InformationRequest {
    public Retry(Selection selection, UUID revision, UUID acquisition, UUID requestId) {
      this(selection, revision, acquisition, requestId, null);
    }

    public Retry {
      stages = processingStages(stages);
      Objects.requireNonNull(selection, "selection");
      new Reference(revision, acquisition);
      if (revision != null) Objects.requireNonNull(requestId, "requestId");
    }

    @Override
    public String operation() {
      return "retry";
    }
  }

  record Rebuild(
      Selection selection, UUID revision, UUID requestId, String stage, List<String> stages)
      implements InformationRequest {
    public Rebuild(Selection selection, UUID revision, UUID requestId, String stage) {
      this(selection, revision, requestId, stage, null);
    }

    public Rebuild {
      stages = processingStages(stages);
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
      if (!Set.of("personal", "project", "global_read").contains(visibility)
          || "project".equals(visibility) != (collectionProject != null))
        throw new IllegalArgumentException("invalid adoption destination");
    }

    @Override
    public String operation() {
      return "migration.adopt";
    }
  }

  record NavigationCapabilities(Selection selection) implements InformationRequest {
    public NavigationCapabilities {
      navigation(selection);
    }

    public String operation() {
      return "navigation.capabilities";
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record DocumentList(
      Selection selection,
      UUID relatedTo,
      String relation,
      String direction,
      Integer limit,
      Integer offset,
      String projection)
      implements InformationRequest {
    public DocumentList(
        Selection selection,
        UUID relatedTo,
        String relation,
        String direction,
        Integer limit,
        Integer offset) {
      this(selection, relatedTo, relation, direction, limit, offset, null);
    }

    public DocumentList {
      navigation(selection);
      if (projection != null
          && (!InformationNavigation.METADATA_PROJECTION.equals(projection)
              || !InformationContent.VIEW.equals(selection.view())))
        throw new IllegalArgumentException("Metadata projection requires its supporting view");
      page(limit, offset, 100);
      if (relatedTo == null && (relation != null || direction != null))
        throw new IllegalArgumentException("traversal needs a source");
      if (relation != null) InformationNavigation.relation(relation);
      if (direction != null) InformationNavigation.direction(direction);
    }

    public String operation() {
      return "documents.list";
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record DocumentFacets(Selection selection, UUID relatedTo, String relation, String direction)
      implements InformationRequest {
    public DocumentFacets {
      navigation(selection);
      if (relatedTo == null && (relation != null || direction != null))
        throw new IllegalArgumentException("traversal needs a source");
      if (relation != null) InformationNavigation.relation(relation);
      if (direction != null) InformationNavigation.direction(direction);
    }

    public String operation() {
      return "documents.facets";
    }
  }

  record DocumentGet(Selection selection, InformationNavigation.Target target)
      implements InformationRequest {
    public DocumentGet {
      navigation(selection);
      Objects.requireNonNull(target);
    }

    public String operation() {
      return "documents.get";
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  record RelationshipList(
      Selection selection,
      UUID resource,
      String direction,
      String relation,
      UUID otherResource,
      Integer limit,
      Integer offset)
      implements InformationRequest {
    public RelationshipList {
      navigation(selection);
      Objects.requireNonNull(resource);
      InformationNavigation.direction(direction);
      if (relation != null) InformationNavigation.relation(relation);
      page(limit, offset, 100);
    }

    public String operation() {
      return "relationships.list";
    }
  }

  record RelationshipCreate(
      Selection selection,
      UUID requestId,
      UUID source,
      InformationNavigation.Target target,
      String relation,
      UUID expectedSourceRevision)
      implements InformationRequest {
    public RelationshipCreate {
      navigation(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(source);
      Objects.requireNonNull(target);
      Objects.requireNonNull(expectedSourceRevision);
      InformationNavigation.relation(relation);
    }

    public String operation() {
      return "relationships.create";
    }
  }

  record RelationshipRemove(
      Selection selection, UUID requestId, UUID relationship, Long expectedVersion)
      implements InformationRequest {
    public RelationshipRemove {
      navigation(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(relationship);
      control(expectedVersion, false);
    }

    public String operation() {
      return "relationships.remove";
    }
  }

  record NavigationPolicyGet(Selection selection, InformationNavigation.PolicyTarget target)
      implements InformationRequest {
    public NavigationPolicyGet {
      navigation(selection);
      Objects.requireNonNull(target);
    }

    public String operation() {
      return "policy.get";
    }
  }

  record NavigationPolicySet(
      Selection selection,
      UUID requestId,
      InformationNavigation.PolicyTarget target,
      Long expectedVersion,
      InformationNavigation.PolicySettings settings,
      String reason)
      implements InformationRequest {
    public NavigationPolicySet {
      navigation(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(target);
      control(expectedVersion, true);
      Objects.requireNonNull(settings);
      reason = ContractValues.text(reason, "reason", 4096, true);
    }

    public String operation() {
      return "policy.set";
    }
  }

  record NavigationViews(Selection selection) implements InformationRequest {
    public NavigationViews {
      navigation(selection);
    }

    public String operation() {
      return "views.list";
    }
  }

  record NavigationViewGet(Selection selection, UUID id) implements InformationRequest {
    public NavigationViewGet {
      navigation(selection);
      Objects.requireNonNull(id);
    }

    public String operation() {
      return "views.get";
    }
  }

  record NavigationViewSave(
      Selection selection,
      UUID requestId,
      UUID id,
      Long expectedVersion,
      String label,
      InformationNavigation.Path path)
      implements InformationRequest {
    public NavigationViewSave {
      navigation(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(id);
      control(expectedVersion, true);
      label = ContractValues.identity(label, "label", 120);
      Objects.requireNonNull(path);
    }

    public String operation() {
      return "views.save";
    }
  }

  record NavigationViewDelete(Selection selection, UUID requestId, UUID id, Long expectedVersion)
      implements InformationRequest {
    public NavigationViewDelete {
      navigation(selection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(id);
      control(expectedVersion, false);
    }

    public String operation() {
      return "views.delete";
    }
  }

  private static void navigation(Selection selection) {
    Objects.requireNonNull(selection);
    if (!"documents".equals(selection.corpus()))
      throw new IllegalArgumentException("document navigation requires documents corpus");
  }

  private static void control(Long version, boolean create) {
    if (version == null || version < (create ? 0 : 1) || version > 9007199254740990L)
      throw new IllegalArgumentException("invalid expected version");
  }

  private static void authored(
      Selection selection, UUID expected, InformationContent.Typing typing) {
    if (expected != null && typing == null)
      throw new IllegalArgumentException("expectedRevision requires explicit typing");
    if (typing != null
        && (!InformationContent.VIEW.equals(selection.view())
            || !"documents".equals(selection.corpus())))
      throw new IllegalArgumentException("typed writes require the supporting document view");
  }

  private static List<String> processingStages(List<String> stages) {
    if (stages == null) return null;
    if (stages.isEmpty()
        || stages.size() > 7
        || stages.stream().distinct().count() != stages.size()
        || stages.stream()
            .anyMatch(
                stage ->
                    !Set.of(
                            "extract",
                            "derive",
                            "embed",
                            "summarise",
                            "summary_embed",
                            "autoTag",
                            "tagGroups")
                        .contains(stage)))
      throw new IllegalArgumentException("invalid selected processing stages");
    return List.copyOf(stages);
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
