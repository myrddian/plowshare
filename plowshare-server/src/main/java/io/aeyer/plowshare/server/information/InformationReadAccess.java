package io.aeyer.plowshare.server.information;

import java.util.UUID;

/** Rechecks a retained revision in the caller's original selection before use or publication. */
@FunctionalInterface
public interface InformationReadAccess {
  void requireReadable(InformationContext context, UUID revision);
}
