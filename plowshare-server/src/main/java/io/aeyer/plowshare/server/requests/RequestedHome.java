package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The project a request named, or the global home when it named none.
 *
 * <p><b>The wrapper is unavoidable and that is a module boundary, not a
 * preference.</b> {@link Home} lives in {@code plowshare-protocol}, which the
 * client also depends on, so it cannot import the server's {@link CallerFault}
 * and must go on raising {@link IllegalArgumentException}. This is where that
 * becomes a fault a surface can answer with.
 */
public final class RequestedHome {

    private RequestedHome() {
    }

    public static Home in(String project) {
        if (project == null) {
            return Home.global();
        }
        try {
            return Home.of(project);
        } catch (IllegalArgumentException blank) {
            throw new CallerFault(blank.getMessage(), blank);
        }
    }
}
