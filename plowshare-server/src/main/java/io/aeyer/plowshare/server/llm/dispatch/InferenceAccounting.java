package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;

/** Admission seam, resolved once after routing and before submitting to a lane. */
@FunctionalInterface
public interface InferenceAccounting {
    InferenceAccounting NONE = (pool, model, specifier, lane, owner) -> InferenceObserver.NONE;

    InferenceObserver begin(String pool, String wireModel, String specifier, Lane lane, UsageAttribution owner);
}
