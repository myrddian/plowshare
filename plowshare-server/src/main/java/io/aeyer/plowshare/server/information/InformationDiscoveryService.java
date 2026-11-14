package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationDiscovery;

/**
 * Stateless selection plus authenticated, query-bound continuation; cursors convey no authority.
 */
public interface InformationDiscoveryService {
  InformationDiscovery.Scopes scopes(
      InformationDiscoveryRepository.Reader reader, InformationDiscovery.ScopesRequest request);

  InformationDiscovery.Page list(
      InformationDiscoveryRepository.Reader reader, InformationDiscovery.Query query);

  InformationDiscovery.Page search(
      InformationDiscoveryRepository.Reader reader, InformationDiscovery.Query query);

  InformationDiscovery.Facets facets(
      InformationDiscoveryRepository.Reader reader, InformationDiscovery.Query query);

  Information.Window read(
      InformationDiscoveryRepository.Reader reader, InformationDiscovery.Read request);
}
