package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.ContainmentBounds;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The whole {@code plowshare.containment} prefix: where a child's source area is allocated, and the
 * depth and breadth bounds {@link ContainmentBounds} carries.
 *
 * <p>These are deployment values and are supplied by configuration only. Nothing here defaults a
 * path, and no fallback connects a child's source to the server's own install directory or to a
 * local example: a deployment that wants nesting says where it lives.
 *
 * <p><strong>Absent and invalid are different, deliberately.</strong> The prefix as a whole may be
 * absent: a server that does not use containment is a normal server, so this bean is conditional on
 * {@code plowshare.containment.root} and simply does not exist without it. {@code
 * PinnedOperationAuthority} then holds {@link ContainmentBounds#absent()} and refuses every hop,
 * and {@code SourceContainedApplications} refuses every creation — declined, not unlimited. But a
 * prefix that is <em>present and incomplete or invalid</em> is an operator mistake about a
 * capability the deployment has asked for, and it fails at startup rather than silently nesting
 * under a guessed root or an unbounded limit.
 *
 * <p>The depth and breadth values are bound here only to construct {@link ContainmentBounds}; this
 * type does not re-declare them as part of its own contract, and {@link #bounds()} is the only way
 * to read them. One record owns the meaning of a bound, including that absence is never unlimited.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "plowshare.containment", name = "root")
@ConfigurationProperties(prefix = "plowshare.containment")
public final class ContainmentConfig {
  private static final ContainmentConfig ABSENT = new ContainmentConfig();

  private final Path root;
  private final ContainmentBounds bounds;

  private ContainmentConfig() {
    this.root = null;
    this.bounds = ContainmentBounds.absent();
  }

  @ConstructorBinding
  public ContainmentConfig(String root, Integer maxDepth, Integer maxLiveDescendants) {
    if (root == null || root.isBlank())
      throw new IllegalStateException(
          "Configure plowshare.containment.root with the server-owned directory contained"
              + " Application source is allocated under");
    Path resolved = Paths.get(root.strip()).normalize();
    if (!resolved.isAbsolute())
      throw new IllegalStateException(
          "plowshare.containment.root must be an absolute server path, not " + root);
    if (maxDepth == null || maxLiveDescendants == null || maxDepth <= 0 || maxLiveDescendants <= 0)
      throw new IllegalStateException(
          "Configure positive plowshare.containment.max-depth and"
              + " plowshare.containment.max-live-descendants; containment is not unbounded by"
              + " omission");
    this.root = resolved;
    this.bounds = new ContainmentBounds(maxDepth, maxLiveDescendants);
  }

  /** The configuration a server without containment holds; every creation refuses under it. */
  public static ContainmentConfig absent() {
    return ABSENT;
  }

  /** Whether a root and both bounds were configured. Never answers a guessed location. */
  public boolean configured() {
    return root != null && bounds.configured();
  }

  /**
   * The directory a child's source area is allocated under. Callers must check {@link
   * #configured()} first; this refuses rather than inventing a path.
   */
  public Path root() {
    if (root == null) throw new IllegalStateException("Containment is not configured");
    return root;
  }

  public ContainmentBounds bounds() {
    return bounds;
  }

  /** The bounds the authority substrate enforces, injected rather than passed by any caller. */
  @Bean
  public ContainmentBounds containmentBounds() {
    return bounds;
  }
}
