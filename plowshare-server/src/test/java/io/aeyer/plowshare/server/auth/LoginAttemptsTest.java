package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * {@link LoginAttempts} against a clock this file drives, {@code
 * TokenStoreTest}'s reason for the same shape: the lockout is fifteen minutes
 * long and no test here should spend real time waiting one out.
 *
 * <p>The limit and the lockout are read from a fresh {@link AuthProperties}
 * rather than held as constants in this file, since {@link LoginAttempts} no
 * longer has any of its own — see {@link AuthConfig#loginAttempts(AuthProperties)}
 * for why they moved to that class.
 */
class LoginAttemptsTest {

    private static final AuthProperties DEFAULTS = new AuthProperties();

    @Test
    void one_failure_short_of_the_limit_does_not_lock_the_handle() {
        LoginAttempts attempts = attempts(new Ticking());

        for (int i = 0; i < DEFAULTS.getLoginMaxFailures() - 1; i++) {
            attempts.record("root", false);
        }

        assertFalse(attempts.tooMany("root"),
                "one failure short of the limit already refuses the handle");
    }

    @Test
    void the_limit_of_failures_locks_the_handle() {
        LoginAttempts attempts = attempts(new Ticking());

        for (int i = 0; i < DEFAULTS.getLoginMaxFailures(); i++) {
            attempts.record("root", false);
        }

        assertTrue(attempts.tooMany("root"),
                "the limit of consecutive failures was reached and the handle is still not"
                        + " refused, so the throttle this class exists to be does nothing");
    }

    @Test
    void a_success_resets_the_failure_count() {
        LoginAttempts attempts = attempts(new Ticking());
        for (int i = 0; i < DEFAULTS.getLoginMaxFailures() - 1; i++) {
            attempts.record("root", false);
        }

        attempts.record("root", true);
        attempts.record("root", false);

        assertFalse(attempts.tooMany("root"),
                "a successful login did not clear the failure count, so a genuine login is left"
                        + " one stray failure away from a lockout it should not be anywhere near");
    }

    @Test
    void the_lockout_expires_after_its_own_duration() {
        Ticking clock = new Ticking();
        LoginAttempts attempts = attempts(clock);
        for (int i = 0; i < DEFAULTS.getLoginMaxFailures(); i++) {
            attempts.record("root", false);
        }
        assertTrue(attempts.tooMany("root"), "the handle was not locked to begin with");

        clock.advance(DEFAULTS.getLoginLockout().plusSeconds(1));

        assertFalse(attempts.tooMany("root"), "the lockout outlived its own configured duration");
    }

    /**
     * The failure count itself resets when a lockout expires, not only the
     * lockout — see {@link LoginAttempts}'s class note on why a failure right
     * after an expired lockout must not resume the count that produced it.
     */
    @Test
    void the_failure_count_does_not_survive_an_expired_lockout() {
        Ticking clock = new Ticking();
        LoginAttempts attempts = attempts(clock);
        for (int i = 0; i < DEFAULTS.getLoginMaxFailures(); i++) {
            attempts.record("root", false);
        }
        clock.advance(DEFAULTS.getLoginLockout().plusSeconds(1));

        for (int i = 0; i < DEFAULTS.getLoginMaxFailures() - 1; i++) {
            attempts.record("root", false);
        }

        assertFalse(attempts.tooMany("root"),
                "a failure count from before an expired lockout carried over, so the handle was"
                        + " locked out again on fewer fresh failures than the configured limit");
    }

    @Test
    void failures_under_one_handle_do_not_lock_a_different_handle() {
        LoginAttempts attempts = attempts(new Ticking());

        for (int i = 0; i < DEFAULTS.getLoginMaxFailures(); i++) {
            attempts.record("root", false);
        }

        assertFalse(attempts.tooMany("ops"),
                "failures recorded under one handle locked a handle nobody has failed to log in"
                        + " as");
    }

    @Test
    void a_handle_nobody_has_attempted_is_not_locked() {
        LoginAttempts attempts = attempts(new Ticking());

        assertFalse(attempts.tooMany("nobody-has-tried-this-handle"));
    }

    // --- bounding the map: FIX 3's other half --------------------------------

    /**
     * The finding this pins: a caller who fails under enough distinct handle
     * strings used to grow this map forever, with nothing bounding it. A
     * stale entry — one whose lockout window has fully passed with no
     * failure since — is now dropped by the very next {@link
     * LoginAttempts#record} call, whichever handle it is for, since {@link
     * LoginAttempts#sweep(java.time.Instant)} runs unconditionally at the top
     * of that method.
     */
    @Test
    void a_stale_entry_is_dropped_by_the_next_record_call_for_any_handle() {
        Ticking clock = new Ticking();
        LoginAttempts attempts = attempts(clock);
        attempts.record("attacker-guess-1", false);
        assertEquals(1, attempts.trackedHandles());

        clock.advance(DEFAULTS.getLoginLockout().plusSeconds(1));
        attempts.record("attacker-guess-2", false);

        assertEquals(1, attempts.trackedHandles(),
                "the stale entry for 'attacker-guess-1' was not swept when a later call grew the"
                        + " map, so distinct handles are unbounded again — got "
                        + attempts.trackedHandles() + " tracked handles");
    }

    /** The other half: an entry still within its own lockout window — a
     *  handle under active, sustained attack — must survive a sweep, or the
     *  throttle it exists to enforce would reset itself out from under an
     *  attack still in progress. */
    @Test
    void an_entry_still_within_its_lockout_survives_a_sweep_triggered_by_another_handle() {
        Ticking clock = new Ticking();
        LoginAttempts attempts = attempts(clock);
        for (int i = 0; i < DEFAULTS.getLoginMaxFailures(); i++) {
            attempts.record("root", false);
        }
        assertTrue(attempts.tooMany("root"));

        attempts.record("someone-else", false);

        assertTrue(attempts.tooMany("root"),
                "a sweep triggered by a different handle's record() call reset a lockout that had"
                        + " not yet expired");
    }

    /** A handle actively retried — failing again before its own lockout
     *  window would have gone stale — is never swept out from under itself,
     *  because every failure stamps a fresh {@code lastActivity}. */
    @Test
    void repeated_failures_under_one_handle_never_sweep_themselves() {
        Ticking clock = new Ticking();
        LoginAttempts attempts = attempts(clock);

        for (int i = 0; i < 50; i++) {
            attempts.record("root", false);
            clock.advance(DEFAULTS.getLoginLockout().minusSeconds(1));
        }

        assertEquals(1, attempts.trackedHandles(),
                "a handle failing steadily, always inside its own lockout window, was swept away"
                        + " by its own activity");
    }

    /**
     * The two configuration defects this class refuses at construction, on
     * {@link TokenStore}'s reasoning for its own lifetime arguments: both
     * arrive from {@link AuthProperties}, and a bad value there should fail
     * loudly at boot rather than silently either never locking anything out
     * or locking every handle out on its first attempt.
     */
    @Test
    void a_non_positive_max_failures_or_lockout_is_a_configuration_defect() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoginAttempts(new Ticking(), 0, Duration.ofMinutes(15)),
                "maxFailures of zero would never lock anything out, which is not a throttle, and"
                        + " that was not refused");
        assertThrows(IllegalArgumentException.class,
                () -> new LoginAttempts(new Ticking(), -1, Duration.ofMinutes(15)));
        assertThrows(IllegalArgumentException.class,
                () -> new LoginAttempts(new Ticking(), 5, Duration.ZERO),
                "a zero lockout refuses nothing the instant it would start, and that was not"
                        + " refused");
        assertThrows(IllegalArgumentException.class,
                () -> new LoginAttempts(new Ticking(), 5, Duration.ofMinutes(-1)));
    }

    /** {@link LoginAttempts} at this file's shipping defaults, fresh for
     *  whichever test asks. */
    private static LoginAttempts attempts(Clock clock) {
        return new LoginAttempts(clock, DEFAULTS.getLoginMaxFailures(), DEFAULTS.getLoginLockout());
    }

    /** A clock that moves only when a test moves it. */
    private static final class Ticking extends Clock {

        private volatile Instant now = Instant.parse("2026-09-01T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }
}
