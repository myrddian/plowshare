package io.aeyer.plowshare.server.agents;

/**
 * The caps a person set for a project (spec 2026-09-29 §2), each with where it came from: the
 * rooting machine's {@code .plowshare/environment.yml}, the server's {@code environment.yml}, or
 * nowhere — each run's definition's own.
 *
 * <p><b>Each value carries its source</b> because {@code /cap} shows the person the effective
 * number and why it is that number: a steps cap the server's file set looks, in a bare number,
 * exactly like one the person set and forgot, and the fix for each is in a different file.
 *
 * <p><b>Two caps no definition has</b> (measured 2026-09-30, {@code orc_318DFD3782228160}: 663
 * minutes, 21 failures of one check, and nobody asked): {@code time}, the minutes a run goes before
 * it asks whether to go on — none when unset, the behaviour as it was — and {@code failedChecks},
 * how many times its check may fail before it asks — {@value #DEFAULT_FAILED_CHECKS} when unset,
 * {@link #DEFAULT}, since the measured cost of no limit was hours.
 *
 * @param steps steps per turn, overriding a conductor's {@code max-turns}
 * @param budget model calls per run, overriding a conductor's {@code max-model-calls}
 * @param autoContinue caps a run passes without asking
 * @param time minutes a run goes before it asks, or unset for none
 * @param failedChecks check failures a run takes before it asks
 * @param unreadable why a file was not read, or null
 */
public record ProjectCaps(Setting steps, Setting budget, Setting autoContinue, Setting time,
        Setting failedChecks, String unreadable) {

    /** A value the rooting machine's own file set. */
    public static final String PROJECT_FILE = ".plowshare/environment.yml";

    /** A value the server's per-project file set. */
    public static final String SERVER_FILE = "the server's environment.yml";

    /** No file set it: each run's definition's own number stands. */
    public static final String DEFINITION = "definition";

    /** No file set it, and no definition has one: the harness's own number stands. */
    public static final String DEFAULT = "default";

    /** How many check failures a run takes before it asks, when no file says. */
    public static final int DEFAULT_FAILED_CHECKS = 5;

    /** {@link #failedChecks} when no file sets it. */
    public static final Setting FAILED_CHECKS_DEFAULT = new Setting(DEFAULT_FAILED_CHECKS, DEFAULT);

    /** Nothing set anywhere. */
    public static final ProjectCaps NONE =
            new ProjectCaps(Setting.UNSET, Setting.UNSET, Setting.UNSET, null);

    /**
     * One cap.
     *
     * @param value the number, or null when unset
     * @param source where it came from: {@link #PROJECT_FILE}, {@link #SERVER_FILE}, {@link
     *     #DEFINITION} or {@link #DEFAULT}
     */
    public record Setting(Integer value, String source) {

        /** Not set by either file. */
        public static final Setting UNSET = new Setting(null, DEFINITION);
    }

    /**
     * The first three caps alone, with no time cap and the default failed-checks limit — every
     * caller written before those two existed.
     */
    public ProjectCaps(Setting steps, Setting budget, Setting autoContinue, String unreadable) {
        this(steps, budget, autoContinue, Setting.UNSET, FAILED_CHECKS_DEFAULT, unreadable);
    }

    /** @return how many check failures a run takes before it asks: the setting, or the default */
    public int failedChecksLimit() {
        Integer set = failedChecks == null ? null : failedChecks.value();
        return set == null || set < 1 ? DEFAULT_FAILED_CHECKS : set;
    }

    /**
     * @param conductor a conductor as its definition has it
     * @return it with these steps and budget, where set
     */
    public AgentDefinition applyTo(AgentDefinition conductor) {
        return conductor.withCaps(steps.value(), budget.value());
    }
}
