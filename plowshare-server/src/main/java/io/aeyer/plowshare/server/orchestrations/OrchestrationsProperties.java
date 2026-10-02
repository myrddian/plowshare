package io.aeyer.plowshare.server.orchestrations;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The orchestration engine's switches. */
@ConfigurationProperties(prefix = "plowshare.orchestrations")
public class OrchestrationsProperties {

    /**
     * Whether a boot picks up the runs a restart interrupted: restarts every {@code running}
     * conductor, speaks every pending answer, and delivers every undelivered question and ending.
     * The test classpath turns it off, so no {@code @SpringBootTest} context speaks to a conductor
     * just by starting.
     */
    private boolean recoverAtBoot = true;

    /**
     * How deep a tree of orchestrations may go. A root run is depth 0, so a start is refused when
     * {@code parent.depth() + 1} would exceed this: the default of 2 lets a root start a child and
     * that child start a grandchild, and refuses the great-grandchild.
     *
     * <p><b>0 turns nesting off</b> — every conductor's own start is refused, however it was
     * granted, since a root is already at the last depth allowed — which is the switch a deployment
     * that wants orchestrations without trees uses. A negative value is refused outright rather
     * than read as a stricter 0: nothing sensible asks for it, and a typo that silently disabled a
     * feature would be worse than a boot that says so.
     *
     * <p>Each run keeps its own model-call budget, so a tree's total spend is depth × breadth ×
     * budget. This number and the grant-cycle refusal at load are the only two things bounding it —
     * Decision 4.
     */
    private int maxDepth = 2;

    /**
     * Whether the stall sweep runs at all: every minute, a {@code running} run quiet for 15
     * minutes across its conversation tree is reported to its account, once per stall — spec
     * 2026-09-27 §4. The test classpath turns it off, the ticker's own reason: no {@code
     * @SpringBootTest} context should judge a run stalled on a clock no test controls.
     */
    private boolean stallSweepEnabled = true;

    public boolean isRecoverAtBoot() {
        return recoverAtBoot;
    }

    public void setRecoverAtBoot(boolean recoverAtBoot) {
        this.recoverAtBoot = recoverAtBoot;
    }

    public boolean isStallSweepEnabled() {
        return stallSweepEnabled;
    }

    public void setStallSweepEnabled(boolean stallSweepEnabled) {
        this.stallSweepEnabled = stallSweepEnabled;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("plowshare.orchestrations.max-depth is " + maxDepth
                    + ", and it is 0 (no nesting at all) or more");
        }
        this.maxDepth = maxDepth;
    }
}
