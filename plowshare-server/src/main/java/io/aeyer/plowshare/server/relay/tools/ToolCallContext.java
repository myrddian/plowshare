package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.server.applications.ApplicationServerSettings;
import io.aeyer.plowshare.server.relay.tools.ApplicationToolRegistry.Entry;
import io.aeyer.plowshare.server.relay.tools.ApplicationToolRegistry.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything the registry resolved about one project, caller and tool while it listed that tool:
 * the durable project id, the project's settings (including the caller's hosted connections), the
 * catalogue snapshot built from them and the listed entry. A {@code LiveTool} holds one so that a
 * call made soon after the listing does not rebuild the same settings, snapshot and connection
 * lists.
 *
 * <p>The context is immutable and carries no authority decision: callers still run the grant and
 * membership checks on every call. {@code resolvedAt} comes from the registry's injected clock so
 * the age of the context is measured against the same time source as catalogue leases.
 *
 * @param projectId durable id the project name resolved to at {@code resolvedAt}
 * @param project project name the tool was listed for
 * @param settings the project's settings merged with the caller's hosted connections
 * @param snapshot the catalogue snapshot built from {@code settings}
 * @param entry the listed tool's entry within {@code snapshot}
 * @param resolvedAt when the context was resolved
 */
record ToolCallContext(
    long projectId,
    String project,
    ApplicationServerSettings settings,
    Snapshot snapshot,
    Entry entry,
    Instant resolvedAt) {
  ToolCallContext {
    Objects.requireNonNull(project);
    Objects.requireNonNull(settings);
    Objects.requireNonNull(snapshot);
    Objects.requireNonNull(entry);
    Objects.requireNonNull(resolvedAt);
  }

  /** True while the context is younger than {@code bound}; a zero bound never reuses. */
  boolean youngerThan(Duration bound, Instant now) {
    var age = Duration.between(resolvedAt, now);
    return !age.isNegative() && age.compareTo(bound) < 0;
  }
}
