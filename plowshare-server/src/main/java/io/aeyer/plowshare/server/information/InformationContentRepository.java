package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Revision typing, lineage and stage authority persistence, bounded by the owning namespace. */
public interface InformationContentRepository {
  record Head(
      UUID resource, UUID revision, String owner, String kind, String availability, boolean typed) {
    public Head(UUID resource, UUID revision, String owner, String kind, String availability) {
      this(resource, revision, owner, kind, availability, false);
    }
  }

  Optional<Head> head(String namespace, String name, boolean lock);

  /** Live home WRITE and mandatory policy; no head, typing or source lifecycle bypass. */
  void requireWrite(InformationContext context, UUID revision);

  Optional<InformationContent.Binding> binding(UUID revision);

  void attach(UUID revision, InformationContent.Binding binding);

  InformationContent.View view(UUID revision);

  boolean explicitOnly(UUID revision);

  /** Grants only the current generation, within the caller's home-locked transaction. */
  void authorise(InformationContext context, UUID revision, UUID request, List<String> stages);

  void redact(UUID revision);
}
