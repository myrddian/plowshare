package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * One memory: a single claim the archive stands behind, or once did.
 *
 * <p>Ported from Excalibur's {@code archive/record.py}, where the same fields
 * are a Markdown file's YAML frontmatter. Two fields are easy to confuse:
 *
 * <ul>
 *   <li>{@code summary} is the claim itself, stated so it can be read alone.
 *   <li>{@code scope} is prose describing <em>when to recall it</em> — "Working
 *       on auth, service-to-service calls, or anything in payments". It is not
 *       a tier and not a project; {@link Home} is the tier. They read alike and
 *       mean different things, which is why they are documented together here.
 * </ul>
 *
 * <p>The record is immutable and every mutator returns a copy, so a lifecycle
 * bug in a later stage cannot corrupt a caller's copy of a memory it already
 * holds — a store that hands out the same object it keeps is a store whose rows
 * change under a reader that never asked.
 *
 * @param id stable identity, assigned once and never reused
 * @param summary the claim, stated so it stands alone
 * @param scope prose saying when this memory is worth recalling
 * @param formed where and when the memory came from
 * @param state one of four, none of which means deleted
 * @param pinned held in the working set regardless of decay
 * @param uses how many times recall has returned it
 * @param lastUsed when recall last returned it, or {@code null} if never
 * @param body the full text behind the summary
 * @param supersedes the id of the memory this one replaced, or {@code null}
 * @param supersededBy the id of the memory that replaced this one, or {@code null}
 * @param invalidation the tombstone, or {@code null} while the memory is true
 * @param home the tier this memory lives in — a project, or global
 */
public record Memory(
        String id,
        String summary,
        String scope,
        Provenance formed,
        MemoryState state,
        boolean pinned,
        int uses,
        Instant lastUsed,
        String body,
        String supersedes,
        String supersededBy,
        Invalidation invalidation,
        Home home) {

    /**
     * Rejects the nulls that have no meaning, and only those.
     *
     * <p>{@code lastUsed}, {@code supersedes}, {@code supersededBy} and {@code
     * invalidation} are legitimately absent on a memory that has never been
     * recalled, replaced or invalidated. The rest are not: a {@code null}
     * {@code state} or {@code home} would reach the store as a NOT NULL
     * violation from a stack trace many layers away from whichever caller
     * dropped the field, and a {@code null} state would make the four-state
     * lifecycle five.
     */
    public Memory {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(formed, "formed");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(home, "home");
    }

    /**
     * A newly formed memory: {@code ACTIVE}, unpinned, never used, with no
     * tombstone and no supersession chain.
     *
     * <p>The canonical constructor stays public alongside this, because the
     * store has to rehydrate a stored row in whatever state it was written in —
     * a factory that could only produce active memories would make a superseded
     * row unloadable.
     */
    public static Memory formed(
            String id, String summary, String scope, String body, Provenance formed, Home home) {
        return new Memory(
                id, summary, scope, formed, MemoryState.ACTIVE, false, 0, null, body, null, null,
                null, home);
    }

    /* Withers. Each returns a copy; nothing here mutates. They live on the
     * record rather than with the lifecycle rules because they are accessors —
     * they express no policy about *when* a state may change, only how to say
     * the changed value. The lifecycle stage decides; this is what it says it
     * with. */

    public Memory withState(MemoryState newState) {
        return new Memory(id, summary, scope, formed, newState, pinned, uses, lastUsed, body,
                supersedes, supersededBy, invalidation, home);
    }

    public Memory withUses(int newUses) {
        return new Memory(id, summary, scope, formed, state, pinned, newUses, lastUsed, body,
                supersedes, supersededBy, invalidation, home);
    }

    public Memory withLastUsed(Instant newLastUsed) {
        return new Memory(id, summary, scope, formed, state, pinned, uses, newLastUsed, body,
                supersedes, supersededBy, invalidation, home);
    }

    public Memory withInvalidation(Invalidation newInvalidation) {
        return new Memory(id, summary, scope, formed, state, pinned, uses, lastUsed, body,
                supersedes, supersededBy, newInvalidation, home);
    }

    public Memory withSupersededBy(String newSupersededBy) {
        return new Memory(id, summary, scope, formed, state, pinned, uses, lastUsed, body,
                supersedes, newSupersededBy, invalidation, home);
    }

    public Memory withBody(String newBody) {
        return new Memory(id, summary, scope, formed, state, pinned, uses, lastUsed, newBody,
                supersedes, supersededBy, invalidation, home);
    }
}
