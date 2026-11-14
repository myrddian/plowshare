package io.aeyer.plowshare.server.information;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One tenant-independent row group that the targeted readability check reads, as an immutable typed
 * value: a revision's own facts or a resource's own facts. Each value is cached under exactly one
 * READABILITY item ({@code revision:<id>} or {@code resource:<id>}, migration V257), and every
 * column here moves that item's counter on write, or is immutable once its revision commits.
 *
 * <p>No value is an allow decision: {@link KnowledgeReadability} combines these facts with the
 * caller's authority snapshot. Absent and oversized rows are values too, so a negative answer is
 * cached under the same item that an insert would move.
 */
public sealed interface KnowledgeFact {
  /** Most edges of one kind a fact holds; one more makes the row {@link Oversized}. */
  int MAX_EDGES = 1024;

  /** Approximate retained bytes, for the cache's weight budget. */
  long weight();

  /**
   * One {@code information_revisions} row, item {@code revision:<id>}.
   *
   * @param active {@code availability='active'}
   * @param allowance the row has its {@code execution_allowances} row, so it appears in {@code
   *     information_revision_allowances}; created with the revision and never removed while
   *     referenced (V173)
   * @param typedContent an {@code information_revision_content} row exists; inserted with the
   *     revision and immutable (V143)
   * @param explicitOnly {@code processing_policy='explicit_only'}; immutable (V144)
   * @param inputs {@code information_inputs.input_revision} where this is the derived revision
   * @param links {@code information_links.project_id} of this revision
   */
  record Revision(
      UUID id,
      UUID resource,
      boolean active,
      boolean allowance,
      boolean typedContent,
      boolean explicitOnly,
      List<UUID> inputs,
      List<Long> links)
      implements KnowledgeFact {
    public Revision {
      Objects.requireNonNull(id);
      Objects.requireNonNull(resource);
      inputs = List.copyOf(inputs);
      links = List.copyOf(links);
      if (inputs.size() > MAX_EDGES || links.size() > MAX_EDGES)
        throw new IllegalArgumentException("Revision fact exceeds its edge bound");
    }

    @Override
    public long weight() {
      return 256L + 32L * inputs.size() + 24L * links.size();
    }
  }

  /**
   * One {@code information_resources} row, item {@code resource:<id>}, with what V257 addresses to
   * that item: its root policy, its document navigation policy (platform and project policies move
   * the domain fallback item), its navigation head (any revision insert, delete or reorder moves
   * the resource item) and the reference obligations whose source it is.
   *
   * @param homeScope {@code home_scope_id}; the scope's own columns come from the authority
   *     snapshot, because {@code authority_scopes} moves the AUTHORITY version, not an item
   * @param policy the {@code information_resource_policies} row, if any
   * @param navigation {@code information_navigation_policy(id)} converted at the repository
   *     boundary
   * @param head {@code information_navigation_head(id)}: the revision with the highest ordinal
   */
  record Resource(
      UUID id,
      Optional<UUID> homeScope,
      Optional<RootPolicy> policy,
      NavigationPolicy navigation,
      Optional<UUID> head,
      List<Obligation> obligations)
      implements KnowledgeFact {
    public Resource {
      Objects.requireNonNull(id);
      Objects.requireNonNull(homeScope);
      Objects.requireNonNull(policy);
      Objects.requireNonNull(navigation);
      Objects.requireNonNull(head);
      obligations = List.copyOf(obligations);
      if (obligations.size() > MAX_EDGES)
        throw new IllegalArgumentException("Resource fact exceeds its edge bound");
    }

    @Override
    public long weight() {
      return 384L + 64L * obligations.size();
    }
  }

  /** {@code information_resource_policies.audience}, as constrained since V172. */
  enum Audience {
    HOME,
    GLOBAL_READ,
    QUARANTINED
  }

  /** The root policy columns the readability rule reads. */
  record RootPolicy(Audience audience, boolean privateOnly, Optional<UUID> privacyPrincipal) {
    public RootPolicy {
      Objects.requireNonNull(audience);
      Objects.requireNonNull(privacyPrincipal);
    }
  }

  /**
   * The effective navigation policy, reduced to what readability reads: {@code
   * coalesce((policy->>'ownerOnly')::boolean,true)}.
   */
  record NavigationPolicy(boolean ownerOnly) {}

  /**
   * One {@code information_reference_obligations} row of a source resource: the dependency is the
   * pinned revision, or the target's navigation head when unpinned.
   */
  record Obligation(UUID target, Optional<UUID> pinned) {
    public Obligation {
      Objects.requireNonNull(target);
      Objects.requireNonNull(pinned);
    }
  }

  /** No row with this id; an insert of it moves the same item. */
  record Absent(UUID id) implements KnowledgeFact {
    public Absent {
      Objects.requireNonNull(id);
    }

    @Override
    public long weight() {
      return 128L;
    }
  }

  /** A row with more than {@link #MAX_EDGES} edges of one kind: the check is left to SQL. */
  record Oversized(UUID id) implements KnowledgeFact {
    public Oversized {
      Objects.requireNonNull(id);
    }

    @Override
    public long weight() {
      return 128L;
    }
  }
}
