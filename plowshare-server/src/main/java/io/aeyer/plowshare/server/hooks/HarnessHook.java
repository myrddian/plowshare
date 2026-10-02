package io.aeyer.plowshare.server.hooks;

import java.util.List;

/**
 * A harness hook built for one run and dropped with it, so its own fields are its
 * per-run state.
 */
public interface HarnessHook extends Hooks {

    /**
     * The run is over, however it ended. Abandon anything still in flight and say
     * what should be recorded about it. Called exactly once.
     */
    default List<HookRecord> finish() {
        return List.of();
    }
}
