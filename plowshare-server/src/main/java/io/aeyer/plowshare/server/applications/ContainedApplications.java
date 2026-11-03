package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Parent-authorized creation of a confined child Application.
 *
 * <p>This is ADR 0005's deployment lifecycle with its admission rule and its destination replaced,
 * and nothing else: the package limits, the request-UUID receipt and the staging-before-commit
 * order are the same. What differs is who may ask and where the source lands. There is no
 * server-administrator check and no destination FileStore {@code MANAGER} check on this path — the
 * parent's own retained admission, narrowed by {@code OperationAuthority.contain}, is the
 * authority, and the server allocates the source area under its configured containment root. The
 * administrator-gated {@code application.deploy} path is untouched and keeps both of its checks.
 *
 * <p>Nesting is opt-in: the creating Application's own manifest must declare it, so no existing
 * Application acquires the capability by upgrade alone.
 *
 * <p><strong>A refusal is total.</strong> Every validation — the package, the child's declared
 * surfaces against the parent's hold, the breadth bound, the ceiling — completes before any durable
 * write and before any byte is written under the containment root. What follows runs in one unit of
 * work whose failure rolls back the project row, the contained scope, the principal, the allowance
 * and the containment record together, and whose staged source is collected on the way out.
 * Honouring a child's declaration of a surface beyond its parent's hold, and accepting the package
 * while silently dropping that declaration, are both failures.
 */
public interface ContainedApplications {
  /**
   * A creation request as the frame has already converted and bounded it.
   *
   * @param conversation the parent's own live conversation; its retained admission and its budget
   *     are the authority and the ceiling bound for this creation, and neither is caller-supplied
   * @param requestId the caller's idempotency key, unique within this container
   * @param ceiling the child's own allowance ceiling, which the parent sets and may later reduce to
   *     zero. Never absent: {@link io.aeyer.plowshare.server.allowances.AllowanceSnapshot}
   *     documents an absent ceiling as <em>unlimited</em>, so a contained child created without one
   *     would be unbounded. Zero is a child that cannot make a call, which is a permitted and
   *     useful state.
   * @param requestedDestination must be absent. The field exists for request-shape compatibility
   *     with ADR 0005 — every SDK sends the key — but a named destination is <em>refused</em>
   *     rather than accepted and dropped: this path allocates the child's source area itself under
   *     {@link ContainmentConfig#root()}, and a parent typically holds no FileStore {@code MANAGER}
   *     over its own retained source, so proxying a destination could not yield a legal one either.
   *     A field accepted and discarded reads to a caller as supported.
   * @param writableAreas runtime writable areas the child asks for; in this slice a child's
   *     writable area is allocated under its own server-owned root, so any entry here names a
   *     FileStore outside the parent's fence and is refused
   * @param files the child's complete source package
   */
  record Create(
      String conversation,
      UUID requestId,
      Integer ceiling,
      FileStoreReference requestedDestination,
      List<FileStoreReference> writableAreas,
      List<File> files) {
    public Create {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(files, "files");
      writableAreas = List.copyOf(writableAreas == null ? List.of() : writableAreas);
      files = List.copyOf(files);
      if (conversation == null || conversation.isBlank() || conversation.length() > 1024)
        throw new IllegalArgumentException(
            "A contained Application is created inside the creating Application's conversation");
      // Absent is unlimited, so it is not representable here. Negative is not a ceiling at all.
      if (ceiling == null || ceiling < 0)
        throw new IllegalArgumentException(
            "A contained Application needs an explicit non-negative allowance ceiling");
    }
  }

