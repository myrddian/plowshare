package io.aeyer.plowshare.server.agents;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What is left of {@code plowshare.agents} once its directory key retired.
 *
 * <p><b>This class used to be about where an operator's agent definitions
 * live, and it no longer is.</b> Its retired {@code directory} key named a
 * standalone filesystem path an operator dropped {@code .md} files into, and
 * two things read it: {@code AgentsConfig.agentRegistry}, until Task 6 rewired
 * it to build from the classpath seed layered under {@link
 * io.aeyer.plowshare.server.data.DataLayout}'s {@code global/agents} and
 * {@code global/bots}; and {@code AgentsConfig.projectStore}, which fenced the
 * key's path off as one of a project's mandatory exclusions regardless of
 * whether anything was there. Task 10 retires the key itself: with nothing
 * left reading it, a directory named here would be dead configuration nobody
 * consulted, and the fence it used to need is now the data tree's own root
 * exclusion — naming the root is what makes the rule survive a subdirectory
 * being added, which is exactly what let this key go without widening what
 * the fence covers. See {@code ProjectStore}'s own javadoc and {@code
 * ProjectStoreTest.no_workspace_may_reach_the_definitions_directories}.
 *
 * <p>This is a breaking change for a deployment that set the environment
 * variable naming this directory, or passed the CLI flag that named it — both
 * gone from {@code bin/plowshare} in the same commit. The key existed only to
 * work around the classpath not being loadable as a {@code Path}, which Task
 * 6's {@code ClasspathDefinitions} fixes; left in place it would have loaded
 * the same shipped definitions twice, from two sources.
 *
 * <p>What survives is {@link #curatorBudget}, which has nothing to do with
 * where definitions live and was never a candidate for retiring alongside it.
 */
@ConfigurationProperties(prefix = "plowshare.agents")
public class AgentsProperties {

    /**
     * How many model calls one curator pass may spend when the caller names no
     * number of its own.
     *
     * <p>200, and the reason is Task 9's own arithmetic rather than a round
     * number. A ruling is <b>two</b> model calls — {@code promotion_judge.md}
     * tells the judge to read the memory first, so an ordinary ruling is a tool
     * turn and an answer turn — so this is about a hundred candidates settled
     * per pass. A first pass over a 200-memory project therefore takes two
     * nights rather than one, and every later pass costs only what has been
     * written since, because a settled memory never comes back: {@code
     * ProposalStore.ruledOn} is subtracted before the loop.
     *
     * <p>Spending it is not a failure. {@code CALL_BUDGET} is a designed ending
     * — the pass stops, says so, and keeps every promotion and proposal it
     * already made — which is what makes a conservative default the safe one.
     */
    private int curatorBudget = 200;

    public int getCuratorBudget() {
        return curatorBudget;
    }

    public void setCuratorBudget(int curatorBudget) {
        this.curatorBudget = curatorBudget;
    }
}
