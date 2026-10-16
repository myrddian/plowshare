package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.protocol.SchemaValidation;
import io.aeyer.plowshare.protocol.SchemaValue;
import java.util.UUID;

/** Scoped content-type discovery and management; methods recheck live authority on every call. */
public interface InformationContentTypes {
  InformationContent.Versions list(
      InformationContext context, boolean drafts, int limit, int offset);

  InformationContent.Contract get(InformationContext context, UUID version, UUID attachedRevision);

  /** Model-facing registry reads never expose drafts, even for a manager principal. */
  InformationContent.Contract published(
      InformationContext context, UUID version, UUID attachedRevision);

  /**
   * Creates an immutable draft; expected control revision is required only for an existing type.
   */
  InformationContent.Version create(
      InformationContext context,
      UUID request,
      UUID type,
      Long expectedControlRevision,
      String slug,
      String label,
      String description,
      SchemaDefinition schema,
      SchemaValue.ObjectValue presentation,
      String session);

  InformationContent.Version transition(
      InformationContext context,
      UUID request,
      UUID version,
      long expectedControlRevision,
      boolean activate,
      String session);

  SchemaValidation.Result validate(
      InformationContext context,
      UUID version,
      UUID attachedRevision,
      SchemaValue.ObjectValue metadata);

  /**
   * Resolves an assignment under the caller's original namespace; final commits must recheck it.
   */
  InformationContent.Contract assignable(InformationContext context, UUID version, boolean inherit);
}
