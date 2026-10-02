package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Ported from Excalibur's {@code tests/archive/test_lifecycle.py} (7 tests).
 * All seven port: nothing here touches the Markdown file layout Postgres
 * replaces — these are state transitions over a record either way.
 */
class LifecycleTest {

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    private static Memory active(String id) {
        return Memory.formed(
                id, "s", "sc", "body", new Provenance(Instant.EPOCH, "probe", "test"),
                Home.global());
    }

    /** Superseding marks the old record and points it at its replacement. The
     *  body is untouched — the archive never rewrites prose, and the old text
     *  is the evidence of what was once believed. */
    @Test
    void superseding_marks_the_old_record_and_keeps_its_body() {
        Memory old = active("mem_01");
        Memory done = Lifecycle.supersede(old, "mem_02");

        assertEquals(MemoryState.SUPERSEDED, done.state());
        assertEquals("mem_02", done.supersededBy());
        assertEquals(old.body(), done.body());
        assertEquals(old.summary(), done.summary());
    }

    /** Only the backward link moves. {@code supersedes} is the *forward* link,
     *  set on the replacement when it is formed — writing it here would make
     *  the old record claim it replaced its own successor, and the chain would
     *  resolve in a circle. This is why {@code Memory} has no
     *  {@code withSupersedes}. */
    @Test
    void superseding_does_not_touch_the_forward_link() {
        Memory done = Lifecycle.supersede(active("mem_01"), "mem_02");

        assertNull(done.supersedes());
    }

    /** The exact seam matters. Excalibur's {@code merge_body} writes a dated
     *  provenance line between the two texts; without it the merged record
     *  reads as one voice, and nobody can later tell which half was added
     *  when, or by whom. */
    @Test
    void merging_appends_under_a_dated_separator() {
        Memory target = active("mem_T").withBody("first observation");

        Memory result = Lifecycle.mergeBody(target, "second observation", NOW, "claude-code");

        assertEquals(
                "first observation\n\n"
                        + "---\n"
                        + "*Merged 2026-08-16T12:00:00Z by claude-code*\n\n"
                        + "second observation",
                result.body());
    }

    /** The seam is exactly one blank line on each side however ragged the
     *  inputs are. Excalibur rstrips the target and strips the addition, so a
     *  body that already ends in a newline does not open a widening gap every
     *  time it is merged into. */
    @Test
    void merging_trims_the_ragged_edges_it_joins() {
        Memory target = active("mem_T").withBody("first observation\n\n");

        Memory result = Lifecycle.mergeBody(target, "  second observation  ", NOW, "scribe");

        assertEquals(
                "first observation\n\n---\n*Merged 2026-08-16T12:00:00Z by scribe*\n\n"
                        + "second observation",
                result.body());
    }

    /** The scribe said this <em>is</em> that memory, so its index entry still
     *  applies: identity, summary and scope are unchanged, and the record stays
     *  active. A merge that re-summarised would ask a small model to reconcile
     *  two texts into one, which is the thing appending exists to avoid. */
    @Test
    void merging_preserves_identity_summary_scope_and_state() {
        Memory target = active("mem_T");

        Memory result = Lifecycle.mergeBody(target, "more", NOW, "claude-code");

        assertEquals("mem_T", result.id());
        assertEquals("s", result.summary());
        assertEquals("sc", result.scope());
        assertEquals(MemoryState.ACTIVE, result.state());
    }

    /** The corpse is kept. An invalidated memory that lost its body would lose
     *  the very thing that lets someone judge whether the invalidation was
     *  right. The state moves too: a tombstone that still reads {@code active}
     *  is a memory recall will keep returning. */
    @Test
    void invalidating_keeps_the_body_and_records_who_and_why() {
        Memory dead = Lifecycle.invalidate(
                active("mem_01"), NOW, "claude-code", "migrated to mTLS");

        assertEquals(MemoryState.INVALIDATED, dead.state());
        assertEquals("migrated to mTLS", dead.invalidation().reason());
        assertEquals("claude-code", dead.invalidation().by());
        assertEquals(NOW, dead.invalidation().at());
        assertEquals("body", dead.body());
    }

    /** Demotion is not deletion. A cold memory is still true — merely unused —
     *  and stays reachable by id and by search. Its use count survives, because
     *  a demotion that zeroed the counter would make a memory that fell out of
     *  the working set once look like one that was never useful at all. */
    @Test
    void demotion_only_changes_state() {
        Memory cold = Lifecycle.demote(active("mem_01").withUses(3));

        assertEquals(MemoryState.COLD, cold.state());
        assertEquals("body", cold.body());
        assertEquals(3, cold.uses());
    }

    /** Increments; does not set. Starting from a non-zero count is the point of
     *  this test — from zero, an implementation that simply stamped {@code 1}
     *  would pass and then lose every use after the first. */
    @Test
    void recording_a_use_increments_and_stamps() {
        Memory used = Lifecycle.recordUse(active("mem_01").withUses(4), NOW);

        assertEquals(5, used.uses());
        assertEquals(NOW, used.lastUsed());
    }
}