  /**
   * The child as its container addresses it afterwards: read, reconfigure, destroy.
   *
   * <p>A boundary DTO, so every component is a plain wire value. {@code scope} is the authority
   * scope's identifier and deliberately <em>not</em> {@link ScopeId}: that is an internal value
   * object with no {@code @JsonValue}, so carrying one here would have published {@code {"value":
   * "..."}} as the public contract shape and let an internal type travel outward through five
   * generated SDKs. Internal callers wrap it back into a {@link ScopeId} where they need the typed
   * form; the conversion belongs at the boundary and the boundary is this record.
   */
  record Child(UUID id, UUID scope, String project, UUID revision) {
    public Child {
      Objects.requireNonNull(id);
      Objects.requireNonNull(scope);
      Objects.requireNonNull(revision);
      if (project == null || !project.startsWith("contained:"))
        throw new IllegalArgumentException("A contained project carries the reserved prefix");
    }
  }

  /**
   * One retained conversation envelope of a contained Application, as its container reads it.
   *
   * <p>A boundary DTO over {@link
   * io.aeyer.plowshare.server.access.OperationAuthority.Conversation}, which is a durable execution
   * envelope whose provenance and budget owner are never inferred on resume. What crosses out here
   * is the evidence a container needs to see what it confined do: the conversation, the agent that
   * answered it, its delegation {@code parent}, the allowance its spend was attributed to and the
   * capability fingerprint it ran under. The admission itself does not cross: it carries grant
   * identifiers and an account id, which are the container's own authority restated, not the
   * child's behaviour.
   *
   * <p>Evidence, not permission. {@code OperationAuthority.Conversation} says so of itself —
   * callers must still recheck live authority — and both read paths here do, before any of this is
   * assembled.
   */
  record Step(String conversation, String agent, String parent, UUID budget, String capabilities) {
    public Step {
      Objects.requireNonNull(conversation);
      Objects.requireNonNull(budget);
      if (agent == null || agent.isBlank())
        throw new IllegalArgumentException("A retained conversation names its agent");
    }
  }

  /**
   * One source resource of a contained Application, described and never delivered.
   *
   * <p>A boundary DTO, so every component is a plain wire value. There is deliberately no content
   * component: a child's writes are untrusted input to its container and a prompt-injection surface
   * by construction, so this listing carries only what lets a container decide what to ask about.
   * Child bytes cannot reach a container's prompt through it.
   *
   * @param path the resource's portable relative path within the child's source package
   * @param kind {@code manifest} for the Application manifest, otherwise {@code file}
   * @param revision the child's retained revision this resource belongs to
   * @param bytes the resource's size on disk
   */
  record Resource(String path, String kind, UUID revision, long bytes) {
    public Resource {
      Objects.requireNonNull(revision);
      if (path == null || path.isBlank() || path.length() > 1024)
        throw new IllegalArgumentException("A resource names its path");
      if (!"manifest".equals(kind) && !"file".equals(kind))
        throw new IllegalArgumentException("A resource is a manifest or a file");
      if (bytes < 0) throw new IllegalArgumentException("A resource has a non-negative size");
    }
  }

  /**
   * A request to start work inside one contained Application, as the frame has converted it.
   *
   * <p>There is deliberately no project field. {@code project(String)} refuses a contained scope,
   * and the run's home is resolved from the child's own stored row, so a caller names the child by
   * UUID and has no way to name the tier its work runs in.
   *
   * @param conversation the container's own live conversation; its retained admission is the
   *     authority for this start, exactly as it is for {@link #create} and {@link #ceiling}, and it
   *     is not the conversation the run itself is a turn in
   * @param child the Application to start work inside, named by its contained record's id
   * @param agent the agent to run, resolved against the <em>child's</em> own package and nothing
   *     else, so a container cannot name an agent of its own and have it run inside the child
   * @param task what the child is being asked to do, in prose. Never null, and never blank: the
   *     frame refuses a blank string before this record is built, so a task that reaches here is
   *     one the run service will accept
   * @param maxTurns how many turns this run may take, or {@code null} to decide nothing and leave
   *     the agent's own cap. Unlike a ceiling, absence here is not unlimited — the agent's cap
   *     stands — so {@code null} is representable and zero is not a cap at all
   */
  record Run(String conversation, UUID child, String agent, String task, Integer maxTurns) {
    public Run {
      Objects.requireNonNull(child, "child");
      Objects.requireNonNull(task, "task");
      if (conversation == null || conversation.isBlank() || conversation.length() > 1024)
        throw new IllegalArgumentException(
            "Work inside a contained Application is started from a live conversation of its"
                + " container");
      if (agent == null || agent.isBlank())
        throw new IllegalArgumentException("A run names the agent it starts");
      if (maxTurns != null && maxTurns <= 0)
        throw new IllegalArgumentException("A turn cap is positive, or absent to decide nothing");
    }
  }

