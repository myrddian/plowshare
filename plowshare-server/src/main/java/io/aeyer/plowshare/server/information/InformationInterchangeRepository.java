package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationInterchange.*;
import java.util.*;

/**
 * Required dependency closure and imported provenance; callers own transaction and access checks.
 */
public interface InformationInterchangeRepository {
  List<UUID> dependencies(UUID revision);

  /**
   * Included endpoints do not authorize disclosure; filter live/pinned visibility before bounds.
   */
  List<Link> relationships(InformationContext context, List<UUID> revisions);

  void lockDestination(InformationContext context, String name);

  void retain(UUID revision, Origin origin, List<UUID> dependencies);

  Optional<Origin> origin(UUID revision);
}
