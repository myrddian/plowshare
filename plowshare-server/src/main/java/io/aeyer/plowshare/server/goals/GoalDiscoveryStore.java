package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.goals.GoalAgents.Listing;
import io.aeyer.plowshare.server.goals.GoalAgents.Page;
import io.aeyer.plowshare.server.goals.GoalAgents.ReceiptRequest;
import io.aeyer.plowshare.server.goals.GoalAgents.Started;
import io.aeyer.plowshare.server.goals.GoalAgents.Status;
import io.aeyer.plowshare.server.goals.GoalAgents.StatusRequest;
import java.util.Optional;

/** Bounded read-side snapshots for dedicated Goal Agents and original launch receipts. */
interface GoalDiscoveryStore extends GoalConversationAccess {
  Page list(String account, Listing request);

  Optional<Status> status(String account, StatusRequest request);

  Optional<Started> receipt(String account, ReceiptRequest request);
}
