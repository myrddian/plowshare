package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationDiscovery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Permission filtering, current-head selection and audit retention occur before paging. */
public interface InformationDiscoveryRepository {
  /** A boundary is present for confined/contained work; ordinary discovery uses effective RBAC. */
  record Reader(String actor, String boundaryHome, Audit audit) {
    public Reader {
      if (actor == null || actor.isBlank())
        throw new IllegalArgumentException("Authenticated reader required");
    }
  }

  record Audit(String log, String attribution) {}

  record Position(Instant at, String key) {}

  record Text(UUID revision, String text, String documentType, String subtype) {}

  List<InformationDiscovery.Scope> scopes(Reader reader, String after, int limit);

  List<InformationDiscovery.Item> list(
      Reader reader,
      InformationDiscovery.Query query,
      InformationFacets filter,
      Position after,
      int limit);

  List<InformationDiscovery.Facet> facets(
      Reader reader,
      InformationDiscovery.Query query,
      InformationFacets filter,
      String after,
      int limit);

  Text read(Reader reader, InformationDiscovery.Read request);
}
