package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Portable data is untrusted provenance, never an authority or a filesystem instruction. */
public final class InformationInterchange {
  private InformationInterchange() {}

  public static final String FORMAT = "plowshare-information-bundle/1";
  public static final int MAX_DOCUMENTS = 16;
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
  public static final String COVERAGE = "included-endpoints-only";

  public record Capabilities(String format, int maxDocuments, int maxBytes, int maxJsonBytes) {
    public Capabilities {
      if (!FORMAT.equals(format)
          || maxDocuments != MAX_DOCUMENTS
          || maxBytes != MAX_BYTES
          || maxJsonBytes != MAX_JSON_BYTES)
        throw new IllegalArgumentException("Unsupported interchange limits");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Evidence(UUID id, UUID revision, int start, int end, String quote, String locator) {
    public Evidence {
      Objects.requireNonNull(id);
      Objects.requireNonNull(revision);
      if (start < 0 || end <= start)
        throw new IllegalArgumentException("Invalid foreign evidence span");
      quote = text(quote, "quote", 65536, true);
      if (quote.length() != end - start)
        throw new IllegalArgumentException("Foreign quote span differs");
      if (locator != null) locator = text(locator, "locator", 4096, false);
    }
  }

  public record OriginReference(
      UUID bundle,
      String manifestHash,
      UUID resource,
      UUID revision,
      String owner,
      String sourceHash) {
    public OriginReference {
      Objects.requireNonNull(bundle);
      Objects.requireNonNull(resource);
      Objects.requireNonNull(revision);
      manifestHash = InformationContent.digest(manifestHash);
      sourceHash = InformationContent.digest(sourceHash);
      owner = text(owner, "origin owner", 1024, true);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Provenance(
      String owner,
      String kind,
      String createdAt,
      String sourceHash,
      List<UUID> dependencies,
      List<Evidence> evidence,
      OriginReference importedFrom,
      String producer,
      String producerDefinitionHash) {
    public Provenance {
      owner = text(owner, "foreign owner", 1024, true);
      if (producer != null) producer = text(producer, "foreign producer", 1024, true);
      if (producerDefinitionHash != null) {
        producerDefinitionHash = InformationContent.digest(producerDefinitionHash);
        if (producer == null) throw new IllegalArgumentException("Producer hash requires producer");
      }
      Instant.parse(createdAt);
      sourceHash = InformationContent.digest(sourceHash);
      if (!Set.of("source", "report").contains(kind))
        throw new IllegalArgumentException("Invalid foreign kind");
      dependencies = unique(dependencies, MAX_DOCUMENTS, "dependencies");
      evidence = List.copyOf(evidence);
      if (evidence.size() > 64) throw new IllegalArgumentException("Too much foreign evidence");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Descriptor(
      UUID resource,
      UUID revision,
      int ordinal,
      String sourceName,
      String title,
      String path,
      String mediaType,
      String contentHash,
      List<String> tags,
      InformationContent.Binding contentType,
      Provenance provenance) {
    public Descriptor {
      Objects.requireNonNull(resource);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(provenance);
      if (ordinal < 1) throw new IllegalArgumentException("Positive foreign ordinal required");
      sourceName = text(sourceName, "foreign source name", 1024, true);
      title = text(title, "foreign title", 32768, false);
      path = safePath(path);
      if (mediaType == null
          || !mediaType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[ -~]{1,128})?")
          || mediaType.length() > 256)
        throw new IllegalArgumentException("Invalid portable media type");
      contentHash = InformationContent.digest(contentHash);
      tags =
          ContractValues.list(tags, "tags", 32).stream()
              .map(
                  value ->
                      ContractValues.identity(value, "tag", 64)
                          .toLowerCase(Locale.ROOT)
                          .replaceAll("\\s+", " "))
              .distinct()
              .sorted()
              .toList();
    }
  }

  public record Document(Descriptor descriptor, String bytesBase64) {
    public Document {
      Objects.requireNonNull(descriptor);
      byte[] bytes = bytes(bytesBase64);
      if (!hash(bytes).equals(descriptor.contentHash()))
        throw new IllegalArgumentException("Portable content hash differs from bytes");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Link(UUID source, UUID target, UUID pinnedRevision, String relation) {
    public Link {
      Objects.requireNonNull(source);
      Objects.requireNonNull(target);
      if (relation == null || !relation.matches("[a-z][a-z0-9_-]{0,63}"))
        throw new IllegalArgumentException("Invalid portable relation");
    }
  }

  public record Bundle(
      String format,
      UUID id,
      List<Document> documents,
      List<Link> relationships,
      String relationshipCoverage) {
    public Bundle {
      if (!FORMAT.equals(format) || !COVERAGE.equals(relationshipCoverage))
        throw new IllegalArgumentException("Unsupported portable format/coverage");
      Objects.requireNonNull(id);
      documents = List.copyOf(documents);
      relationships = List.copyOf(relationships);
      if (documents.isEmpty() || documents.size() > MAX_DOCUMENTS || relationships.size() > 64)
        throw new IllegalArgumentException("Portable bundle exceeds record bounds");
      var revisions = new HashMap<UUID, Descriptor>();
      var paths = new HashSet<String>();
      var ordinals = new HashSet<String>();
      var resources = new HashSet<UUID>();
      int size = 0;
      for (var doc : documents) {
        var d = doc.descriptor();
        if (revisions.put(d.revision(), d) != null
            || !paths.add(d.path().toLowerCase(Locale.ROOT))
            || !ordinals.add(d.resource() + ":" + d.ordinal()))
          throw new IllegalArgumentException("Duplicate portable identity or path");
        resources.add(d.resource());
        size += bytes(doc.bytesBase64()).length;
      }
      if (paths.stream()
          .anyMatch(path -> paths.stream().anyMatch(other -> other.startsWith(path + "/"))))
        throw new IllegalArgumentException("Portable file/directory path collision");
      if (size > MAX_BYTES)
        throw new IllegalArgumentException("Portable bundle exceeds byte bound");
      for (var d : revisions.values()) {
        if (d.provenance().dependencies().contains(d.revision()))
          throw new IllegalArgumentException("A portable revision cannot depend on itself");
        if (!revisions.keySet().containsAll(d.provenance().dependencies()))
          throw new IllegalArgumentException("Portable dependency missing");
        for (var e : d.provenance().evidence())
          if (!revisions.containsKey(e.revision()))
            throw new IllegalArgumentException("Portable evidence source missing");
      }
      var edges = new HashSet<Link>();
      for (var link : relationships) {
        if (!edges.add(link)
            || !resources.containsAll(List.of(link.source(), link.target()))
            || link.pinnedRevision() != null
                && (!revisions.containsKey(link.pinnedRevision())
                    || !revisions.get(link.pinnedRevision()).resource().equals(link.target())))
          throw new IllegalArgumentException("Invalid portable relationship endpoints");
      }
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Destination(UUID resource, String name, String mode, UUID expectedRevision) {
    public Destination {
      Objects.requireNonNull(resource);
      name = ContractValues.identity(name, "destination name", 1024);
      if (!Set.of("create", "revise").contains(mode)
          || mode.equals("revise") != (expectedRevision != null))
        throw new IllegalArgumentException(
            "Choose create or revise against an exact current revision");
    }
  }

  public record TypeMapping(UUID foreignVersion, UUID localVersion) {
    public TypeMapping {
      Objects.requireNonNull(foreignVersion);
      Objects.requireNonNull(localVersion);
    }
  }

  public record Reuse(UUID foreignRevision, UUID localRevision) {
    public Reuse {
      Objects.requireNonNull(foreignRevision);
      Objects.requireNonNull(localRevision);
    }
  }

  public record ImportPlan(
      Bundle bundle,
      List<Destination> destinations,
      List<TypeMapping> types,
      List<Reuse> reuse,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          boolean restoreRelationships) {
    public ImportPlan(Bundle bundle, List<Destination> destinations, List<TypeMapping> types) {
      this(bundle, destinations, types, List.of(), true);
    }

    public ImportPlan {
      Objects.requireNonNull(bundle);
      destinations = List.copyOf(destinations);
      types = List.copyOf(types);
      reuse = List.copyOf(reuse);
      if (reuse.size() > MAX_DOCUMENTS || types.size() > MAX_DOCUMENTS)
        throw new IllegalArgumentException("Too many import mappings");
      var reused = new HashSet<UUID>();
      var local = new HashSet<UUID>();
      var all = new HashSet<UUID>();
      for (var doc : bundle.documents()) all.add(doc.descriptor().revision());
      for (var item : reuse)
        if (!all.contains(item.foreignRevision())
            || !reused.add(item.foreignRevision())
            || !local.add(item.localRevision()))
          throw new IllegalArgumentException("Invalid explicit revision reuse");
      var expected = new HashSet<UUID>();
      for (var d : bundle.documents())
        if (!reused.contains(d.descriptor().revision())) expected.add(d.descriptor().resource());
      if (expected.isEmpty())
        throw new IllegalArgumentException("Import must admit at least one new revision");
      var selected = new HashSet<UUID>();
      var names = new HashSet<String>();
      for (var d : destinations)
        if (!selected.add(d.resource()) || !names.add(d.name()))
          throw new IllegalArgumentException("Duplicate import destination");
      if (!expected.equals(selected))
        throw new IllegalArgumentException(
            "Each imported resource needs exactly one explicit destination");
      var versions = new HashSet<UUID>();
      for (var t : types)
        if (!versions.add(t.foreignVersion()))
          throw new IllegalArgumentException("Duplicate type mapping");
      if (restoreRelationships)
        for (var link : bundle.relationships())
          if (link.pinnedRevision() != null) {
            var pinned =
                bundle.documents().stream()
                    .map(Document::descriptor)
                    .filter(d -> d.revision().equals(link.pinnedRevision()))
                    .findFirst()
                    .orElseThrow();
            if (!pinned.contentHash().equals(pinned.provenance().sourceHash()))
              throw new IllegalArgumentException(
                  "Cannot restore an exact foreign pin to edited bytes; omit relationships or include its unchanged revision");
          }
      if (restoreRelationships
          && bundle.relationships().stream().anyMatch(link -> !selected.contains(link.source())))
        throw new IllegalArgumentException(
            "Relationship restoration requires an imported source; choose omission for reused sources");
    }
  }

  public record Mapping(UUID foreignResource, UUID foreignRevision, UUID resource, UUID revision) {
    public Mapping {
      Objects.requireNonNull(foreignResource);
      Objects.requireNonNull(foreignRevision);
      Objects.requireNonNull(resource);
      Objects.requireNonNull(revision);
    }
  }

  public record Imported(UUID bundle, List<Mapping> mappings) {
    public Imported {
      Objects.requireNonNull(bundle);
      mappings = List.copyOf(mappings);
      if (mappings.isEmpty() || mappings.size() > MAX_DOCUMENTS)
        throw new IllegalArgumentException("Invalid import receipt");
    }
  }

  public record Origin(UUID bundle, String manifestHash, Descriptor foreignDocument) {
    public Origin {
      Objects.requireNonNull(bundle);
      manifestHash = InformationContent.digest(manifestHash);
      Objects.requireNonNull(foreignDocument);
    }
  }

  public record Readable(
      String format,
      String path,
      String representation,
      String markdown,
      String textHash,
      Descriptor sidecar) {
    public Readable {
      if (!FORMAT.equals(format)
          || !Set.of("original_markdown", "extraction").contains(representation))
        throw new IllegalArgumentException("Invalid readable representation");
      path = safePath(path);
      markdown = text(Objects.requireNonNull(markdown), "readable body", MAX_BYTES, false);
      textHash = InformationContent.digest(textHash);
      Objects.requireNonNull(sidecar);
      if (!hash(markdown.getBytes(StandardCharsets.UTF_8)).equals(textHash))
        throw new IllegalArgumentException("Readable text hash differs");
    }
  }

  public static byte[] bytes(String value) {
    if (value == null || value.length() > 4 * ((MAX_BYTES + 2) / 3))
      throw new IllegalArgumentException("Portable bytes exceed bounds");
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid portable base64");
    }
    if (decoded.length == 0
        || decoded.length > MAX_BYTES
        || !Base64.getEncoder().encodeToString(decoded).equals(value))
      throw new IllegalArgumentException("Portable base64 must be canonical and nonempty");
    return decoded;
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String safePath(String path) {
    if (path == null
        || path.length() > 240
        || !path.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")
        || path.endsWith("/"))
      throw new IllegalArgumentException("Portable path must be a safe relative path");
    for (String part : path.split("/", -1))
      if (part.isEmpty()
          || part.equals(".")
          || part.equals("..")
          || Set.of(".git", ".hg", ".svn").contains(part.toLowerCase(Locale.ROOT))
          || part.endsWith(".")
          || part.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?"))
        throw new IllegalArgumentException("Unsafe portable path segment");
    return path;
  }

  private static String text(String value, String field, int limit, boolean required) {
    return InformationContent.text(value, field, limit, required);
  }

  private static <T> List<T> unique(List<T> values, int limit, String field) {
    var result = List.copyOf(values);
    if (result.size() > limit || new HashSet<>(result).size() != result.size())
      throw new IllegalArgumentException("Invalid portable " + field);
    return result;
  }
}
