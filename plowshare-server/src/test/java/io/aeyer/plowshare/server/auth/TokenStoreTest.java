package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.auth.TokenStore.Pair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What holds the console's tokens, and what makes one stop being good.
 *
 * <h2>The assertion rule, and the correction this file had to make to it</h2>
 *
 * <p>{@code TokensTest} states the rule every assertion here obeys: <b>no assertion may print a
 * token's value on failure</b>, because a JUnit failure message goes to stdout, to a build log, and
 * into whatever CI keeps those. {@code assertTrue}, {@code assertFalse} and {@code assertThrows}
 * report only the message they were given, so a token may be an <em>operand</em> of them; {@code
 * assertEquals} interpolates both sides and {@code assertNotEquals} interpolates the actual value,
 * so a token may never be an <em>argument</em> to either.
 *
 * <p><b>{@code assertNotSame} belongs on the forbidden side of that list, and this file was drafted
 * with it on the safe side.</b> Measured against the JUnit this build resolves — 5.10.5, confirmed
 * from {@code :plowshare-server:dependencies} — by calling it on two references that are the same
 * object and reading what came back:
 *
 * <pre>
 *   expected: not same but was: &lt;the value&gt;
 * </pre>
 *
 * <p>It prints one operand, and — exactly like {@code assertNotEquals} — it fails only when the
 * rotation under test did <em>not</em> happen, so the single value it would print is a live token.
 * So {@link #refreshing_rotates_both_and_retires_the_one_presented()} is written as {@code
 * assertFalse(first.access().equals(second.access()))}, which is the spelling {@code TokensTest}
 * already uses for the same reason.
 *
 * <p>{@code assertNotSame} would also be the wrong question even if it printed nothing. It asks
 * about reference identity, and two separately minted tokens are distinct objects whatever their
 * contents — so it passes over a rotation that handed back an equal copy of the token it was given,
 * which is precisely the failure the assertion is there to catch.
 *
 * <h2>The clock, and why time is a parameter rather than a sleep</h2>
 *
 * <p>Every lifetime in here is minutes or days long, so a test that waited them out could not
 * exist. {@link Ticking} moves only when a test moves it, which is the same seam and the same shape
 * {@code SessionRegistry} takes for the same reason.
 *
 * <p>It is a {@link Clock} rather than a {@code Supplier<Instant>} because {@code SessionRegistry}
 * is a {@link Clock}, and <b>not because that is what this repository does</b> — counted, {@code
 * SessionRegistry} is the only class in {@code plowshare-server/src/main/java} that takes one,
 * against {@code Archive}, {@code ProposalStore} and {@code ConversationStore} taking a {@code
 * Supplier<Instant>}. {@link TokenStore} carries the argument for following the minority; what this
 * file adds is that the two spellings make no difference to a test, since {@link Ticking} would be
 * a two-line lambda either way.
 *
 * <p>Lifetimes are constructor arguments too, and one test uses that for more than convenience:
 * {@link #an_expired_refresh_token_is_refused_without_retiring_the_chain()} configures a refresh
 * token <em>shorter</em> than an access token, which no real configuration would, because that is
 * the only arrangement in which the difference between "this token expired" and "this token was
 * reused" is visible from outside the store at all.
 */
class TokenStoreTest {

  /** The lifetimes the design names, so the fixture is the shipping one. */
  private static final Duration ACCESS = Duration.ofMinutes(15);

  private static final Duration REFRESH = Duration.ofDays(7);

  /**
   * {@code AuthProperties}' own default for a ticket, task 3 of the auth slice: seconds, not
   * minutes or days, because a ticket is designed to sit in a URL and its short lifetime is half of
   * what makes that defensible.
   */
  private static final Duration TICKET = Duration.ofSeconds(10);

  private final Ticking clock = new Ticking();

  private final TokenStore store = new TokenStore(clock, ACCESS, REFRESH, TICKET);

  @Test
  void duplicate_intent_returns_the_same_pair_without_extending_lifetimes() {
    var parent = store.issuePair();
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    var one = store.refresh(parent.refresh(), intent).orElseThrow();
    int count = store.trackedRecords();
    clock.advance(Duration.ofSeconds(5));
    var two = store.refresh(parent.refresh(), intent).orElseThrow();
    assertTrue(one.pair().access().equals(two.pair().access()));
    assertTrue(one.pair().refresh().equals(two.pair().refresh()));
    assertEquals(ACCESS.minusSeconds(5), two.accessLifetime());
    assertEquals(REFRESH.minusSeconds(5), two.refreshLifetime());
    assertEquals(count, store.trackedRecords());
    assertTrue(store.validAccess(two.pair().access()));
  }

  @Test
  void changed_or_missing_intent_still_retires_the_chain() {
    var parent = store.issuePair();
    var one =
        store
            .refresh(parent.refresh(), new RefreshIntent(java.util.UUID.randomUUID()))
            .orElseThrow();
    assertTrue(
        store.refresh(parent.refresh(), new RefreshIntent(java.util.UUID.randomUUID())).isEmpty());
    assertFalse(store.validAccess(one.pair().access()));
    var other = store.issuePair();
    var two =
        store
            .refresh(other.refresh(), new RefreshIntent(java.util.UUID.randomUUID()))
            .orElseThrow();
    assertTrue(store.refresh(other.refresh()).isEmpty());
    assertFalse(store.validAccess(two.pair().access()));
  }

  @Test
  void intent_cannot_recover_a_legacy_rotation_or_an_expired_receipt() {
    var parent = store.issuePair();
    var legacy = store.refresh(parent.refresh()).orElseThrow();
    assertTrue(
        store.refresh(parent.refresh(), new RefreshIntent(java.util.UUID.randomUUID())).isEmpty());
    assertFalse(store.validAccess(legacy.access()));
    parent = store.issuePair();
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    var one = store.refresh(parent.refresh(), intent).orElseThrow();
    clock.advance(Duration.ofSeconds(29));
    assertTrue(store.refresh(parent.refresh(), intent).isPresent());
    clock.advance(Duration.ofSeconds(1));
    assertTrue(store.refresh(parent.refresh(), intent).isEmpty());
    assertFalse(store.validAccess(one.pair().access()));
  }

  @Test
  void logout_and_a_spent_successor_fence_duplicate_recovery() {
    var parent = store.issuePair();
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    var one = store.refresh(parent.refresh(), intent).orElseThrow();
    store.revoke(one.pair().access());
    assertTrue(store.refresh(parent.refresh(), intent).isEmpty());
    parent = store.issuePair();
    one = store.refresh(parent.refresh(), intent).orElseThrow();
    var later =
        store
            .refresh(one.pair().refresh(), new RefreshIntent(java.util.UUID.randomUUID()))
            .orElseThrow();
    assertTrue(store.refresh(parent.refresh(), intent).isEmpty());
    assertFalse(store.validAccess(later.pair().access()));
  }

  @Test
  void simultaneous_identical_intents_issue_only_one_pair() throws Exception {
    var parent = store.issuePair();
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return store.refresh(parent.refresh(), intent).orElseThrow();
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return store.refresh(parent.refresh(), intent).orElseThrow();
              });
      var one = a.get();
      var two = b.get();
      assertTrue(one.pair().access().equals(two.pair().access()));
      assertTrue(one.pair().refresh().equals(two.pair().refresh()));
      assertTrue(store.validAccess(one.pair().access()));
    }
  }

  // --- the bootstrap token -------------------------------------------------

  /**
   * The one-time token printed at startup, spent by the first visit.
   *
   * <p>"One-time" is the whole of its defence. It is the single secret this project puts in a URL —
   * the design spec permits it only because it is spent on arrival — and a URL reaches shell
   * history, a terminal scrollback and whatever the browser syncs. A second acceptance would mean
   * everything that ever saw that line is still a way in.
   */
  @Test
  void a_bootstrap_token_works_once_and_is_then_gone() {
    String bootstrap = store.mintBootstrap();

    assertTrue(store.spendBootstrap(bootstrap), "the first use must be accepted");
    assertFalse(store.spendBootstrap(bootstrap), "the second use of a one-time token must not be");
  }

  /**
   * A token this store did not mint spends nothing, and does not consume the one it did.
   *
   * <p>The second half is the half that would be assumed. An implementation that cleared the stored
   * hash before checking the presented value — or that cleared it on any presentation at all —
   * passes "a wrong token is refused" and turns the startup line into a token any passer-by can
   * invalidate before the operator has clicked it.
   */
  @Test
  void a_token_this_store_did_not_mint_spends_nothing() {
    assertFalse(
        store.spendBootstrap(Tokens.mint()), "nothing has been minted, so nothing can be spent");
    assertFalse(store.spendBootstrap(null), "a request carrying no token spent one");
    assertFalse(store.spendBootstrap("   "), "a blank spent one");

    String bootstrap = store.mintBootstrap();

    assertFalse(store.spendBootstrap(Tokens.mint()), "a foreign token was accepted");
    assertFalse(store.spendBootstrap(null), "an absent token was accepted");
    assertTrue(
        store.spendBootstrap(bootstrap),
        "the real token stopped working after something else was refused, so a"
            + " refusal is spending it");
  }

  /**
   * A second mint replaces the first, because a restart is the ordinary way one happens.
   *
   * <p>It is printed once, in the startup line, and a restart prints a new one — so the store and
   * the line have to agree on which is current. The newest is; anything else would leave a
   * restarted server accepting a token that no longer appears anywhere an operator can read. (The
   * file beside that line holds the operator token and not this one: {@code
   * TokenStore.acceptOperator} carries why the two are different credentials.)
   */
  @Test
  void a_second_mint_replaces_the_bootstrap_token_before_it() {
    String first = store.mintBootstrap();
    String second = store.mintBootstrap();

    assertFalse(store.spendBootstrap(first), "the superseded token still spends");
    assertTrue(store.spendBootstrap(second), "the current token does not");
  }

  // --- the ticket, task 3 of the auth slice --------------------------------

  /**
   * A ticket validates once and not twice.
   *
   * <p>Single-use is half of what makes a ticket acceptable in a URL at all — see the class note on
   * the fifth kind this store holds. The second presentation of the same value finds nothing, the
   * same shape {@link #a_bootstrap_token_works_once_and_is_then_gone()} asserts for the bootstrap
   * token.
   */
  @Test
  void a_ticket_validates_once_and_not_twice() {
    String ticket = store.mintTicket();

    assertTrue(store.spendTicket(ticket), "the first presentation must be accepted");
    assertFalse(
        store.spendTicket(ticket), "the second presentation of a single-use ticket must not be");
  }

  /**
   * A ticket past its lifetime does not validate, even though nothing ever presented it before.
   *
   * <p>Expiry is the other half of the defence: a ticket sits in a URL, which is read long after
   * the one HTTP round trip it was minted for, so its usefulness has to run out on a clock and not
   * only on being used once.
   */
  @Test
  void a_ticket_past_its_lifetime_does_not_validate() {
    String ticket = store.mintTicket();

    clock.advance(TICKET.plusSeconds(1));

    assertFalse(
        store.spendTicket(ticket),
        "a ticket outlived the lifetime it was minted with, and nothing ever presented"
            + " it before this call to say it was already spent");
  }

  /** Everything a ticket may not be: nothing this store minted, and nothing at all. */
  @Test
  void a_ticket_this_store_never_issued_does_not_validate() {
    assertFalse(store.spendTicket(Tokens.mint()), "a value nothing minted was accepted");
    assertFalse(store.spendTicket(null), "a request with no ticket was accepted");
    assertFalse(store.spendTicket(""), "an empty ticket was accepted");
    assertFalse(store.spendTicket("   "), "a blank ticket was accepted");
  }

  // --- a restricted chain and revocation, FIX 1 and FIX 6 ------------------

  /**
   * {@link TokenStore#issuePair(boolean)} with {@code true} is what {@link AuthController#login}
   * calls for an admin who still must change their password — see that method's own test. {@link
   * TokenStore#chainIsRestricted(String)} is what {@code AuthController#ticket} asks before minting
   * one.
   */
  @Test
  void a_pair_issued_restricted_reports_its_access_token_as_restricted() {
    Pair pair = store.issuePair(true);

    assertTrue(store.chainIsRestricted(pair.access()));
  }

  @Test
  void an_ordinarily_issued_pair_is_not_restricted() {
    Pair unrestricted = store.issuePair(false);
    Pair viaTheOldOverload = store.issuePair();

    assertFalse(store.chainIsRestricted(unrestricted.access()));
    assertFalse(store.chainIsRestricted(viaTheOldOverload.access()));
  }

  /**
   * An access token this store never issued, or none at all, is not restricted — {@link
   * TokenStore#chainIsRestricted(String)}'s own contract for why it does not have to distinguish
   * "invalid" from "unrestricted" for its one caller.
   */
  @Test
  void an_unknown_or_absent_access_token_is_not_restricted() {
    assertFalse(store.chainIsRestricted(Tokens.mint()));
    assertFalse(store.chainIsRestricted(null));
    assertFalse(store.chainIsRestricted(""));
    assertFalse(store.chainIsRestricted("   "));
  }

  /**
   * A restricted chain's access token still authenticates ordinary gated routes — {@code
   * AuthController}'s own class note on what this fix does not attempt: the restriction is one bit
   * checked by one endpoint, not a refusal {@link AuthFilter} enforces on every request.
   */
  @Test
  void a_restricted_access_token_still_authenticates_ordinary_requests() {
    Pair pair = store.issuePair(true);

    assertTrue(store.validAccess(pair.access()));
  }

  /**
   * {@link TokenStore#revoke(String)} is {@code POST /v1/auth/password}'s mechanism for ending the
   * session that changed a password. This is the whole of what it must do: both the access token
   * presented and the refresh token issued alongside it on the same chain stop working, in one call
   * — see the class note on {@link TokenStore#revoke(String)} for why that call now takes the same
   * {@code rotation} lock {@link #refreshing_rotates_both_and_retires_the_one_presented()}'s
   * subject does, and {@link #revoke_racing_a_concurrent_refresh_does_not_resurrect_the_chain()}
   * for the race that lock closes.
   */
  @Test
  void revoke_retires_the_access_token_s_whole_chain() {
    Pair pair = store.issuePair();
    assertTrue(store.validAccess(pair.access()));

    store.revoke(pair.access());

    assertFalse(
        store.validAccess(pair.access()),
        "the access token presented to revoke() was still valid afterwards");
    assertTrue(
        store.refresh(pair.refresh()).isEmpty(),
        "the refresh token on a revoked chain still rotated a new pair");
  }

  /**
   * A value naming no live access token retires nothing, and does not throw — {@link
   * #a_token_this_store_did_not_mint_spends_nothing()}'s own reasoning applied to a method with no
   * boolean to report.
   */
  @Test
  void revoke_against_no_live_access_token_is_a_silent_no_op() {
    Pair untouched = store.issuePair();

    store.revoke(null);
    store.revoke("");
    store.revoke("   ");
    store.revoke(Tokens.mint());

    assertTrue(
        store.validAccess(untouched.access()),
        "revoking a value naming no live access token affected an unrelated session");
  }

  /**
   * Revoking one chain does not reach another — the same isolation {@link
   * #retiring_a_browser_chain_does_not_reach_the_operator_token()} pins for reuse-triggered
   * retirement.
   */
  @Test
  void revoking_one_session_does_not_end_a_different_one() {
    Pair first = store.issuePair();
    Pair second = store.issuePair();

    store.revoke(first.access());

    assertFalse(store.validAccess(first.access()));
    assertTrue(
        store.validAccess(second.access()),
        "revoking one session's access token ended a completely different session");
  }

  /**
   * A password change racing the very refresh a stolen cookie would perform must not lose: the
   * resurrection {@link TokenStore#revoke(String)}'s own class note describes.
   *
   * <p>{@link #revoke_retires_the_access_token_s_whole_chain()} above proves the sequential case —
   * revoke, then refresh, is refused. It says nothing about the two running at once, which is
   * exactly the shape a changed password has to survive: the caller who just proved they know the
   * new password is one request; a refresh already in flight, from the same browser or a copy of
   * its cookies, is the other. {@link TokenStore#refresh(String)} is not one atomic step from the
   * outside — it reads whether the chain is still live, and only several lines and two {@link
   * Tokens#mint()} calls later does it write the chain back — and a revoke with no lock of its own
   * could land in exactly that gap, described at length in the method's own class note: {@code
   * chains.remove} ahead of {@code chains.put} results in the chain being resurrected by the very
   * rotation the password change was meant to end.
   *
   * <p>Raced rather than sequenced, because the gap is inside {@link TokenStore#refresh(String)}
   * and this test has no way to pause a thread there — only many attempts at meeting the timing by
   * chance, the same instrument {@link #two_simultaneous_refreshes_of_one_token_leave_one_winner()}
   * uses for the analogous gap in that method. Whichever of the two a round runs first, the chain
   * must end the round dead: a revoke that lands first must be seen by the refresh that follows —
   * the ordinary, sequential case — and a refresh that lands first and completes before the revoke
   * runs must have its freshly issued pair taken down along with the chain it was issued on, since
   * the caller who changed the password is not told anything rotated in the meantime and has no way
   * to trust a pair it never asked for.
   */
  @Test
  void revoke_racing_a_concurrent_refresh_does_not_resurrect_the_chain()
      throws InterruptedException {
    int rounds = 1000;
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int round = 0; round < rounds; round++) {
        Pair start = store.issuePair();
        CyclicBarrier line = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Pair> rotated = new AtomicReference<>();

        threads.execute(
            () -> {
              try {
                line.await();
                store.refresh(start.refresh()).ifPresent(rotated::set);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (BrokenBarrierException e) {
                throw new IllegalStateException(e);
              } finally {
                done.countDown();
              }
            });
        threads.execute(
            () -> {
              try {
                line.await();
                store.revoke(start.access());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (BrokenBarrierException e) {
                throw new IllegalStateException(e);
              } finally {
                done.countDown();
              }
            });
        done.await();

        assertFalse(
            store.validAccess(start.access()),
            "round " + round + ": revoke did not end the session it was called on");
        Pair after = rotated.get();
        if (after != null) {
          assertFalse(
              store.validAccess(after.access()),
              "round "
                  + round
                  + ": a refresh that raced a revoke produced a pair"
                  + " that outlived it, so the chain was resurrected");
        }
      }
    }
  }

  // --- a chain remembers whose it is, task 1 of "a session knows whose it is" --

  /**
   * {@link TokenStore#issuePair(String, boolean)} is what {@link AuthController#login} calls with
   * the handle it just verified. {@link TokenStore#handleFor(String)} is what lets {@code
   * AuthController#changePassword} take the handle it changes from the caller's own session rather
   * than from the request body — the whole point of this task, since a body-supplied handle is a
   * handle any authenticated caller could name.
   */
  @Test
  void a_pair_issued_for_a_handle_answers_that_handle() {
    Pair pair = store.issuePair("root", false);

    assertEquals(Optional.of("root"), store.handleFor(pair.access()));
  }

  /**
   * {@link TokenStore#issuePair()} and {@link TokenStore#issuePair(boolean)} are the bootstrap and
   * operator paths, which belong to no account — see the class note "A seventh kind". A session
   * with no handle to answer is exactly what lets {@code AuthController#changePassword} refuse such
   * a session with 401 instead of guessing whose password it might mean to change.
   */
  @Test
  void a_pair_issued_without_a_handle_answers_empty() {
    Pair viaNoArgOverload = store.issuePair();
    Pair viaBooleanOverload = store.issuePair(false);
    Pair viaBooleanOverloadRestricted = store.issuePair(true);

    assertTrue(store.handleFor(viaNoArgOverload.access()).isEmpty());
    assertTrue(store.handleFor(viaBooleanOverload.access()).isEmpty());
    assertTrue(
        store.handleFor(viaBooleanOverloadRestricted.access()).isEmpty(),
        "a chain restricted with no handle answered one anyway");
  }

  /**
   * Everything a request may carry that never names a live chain, the same set {@link
   * #an_unknown_or_absent_access_token_is_not_restricted()} pins for {@link
   * TokenStore#chainIsRestricted(String)}.
   */
  @Test
  void an_unknown_or_absent_access_token_answers_no_handle() {
    store.issuePair("root", false);

    assertTrue(store.handleFor(Tokens.mint()).isEmpty(), "a token nothing issued named a handle");
    assertTrue(store.handleFor(null).isEmpty());
    assertTrue(store.handleFor("").isEmpty());
    assertTrue(store.handleFor("   ").isEmpty());
  }

  /**
   * The handle goes when the chain is revoked — unlike {@link
   * TokenStore#chainIsRestricted(String)}, which does not check {@link TokenStore}'s chain
   * membership because both of its callers ask only after {@link AuthFilter} already has. {@link
   * TokenStore#handleFor(String)} has no such caller to rely on: {@code
   * AuthController#changePassword} could ask about a token whose session a concurrent request just
   * ended, and it must not be told a handle for a session that is no longer live.
   */
  @Test
  void the_handle_goes_when_the_chain_is_revoked() {
    Pair pair = store.issuePair("root", false);
    assertEquals(Optional.of("root"), store.handleFor(pair.access()));

    store.revoke(pair.access());

    assertTrue(
        store.handleFor(pair.access()).isEmpty(),
        "a revoked chain still answered the handle it belonged to");
  }

  /**
   * The sweep does not strand an entry: {@link TokenStore#accountChains} still loses its entry once
   * the chain it names is gone, exactly as it did before it carried a handle — the class note on
   * why the sweep's {@code accountChains.keySet().removeIf} needed no change at all to keep doing
   * that.
   *
   * <p>Asserted through {@link TokenStore#trackedRecords()}, which counts {@link
   * TokenStore#accountChains} now — see that method's own javadoc — rather than through {@link
   * TokenStore#chainIsRestricted(String)} or {@link TokenStore#handleFor(String)}: both of those
   * already answer false or empty for an access token that has itself expired, whether or not the
   * {@link TokenStore#accountChains} entry behind it was ever swept, so neither can tell a sweep
   * that drops the entry from one that leaks it forever. The count can.
   *
   * <p>Two chains are issued — one restricted with no handle, one unrestricted with a handle — so
   * each contributes exactly one entry to {@link TokenStore#accountChains} for a different reason,
   * the same two reasons {@link TokenStore#issuePair(String, boolean)} writes one at all. 8 is two
   * chains at three records each — a chain, an access grant, a refresh grant — plus the two {@link
   * TokenStore#accountChains} entries. After both chains expire and a third, plain pair is issued —
   * which sweeps — only that third pair's three records remain: its chain has no handle and is not
   * restricted, so it adds nothing to {@link TokenStore#accountChains}, and 3 is what is left only
   * if the sweep actually dropped both of the earlier entries rather than leaking them.
   */
  @Test
  void the_sweep_does_not_strand_a_restricted_chains_entry() {
    store.issuePair(true);
    store.issuePair("root", false);
    assertEquals(
        8,
        store.trackedRecords(),
        "two chains are six token records plus two accountChains entries");

    clock.advance(REFRESH.plusDays(1));
    // Something that sweeps. issuePair is the cheapest of the two.
    store.issuePair();

    assertEquals(
        3,
        store.trackedRecords(),
        "issuing a plain pair sweeps what expired, so what is left is that pair's own"
            + " chain and two tokens — anything more is an accountChains entry the"
            + " sweep stranded");
  }

  /**
   * Review round 1's first finding: {@link TokenStore#accountChains}'s entry for a chain used to be
   * written before {@link TokenStore#chains}'s — {@link TokenStore#issuePair(String, boolean)}
   * wrote {@code accountChains.put(chain, …)} and only then called {@code issueOn}, which wrote
   * {@link TokenStore#chains} last, after minting both tokens. A {@link TokenStore#sweep()} landing
   * in that window — triggered by any other call this store makes on another thread, since {@link
   * TokenStore#sweep()} runs unlocked — saw an {@link TokenStore#accountChains} entry whose chain
   * {@link TokenStore#chains} did not yet contain, which is exactly the condition {@code
   * dropOrphanedAccountChains} removes an entry for: it deleted the entry a login had just written,
   * permanently, before the write that would have protected it from that very check ever became
   * visible. Losing the {@code restricted} bit that way is the worse half — {@link
   * TokenStore#chainIsRestricted(String)} would then answer {@code false} for a flagged admin's own
   * access token, letting it trade for a ticket and hold an events socket open, which is the exact
   * thing three earlier rounds of this fix went to close; losing the handle is a fail-closed 401 at
   * worst.
   *
   * <p>The fix — {@link TokenStore#renewChain(String, Instant)} called before {@link
   * TokenStore#accountChains} is ever written — does not shrink the window, it deletes it: {@link
   * TokenStore#chains} now holds the chain before {@link TokenStore#accountChains} can, in every
   * interleaving, for every thread that could ever ask, because nothing but the thread running
   * {@link TokenStore#issuePair(String, boolean)} ever writes either map under this freshly minted
   * {@link java.util.UUID}. So this does not need to catch a rare interleaving — every one of these
   * rounds runs a sweep genuinely concurrently with the issuance it is racing, at {@link
   * #two_simultaneous_refreshes_of_one_token_leave_one_winner()}'s own round count, and the
   * assertion is expected to hold every single time against the fix. <b>Measured, not assumed</b> —
   * against the fix reverted, {@code accountChains.put} moved back ahead of {@code renewChain} and
   * {@code issueOn}, the exact shape this finding described — four separate runs of these 2000
   * rounds each failed on the restricted bit, at round 16, 30, 50 and 61 respectively: never past
   * round 61, which is what "does not need to catch a rare interleaving" means in numbers rather
   * than in argument. Against the fix, three separate runs held 2000 of 2000 rounds each.
   */
  @Test
  void a_concurrent_sweep_does_not_strand_the_account_entry_a_login_just_wrote()
      throws InterruptedException {
    int rounds = 2000;
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int round = 0; round < rounds; round++) {
        CyclicBarrier line = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Pair> issued = new AtomicReference<>();

        threads.execute(
            () -> {
              try {
                line.await();
                issued.set(store.issuePair("root", true));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (BrokenBarrierException e) {
                throw new IllegalStateException(e);
              } finally {
                done.countDown();
              }
            });
        threads.execute(
            () -> {
              try {
                line.await();
                // Any call that sweeps. mintTicket is the cheapest of
                // the ones that do not also touch accountChains
                // themselves.
                store.mintTicket();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (BrokenBarrierException e) {
                throw new IllegalStateException(e);
              } finally {
                done.countDown();
              }
            });
        done.await();

        Pair pair = issued.get();
        assertTrue(
            store.chainIsRestricted(pair.access()),
            "round "
                + round
                + ": a concurrent sweep dropped the restricted bit a"
                + " same-instant login had just written, so a flagged admin's"
                + " session would authenticate a ticket mint it must be refused");
        assertEquals(
            Optional.of("root"),
            store.handleFor(pair.access()),
            "round "
                + round
                + ": a concurrent sweep dropped the handle a"
                + " same-instant login had just written");
      }
    }
  }

  // --- a ticket carries the handle it was minted for, task 5 ---------------

  /**
   * {@link TokenStore#mintTicket(String)} is what {@link AuthController}'s ticket endpoint calls
   * with the caller's own handle, and {@link TokenStore#redeemTicket(String)} is what {@link
   * AuthFilter} reads it back with on the events upgrade — see the class notes on the fifth kind
   * this store holds for why a ticket exists at all.
   */
  @Test
  void a_ticket_minted_for_a_handle_redeems_to_that_handle_once() {
    String ticket = store.mintTicket("enzo");
    assertEquals(Optional.of(new TokenStore.Redeemed("enzo")), store.redeemTicket(ticket));
    assertTrue(store.redeemTicket(ticket).isEmpty());
  }

  /**
   * The no-arg {@link TokenStore#mintTicket()} still works — an operator or bootstrap session
   * trading for a ticket names no account of its own.
   */
  @Test
  void a_ticket_minted_for_nobody_still_admits_and_names_no_handle() {
    String ticket = store.mintTicket();
    assertEquals(Optional.of(new TokenStore.Redeemed(null)), store.redeemTicket(ticket));
  }

  // --- the operator token --------------------------------------------------

  /**
   * The CLI's credential is not reachable by anything that happens to a browser session.
   *
   * <p>{@link TokenStore#acceptOperator(String)} records a chain of its own with no refresh
   * partner, and this is what that buys. Reuse of a refresh token retires a whole chain —
   * deliberately, because a spent refresh token means somebody else has a copy — so a console that
   * double-submits logs itself out. <b>If the operator token shared that chain, the same
   * double-submit would silently log the CLI out too</b>, and the only remedy would be a server
   * restart, since nothing re-mints an operator token while the process lives.
   */
  @Test
  void retiring_a_browser_chain_does_not_reach_the_operator_token() {
    String operator = operatorToken();
    Pair browser = store.issuePair();

    // Rotate, then present the spent token again. That is reuse, and reuse
    // retires the chain the browser is on.
    Pair rotated = store.refresh(browser.refresh()).orElseThrow();
    assertTrue(store.refresh(browser.refresh()).isEmpty(), "a spent token rotated again");

    assertFalse(
        store.validAccess(rotated.access()),
        "the reuse did not retire the browser's chain, so this test is measuring nothing");
    assertTrue(
        store.validAccess(operator),
        "a browser that double-submitted logged the CLI out, and nothing mints another"
            + " operator token until this server restarts");
  }

  /**
   * It has no expiry, and it survives a sweep rather than merely outliving one.
   *
   * <p>The sweep rides on every method that adds a record, so a store that has been used at all has
   * swept many times by the time the CLI presents anything. A record recorded at {@link
   * java.time.Instant#MAX} is never dropped by it, which is what the second half asserts: the count
   * includes the operator's grant and its chain after a clock advance that expires everything else.
   */
  @Test
  void the_operator_token_outlives_every_lifetime_and_every_sweep() {
    String operator = operatorToken();
    store.issuePair();

    clock.advance(REFRESH.multipliedBy(2));
    // Something that sweeps. issuePair is the cheapest of the two.
    store.issuePair();

    assertTrue(
        store.validAccess(operator),
        "the operator token expired or was swept, so a detached server left up past the"
            + " refresh lifetime answers the CLI 401 with a file on disk that still"
            + " reads like a credential");
  }

  /**
   * Two calls are two credentials, and the earlier one keeps working — unlike {@link
   * TokenStore#mintBootstrap()}, which replaces. Nothing calls this twice today; the asymmetry is
   * worth pinning because the two methods sit next to each other and read alike.
   */
  @Test
  void a_second_operator_token_does_not_replace_the_first() {
    String first = operatorToken();

    String second = operatorToken();

    assertTrue(store.validAccess(first), "minting a second operator token killed the first");
    assertTrue(store.validAccess(second), "the second one does not work");
  }

  // --- the access token ----------------------------------------------------

  /**
   * Fifteen minutes, and the boundary is asserted rather than approached.
   *
   * <p>The short lifetime is the entire reason the access token may ride on every request: a leak
   * of one is a leak that expires. An off-by-one at the boundary is not a rounding difference, it
   * is that sentence being false for some interval nobody chose, so the instant of expiry is pinned
   * from both sides.
   */
  @Test
  void an_access_token_stops_working_when_its_lifetime_has_passed() {
    String access = store.issuePair().access();

    assertTrue(store.validAccess(access), "a token is not valid at the moment it is issued");
    clock.advance(ACCESS.minusSeconds(1));
    assertTrue(store.validAccess(access), "it died a second early");
    clock.advance(Duration.ofSeconds(1));
    assertFalse(
        store.validAccess(access),
        "an access token outlives its lifetime by nothing, and this one was still"
            + " valid at exactly the instant it expires");
    clock.advance(Duration.ofMinutes(1));
    assertFalse(store.validAccess(access), "and it does not come back");
  }

  /**
   * Everything a request may carry that is not a live access token.
   *
   * <p>The last two are the cross-use pair, and they are here because the two kinds of token travel
   * differently and could be presented in each other's place: the refresh cookie is scoped {@code
   * Path=/v1/auth} so it is not sent on ordinary calls, but that is the browser's promise rather
   * than this store's, and the CLI puts whatever it holds in an {@code Authorization} header. A
   * refresh token that authenticated an ordinary request would be a seven-day credential doing a
   * fifteen-minute job.
   */
  @Test
  void an_access_token_this_store_never_issued_is_not_valid() {
    Pair pair = store.issuePair();

    assertFalse(store.validAccess(Tokens.mint()), "a token nothing issued was accepted");
    assertFalse(store.validAccess(null), "a request with no token was accepted");
    assertFalse(store.validAccess(""), "an empty token was accepted");
    assertFalse(store.validAccess("   "), "a blank token was accepted");
    assertFalse(
        store.validAccess(pair.refresh()), "a refresh token authenticated an ordinary request");
    assertTrue(
        store.refresh(pair.access()).isEmpty(),
        "an access token was accepted where a refresh token belongs");
    assertTrue(
        store.validAccess(pair.access()),
        "and none of those refusals disturbed the pair that is actually live");
  }

  // --- rotation ------------------------------------------------------------

  /**
   * Refresh hands back a new pair and the presented token is done.
   *
   * <p>Both halves matter and they are different claims. Rotating only the access token would leave
   * a seven-day secret in place for as long as the session lives, which is the thing the
   * fifteen-minute access token exists to avoid; and a refresh token that survived its own use
   * would make the reuse signal below unavailable, because there would be no such thing as a spent
   * one.
   *
   * <p>The distinctness assertion is {@code assertFalse(a.equals(b))} and not {@code
   * assertNotSame}: see the class note, which records what that prints.
   */
  @Test
  void refreshing_rotates_both_and_retires_the_one_presented() {
    Pair first = store.issuePair();
    Pair second = store.refresh(first.refresh()).orElseThrow();

    assertFalse(
        first.access().equals(second.access()),
        "refresh handed back the access token it was already holding");
    assertFalse(
        first.refresh().equals(second.refresh()),
        "refresh handed back the refresh token it was presented with");
    assertTrue(store.validAccess(second.access()), "the rotated access token does not work");
    assertTrue(
        store.refresh(first.refresh()).isEmpty(),
        "a refresh token is single-use; presenting a spent one is not a retry");
  }

  /**
   * The reuse rule, which is the one decision in this class that costs a live session on purpose.
   *
   * <p>A spent refresh token is only ever presented for one reason: somebody else has it. The
   * legitimate client rotated and holds the new pair, so the copy on the wire is a copy.
   * <b>Rotation alone would answer this by refusing the call and leaving the thief holding a live
   * pair</b> — the theft would be invisible and permanent, renewed every fifteen minutes. So the
   * reuse itself is the signal and the response is to retire the whole chain, which logs out
   * whoever holds it, both of them, and forces a bootstrap the only party at the machine can
   * perform.
   *
   * <p>The second assertion is the one that would be assumed. An implementation that merely refused
   * the reused call passes the first and the third and leaves the stolen access token working for
   * its full fifteen minutes, with nothing to notice.
   */
  @Test
  void presenting_a_spent_refresh_token_invalidates_the_chain_it_belonged_to() {
    Pair first = store.issuePair();
    Pair second = store.refresh(first.refresh()).orElseThrow();

    assertTrue(store.refresh(first.refresh()).isEmpty(), "the spent token refreshed again");
    assertFalse(
        store.validAccess(second.access()),
        "reuse of a retired refresh token must retire the chain, not just refuse the" + " call");
    assertTrue(
        store.refresh(second.refresh()).isEmpty(),
        "the chain's own live refresh token still rotates, so the chain was not" + " retired");
  }

  /**
   * Reuse of an <em>old</em> token retires the whole chain and not the part of it descended from
   * that token.
   *
   * <p>The test above goes one generation, and one generation is the case every plausible wrong
   * implementation also gets right. A session that has been alive a day has rotated ninety-six
   * times, and the token a thief captured is not the newest one — so what the design actually
   * promises is that presenting <b>any</b> spent token, however far back, logs the session out.
   * Three generations is the shortest arrangement in which the two wrong shapes are distinguishable
   * from the right one:
   *
   * <ul>
   *   <li>a store that <b>refused the call and stopped</b> leaves all three generations working —
   *       caught by the second assertion, as it is above;
   *   <li>a store that <b>retired what the presented token produced</b> — walking from the spent
   *       record to the pair it minted — kills the second generation and leaves the third, which is
   *       the one still on the wire and the only one that matters. Caught by the third assertion,
   *       and by nothing in the one-generation test, where the descendant and the newest pair are
   *       the same object.
   * </ul>
   *
   * <p>The first assertion is the precondition rather than the point: the middle generation's
   * access token has to be alive before the reuse for its death after to mean anything, and it is
   * alive because rotation deliberately does not retire the access token beside the refresh token
   * it spent.
   */
  @Test
  void reusing_a_token_from_three_generations_back_retires_the_whole_chain() {
    Pair first = store.issuePair();
    Pair second = store.refresh(first.refresh()).orElseThrow();
    Pair third = store.refresh(second.refresh()).orElseThrow();

    assertTrue(
        store.validAccess(second.access()),
        "the middle generation was already dead before the reuse, so what follows"
            + " would prove nothing");

    assertTrue(
        store.refresh(first.refresh()).isEmpty(),
        "the first generation's spent refresh token rotated again");

    assertFalse(
        store.validAccess(third.access()),
        "reuse two generations back left the newest access token working, so the"
            + " retirement is following descendants rather than retiring the chain");
    assertFalse(
        store.validAccess(second.access()),
        "the generation in between the reused token and the newest pair survived");
    assertTrue(
        store.refresh(third.refresh()).isEmpty(),
        "the newest refresh token still rotates, so the chain was not retired");
  }

  /**
   * A chain is retired by reuse and not by a clock.
   *
   * <p>An expired refresh token is not evidence of anything: it is a laptop that was shut. Treating
   * it as reuse would mean the sibling access token dies too, which is a logout for the passage of
   * time.
   *
   * <p><b>This is the one test that configures lifetimes no deployment would.</b> With the real
   * pair — fifteen minutes against seven days — the difference is unobservable, because by the time
   * a refresh token expires every access token in its chain expired six days earlier and the
   * assertion below would pass against an implementation that retires chains on expiry. Inverting
   * the two is what makes "the chain survived" a thing this suite can see, and it is the reason the
   * lifetimes are constructor arguments rather than constants even before {@code AuthProperties}
   * exists to supply them.
   */
  @Test
  void an_expired_refresh_token_is_refused_without_retiring_the_chain() {
    TokenStore odd = new TokenStore(clock, Duration.ofMinutes(15), Duration.ofMinutes(5), TICKET);
    Pair pair = odd.issuePair();

    clock.advance(Duration.ofMinutes(6));

    assertTrue(odd.refresh(pair.refresh()).isEmpty(), "an expired refresh token rotated");
    assertTrue(
        odd.validAccess(pair.access()),
        "the access token died with the refresh token, so expiry is being treated as" + " reuse");
  }

  /**
   * Seven days, and the boundary is asserted from both sides — the same treatment the access
   * token's boundary gets, and for the same reason.
   *
   * <p>{@link #an_expired_refresh_token_is_refused_without_retiring_the_chain()} asks a different
   * question and approaches the boundary from six minutes away on a five-minute lifetime, which
   * cannot see an off-by-one. This one pins the instant: alive one second before, dead at exactly
   * the instant of expiry. A store that answered {@code isBefore} the other way round would make a
   * seven-day credential a seven-day-and-a-moment one, which is a smaller error than it sounds and
   * still an interval nobody chose.
   *
   * <p><b>It takes two pairs where the access-token test takes one</b>, and that is forced rather
   * than sloppy: {@code validAccess} may be asked twice because asking does not change the answer,
   * while {@code refresh} spends what it accepts, so the token that proves "alive at minus one
   * second" is spent by proving it. Both pairs are issued at the same instant from the same clock,
   * so they expire at the same instant and the two assertions are about one boundary.
   */
  @Test
  void a_refresh_token_stops_working_when_its_lifetime_has_passed() {
    Pair early = store.issuePair();
    Pair late = store.issuePair();

    clock.advance(REFRESH.minusSeconds(1));
    assertTrue(store.refresh(early.refresh()).isPresent(), "it died a second early");

    clock.advance(Duration.ofSeconds(1));
    assertTrue(
        store.refresh(late.refresh()).isEmpty(),
        "a refresh token outlives its lifetime by nothing, and this one still rotated"
            + " at exactly the instant it expires");

    clock.advance(Duration.ofDays(1));
    assertTrue(store.refresh(late.refresh()).isEmpty(), "and it does not come back");
  }

  /**
   * A refresh token nothing issued is refused and is not evidence about anything.
   *
   * <p>The store cannot tell a token it never minted from one it minted and swept, and it must not
   * guess: an unknown value is what a stale cookie, a restarted server and a fumbled paste all look
   * like. Retiring a chain on one would need a chain to retire, which is exactly what an unknown
   * token does not name — but an implementation that retired something broader, or that threw,
   * would turn a stale cookie into an outage.
   */
  @Test
  void a_refresh_token_this_store_never_issued_retires_nothing() {
    Pair pair = store.issuePair();

    assertTrue(store.refresh(Tokens.mint()).isEmpty(), "a token nothing issued rotated");
    assertTrue(store.refresh(null).isEmpty(), "a request with no token rotated");
    assertTrue(store.refresh("").isEmpty(), "an empty token rotated");
    assertTrue(store.refresh("   ").isEmpty(), "a blank token rotated");
    assertTrue(store.validAccess(pair.access()), "an unknown token took the live pair with it");
    assertTrue(
        store.refresh(pair.refresh()).isPresent(), "an unknown token retired the live chain");
  }

  /**
   * Many clients presenting one refresh token at the same instant: one pair comes back, and every
   * loser is reuse.
   *
   * <p>This is the compound action the whole class's locking exists for — read the record, see that
   * it is unspent, mark it spent, issue on its chain. Interleave two of those and both callers see
   * an unspent record, both are issued a pair, and <b>the store has minted two live sessions from
   * one single-use token</b>, which is the entire thing single-use means.
   *
   * <p><b>It races two thousand times rather than once, and the count is the whole of whether this
   * is an instrument or a coin.</b> Measured by making the mistake: with the lock taken out of
   * {@code TokenStore.refresh} so that the read and the mark are a plain check-then-act, counting
   * how many runs went <em>red</em> against that broken store —
   *
   * <ul>
   *   <li><b>one</b> race, thirty-two virtual threads released together off a {@link
   *       CountDownLatch}: <b>0 red of 5</b>. The window between the read and the mark is a map
   *       read and a comparison wide, and thirty-two threads woken from one latch do not all arrive
   *       inside it. That is the version this test started as, and the figure is the one recorded
   *       when it was written rather than one re-measured since;
   *   <li><b>two hundred</b> rounds of eight threads meeting at a {@link CyclicBarrier}: <b>13 red
   *       of 16</b>, at 201 to 207 exchanges against 200 races. This paragraph used to say "failed
   *       5 of 5" and mean it as a property of the test; it was a true reading of a sample of five;
   *   <li><b>two thousand</b> rounds, which is what it runs: <b>13 red of 13</b>, at 2042 to 2050
   *       exchanges against 2000 races.
   * </ul>
   *
   * <p><b>The middle row is the reason for the last one, and it is a worse result than it
   * looks.</b> The interleaving happens on roughly one round in a hundred — 33 excess exchanges
   * across those sixteen 200-round runs, so a Poisson mean near 2 — and {@code e^-2} is about one
   * run in eight coming back green against a store with no lock in it at all. The three greens in
   * sixteen are that. <b>A race test that goes green three runs in sixteen against a knowingly
   * broken store is worse than no test</b>: the next to see it red re-runs it, gets green, and
   * files it as flaky — and the thing it was telling them is that two callers can spend one
   * single-use token. Two thousand rounds puts the mean at twenty even on the lower of the two
   * measured per-round rates, and {@code e^-20} does not happen. The whole cost is this test going
   * from about 31 ms to about 213 ms.
   *
   * <p>It is still an instrument and not a proof — one green run of a race detector never was,
   * which is the caveat {@code SessionRegistryTest} records about its own. What changed is that the
   * reliability is a measured number here rather than an impression, and if it is ever raised again
   * the thing to raise it on is a re-run of that count, not a hunch.
   *
   * <p>The consequence for the losers is deliberate and is not softened here: each losing call
   * presented a token the winner spent microseconds earlier, which is indistinguishable from theft,
   * so the chain is retired and <em>everybody</em> is logged out — which is what the last two
   * assertions say. Two browser tabs refreshing in the same instant would do this. The alternative
   * — a grace window in which the immediately preceding token replays the pair it already produced
   * instead of counting as reuse — is a real design and is rejected in {@link TokenStore}, where
   * the reason is written down; if the console turns out to double-submit, that is the paragraph to
   * revisit rather than this test.
   */
  @Test
  void two_simultaneous_refreshes_of_one_token_leave_one_winner() throws InterruptedException {
    int rounds = 2000;
    int refreshers = 8;
    AtomicInteger pairsIssued = new AtomicInteger();
    AtomicReference<Pair> lastWinner = new AtomicReference<>();

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int round = 0; round < rounds; round++) {
        Pair start = store.issuePair();
        CyclicBarrier line = new CyclicBarrier(refreshers);
        CountDownLatch done = new CountDownLatch(refreshers);
        for (int i = 0; i < refreshers; i++) {
          threads.execute(
              () -> {
                try {
                  line.await();
                  store
                      .refresh(start.refresh())
                      .ifPresent(
                          won -> {
                            pairsIssued.incrementAndGet();
                            lastWinner.set(won);
                          });
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (BrokenBarrierException e) {
                  throw new IllegalStateException(e);
                } finally {
                  done.countDown();
                }
              });
        }
        done.await();
      }
    }

    assertEquals(
        rounds,
        pairsIssued.get(),
        "single-use refresh tokens were exchanged "
            + pairsIssued.get()
            + " times over "
            + rounds
            + " races of "
            + refreshers
            + ", so two callers read one token as"
            + " unspent and both were issued a pair");
    Pair winner = lastWinner.get();
    assertFalse(
        store.validAccess(winner.access()),
        "seven callers presented a token the winner had just spent and the chain"
            + " survived it, so a loser is being refused rather than treated as"
            + " reuse");
    assertTrue(store.refresh(winner.refresh()).isEmpty(), "the retired chain still rotates");
  }

  // --- what a pair may print -----------------------------------------------

  /**
   * {@link Pair} renders itself without either token in it.
   *
   * <p>A record's generated {@code toString} interpolates every component, so this assertion fails
   * against the declaration with the override deleted — which is how the override was arrived at
   * rather than assumed. The protection that {@code toString} replaces was "nothing in this package
   * logs a {@code Pair}", and that expired at task 8, where the record began crossing into {@code
   * AuthController} and anything may render it: a {@code log.debug("issued {}", pair)}, an
   * exception built with it in the message, a Spring error page printing a handler argument. None
   * of those is a decision anybody makes on purpose, which is why the record and not its callers is
   * where this is fixed.
   *
   * <p>Written as {@code assertFalse(printed.contains(token))} and emphatically not as an {@code
   * assertEquals} against the expected rendering: the class note gives the rule, and here it has
   * teeth, because {@code assertEquals} interpolates the actual value and the actual value is
   * exactly the string this test exists to catch — the one with two live tokens in it. The
   * assertion that would report the bug would also publish it.
   */
  @Test
  void a_pair_never_prints_the_tokens_it_carries() {
    Pair pair = store.issuePair();
    String printed = pair.toString();

    assertFalse(
        printed.contains(pair.access()),
        "Pair.toString() contains the access token, so anything that logs or renders a"
            + " pair publishes a live session");
    assertFalse(
        printed.contains(pair.refresh()),
        "Pair.toString() contains the refresh token, so anything that logs or renders a"
            + " pair publishes a seven-day credential");
    assertTrue(
        printed.contains("redacted"),
        "the rendering says nothing about having withheld anything, which is how the"
            + " next person concludes there was nothing to withhold");
  }

  // --- what the store keeps ------------------------------------------------

  /**
   * Expired records leave, by the sweep that rides on issuing and by the lookup that trips over
   * one.
   *
   * <p>Neither alone is enough, which is why both are asserted. A store that evicted only on lookup
   * keeps every record nobody ever presents again — which is <em>every</em> record of an abandoned
   * session, since abandoning is exactly not presenting it. A store that only swept would hold an
   * expired record between sweeps, which is harmless for correctness only because every read path
   * checks the expiry itself; asserting the eviction is what keeps that from being the only line of
   * defence.
   *
   * <p>The counts are exact rather than "smaller", because "smaller" passes over a sweep that drops
   * one record per call and never catches up.
   */
  @Test
  void expired_records_do_not_accumulate() {
    for (int i = 0; i < 200; i++) {
      store.issuePair();
    }
    assertEquals(
        600,
        store.trackedRecords(),
        "two hundred pairs are two hundred chains and four hundred tokens");

    clock.advance(REFRESH.plusDays(1));
    store.issuePair();

    assertEquals(
        3,
        store.trackedRecords(),
        "issuing a pair sweeps what has expired, so what is left is that pair and its" + " chain");

    Pair live = store.issuePair();
    clock.advance(ACCESS.plusMinutes(1));
    assertFalse(store.validAccess(live.access()), "the expired token is still accepted");
    assertEquals(
        5,
        store.trackedRecords(),
        "a lookup that finds an expired record must drop it rather than leave it for a"
            + " sweep that may not come");
  }

  /**
   * The sweep's budget is a real bound, and a bounded sweep still drains.
   *
   * <p>{@link #expired_records_do_not_accumulate()} uses two hundred pairs against a budget of 1024
   * entries per map, so the budget never engages — that test would pass identically against a sweep
   * with no bound at all, and the class note's "at most 1024 entries of each map are examined per
   * call" is unasserted there. Three thousand pairs is enough to engage it, and the two failures
   * worth separating are opposite ones:
   *
   * <ul>
   *   <li><b>a sweep that is not bounded</b> clears all nine thousand records in the first call,
   *       and one caller has absorbed the whole pause — caught by the exact count after that call;
   *   <li><b>a sweep that is bounded and loses ground</b> — one that made a fixed small amount of
   *       progress, or that examined the same prefix every time — never catches up, and the store
   *       grows without limit. Caught by the drain to nine.
   * </ul>
   *
   * <p>The numbers are exact rather than "fewer than before", which is the same choice {@link
   * #expired_records_do_not_accumulate()} makes and for the same reason. 5931 is 9000 minus 3 ×
   * 1024 plus the 3 records the issuing call adds; the drain to 9 is 3 live records — one chain and
   * one pair — from each of the last three calls, which is what is left when everything expired has
   * gone. <b>The count after the second sweep is deliberately not asserted</b>: by then one live
   * record is in each map, and whether the budget spends an examination on it depends on where an
   * unordered map iterates it, so the answer is 2862 or 2865 and pinning either would be pinning a
   * hash order.
   *
   * <p>If {@code SWEEP_BUDGET} changes, this test fails on the arithmetic rather than on the
   * behaviour. That is intended — the constant is a claim the class note makes out loud, and a
   * claim nothing checks is the thing this round of review is about.
   */
  @Test
  void the_sweep_examines_a_bounded_number_of_records_and_still_drains() {
    for (int i = 0; i < 3000; i++) {
      store.issuePair();
    }
    assertEquals(
        9000,
        store.trackedRecords(),
        "three thousand pairs are three thousand chains and six thousand tokens, and"
            + " nothing has expired yet for a sweep to take");

    clock.advance(REFRESH.plusDays(1));

    store.issuePair();
    assertEquals(
        5931,
        store.trackedRecords(),
        "one issuing call must examine at most 1024 entries of each of the three maps"
            + " — 9000 - 3072 + the 3 records it just added — so a caller cannot be"
            + " made to absorb an unbounded pause");

    store.issuePair();
    store.issuePair();
    assertEquals(
        9,
        store.trackedRecords(),
        "a bounded sweep has to catch up as well as be bounded, and this one is still"
            + " holding records that expired a day ago");
  }

  // --- configuration -------------------------------------------------------

  /**
   * A lifetime that is not positive is refused where it is supplied.
   *
   * <p>These are {@code AuthProperties}' {@code access-lifetime} and {@code refresh-lifetime}, so
   * they are something an operator can set in {@code application.yml}. Zero mints tokens that are
   * dead at the instant they are issued — every request unauthenticated, every refresh refused, and
   * no message anywhere saying why — so the constructor is the place it is worth one line to say
   * so.
   */
  @Test
  void a_lifetime_that_is_not_positive_is_a_configuration_defect() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenStore(clock, Duration.ZERO, REFRESH, TICKET));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenStore(clock, ACCESS, Duration.ofSeconds(-1), TICKET));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenStore(clock, ACCESS, REFRESH, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TokenStore(clock, ACCESS, REFRESH, Duration.ofSeconds(-1)));
    assertThrows(NullPointerException.class, () -> new TokenStore(null, ACCESS, REFRESH, TICKET));
    assertThrows(NullPointerException.class, () -> new TokenStore(clock, null, REFRESH, TICKET));
    assertThrows(NullPointerException.class, () -> new TokenStore(clock, ACCESS, null, TICKET));
    assertThrows(NullPointerException.class, () -> new TokenStore(clock, ACCESS, REFRESH, null));
  }

  // --- the fixture ---------------------------------------------------------

  /**
   * A clock that moves only when a test moves it.
   *
   * <p>The same shape, for the same reason, as {@code SessionRegistryTest}'s: an instant this file
   * asserts on is one this file set, rather than one a sleep hoped for. Here it is not a
   * convenience — the shortest lifetime under test is fifteen minutes.
   */
  private static final class Ticking extends Clock {

    /**
     * {@code volatile} because {@code two_simultaneous_refreshes_of_one_token_leave_one_winner}
     * reads this from sixteen thousand virtual threads.
     *
     * <p>It is not needed for what that test does <em>today</em>: it never calls {@link
     * #advance(Duration)} while threads are running, and the one write — this initialiser —
     * happens-before every task through {@code ExecutorService.execute}. The keyword is here for
     * the next person, who adds a {@code clock.advance(...)} inside that loop to test expiry under
     * load and would otherwise get a data race with no visibility guarantee, no exception and no
     * failing run to point at it. One keyword is cheaper than that afternoon.
     */
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

  /**
   * What {@code AuthConfig.announce} does, minus the file: mint, and record.
   *
   * <p>{@link TokenStore#acceptOperator(String)} takes the token rather than making one, because
   * production must not record a grant for a token that failed to reach disk — see that method.
   * These tests are about the grant, so they do the two steps together here rather than at every
   * call site.
   */
  private String operatorToken() {
    String token = Tokens.mint();
    store.acceptOperator(token);
    return token;
  }
}
