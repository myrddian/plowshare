package io.aeyer.plowshare.server.information;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Receipt serde boundary. Legacy tuples are retained so existing request IDs reconcile unchanged.
 */
final class InformationGateFingerprint {
  private InformationGateFingerprint() {}

  static String of(
      String operation, InformationContext.Selection selection, InformationGateIdentity identity) {
    List<?> tuple =
        switch (identity) {
          case InformationGateIdentity.Intake i ->
              i.corpus() == InformationContext.Corpus.CODE
                  ? Arrays.asList(i.name(), i.hash(), i.mediaType(), i.sourceUri(), "code")
                  : Arrays.asList(i.name(), i.hash(), i.mediaType(), i.sourceUri());
          case InformationGateIdentity.CodeSnapshot i ->
              List.of(i.name(), i.hash(), i.sourceUri(), "workspace-syntax-v1");
          case InformationGateIdentity.Evidence i ->
              Arrays.asList(i.revision(), i.start(), i.end(), i.quote(), i.locator());
          case InformationGateIdentity.Report i ->
              Arrays.asList(
                  i.name(),
                  i.hash(),
                  i.inputs(),
                  i.evidence(),
                  i.feedback(),
                  i.details(),
                  i.producer());
          case InformationGateIdentity.Release i ->
              Arrays.asList(i.payload(), i.owner(), i.selection(), i.revisions(), i.reason());
          case InformationGateIdentity.Adopt i ->
              Arrays.asList(i.document(), i.owner(), i.visibility(), i.project(), i.reason());
        };
    return InformationCatalogue.sha256(
        InformationJson.json(Arrays.asList(operation, selection, tuple))
            .getBytes(StandardCharsets.UTF_8));
  }
}
