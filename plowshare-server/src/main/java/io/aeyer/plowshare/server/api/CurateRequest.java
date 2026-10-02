package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/curate}.
 *
 * @param project the project whose memories are considered. Required: there is
 *     no global curation, because promotion is the operation that puts a
 *     project's memory into global and a pass over global would have nowhere to
 *     promote to.
 * @param maxModelCalls the whole pass's budget across every ruling in it, or
 *     {@code null} for the configured default. A pass that spends it stops and
 *     says so, keeping everything it already did — {@code CALL_BUDGET} is a
 *     designed ending and not a failure.
 */
public record CurateRequest(String project, Integer maxModelCalls) {}
