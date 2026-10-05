package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates project packages without effects, then routes one retained input into durable
 * admission.
 */
public final class ProjectRelayRouting implements RelayRouting {
  private final RelayProjectFiles files;
  private final RelayRouteProgram programs;
  private final Relay relay;
  private final RelayDeliveries deliveries;

  public ProjectRelayRouting(
      RelayProjectFiles files,
      RelayRouteProgram programs,
      Relay relay,
      RelayDeliveries deliveries) {
    this.files = Objects.requireNonNull(files);
    this.programs = Objects.requireNonNull(programs);
    this.relay = Objects.requireNonNull(relay);
    this.deliveries = Objects.requireNonNull(deliveries);
  }

  @Override
  public Project load(RelayProjectFiles.Access access) {
    files.requireAccess(access);
    var active = files.read(access, "active.json").map(RelayRouteCodec::active).orElse(List.of());
    var policies =
        files.read(access, "topics.json").map(RelayRouteCodec::policies).orElse(Map.of());
    var packages = new ArrayList<Package>();
    int bytes = 0;
    for (String name : active) {
      var source = required(access, name + "/routes.js");
      bytes += source.source().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 4194304) throw new CallerFault("Active Relay routing sources exceed 4 MiB");
      packages.add(new Package(name, source, programs.manifest(source)));
    }
    return new Project(packages, policies);
  }

  @Override
  public RelayDeliveries.Admission admit(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      Relay.Publication input) {
    return admit(access, subscription, input, deliveries::admit, false);
  }

  @Override
  public RelayDeliveries.Admission admit(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      Relay.Publication input,
      AdmissionCommit commit) {
    return admit(access, subscription, input, commit, true);
  }

  private RelayDeliveries.Admission admit(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      Relay.Publication input,
      AdmissionCommit commit,
      boolean fenceRetained) {
    Objects.requireNonNull(commit);
    files.requireAccess(access);
    if (!subscription.topic().scope().equals(new Relay.ProjectScope(access.projectId()))
        || !subscription.topic().equals(input.topic()))
      throw new CallerFault("Relay admission must belong to the authorized project and topic");
    var key = new RelayDeliveries.AdmissionKey(subscription, input.position());
    var existing = deliveries.admission(key);
    if (existing.isPresent()) {
      if (!existing.get().publication().equals(input))
        throw new CallerFault("Relay admission retry differs from its pinned input");
      // Owned retries must recheck the lease atomically; the legacy read-only retry is unchanged.
      return fenceRetained ? commit.admit(key, existing.get().decision()) : existing.get();
    }
    var project = load(access);
    Package selected = null;
    Subscription selectedSubscription = null;
    for (var candidate : project.relays()) {
      for (var declared : candidate.manifest().subscriptions()) {
        if (candidate.key(access.projectId(), declared).equals(subscription)) {
          selected = candidate;
          selectedSubscription = declared;
        }
      }
    }
    if (selected == null) throw new CallerFault("Relay subscription is not currently active");
    // Verify against the actual retained input, not just a caller-supplied position. Admission
    // rechecks order/gaps under the topic lock; concurrent expiry or another admission fails
    // safely.
    var read = relay.read(subscription, 1);
    if (read.gap().isPresent())
      throw new CallerFault("Relay expiry gap requires explicit acknowledgement");
    if (read.publications().isEmpty() || !read.publications().getFirst().equals(input))
      throw new CallerFault("Relay routing requires the next retained publication");
    var branches = new ArrayList<RelayDeliveries.Branch>();
    for (var target : programs.route(selected, selectedSubscription, input)) {
      var handler =
          target.script() == null
              ? null
              : required(access, selected.name() + "/scripts/" + target.script());
      branches.add(
          new RelayDeliveries.Branch(
              target.name(), target.receiver(), handler, target.publishTo(), target.work()));
    }
    var decision = new RelayDeliveries.Decision(selected.routing(), branches);
    files.requireAccess(access);
    return commit.admit(key, decision);
  }

  private RelayDeliveries.SourcePin required(RelayProjectFiles.Access access, String path) {
    return RelayDeliveries.SourcePin.of(
        path,
        files
            .read(access, path)
            .orElseThrow(
                () ->
                    new CallerFault(
                        "An active Relay routes.js or selected handler script is missing")));
  }
}
