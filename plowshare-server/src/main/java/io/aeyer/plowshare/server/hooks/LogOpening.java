package io.aeyer.plowshare.server.hooks;

/**
 * What {@code log.open} is shown beyond its context (spec 2026-09-28-hooks-reach-the-log §3).
 *
 * @param parent the delegating log, on a {@code delegation} log; otherwise {@code null}
 * @param owner the account that owns the log, or {@code null}
 */
public record LogOpening(String parent, String owner) {}
