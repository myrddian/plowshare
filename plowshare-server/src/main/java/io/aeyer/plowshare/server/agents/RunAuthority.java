package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest;
import java.util.List;

/**
 * Live authority for a run's fixed home and resource references. This intersects the ordinary tool
 * grants, hooks and provider fences; it never grants a tool or selects an execution account.
 */
@FunctionalInterface
public interface RunAuthority {
  /** Unknown, global or relocated resources must refuse, including after a tool returns. */
  void require(Home home, List<AccessRequest.Resource> resources);
}
