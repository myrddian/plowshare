package io.aeyer.plowshare.protocol;

/**
 * Which of the two tiers a memory lives in: a named project, or global.
 *
 * <p>A memory has exactly one home, so there are no copies and no sync.
 *
 * <p><b>Global is the absence of a project, not a project named "global".</b> If the global tier
 * were spelled as the project string {@code "global"}, then a real project that happened to be
 * called that — or any code that defaulted a missing project to a placeholder — would silently
 * write into the tier every agent everywhere reads. Modelling it as {@code null} makes that
 * unspellable: {@code Home.of("global")} is an ordinary project and {@link #isGlobal()} returns
 * false for it.
 *
 * @param project the project this memory belongs to, or {@code null} for global
 */
public record Home(String project) {

  public Home {
    if (project != null) {
      ContractValues.identity(project, "project", 1024);
      if (!project.equals(project.strip())) {
        throw new IllegalArgumentException("project must not have edge whitespace");
      }
    }
  }

  private static final Home GLOBAL = new Home(null);

  /** The global tier: the memories that hold everywhere, for every project. */
  public static Home global() {
    return GLOBAL;
  }

  /**
   * A named project's home.
   *
   * <p>Blank is rejected rather than folded into global. An empty string is what arrives from an
   * omitted request field, and quietly treating it as "global" would promote a project memory to
   * the tier every agent pays for on recall — the exact failure the null-means-global rule above
   * exists to prevent. A caller that means global says so.
   */
  public static Home of(String project) {
    if (project == null || project.isBlank()) {
      throw new IllegalArgumentException(
          "a project home needs a project name; use Home.global() for the global tier");
    }
    return new Home(project);
  }

  /** True for the global tier, which is exactly the absence of a project. */
  public boolean isGlobal() {
    return project == null;
  }
}
