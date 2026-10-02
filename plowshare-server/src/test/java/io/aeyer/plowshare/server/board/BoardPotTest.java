package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.Budget;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link BoardPot} against a real V74 schema — {@link BoardStoreTest}'s own setup, since a lease
 * reads a root topic's row through {@link BoardStore}.
 */
@Testcontainers
class BoardPotTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private static final Instant T0 = Instant.parse("2026-09-30T09:00:00Z");
    private final AtomicLong ticks = new AtomicLong();
    private final Supplier<Instant> clock = () -> T0.plusMillis(ticks.getAndIncrement());

    private BoardStore store;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void fresh() {
        jdbc.execute("TRUNCATE TABLE firings, board_seats, board_messages, board_topics,"
                + " admins CASCADE");
        jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
        store = new BoardStore(jdbc, clock);
    }

    private BoardTopic root(int total, int reserve) {
        return root(store, total, reserve);
    }

    private BoardTopic root(BoardStore aStore, int total, int reserve) {
        return aStore.openRoot(new BoardStore.NewTopic("payments", "sync", "BAD SPEC", "enzo",
                BoardTopic.BY_PERSON, "enzo", null, total, reserve));
    }

    /**
     * A {@link BoardStore} whose {@link #spend} throws once, then behaves normally — for {@link
     * #a_failed_charge_keeps_the_allowance_and_reserve_for_a_retry}. {@link BoardStore} is
     * a plain, non-final class, so overriding one method here needs no test double library.
     */
    private static final class FlakySpend extends BoardStore {

        private boolean failedOnce;

        FlakySpend(JdbcTemplate jdbc, Supplier<Instant> clock) {
            super(jdbc, clock);
        }

        @Override
        public void spend(String root, int calls) {
            if (!failedOnce) {
                failedOnce = true;
                throw new RuntimeException("simulated: the connection to the pot dropped");
            }
            super.spend(root, calls);
        }
    }

    @Test
    void a_member_leases_up_to_its_cap_and_never_into_the_reserve() {
        BoardTopic topic = root(10, 2);
        BoardPot pot = new BoardPot(store);
        Budget first = pot.lease(topic.id(), false, 5).orElseThrow();
        Budget second = pot.lease(topic.id(), false, 5).orElseThrow();
        assertEquals(5, first.limit());
        assertEquals(3, second.limit(), "a member's lease reached into the reserve");
        assertTrue(pot.lease(topic.id(), false, 5).isEmpty());
        assertEquals(2, pot.lease(topic.id(), true, 5).orElseThrow().limit(),
                "the opener could not reach the reserve");
    }

    /**
     * The final review's I-2: a pot leased out to other wakes is not a spent one. Only {@code
     * spent} may exhaust a root; {@code leasable} going false while leases are outstanding means
     * wait, and a settle makes it true again.
     */
    @Test
    void a_pot_leased_out_is_not_leasable_but_is_not_spent_until_its_calls_are() {
        BoardTopic topic = root(10, 2);
        BoardPot pot = new BoardPot(store);
        Budget first = pot.lease(topic.id(), false, 5).orElseThrow();
        Budget second = pot.lease(topic.id(), false, 5).orElseThrow();
        assertFalse(pot.leasable(topic.id(), false));
        assertFalse(pot.spent(topic.id(), false), "leased out was taken for spent");
        assertTrue(pot.leasable(topic.id(), true), "the opener's reserve was leased to members");

        first.trySpend();
        pot.settle(topic.id(), first);
        assertTrue(pot.leasable(topic.id(), false), "a settle that gave back 4 calls freed none");
        assertFalse(pot.spent(topic.id(), false));

        Budget third = pot.lease(topic.id(), false, 10).orElseThrow();
        assertEquals(4, third.limit());
        for (Budget lease : new Budget[] {second, third}) {
            while (lease.trySpend()) {
                // spending the whole lease
            }
            pot.settle(topic.id(), lease);
        }
        assertEquals(8, store.topic(topic.id()).orElseThrow().potSpent());
        assertTrue(pot.spent(topic.id(), false), "8 of 10 spent with 2 held back is spent");
        assertFalse(pot.leasable(topic.id(), false));
        assertFalse(pot.spent(topic.id(), true), "the reserve is still the opener's to spend");
        assertTrue(pot.leasable(topic.id(), true));
    }

    @Test
    void settling_returns_unused_calls_without_charging_twice() {
        BoardTopic topic = root(10, 2);
        BoardPot pot = new BoardPot(store);
        Budget lease = pot.lease(topic.id(), false, 5).orElseThrow();
        lease.trySpend();
        lease.trySpend();
        pot.settle(topic.id(), lease);
        assertEquals(2, store.topic(topic.id()).orElseThrow().potSpent());
        assertEquals(0, pot.leased(topic.id()));
        assertEquals(6, pot.lease(topic.id(), false, 10).orElseThrow().limit());
    }

    @Test
    void a_child_topic_has_no_pot_to_lease_from() {
        BoardPot pot = new BoardPot(store);
        assertThrows(IllegalArgumentException.class, () -> pot.lease("bdt_missing", false, 5));
    }

    /**
     * The controller ruling carried in from Task 3's review: a lease is the seat job's live
     * {@link Budget}, which an operator may raise with {@code POST /v1/jobs/{id}/limits} while
     * the wake is running. {@code settle} must un-lease the amount this pot actually granted —
     * not {@link Budget#limit()}, which by settle time is the raised number — or an operator's
     * raise would either wipe out another wake's outstanding lease on the same root (if the raise
     * overshot what remained) or leak leased capacity forever (if it undershot). The pot still
     * charges the root honestly for what was actually spent, raised limit and all.
     */
    @Test
    void settling_a_raised_lease_un_leases_only_what_was_granted_and_charges_what_was_spent() {
        BoardTopic topic = root(10, 2);
        BoardPot pot = new BoardPot(store);
        Budget lease = pot.lease(topic.id(), false, 5).orElseThrow();
        Budget other = pot.lease(topic.id(), false, 5).orElseThrow();
        assertEquals(3, other.limit(), "a member's lease reached into the reserve");
        lease.changeTo(20);
        for (int i = 0; i < 7; i++) {
            assertTrue(lease.trySpend());
        }
        pot.settle(topic.id(), lease);
        assertEquals(3, pot.leased(topic.id()), "settling the raised lease should un-lease only"
                + " what it was granted (5), leaving the other lease's own share (3) untouched —"
                + " not 0, which is what lease.limit() (20) would have wiped out to");
        assertEquals(7, store.topic(topic.id()).orElseThrow().potSpent());
        // The other lease, granted before the raise and never touched, still holds its own share:
        // settling the first lease left it alone.
        pot.settle(topic.id(), other);
        assertEquals(0, pot.leased(topic.id()));

        // Settling a lease this pot never handed out, or settling the same lease twice, must not
        // throw and must not change what is leased.
        assertDoesNotThrow(() -> pot.settle(topic.id(), Budget.of(5)));
        assertDoesNotThrow(() -> pot.settle(topic.id(), lease));
        assertEquals(0, pot.leased(topic.id()));
        assertEquals(7, store.topic(topic.id()).orElseThrow().potSpent(),
                "settling an unknown lease or the same lease twice must not double-charge the pot");
    }

    /**
     * Fix round 1, finding 1: {@code settle} used to accept whatever {@code root} a caller named
     * with no check against the grant's own root. The ruling: keep the bookkeeping against the
     * grant's own root (a caller's argument is not trusted over what this pot actually recorded
     * at lease time), but a mismatch is no longer silent — {@code BoardPot} logs a WARN naming
     * both roots. This test proves the bookkeeping side: the grant's own root is what is charged
     * and un-leased, not the root named in the mismatched call.
     */
    @Test
    void settling_with_a_mismatched_root_charges_and_un_leases_the_grants_own_root() {
        BoardTopic actual = root(10, 2);
        BoardTopic other = root(10, 2);
        BoardPot pot = new BoardPot(store);
        Budget lease = pot.lease(actual.id(), false, 5).orElseThrow();
        lease.trySpend();
        lease.trySpend();
        lease.trySpend();
        pot.settle(other.id(), lease);
        assertEquals(0, pot.leased(actual.id()),
                "the grant's own root is un-leased despite the mismatched root argument");
        assertEquals(0, pot.leased(other.id()), "the mismatched root named in the call never had"
                + " anything leased from it, and settling against it grants it nothing either");
        assertEquals(3, store.topic(actual.id()).orElseThrow().potSpent(),
                "the grant's own root is charged");
        assertEquals(0, store.topic(other.id()).orElseThrow().potSpent(),
                "the mismatched root named in the call is not charged");
    }

    @Test
    void a_failed_charge_keeps_the_allowance_and_reserve_for_a_retry() {
        FlakySpend flaky = new FlakySpend(jdbc, clock);
        BoardTopic topic = root(flaky, 10, 2);
        BoardPot pot = new BoardPot(flaky);
        Budget lease = pot.lease(topic.id(), false, 5).orElseThrow();
        assertThrows(RuntimeException.class, lease::trySpend);
        assertEquals(0, lease.spent());
        assertEquals(5, pot.leased(topic.id()));
        assertEquals(0, store.topic(topic.id()).orElseThrow().potSpent());
        assertTrue(lease.trySpend());
        assertEquals(1, store.topic(topic.id()).orElseThrow().potSpent());
        assertEquals(4, pot.leased(topic.id()));
        pot.settle(topic.id(), lease);
        assertEquals(0, pot.leased(topic.id()));
        assertEquals(1, store.topic(topic.id()).orElseThrow().potSpent());
    }

    @Test
    void restart_preserves_spending_from_an_unfinished_wake_and_releases_only_unused_calls() {
        BoardTopic topic = root(10, 2);
        BoardPot before = new BoardPot(store);
        Budget lease = before.lease(topic.id(), false, 5).orElseThrow();
        assertTrue(lease.trySpend());
        assertTrue(lease.trySpend());
        assertEquals(2, store.topic(topic.id()).orElseThrow().potSpent(),
                "calls must be durable before the wake finishes");
        assertEquals(3, before.leased(topic.id()));
        assertEquals(3, before.lease(topic.id(), false, 10).orElseThrow().limit(),
                "persisted calls must not also be held as unused lease capacity");

        BoardPot after = new BoardPot(new BoardStore(jdbc, clock));
        Budget recovered = after.lease(topic.id(), false, 10).orElseThrow();
        assertEquals(6, recovered.limit(), "an interrupted wake's two calls cannot be reused");
        while (recovered.trySpend()) { }
        assertTrue(after.spent(topic.id(), false));
        assertEquals(2, after.lease(topic.id(), true, 10).orElseThrow().limit(),
                "restart must preserve the closing reserve");
        after.settle(topic.id(), recovered);
        assertEquals(8, store.topic(topic.id()).orElseThrow().potSpent(),
                "settling must not charge the persisted calls again");
    }

    @Test
    void a_durable_charge_survives_an_unrelated_outer_transaction_rollback() {
        BoardTopic topic = root(10, 2);
        BoardPot pot = new BoardPot(store);
        Budget lease = pot.lease(topic.id(), false, 5).orElseThrow();
        var work = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        work.executeWithoutResult(tx -> {
            assertTrue(lease.trySpend());
            tx.setRollbackOnly();
        });
        assertEquals(1, store.topic(topic.id()).orElseThrow().potSpent());
        pot.settle(topic.id(), lease);
        assertEquals(1, store.topic(topic.id()).orElseThrow().potSpent());
        assertThrows(IllegalStateException.class, lease::trySpend,
                "a released lease cannot send another call");
    }

    /**
     * Fix round 1, finding 3: {@code lease} used to compute {@code min(max(1, wakeCap),
     * available)}, so a {@code wakeCap} of zero or below still granted a one-call lease. Every
     * caller floors its own cap before reaching this pot ({@code SwarmProperties.wakeCapNow},
     * {@code SeatRunner}), so a sub-one {@code wakeCap} here is a caller bug, not a shape to
     * paper over.
     */
    @Test
    void a_wake_cap_below_one_is_refused_as_a_caller_bug() {
        BoardTopic topic = root(10, 2);
        BoardPot pot = new BoardPot(store);
        assertThrows(IllegalArgumentException.class, () -> pot.lease(topic.id(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> pot.lease(topic.id(), false, -3));
    }
}
