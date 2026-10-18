package io.aeyer.plowshare.server.agents;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What is left of {@code plowshare.agents} once its directory key retired.
 *
 * <p><b>This class used to be about where an operator's agent definitions live, and it no longer
 * is.</b> Its retired {@code directory} key named a standalone filesystem path an operator dropped
 * {@code .md} files into, and two things read it: {@code AgentsConfig.agentRegistry}, until Task 6
 * rewired it to build from the classpath seed layered under {@link
 * io.aeyer.plowshare.server.data.DataLayout}'s {@code global/agents} and {@code global/bots}; and
 * {@code AgentsConfig.projectStore}, which fenced the key's path off as one of a project's
 * mandatory exclusions regardless of whether anything was there. Task 10 retires the key itself:
 * with nothing left reading it, a directory named here would be dead configuration nobody
 * consulted, and the fence it used to need is now the data tree's own root exclusion — naming the
 * root is what makes the rule survive a subdirectory being added, which is exactly what let this
 * key go without widening what the fence covers. See {@code ProjectStore}'s own javadoc and {@code
 * ProjectStoreTest.no_workspace_may_reach_the_definitions_directories}.
 *
 * <p>This is a breaking change for a deployment that set the environment variable naming this
 * directory, or passed the CLI flag that named it — both gone from {@code bin/plowshare} in the
 * same commit. The key existed only to work around the classpath not being loadable as a {@code
 * Path}, which Task 6's {@code ClasspathDefinitions} fixes; left in place it would have loaded the
 * same shipped definitions twice, from two sources.
 *
 * <p>What survives is {@link #curatorBudget}, which has nothing to do with where definitions live
 * and was never a candidate for retiring alongside it.
 */
@ConfigurationProperties(prefix = "plowshare.agents")
public class AgentsProperties {

  /** Selected whole-pass policy: unlimited by default, zero disabled, positive limited. */
  private String curatorBudget = "unlimited";

  public String getCuratorBudget() {
    return curatorBudget;
  }

  public void setCuratorBudget(String value) {
    io.aeyer.plowshare.server.embedding.SpendingPolicy.limit(value, "curator-budget");
    curatorBudget = value;
  }

  public void setCuratorBudget(int value) {
    setCuratorBudget(Integer.toString(value));
  }

  public Budget curatorAllowance() {
    return io.aeyer.plowshare.server.embedding.SpendingPolicy.budget(
        io.aeyer.plowshare.server.embedding.SpendingPolicy.limit(curatorBudget, "curator-budget"));
  }
}
