package io.aeyer.plowshare.server.relay;

import java.util.List;

/** Pure bounded sandbox boundary: only explicit typed values leave guest JavaScript. */
public interface RelayRouteProgram {
  RelayRouting.Manifest manifest(RelayDeliveries.SourcePin routing);

  List<RelayRouting.Selection> route(
      RelayRouting.Package relay, RelayRouting.Subscription subscription, Relay.Publication input);
}
