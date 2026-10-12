package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.RelayReadBudget;
import java.util.*;

/**
 * Owner management and fenced worker capabilities. Workers acquire no general project or Personal
 * access.
 */
public interface HostedToolScopes extends HostedInvocationAuthority {
  Receipt save(String actor, Save request);

  Receipt change(String actor, Change request);

  List<Binding> list(String actor, Query request);

  Binding status(String actor, Status request);

  Receipt receipt(String actor, ReceiptQuery request);

  Registration register(String actor, String session, String socket, Register request);

  Registration renew(String actor, String session, String socket, Worker request);

  WorkerStatus workerStatus(String actor, String session, String socket, WorkerQuery request);

  Credential credential(String actor, String session, String socket, CredentialRequest request);

  Reconciliation read(String actor, Read request);

  RelayPort.Published publish(String actor, String session, String socket, Publish request);

  RelayPort.Batch consume(
      String actor, String session, String socket, Consume request, RelayReadBudget budget);

  RelayPort.Acknowledged acknowledge(String actor, String session, String socket, Ack request);

  List<io.aeyer.plowshare.protocol.ToolScopes.Connection> connections(String project, String owner);

  boolean permits(
      io.aeyer.plowshare.protocol.ToolScopes.Connection connection,
      String owner,
      String agent,
      String session,
      String tool);
}
