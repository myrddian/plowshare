package io.aeyer.plowshare.protocol;

/**
 * The one definition of the reserved project-name prefix that marks containment.
 *
 * <p><a href="../../../../../../../docs/decisions/0042-nested-application-containment.md">ADR
 * 0042</a> makes this prefix load-bearing: V208 excludes {@code contained:%} from {@code
 * authority_map_project}, so a project carrying it acquires no addressable {@code PROJECT} scope,
 * and is instead an eligible host for an explicitly inserted {@code CONTAINED} one. Every surface
 * that already excludes {@code personal:} and {@code client:} by name therefore has to decide what
 * a contained project means to it, and the decisions are inventoried in that record.
 *
 * <p>The test is <strong>case-insensitive</strong> while the SQL exclusions are {@code LIKE
 * 'contained:%'} and so are not. That asymmetry is deliberate and is the safety belt rather than a
 * discrepancy: creation goes through {@code ProjectStore.ordinary}, which reserves the prefix
 * case-insensitively, so a project named {@code Contained:x} cannot be created at all. Were one to
 * exist it would be mapped to an ordinary {@code PROJECT} scope by SQL, which is why no Java fence
 * may admit it on a lowercase comparison.
 *
 * <p>This lives in the protocol module because two of the sites are here — {@link
 * ApplicationIdentity#project(String)} and {@link HostedToolScopes#project(String)} — and a second
 * copy of the prefix is exactly the drift the inventory exists to prevent.
 */
public final class ContainedProjects {
  /** The reserved prefix, as the server allocates it and as the SQL exclusions spell it. */
  public static final String PREFIX = "contained:";

  private ContainedProjects() {}

  /**
   * Whether this project name is a contained Application's, case-insensitively.
   *
   * @param name a project name, or null; a null name is not a contained project
   */
  public static boolean name(String name) {
    return name != null && name.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
  }
}
