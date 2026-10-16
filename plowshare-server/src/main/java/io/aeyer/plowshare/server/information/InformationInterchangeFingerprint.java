package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.InformationInterchange.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Receipt identity is independent of JSON object key ordering across SDKs. Validated content,
 * definition and metadata hashes cover the omitted bytes/maps; every remaining claim is retained.
 * List ordering remains part of the submitted plan. These encodings are versioned, not signatures.
 */
final class InformationInterchangeFingerprint {
  private InformationInterchangeFingerprint() {}

  private record Binding(
      UUID type,
      UUID version,
      int ordinal,
      String slug,
      String label,
      String description,
      String profile,
      String definitionHash,
      String metadataHash) {}

  private record DescriptorIdentity(
      UUID resource,
      UUID revision,
      int ordinal,
      String sourceName,
      String title,
      String path,
      String mediaType,
      String contentHash,
      List<String> tags,
      Provenance provenance,
      Binding binding) {}

  private record BundleIdentity(
      String format,
      UUID id,
      List<DescriptorIdentity> documents,
      List<Link> relationships,
      String relationshipCoverage) {}

  private record PlanIdentity(
      BundleIdentity bundle,
      List<Destination> destinations,
      List<TypeMapping> types,
      List<Reuse> reuse,
      boolean restoreRelationships) {}

  private static Binding binding(InformationContent.Binding value) {
    if (value == null) return null;
    var c = value.contract();
    return new Binding(
        c.type(),
        c.version(),
        c.ordinal(),
        c.slug(),
        c.label(),
        c.description(),
        c.profile(),
        c.definitionHash(),
        value.metadataHash());
  }

  private static DescriptorIdentity descriptor(Descriptor d) {
    return new DescriptorIdentity(
        d.resource(),
        d.revision(),
        d.ordinal(),
        d.sourceName(),
        d.title(),
        d.path(),
        d.mediaType(),
        d.contentHash(),
        d.tags(),
        d.provenance(),
        binding(d.contentType()));
  }

  private static BundleIdentity bundle(Bundle b) {
    return new BundleIdentity(
        b.format(),
        b.id(),
        b.documents().stream().map(d -> descriptor(d.descriptor())).toList(),
        b.relationships(),
        b.relationshipCoverage());
  }

  static String of(ImportPlan plan) {
    return hash(
        "portable-import-v1",
        new PlanIdentity(
            bundle(plan.bundle()),
            plan.destinations(),
            plan.types(),
            plan.reuse(),
            plan.restoreRelationships()));
  }

  static String of(Bundle bundle) {
    return hash("portable-bundle-v1", bundle(bundle));
  }

  static String of(Document document) {
    return hash("portable-document-v1", descriptor(document.descriptor()));
  }

  /** JSON encoding is confined to this receipt/persistence boundary over map-free records. */
  private static String hash(String version, Object value) {
    return io.aeyer.plowshare.protocol.InformationInterchange.hash(
        InformationJson.json(List.of(version, value)).getBytes(StandardCharsets.UTF_8));
  }
}
