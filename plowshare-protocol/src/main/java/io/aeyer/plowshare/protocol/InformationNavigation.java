package io.aeyer.plowshare.protocol;

import java.util.*;

/**
 * Document identities and policy controls, separate from immutable revision and collection APIs.
 */
public final class InformationNavigation {
  public static final String METADATA_PROJECTION = "metadata-v1";
  public static final int METADATA_PAGE_BYTES = 524288;
  public static final String CAPABILITY = "document-navigation-v1";
  public static final int MAX_CLOSURE = 1024;
  public static final int MAX_STEPS = 32;

  private InformationNavigation() {}

  public record Capabilities(
      String capability, boolean writesEnabled, int maxSteps, int maxClosure) {
    public Capabilities {
      if (!CAPABILITY.equals(capability) || maxSteps != MAX_STEPS || maxClosure != MAX_CLOSURE)
        throw new IllegalArgumentException("unsupported navigation capability");
    }
  }

  /**
   * True values tighten access; a reference label is an explicitly approved public-to-source label.
   */
  public record Policy(
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) boolean ownerOnly,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) boolean referenceHidden,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) boolean propagate) {
    public static final Policy DEFAULT = new Policy(false, true, true);
    public static final Policy NONE = new Policy(false, false, false);

    public Policy constrain(Policy mandatory) {
      Objects.requireNonNull(mandatory);
      return new Policy(
          ownerOnly || mandatory.ownerOnly,
          referenceHidden || mandatory.referenceHidden,
          propagate || mandatory.propagate);
    }

    public boolean widens(Policy old) {
      return old.ownerOnly && !ownerOnly
          || old.referenceHidden && !referenceHidden
          || old.propagate && !propagate;
    }
  }

  public record PolicySettings(
      Policy defaults,
      Policy mandatory,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) boolean allowOverrides,
      String referenceLabel) {
    public PolicySettings {
      Objects.requireNonNull(defaults);
      Objects.requireNonNull(mandatory);
      referenceLabel = ContractValues.identity(referenceLabel, "referenceLabel", 120);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record PolicyTarget(String kind, String project, UUID resource) {
    public PolicyTarget {
      if (!Set.of("platform", "project", "document").contains(kind))
        throw new IllegalArgumentException("invalid policy target");
      project = ContractValues.optionalIdentity(project, "project", 1024);
      if ((kind.equals("project")) != (project != null)
          || kind.equals("document") != (resource != null))
        throw new IllegalArgumentException("policy target needs only its scope identifier");
    }
  }

  /** Version zero denotes an inherited, unconfigured target. */
  public record PolicyView(
      PolicyTarget target,
      long version,
      PolicySettings settings,
      Policy effective,
      boolean canManage,
      long retainedObligations) {
    public PolicyView {
      Objects.requireNonNull(target);
      Objects.requireNonNull(settings);
      Objects.requireNonNull(effective);
      if (version < 0 || retainedObligations < 0)
        throw new IllegalArgumentException("invalid policy controls");
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Target(UUID resource, UUID revision) {
    public Target {
      Objects.requireNonNull(resource);
    }
  }

  /**
   * Discovery metadata, with a compact typed snapshot only when explicitly projected by list.
   * Missing typing deliberately does not distinguish untyped and redacted bindings.
   */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Document(
      UUID resource,
      UUID revision,
      String title,
      String sourceName,
      List<String> tags,
      InformationRequest.Scope scope,
      String owner,
      InformationContent.Metadata contentMetadata) {
    public Document(
        UUID resource,
        UUID revision,
        String title,
        String sourceName,
        List<String> tags,
        InformationRequest.Scope scope,
        String owner) {
      this(resource, revision, title, sourceName, tags, scope, owner, null);
    }

    public Document {
      Objects.requireNonNull(resource);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(owner);
      title = ContractValues.text(title, "title", 8192, true);
      sourceName = ContractValues.identity(sourceName, "sourceName", 4096);
      tags = ContractValues.list(tags, "tags", 32);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Documents(List<Document> documents, int limit, int offset, String projection) {
    public Documents(List<Document> documents, int limit, int offset) {
      this(documents, limit, offset, null);
    }

    public Documents {
      documents = ContractValues.list(documents, "documents", 100);
      page(limit, offset);
      if (projection != null && !METADATA_PROJECTION.equals(projection))
        throw new IllegalArgumentException("Unsupported document projection");
      if (projection == null && documents.stream().anyMatch(d -> d.contentMetadata() != null))
        throw new IllegalArgumentException("Typed snapshots require a metadata projection");
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Relationship(
      UUID id,
      UUID source,
      UUID target,
      UUID pinnedRevision,
      String relation,
      long version,
      boolean removed) {
    public Relationship {
      Objects.requireNonNull(id);
      Objects.requireNonNull(source);
      Objects.requireNonNull(target);
      relation = InformationNavigation.relation(relation);
      if (version < 1) throw new IllegalArgumentException("invalid relationship version");
    }
  }

  /**
   * A closed variant: locked references have an approved label but no revision, title or metadata.
   */
  @com.fasterxml.jackson.annotation.JsonTypeInfo(
      use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
      property = "kind")
  @com.fasterxml.jackson.annotation.JsonSubTypes({
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Readable.class, name = "readable"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Locked.class, name = "locked"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
        value = Unavailable.class,
        name = "unavailable")
  })
  public sealed interface Selection permits Readable, Locked, Unavailable {}

  public record Readable(Document document) implements Selection {
    public Readable {
      Objects.requireNonNull(document);
    }
  }

  public record Locked(UUID resource, String label) implements Selection {
    public Locked {
      Objects.requireNonNull(resource);
      label = ContractValues.identity(label, "label", 120);
    }
  }

  public record Unavailable() implements Selection {}

  public record Connection(Relationship relationship, Selection selection) {
    public Connection {
      Objects.requireNonNull(relationship);
      Objects.requireNonNull(selection);
    }
  }

  public record Connections(List<Connection> connections, int limit, int offset) {
    public Connections {
      connections = ContractValues.list(connections, "connections", 100);
      page(limit, offset);
    }
  }

  public record Changed(UUID id, long version, boolean retainedObligations) {
    public Changed {
      Objects.requireNonNull(id);
      if (version < 1) throw new IllegalArgumentException("invalid control version");
    }
  }

  /** Each step has exactly the fields of its alternative, not an executable expression. */
  @com.fasterxml.jackson.annotation.JsonTypeInfo(
      use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,
      property = "kind")
  @com.fasterxml.jackson.annotation.JsonSubTypes({
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = ScopeStep.class, name = "scope"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
        value = DocumentStep.class,
        name = "document"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(
        value = TraverseStep.class,
        name = "traverse"),
    @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = FilterStep.class, name = "filter")
  })
  public sealed interface Step permits ScopeStep, DocumentStep, TraverseStep, FilterStep {}

  public record ScopeStep(InformationRequest.Scope scope) implements Step {
    public ScopeStep {
      Objects.requireNonNull(scope);
    }
  }

  public record DocumentStep(Target target) implements Step {
    public DocumentStep {
      Objects.requireNonNull(target);
    }
  }

  public record TraverseStep(String relation, String direction) implements Step {
    public TraverseStep {
      relation = InformationNavigation.relation(relation);
      direction = InformationNavigation.direction(direction);
    }
  }

  public record FilterStep(InformationRequest.FacetFilter filter) implements Step {
    public FilterStep {
      Objects.requireNonNull(filter);
    }
  }

  public record Path(List<Step> steps) {
    public Path {
      steps = ContractValues.list(steps, "steps", MAX_STEPS);
      if (steps.isEmpty() || !(steps.getFirst() instanceof ScopeStep))
        throw new IllegalArgumentException("path must start with a scope");
      if (steps.stream().skip(1).anyMatch(s -> s instanceof ScopeStep))
        throw new IllegalArgumentException("path has one scope");
    }
  }

  public record SavedView(UUID id, String label, Path path, long version, boolean deleted) {
    public SavedView {
      Objects.requireNonNull(id);
      Objects.requireNonNull(path);
      label = ContractValues.identity(label, "label", 120);
      if (version < 1) throw new IllegalArgumentException("invalid saved view version");
    }
  }

  public record Views(List<SavedView> views) {
    public Views {
      views = ContractValues.list(views, "views", 100);
    }
  }

  private static void page(int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0)
      throw new IllegalArgumentException("invalid page bounds");
  }

  public static String relation(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}"))
      throw new IllegalArgumentException("invalid relationship name");
    return value;
  }

  public static String direction(String value) {
    if (!Set.of("outgoing", "backlinks").contains(value))
      throw new IllegalArgumentException("invalid relationship direction");
    return value;
  }
}
