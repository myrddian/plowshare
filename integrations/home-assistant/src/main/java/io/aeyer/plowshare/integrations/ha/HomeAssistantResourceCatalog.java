package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Connection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.MetadataSnapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Selection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.StateSnapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ActionDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Cursor;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Page;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadBatch;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * One bounded metadata snapshot for one configured binding/credential/policy. State values are
 * always batch-read through the native connection, never returned from this metadata cache.
 * Metadata events invalidate immediately; the finite maximum age also bounds reuse when HA denies
 * registry-event subscriptions. Native invalidation tokens remain private: the outward metadata
 * revision advances only if projected permitted descriptors change. A refresh can prove an old page
 * still current; a changed page revision is refused, never silently restarted.
 */
public final class HomeAssistantResourceCatalog
    implements ResourceDiscovery, ResourceReader, ActionOfferValidator {
  private record Snapshot(
      String policy,
      Connection connection,
      long visibleVersion,
      long loadedAt,
      List<Description> descriptions) {}

  private final HomeAssistantResourceSource source;
  private final long metadataMaxAgeNanos;
  private final LongSupplier monotonicClock;
  private final Object lock = new Object();
  private HomeAssistantDiscoveryPolicy policy;
  private Snapshot snapshot;
  private CompletableFuture<Snapshot> loading;

  /**
   * The operator supplies a finite metadata age; short request budgets are owned by each caller.
   */
  public HomeAssistantResourceCatalog(
      HomeAssistantResourceSource source,
      HomeAssistantDiscoveryPolicy policy,
      Duration metadataMaxAge) {
    this(source, policy, metadataMaxAge, System::nanoTime);
  }

  HomeAssistantResourceCatalog(
      HomeAssistantResourceSource source,
      HomeAssistantDiscoveryPolicy policy,
      Duration metadataMaxAge,
      LongSupplier monotonicClock) {
    this.monotonicClock = Objects.requireNonNull(monotonicClock);
    this.source = Objects.requireNonNull(source);
    this.policy = Objects.requireNonNull(policy);
    if (!source.binding().equals(policy.binding()))
      throw new IllegalArgumentException("HA source credential binding does not match policy");
    Objects.requireNonNull(metadataMaxAge);
    if (metadataMaxAge.isZero()
        || metadataMaxAge.isNegative()
        || metadataMaxAge.compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException(
          "HA metadata maximum age must be positive and at most five minutes");
    metadataMaxAgeNanos = metadataMaxAge.toNanos();
  }

  /**
   * A policy change invalidates all pages immediately, including an in-flight old-policy load.
   * Credential rebinding requires a new source/catalogue instead of transferring cached evidence.
   */
  public void replacePolicy(HomeAssistantDiscoveryPolicy replacement) {
    Objects.requireNonNull(replacement);
    synchronized (lock) {
      if (!source.binding().equals(replacement.binding()))
        throw new IllegalArgumentException("HA policy belongs to another credential binding");
      if (replacement == policy) return;
      if (replacement.revision().equals(policy.revision()))
        throw new IllegalArgumentException("HA policy changes require a new revision");
      policy = replacement;
      snapshot = null;
    }
  }

  @Override
  public Page search(Search search, OperationBudget budget) throws IOException {
    Objects.requireNonNull(search);
    Snapshot selected = metadata(search.cursor(), budget);
    Connection current = source.connection();
    HomeAssistantDiscoveryPolicy admitted = policyFor(selected, current, budget);
    Revision revision = revision(admitted, current, selected);
    if (search.area().isPresent()) {
      boolean configured =
          admitted.references().stream()
              .anyMatch(reference -> admitted.selection(reference).orElseThrow().metadata().area());
      if (!configured
          || selected.descriptions().stream()
              .anyMatch(
                  value ->
                      value.registries().entities() != RegistryAvailability.AVAILABLE
                          || value.registries().devices() != RegistryAvailability.AVAILABLE
                          || value.registries().areas() != RegistryAvailability.AVAILABLE))
        throw new IOException("HA area filtering is unavailable under this policy/capability");
    }
    String text = search.text().toLowerCase(Locale.ROOT);
    List<Description> filtered =
        selected.descriptions().stream()
            .filter(
                value ->
                    text.isEmpty()
                        || value.name().toLowerCase(Locale.ROOT).contains(text)
                        || value.reference().alias().toLowerCase(Locale.ROOT).contains(text)
                        || value.entityId().contains(text))
            .filter(
                value -> search.domain().isEmpty() || search.domain().get().equals(value.domain()))
            .filter(value -> search.area().isEmpty() || value.area().equals(search.area()))
            .toList();
    int offset = search.cursor().map(Cursor::offset).orElse(0);
    if (offset > filtered.size()) throw unavailable();
    int end = Math.min(filtered.size(), offset + search.limit());
    Page result =
        new Page(
            revision,
            filtered.subList(offset, end),
            end < filtered.size() ? Optional.of(new Cursor(revision, end)) : Optional.empty());
    policyFor(selected, source.connection(), budget);
    return result;
  }

  @Override
  public Optional<Description> describe(
      Reference reference, Revision revision, OperationBudget budget) throws IOException {
    Snapshot selected = metadata(Optional.of(new Cursor(revision, 0)), budget);
    policyFor(selected, source.connection(), budget);
    Optional<Description> result =
        selected.descriptions().stream()
            .filter(value -> value.reference().equals(reference))
            .findFirst();
    policyFor(selected, source.connection(), budget);
    return result;
  }

  @Override
  public List<ActionDescription> actions(
      Reference reference, Revision revision, OperationBudget budget) throws IOException {
    Snapshot selected = metadata(Optional.of(new Cursor(revision, 0)), budget);
    HomeAssistantDiscoveryPolicy admitted = policyFor(selected, source.connection(), budget);
    List<ActionDescription> result = admitted.actions(reference, revision);
    policyFor(selected, source.connection(), budget);
    return result;
  }

  /**
   * Final queued-effect fence: never loads metadata or issues a transport request. Connection
   * snapshots are taken outside the catalogue lock to avoid adapter/catalogue lock inversion. The
   * second source sample is accepted only while the cached policy/snapshot remains unchanged. These
   * checks admit work; they do not hold locks through a native effect or cancel later changes.
   * Dirty/expired metadata requires a new explicit offer.
   */
  @Override
  public void requireCurrent(Reference reference, ConfiguredAction offered, OperationBudget budget)
      throws IOException {
    budget.remaining();
    Connection before = source.connection();
    Snapshot selected;
    HomeAssistantDiscoveryPolicy admitted;
    synchronized (lock) {
      selected = snapshot;
      admitted = policy;
      if (selected == null || !fresh(selected, admitted, before)) throw unavailable();
      requireExact(offered.revision(), selected, admitted, before);
      if (!admitted.actions(reference, offered.revision()).contains(offered)) throw unavailable();
    }
    Connection after = source.connection();
    budget.remaining();
    synchronized (lock) {
      if (snapshot != selected
          || policy != admitted
          || !before.equals(after)
          || !fresh(selected, admitted, after)) throw unavailable();
    }
  }

  @Override
  public ReadBatch read(ReadRequest request, OperationBudget budget) throws IOException {
    Objects.requireNonNull(request);
    budget.remaining();
    HomeAssistantDiscoveryPolicy admitted;
    synchronized (lock) {
      admitted = policy;
      admitted.require(request);
    }
    Snapshot selected = metadata(Optional.of(new Cursor(request.revision(), 0)), budget);
    Connection before = source.connection();
    if (policyFor(selected, before, budget) != admitted) throw unavailable();
    requireExact(request.revision(), selected, admitted, before);
    List<Selection> selections =
        request.references().stream().map(reference -> selection(admitted, reference)).toList();
    StateSnapshot fetched = source.read(selections, budget);
    budget.remaining();
    Connection current = source.connection();
    synchronized (lock) {
      if (policy != admitted || !before.equals(fetched.connection()) || !before.equals(current))
        throw unavailable();
      Map<Reference, State> states = new HashMap<>();
      fetched
          .states()
          .forEach(
              value -> {
                if (states.put(value.reference(), value) != null)
                  throw new IllegalArgumentException("duplicate HA state");
              });
      if (!states.keySet().equals(Set.copyOf(request.references())))
        throw new IOException("HA batch selection mismatch");
      ReadBatch result =
          new ReadBatch(
              request.revision(),
              request.references().stream()
                  .map(reference -> admitted.project(states.get(reference), request.revision()))
                  .toList());
      budget.remaining();
      return result;
    }
  }

  private Snapshot metadata(Optional<Cursor> cursor, OperationBudget budget) throws IOException {
    budget.remaining();
    Connection current = source.connection();
    if (!current.connected()) throw unavailable();
    HomeAssistantDiscoveryPolicy admitted;
    CompletableFuture<Snapshot> flight;
    Snapshot cached;
    boolean elected = false;
    synchronized (lock) {
      admitted = policy;
      if (cursor.isPresent()) {
        admitted.requireRevision(cursor.get().revision());
        if (!cursor.get().revision().epoch().equals(current.epoch())) throw unavailable();
      }
      cached = snapshot != null && fresh(snapshot, admitted, current) ? snapshot : null;
      flight = loading;
      if (cached == null && flight == null) {
        flight = new CompletableFuture<>();
        loading = flight;
        elected = true;
      }
    }
    if (cached != null) return match(cached, cursor, budget);
    if (!elected) return match(await(flight, budget), cursor, budget);
    Snapshot ready;
    try {
      MetadataSnapshot fetched =
          source.metadata(
              admitted.references().stream()
                  .sorted(Comparator.comparing(Reference::alias))
                  .map(value -> selection(admitted, value))
                  .toList(),
              budget);
      budget.remaining();
      Connection after = source.connection();
      List<Description> permitted =
          fetched.descriptions().stream()
              .map(admitted::project)
              .flatMap(Optional::stream)
              .sorted(Comparator.comparing(value -> value.reference().alias()))
              .toList();
      if (permitted.stream().map(Description::reference).distinct().count() != permitted.size())
        throw new IOException("duplicate HA resource metadata");
      synchronized (lock) {
        if (policy != admitted || !sameMetadata(fetched.connection(), after) || !after.connected())
          throw unavailable();
        long visible = 1;
        if (snapshot != null
            && snapshot.policy().equals(admitted.revision())
            && snapshot.connection().epoch().equals(after.epoch())) {
          visible = snapshot.visibleVersion();
          if (!snapshot.descriptions().equals(permitted)) visible = Math.incrementExact(visible);
        }
        ready =
            new Snapshot(
                admitted.revision(),
                fetched.connection(),
                visible,
                monotonicClock.getAsLong(),
                permitted);
        snapshot = ready;
      }
      flight.complete(ready);
    } catch (IOException | RuntimeException failure) {
      flight.completeExceptionally(failure);
      throw failure;
    } finally {
      synchronized (lock) {
        if (loading == flight) loading = null;
      }
    }
    return match(ready, cursor, budget);
  }

  private Snapshot match(Snapshot selected, Optional<Cursor> cursor, OperationBudget budget)
      throws IOException {
    Connection current = source.connection();
    HomeAssistantDiscoveryPolicy admitted = policyFor(selected, current, budget);
    if (cursor.isPresent()) requireMetadata(cursor.get().revision(), selected, admitted, current);
    return selected;
  }

  private HomeAssistantDiscoveryPolicy policyFor(
      Snapshot selected, Connection current, OperationBudget budget) throws IOException {
    budget.remaining();
    synchronized (lock) {
      if (!fresh(selected, policy, current)) throw unavailable();
      return policy;
    }
  }

  private boolean fresh(
      Snapshot selected, HomeAssistantDiscoveryPolicy admitted, Connection current) {
    return current.connected()
        && selected.policy().equals(admitted.revision())
        && sameMetadata(selected.connection(), current)
        && monotonicClock.getAsLong() - selected.loadedAt() < metadataMaxAgeNanos;
  }

  private static Selection selection(HomeAssistantDiscoveryPolicy policy, Reference reference) {
    HomeAssistantDiscoveryPolicy.Selection selected = policy.selection(reference).orElseThrow();
    return new Selection(
        reference, selected.entityId(), selected.attributes(), selected.metadata());
  }

  private static Revision revision(
      HomeAssistantDiscoveryPolicy policy, Connection current, Snapshot selected) {
    return new Revision(
        policy.binding(),
        policy.revision(),
        current.epoch(),
        selected.visibleVersion(),
        current.stateVersion());
  }

  private static boolean sameMetadata(Connection a, Connection b) {
    return a.epoch().equals(b.epoch()) && a.metadataVersion() == b.metadataVersion();
  }

  private static void requireMetadata(
      Revision asked, Snapshot selected, HomeAssistantDiscoveryPolicy policy, Connection current)
      throws IOException {
    policy.requireRevision(asked);
    if (!current.connected()
        || !asked.epoch().equals(current.epoch())
        || asked.metadataVersion() != selected.visibleVersion()) throw unavailable();
  }

  private static void requireExact(
      Revision asked, Snapshot selected, HomeAssistantDiscoveryPolicy policy, Connection current)
      throws IOException {
    requireMetadata(asked, selected, policy, current);
    if (asked.stateVersion() != current.stateVersion()) throw unavailable();
  }

  private static Snapshot await(CompletableFuture<Snapshot> future, OperationBudget budget)
      throws IOException {
    try {
      Snapshot result = future.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
      budget.remaining();
      return result;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("HA metadata wait interrupted");
    } catch (ExecutionException failed) {
      throw new IOException("HA metadata unavailable");
    } catch (TimeoutException expired) {
      throw new IOException("HA metadata deadline expired");
    }
  }

  private static IOException unavailable() {
    return new IOException("HA resource revision is unavailable; request a fresh catalogue");
  }
}
