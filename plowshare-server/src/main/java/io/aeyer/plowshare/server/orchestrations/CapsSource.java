package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.ProjectCaps;

/**
 * A project's caps, as read now — spec 2026-09-29 §2. {@link #NONE} has none.
 *
 * <p>A seam rather than {@code Environments} itself, because which session roots a project is
 * {@code PresenceRegistry}'s answer and the engine knows neither; the bean in {@link
 * OrchestrationsConfig} joins the two, and a test hands a lambda.
 */
@FunctionalInterface
public interface CapsSource {

    /** No caps anywhere: every run keeps its definition's own numbers. */
    CapsSource NONE = project -> ProjectCaps.NONE;

    /**
     * @param project the run's project, or null
     * @return its caps
     */
    ProjectCaps capsFor(String project);
}
