package io.aeyer.plowshare.server.llm.accounting;

import io.aeyer.plowshare.protocol.Home;

/** Resolve trusted service scope before dispatch. This capability does not authorize requests. */
public interface UsageOwners {
    UsageOwners NONE = (home, account, operation) -> UsageAttribution.LEGACY;
    UsageAttribution in(Home home, String account, UsageAttribution.Operation operation);
    default UsageAttribution orchestration(String id, UsageAttribution.Operation operation) {
        return UsageAttribution.LEGACY;
    }
    default UsageAttribution conversation(String id, int turn, UsageAttribution.Operation operation) {
        return UsageAttribution.LEGACY;
    }
}