  /**
   * One started run inside a contained Application, as its container reads the answer back.
   *
   * <p>A boundary DTO, so every component is a plain wire value and no {@link ScopeId} travels, for
   * the reason {@link Child} records. Nothing child-authored is in it: the agent name is the
   * registry's own spelling of what the container asked for, and the job and conversation
   * identifiers are the server's.
   *
   * @param child the Application the caller named
   * @param job the job id, pollable at once; a run is minutes, so this answer is a handle and not
   *     an outcome
   * @param conversation the conversation opened in the child's own home that this run is the first
   *     turn of — the envelope {@link #trajectory} later reads back to its container
   * @param agent the definition's own name, as the registry spells it
   */
  record Running(UUID child, String job, String conversation, String agent) {
    public Running {
      Objects.requireNonNull(child);
      if (job == null || job.isBlank())
        throw new IllegalArgumentException("A started run names its job");
      if (conversation == null || conversation.isBlank())
        throw new IllegalArgumentException("A started run names the conversation it opened");
      if (agent == null || agent.isBlank())
        throw new IllegalArgumentException("A started run names its agent");
    }
  }

  /** Validates, allocates and admits a child, or refuses leaving nothing behind. */
  Child create(String account, Create request);

  /**
   * Starts work inside one contained Application: the first operation that executes a child.
   *
   * <p><strong>Container-only, and deliberately not the ancestor gate {@link #trajectory}
   * uses.</strong> {@code trajectory} and {@link #resources} walk the containment chain upwards, so
   * a grandparent reads through it. This does not, and the asymmetry is the point rather than an
   * oversight: starting work spends the child's own allowance and occupies the direct container's
   * breadth accounting, and both of those belong to the container that lent them. A grandparent
   * raising work inside a grandchild would spend under a ceiling the intermediate container set and
   * never agreed to lend. So the rule here is {@link #create}'s and {@link #ceiling}'s — the
   * caller's retained admission must be acting as the scope this child is recorded under — and a
   * grandparent, a sibling, a child naming its own container and an unrelated scope are all
   * refused.
   *
   * <p>Do not cite the trajectory/resources pair as the precedent for this asymmetry: ADR 0042 used
   * to describe those two as differing on a destroyed child and that description was wrong -- they
   * refuse a destroyed child identically, because destruction cascades the record away and both
   * open by resolving it. The asymmetry here is real and stands on its own argument above.
   *
   * <p><strong>The child is the executor and the accounting subject.</strong> The run's caller
   * identity is the child's own ephemeral principal and its speaker is the harness naming the
   * child, so the container's handle appears nowhere on the run. ADR 0025's immutable attribution
   * is untouched and no counter is shared between container and child.
   *
   * <p><strong>The order is the contract</strong>, and it is {@code ceiling}'s: the configuration,
   * the caller's live authority, the child's row, the direct-container rule, a destroyed child, a
   * zero ceiling, the home, the agent against the child's own package, and only then the start.
   * Nothing before the authority proof reads the child's package or touches its source area —
   * unlike {@code trajectory} and {@code resources}, whose lookup-before-proof existence oracle ADR
   * 0042 records as a known limitation and which this operation does not copy.
   *
   * <p><strong>Child output is untrusted input to its container.</strong> What comes back here is
   * the server's own identifiers, never anything the child wrote; a consumer that later composes a
   * decision out of child-authored content has re-opened by consumption what this shape closes.
   */
  Running run(String account, Run request);

