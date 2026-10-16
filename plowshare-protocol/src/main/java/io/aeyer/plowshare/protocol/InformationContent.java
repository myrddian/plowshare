package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authored-information value contracts. Identity and metadata never confer resource authority. */
public final class InformationContent {
  private InformationContent() {}

  public static final String CAPABILITY = "information.contentTypes.v1";
  public static final String VIEW = "content-types-v1";

  public record Capabilities(String capability, String view, String profile, boolean typedWrites) {
    public Capabilities {
      if (!CAPABILITY.equals(capability)
          || !VIEW.equals(view)
          || !DocumentMetadata.PROFILE.equals(profile))
        throw new IllegalArgumentException("Unsupported authored-information capability");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Typing(String mode, UUID version, SchemaValue.ObjectValue metadata) {
    public Typing {
      if (!List.of("inherit", "set", "clear").contains(mode))
        throw new IllegalArgumentException("Typing mode must be inherit, set or clear");
      if (mode.equals("set") && (version == null || metadata == null)
          || mode.equals("inherit") && version != null
          || mode.equals("clear") && (version != null || metadata != null))
        throw new IllegalArgumentException("Invalid typing alternative");
      if (metadata != null) metadata = DocumentMetadata.instance(metadata);
    }
  }

  public record Identity(
      UUID type, UUID version, int ordinal, String slug, String label, String definitionHash) {
    public Identity {
      Objects.requireNonNull(type);
      Objects.requireNonNull(version);
      if (ordinal < 1) throw new IllegalArgumentException("Positive version required");
      slug = InformationContent.slug(slug);
      label = text(label, "label", 120, true);
      definitionHash = digest(definitionHash);
    }
  }

  /** Immutable contract visible through either the registry or an authorised attached revision. */
  public record Contract(
      UUID type,
      UUID version,
      int ordinal,
      String slug,
      String label,
      String description,
      String profile,
      String definitionHash,
      SchemaDefinition schema,
      SchemaValue.ObjectValue presentation) {
    public Contract {
      Objects.requireNonNull(type);
      Objects.requireNonNull(version);
      if (ordinal < 1) throw new IllegalArgumentException("Positive type version required");
      slug = InformationContent.slug(slug);
      label = text(label, "label", 120, true);
      description = text(description, "description", 4096, false);
      if (!DocumentMetadata.PROFILE.equals(profile))
        throw new IllegalArgumentException("Unsupported document profile");
      definitionHash = digest(definitionHash);
      DocumentMetadata.definition(schema);
      presentation = DocumentMetadata.presentation(schema, presentation);
      if (!definitionHash.equals(SchemaCodec.documentFingerprint(schema, profile, presentation)))
        throw new IllegalArgumentException("Document contract hash differs from definition");
    }
  }

  /** Lifecycle/control state is separate from the immutable definition and attached snapshot. */
  public record Version(Contract contract, String state, long controlRevision, String createdAt) {
    public Version {
      Objects.requireNonNull(contract);
      Objects.requireNonNull(createdAt);
      Instant.parse(createdAt);
      if (!List.of("draft", "active", "deprecated").contains(state) || controlRevision < 1)
        throw new IllegalArgumentException("Invalid content type lifecycle");
    }
  }

  public record Versions(List<Version> results, boolean hasMore) {
    public Versions {
      results = List.copyOf(results);
      if (results.size() > 100) throw new IllegalArgumentException("Too many content types");
    }
  }

  /** Complete immutable instance binding; an absent binding means an untyped revision. */
  public record Binding(Contract contract, String metadataHash, SchemaValue.ObjectValue metadata) {
    public Binding {
      Objects.requireNonNull(contract);
      metadata = DocumentMetadata.instance(metadata);
      metadataHash = digest(metadataHash);
      if (!metadataHash.equals(SchemaCodec.fingerprint(metadata)))
        throw new IllegalArgumentException("Metadata hash differs from instance");
      if (DocumentMetadata.validate(contract.schema(), metadata).status()
          != SchemaValidation.Status.VALID)
        throw new IllegalArgumentException(
            "Bound metadata does not conform to its immutable contract");
    }
  }

  public record Reuse(String stage, UUID revision, long generation, String fingerprint) {
    public Reuse {
      if (!List.of(
                  "extract",
                  "derive",
                  "embed",
                  "summarise",
                  "summary_embed",
                  "autoTag",
                  "tagGroups")
              .contains(stage)
          || generation < 1) throw new IllegalArgumentException("Invalid projection origin");
      Objects.requireNonNull(revision);
      fingerprint = digest(fingerprint);
    }
  }

  /** Exact validated typing and reuse identity shown to transition hooks before publication. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Candidate(
      UUID expectedRevision,
      Typing typing,
      Binding contentType,
      String processingPolicy,
      String manifestHash,
      List<String> reusedStages) {
    public Candidate {
      Objects.requireNonNull(typing);
      if (!List.of("automatic", "explicit_only").contains(processingPolicy))
        throw new IllegalArgumentException("Invalid candidate processing policy");
      if (manifestHash != null) manifestHash = digest(manifestHash);
      reusedStages = List.copyOf(reusedStages);
      if (reusedStages.size() > 7) throw new IllegalArgumentException("Too many candidate stages");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record View(
      UUID revision,
      Binding contentType,
      UUID startingRevision,
      String revisedBy,
      String processingPolicy,
      List<Reuse> reused,
      List<String> authorisedStages) {
    public View {
      Objects.requireNonNull(revision);
      if (!List.of("automatic", "explicit_only").contains(processingPolicy))
        throw new IllegalArgumentException("Invalid processing policy");
      revisedBy = revisedBy == null ? null : text(revisedBy, "reviser", 1024, true);
      reused = List.copyOf(reused);
      authorisedStages = List.copyOf(authorisedStages);
      if (reused.size() > 7 || authorisedStages.size() > 7)
        throw new IllegalArgumentException("Too many processing stages");
      for (String stage : authorisedStages)
        if (!List.of(
                "extract", "derive", "embed", "summarise", "summary_embed", "autoTag", "tagGroups")
            .contains(stage)) throw new IllegalArgumentException("Invalid authorised stage");
    }
  }

  public static String slug(String value) {
    if (value == null || !value.matches("[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?"))
      throw new IllegalArgumentException(
          "Content type slug must be 1–64 lowercase identifier characters");
    return value;
  }

  public static String text(String value, String field, int maximum, boolean required) {
    if (value == null && !required) return "";
    if (value == null
        || value.length() > maximum
        || value.indexOf('\0') >= 0
        || required && value.isBlank())
      throw new IllegalArgumentException("Invalid content type " + field);
    return value;
  }

  public static String digest(String value) {
    if (value == null || !value.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("SHA256 hash required");
    return value;
  }
}
