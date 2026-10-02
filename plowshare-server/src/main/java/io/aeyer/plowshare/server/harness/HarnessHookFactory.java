package io.aeyer.plowshare.server.harness;

import io.aeyer.plowshare.server.hooks.HarnessHook;
import java.util.List;

/** A harness hook a profile can name, and how to build one for a run. */
public interface HarnessHookFactory {

    /** What a profile's {@code hook:} names it by, e.g. {@code harness:stuck}. */
    String name();

    /** Every parameter it takes, with its type and default. */
    List<Parameter> parameters();

    /** A fresh instance for one run. */
    HarnessHook create(Parameters parameters);
}
