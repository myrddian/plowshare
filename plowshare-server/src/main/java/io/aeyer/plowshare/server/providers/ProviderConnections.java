package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import java.util.List;
import java.util.UUID;

/** Owner-authorized management only. No method releases an upstream bearer to a worker. */
public interface ProviderConnections {
  List<Connection> list(String actor, String project);

  Connection status(String actor, String project, UUID connection);

  Receipt save(String actor, Save request);

  Receipt change(String actor, Change request);

  Credential credential(String actor, Target target);

  Receipt put(String actor, Put request);

  Receipt revoke(String actor, Mutation request);

  Receipt rekey(String actor, Mutation request);

  Authorization authorize(String actor, Mutation request);

  Receipt refresh(String actor, Mutation request);

  Receipt receipt(String actor, String project, UUID requestId);

  /** Authenticated HTTP callback; issuer/state are single-use and bound to the original owner. */
  Credential callback(String actor, String state, String issuer, String code);
}
