package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import java.util.*;

/**
 * Specialist store: project-before-binding mutations and binding-before-credential/intent admission
 * locks.
 */
public interface HostedToolScopeRepository {
  record Lease(
      Registration registration, String session, String socket, UUID server, long catalogueAfter) {
    public Lease {
      Objects.requireNonNull(registration);
      Objects.requireNonNull(session);
      Objects.requireNonNull(socket);
      Objects.requireNonNull(server);
      if (catalogueAfter < 0) throw new IllegalArgumentException("Negative catalogue fence");
    }
  }

  void lockProject(String project);

  List<Binding> list(String project, String owner);

  Optional<Binding> find(UUID binding, boolean lock);

  Optional<Binding> route(String project, String route, boolean lock);

  void save(Binding binding, Long expectedGeneration);

  Optional<Lease> lease(UUID binding);

  void lease(Lease value);

  void clearLease(UUID binding);

  void reserve(UUID requestId, UUID binding, String actor, String operation);

  void receipt(String actor, Receipt receipt);

  Optional<Receipt> receipt(String actor, String project, UUID requestId);
}
