package io.aeyer.plowshare.server.hooks.script;

import io.aeyer.plowshare.server.hooks.Stage;

/** One hook in one context: what the pool hands out. A seam so the pool is testable without GraalJS. */
public interface PooledHook extends AutoCloseable {

    /** The decision as JSON, or {@code "null"} for nothing. */
    String call(Stage stage, String eventJson) throws HookFailure;

    /** Stops a running call from another thread. The hook is not alive afterwards. */
    void cancel();

    boolean isAlive();

    @Override
    void close();
}
