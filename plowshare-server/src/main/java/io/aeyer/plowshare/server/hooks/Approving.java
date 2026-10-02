package io.aeyer.plowshare.server.hooks;

import java.util.List;
import java.util.Objects;

/**
 * {@code approval.pre}: a person is about to be asked to allow one command (spec
 * 2026-09-28-hooks-reach-the-log §3). The environment it would run under is the context's.
 *
 * @param reason why it is being asked, before any hook's note, or {@code null}
 * @param attended whether the person is in the conversation that asks, as against an account
 *     told through the inbox
 * @param scopes what the person may answer with, besides deny
 */
public record Approving(List<String> argv, String cwd, String reason, boolean attended,
        List<String> scopes) {

    public Approving {
        argv = List.copyOf(argv);
        Objects.requireNonNull(cwd, "cwd");
        scopes = List.copyOf(scopes);
    }
}
