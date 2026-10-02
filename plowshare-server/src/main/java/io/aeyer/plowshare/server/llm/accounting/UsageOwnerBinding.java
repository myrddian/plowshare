package io.aeyer.plowshare.server.llm.accounting;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Bind the optional capability without creating agent/service beans during configuration. */
public final class UsageOwnerBinding implements BeanPostProcessor {
    private final ObjectProvider<UsageOwners> source;
    public UsageOwnerBinding(ObjectProvider<UsageOwners> source) { this.source = source; }
    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean instanceof UsageAware aware) { aware.useUsageOwners(source.getObject()); }
        return bean;
    }
}
