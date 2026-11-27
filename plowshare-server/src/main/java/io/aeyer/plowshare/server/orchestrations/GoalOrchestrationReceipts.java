package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Owner;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Run;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Projections of the orchestration owner's existing start journal. */
interface GoalOrchestrationReceipts {
  String SOURCE = "retained-goal-wait";

  record Key(Owner owner, UUID invocation, String project, String name, String hash) {}

  record Child(String id, String conversation) {}

  Optional<Run> find(Key key);

  boolean stillTerminal(Key key, Child child, String state, Instant endedAt);

  Optional<Child> active(Key key);
}
