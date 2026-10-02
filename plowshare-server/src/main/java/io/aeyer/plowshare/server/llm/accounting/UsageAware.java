package io.aeyer.plowshare.server.llm.accounting;

/** Capability binding for services which admit background inference. */
public interface UsageAware {
    void useUsageOwners(UsageOwners source);
}