  /**
   * The live Applications the caller's conversation directly contains.
   *
   * <p>Takes a conversation and not a project name, for {@link #ceiling}'s reasons: the retained
   * admission is the authority and the scope it lists is the scope that admission acts as, so a
   * caller cannot name a container it is not. A project name would have to be resolved to a scope,
   * and the general scope read refuses a contained row — which would have failed this listing
   * closed for every container that is itself contained, exactly as it once failed {@code ceiling}.
   *
   * <p>Direct children only, and this is what makes siblings invisible rather than merely
   * unmentioned: the answer is keyed on the caller's own scope, so a child asking lists what
   * <em>it</em> contains — nothing, in the ordinary case — and never its own level. There is no
   * argument through which it could ask about another.
   */
  List<Child> list(String account, String conversation);

  /**
   * One contained Application's retained conversation lineage, oldest first.
   *
   * <p>Authorized exactly as {@link #ceiling} authorizes a reconfiguration, and then once more: the
   * caller's retained admission must be an <em>ancestor</em> of the child's stored container, which
   * is the containment relation walked upwards from the child. A grandparent therefore reads
   * through the chain, and a sibling, a child's own container or an unrelated scope never appears
   * on it. The walk is bounded by the configured depth, so a chain that ought to be impossible
   * refuses rather than running.
   *
   * <p>Nothing travels the other way. A contained Application asking this about its container finds
   * no containment row to compare against, or a chain that does not pass through its own scope, and
   * is refused before any envelope is read.
   */
  List<Step> trajectory(String account, String conversation, UUID child);

  /**
   * The source resources of one live contained Application, by path, as metadata only.
   *
   * <p>Authorized exactly as {@link #trajectory} is, through the same ancestor gate, and refused
   * before the child's source area is touched: a child naming its container, a sibling and an
   * unrelated scope never cause a directory to be read. A destroyed child is refused rather than
   * answered from whatever source survives, because history is not a resource.
   *
   * <p>No content is returned, by decision: see {@link Resource}.
   */
  List<Resource> resources(String account, String conversation, UUID child);

  /**
   * Lowers or raises a child's own ceiling under its container's authority. Zero disables the
   * child's calls; {@code null} is refused, because absent means unlimited.
   *
   * <p>It takes a conversation for the same two reasons {@link #create} does, and they are the
   * reasons the two paths must not differ. The conversation's retained admission is the container's
   * authority, and it must be acting as the scope this child is recorded under — so a container
   * reconfigures its own children and nothing else. The conversation's budget is the pool the new
   * ceiling is bounded against, under exactly the bound creation applies: without it a container
   * could create a child with a legal ceiling and raise it past its own remaining allowance a
   * moment later, which would make the creation bound decorative. A raise is therefore permitted
   * <em>within</em> the container's remaining allowance rather than refused as a class.
   */
  void ceiling(String account, String conversation, UUID child, Integer calls);

