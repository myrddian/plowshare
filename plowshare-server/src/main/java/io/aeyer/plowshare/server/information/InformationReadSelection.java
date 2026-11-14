package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import java.util.List;
import java.util.Objects;

/**
 * Explicit read locators; identity, confinement and audit ownership are supplied by the runtime.
 */
public record InformationReadSelection(Reader reader, List<String> scopes) {
  public InformationReadSelection {
    Objects.requireNonNull(reader);
    scopes = new InformationDiscovery.Query(scopes, null, null, null, null, null).scopes();
  }
}
