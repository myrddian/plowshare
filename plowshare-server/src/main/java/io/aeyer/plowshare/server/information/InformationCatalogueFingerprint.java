package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.DocumentType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Receipt identity codec preserves the shipped tuple encoding across the typed migration. */
final class InformationCatalogueFingerprint {
  private InformationCatalogueFingerprint() {}

  static String command(
      String operation,
      UUID revision,
      InformationContext.Selection selection,
      InformationCommandParameters parameters) {
    Object tuple =
        switch (parameters) {
          case InformationCommandParameters.None value -> List.of();
          case InformationCommandParameters.Link value -> List.of(value.project(), value.remove());
          case InformationCommandParameters.Sharing value -> List.of(value.shared());
          case InformationCommandParameters.Availability value -> List.of(value.state());
          case InformationCommandParameters.Rebuild value -> List.of(value.stage());
          case InformationCommandParameters.Allowance value -> List.of(value.total());
          case InformationCommandParameters.Tags value -> value.values();
          case InformationCommandParameters.Groups value -> Arrays.asList(value.values());
        };
    return hash(InformationJson.json(Arrays.asList(operation, revision, selection, tuple)));
  }

  static String admission(
      String namespace,
      String name,
      String hash,
      String mediaType,
      String sourceUri,
      String kind,
      List<UUID> inputs,
      List<UUID> citations,
      UUID feedback,
      DocumentType documentType,
      boolean syntaxOnly) {
    String identity =
        InformationJson.json(
            Arrays.asList(
                namespace, name, hash, mediaType, sourceUri, kind, inputs, citations, feedback));
    return hash(
        (documentType.isCode() ? identity + InformationJson.json(documentType) : identity)
            + (syntaxOnly ? ":workspace-syntax-v1" : ""));
  }

  private static String hash(String value) {
    return InformationCatalogue.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
