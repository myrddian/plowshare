package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;

/** Authenticated, read-only public broker inspection. System scope requires live server admin. */
public interface RelayInspection {
  RelayLog.Topics topics(String account, RelayLog.TopicsQuery query);

  RelayLog.Page log(String account, RelayLog.Query query);
}