  /**
   * Stops the work going on inside one contained Application and every descendant beneath it.
   *
   * <p><strong>Container-only, and idempotent.</strong> The rule is {@link #run}'s and {@link
   * #ceiling}'s rather than the ancestor gate the two reads use, and for {@code run}'s reason: the
   * allowance a descendant run is spending was lent by its <em>direct</em> container, so stopping
   * it is that container's call and not a grandparent's. A sibling, a grandparent, a child naming
   * its own container and an unrelated scope are all refused. Cancelling a child that holds no live
   * run is a success and not a refusal — the idiom {@code ContainedPrincipals.revoke} already sets
   * for removing nothing — because a caller that had to tell "nothing was running" apart from "you
   * may not ask" could not stop work idempotently.
   *
   * <p><strong>The subtree, deepest first</strong>, over the same ordered set {@link #destroy}'s
   * cascade walks. One notion of "a child's work" rather than two: a descendant's run was started
   * under an allowance this child lent out of the one its own container lent it, so a cancellation
   * that stopped only the named child would leave the work it set in motion running.
   *
   * <p><strong>Stopping, not erasing.</strong> A cancellation removes no retained conversation
   * envelope and no source area. That is deliberate and is the same asymmetry ADR 0042 records
   * between {@code trajectory} and {@code resources}: a container must be able to read back what it
   * stopped, so {@code Origin.CONTAINED} carries {@code DELEGATION}'s retention and nothing here
   * touches it.
   *
   * <p><strong>A request and not an interrupt.</strong> A run honours the stop at its next turn
   * boundary, so this returns before the work has necessarily finished; what it guarantees is that
   * no further turn will be started. The reachable runs are also this process's, which {@code
   * CancellableRuns} states and which this operation inherits rather than improves on.
   */
  void cancel(String account, String conversation, UUID child);

  /**
   * What a destruction collected, as its container reads the answer back.
   *
   * <p>A boundary DTO, so every component is a plain wire value and no {@link ScopeId} travels. It
   * exists because collection has to be <em>observable</em>: a destruction that silently removed a
   * subtree would be indistinguishable from one that removed nothing, and from one that removed the
   * wrong thing.
   *
   * @param child the Application the caller named
   * @param collected every record removed — the named child and its descendants — deepest first,
   *     which is the order the schema requires and therefore the order this reports
   * @param sources how many of those source areas were removed from disk before this answer. It may
   *     be fewer than {@code collected}: the filesystem cannot join the transaction, so a committed
   *     destruction whose removal is interrupted leaves an unmarked collection record that {@link
   *     ContainedSourceCollector} finishes. Fewer is a recoverable state, never a lost one.
   */
  record Destroyed(UUID child, List<UUID> collected, int sources) {
    public Destroyed {
      Objects.requireNonNull(child);
      collected = List.copyOf(collected);
      if (!collected.contains(child))
        throw new IllegalArgumentException("A destruction accounts for the Application it names");
      if (sources < 0 || sources > collected.size())
        throw new IllegalArgumentException("A destruction collects at most one source per record");
    }
  }

  /**
   * Destroys one contained Application and every descendant beneath it.
   *
   * <p>Authorized exactly as {@link #ceiling} authorizes a reconfiguration, and for the same
   * reasons: the conversation's retained admission is the container's authority, live authority is
   * rechecked rather than trusted, and that admission must be acting as the scope this child is
   * recorded under — so a container destroys its own children and nothing else. A caller naming a
   * sibling, its own container or an unrelated Application is refused before anything is removed.
   *
   * <p>Descendants go with it, deepest first, which is both the schema's requirement and the
   * design's: a surviving descendant of a destroyed container is the orphan this capability names
   * as failure. Each descendant's allowance is zeroed before its rows go, so work in flight cannot
   * claim another call on the way out.
   *
   * <p><strong>Every descendant's work is stopped before the first source area is removed</strong>,
   * over that same deepest-first set, so no run is left reading a directory this destruction is
   * collecting. The stop is {@link #cancel}'s and carries its terms: a request honoured at the next
   * turn boundary, reaching this process's runs. It is not reported in {@link Destroyed} — {@code
   * collected} and {@code sources} mean exactly what they meant before, and that shape travels
   * through five SDKs.
   *
   * <p>A destroyed child is <strong>not</strong> promoted to a retained release. ADR 0005's release
   * history records what may be activated and a destroyed child may not be activated at all; what
   * is written instead is a collection record, which is history and carries no digest, file count
   * or destination.
   */
  Destroyed destroy(String account, String conversation, UUID child);
}
