package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationInterchange.*;
import java.util.*;

/**
 * Required dependency closure and imported provenance; callers own transaction and access checks.
 */
public interface InformationInterchangeRepository {
  List<UUID> dependencies(UUID revision);

  List<Link> relationships(List<UUID> revisions);

  void lockDestination(InformationContext context, String name);

  void retain(UUID revision, Origin origin, List<UUID> dependencies);

  Optional<Origin> origin(UUID revision);
}
