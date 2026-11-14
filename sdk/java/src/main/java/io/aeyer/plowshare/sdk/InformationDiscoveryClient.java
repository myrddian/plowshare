package io.aeyer.plowshare.sdk;

import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.protocol.InformationDiscovery;
import java.util.concurrent.CompletionStage;

/** Read-only multi-scope information navigation on the existing authenticated socket. */
public interface InformationDiscoveryClient {
  CompletionStage<InformationDiscovery.Scopes> scopes(
      InformationDiscovery.ScopesRequest request, RequestOptions options);

  CompletionStage<InformationDiscovery.Page> list(
      InformationDiscovery.Query request, RequestOptions options);

  CompletionStage<InformationDiscovery.Page> search(
      InformationDiscovery.Query request, RequestOptions options);

  CompletionStage<InformationDiscovery.Facets> facets(
      InformationDiscovery.Query request, RequestOptions options);

  CompletionStage<Information.Window> read(
      InformationDiscovery.Read request, RequestOptions options);
}
