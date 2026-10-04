package io.aeyer.plowshare.server.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Which tokens are still good, and what makes one stop being good.
 *
 * <p>Production account login sessions delegate to {@link DurableSessions}, which persists digests,
 * identity, restrictions and revocation in Postgres. The maps here retain bootstrap/operator
 * sessions and single-use WS tickets; isolated fixtures without a database also use the maps for
 * account sessions.
 *
 * <p>The three kinds the slice design names, all minted by {@link Tokens} and all held here as
 * {@link Tokens#hash(String)} of themselves and never as themselves:
 *
 * <ul>
 *   <li>the <b>bootstrap</b> token, minted once at startup and printed in the console URL. It is
 *       spent by the first exchange and there is then no such token until the next start;
 *   <li>an <b>access</b> token, short-lived, presented on every request — as an {@code HttpOnly}
 *       cookie by the browser and as an {@code Authorization} header by the CLI, which is a
 *       distinction the filter makes and this class does not; and
 *   <li>a <b>refresh</b> token, long-lived and single-use, exchanged for a new pair of both.
 * </ul>
 *
 * <p>And a fourth this class grew at task 8, which the design did not name: the <b>operator</b>
 * token of {@link #acceptOperator(String)}, an access grant with no refresh partner and no expiry,
 * written to a mode-600 file for the CLI to read. See that method for why two credentials are
 * minted at startup rather than one.
 *
 * <p><b>Something calls this now.</b> This paragraph read "Nothing calls this yet ... so every
 * comment in this repository that says the server has no authentication is still true"; task 7
 * built {@link AuthFilter}, which asks {@link #validAccess(String)} on every request under {@code
 * /v1/} including both WebSocket upgrades, and {@link AuthConfig} registers it. Those comments were
 * swept in the same commit.
 *
 * <p><b>And every method here has a production caller as of task 8.</b> This paragraph read
 * "nothing calls {@link #mintBootstrap()}, {@link #spendBootstrap(String)}, {@link #issuePair()} or
 * {@link #refresh(String)} in production ... a server with the gate on has no way to hand out a
 * credential over HTTP". {@link AuthConfig}'s startup announcement calls {@code mintBootstrap} and
 * {@code acceptOperator}; {@link AuthController} calls {@code spendBootstrap}, {@code issuePair}
 * and {@code refresh}. {@code PLOWSHARE_AUTH_ENABLED=false} is no longer the only way to reach a
 * default-configured server, and the comments saying it was were swept in the same commit.
 *
 * <h2>Stored by hash, and looked up by hash rather than compared</h2>
 *
 * <p>{@link Tokens} argues why the stored form is a digest. What this class adds is that the access
 * and refresh tokens are <em>looked up</em> by that digest — {@code map.get(hash(presented))} —
 * where the bootstrap token is checked with {@link Tokens#verify(String, String)}. That is not an
 * inconsistency, and it is worth being explicit about why the lookup is not a timing regression:
 *
 * <ul>
 *   <li><b>what the constant-time comparison protects is the stored value.</b> An early-returning
 *       comparison of a presented token against a stored secret leaks how long a prefix matched,
 *       and the attacker walks the secret out one byte at a time. Here nothing compares the
 *       presented token against anything: it is hashed, and the digest is a hash-table key;
 *   <li><b>what a timing attack on the table could recover is a digest</b> — of a value the
 *       attacker chose, or at best a stored one — and a digest is not a credential. The filter
 *       accepts a token and hashes it, so an attacker holding the stored hash of a live session
 *       still needs a SHA-256 preimage to present anything;
 *   <li><b>the bootstrap token cannot be a table lookup</b>, because there is at most one of it and
 *       no key to look it up by. Comparing it is therefore unavoidable, which is exactly the case
 *       {@code verify} exists for.
 * </ul>
 *
 * <p>The other consequence of hashing on the way in: {@link Tokens#hash(String)} throws on a null
 * or blank, deliberately, because it is documented as being called only on values this server
 * minted. Everything reaching this class from a request is guarded before it gets there, and a
 * missing cookie is answered with {@code false} or an empty {@link Optional} — the ordinary shape
 * of an unauthenticated request, not an exception.
 *
 * <h2>A chain, so that retiring a session is one operation</h2>
 *
 * <p>Every pair descended from one {@link #issuePair()} shares a <b>chain id</b>: the pair a
 * refresh hands back is issued on the chain of the token presented, and so on for as long as the
 * session lives. Both the access and the refresh records name their chain, and every read path asks
 * whether the chain is still present. So retiring a session is {@code chains.remove(chain)} — one
 * map operation that kills both sides at once and every token ever issued on that chain, rather
 * than a walk of two maps looking for records that mention it.
 *
 * <p>The chain id is a {@link UUID} and not a {@link Tokens#mint()} secret, because it is not one:
 * it never leaves this process, it authenticates nothing, and minting it from the token source
 * would suggest to the next reader that it does.
 *
 * <p>A retired chain leaves its access and refresh records behind, inert, until they expire and are
 * swept. That is deliberate — the alternative is the walk this design exists to avoid — and it is
 * safe because presence of the chain is checked on every path that could accept one of them.
 *
 * <h2>Reuse retires the chain, which costs a live session on purpose</h2>
 *
 * <p>A refresh token is single-use, so a <em>spent</em> one is only ever presented for one reason:
 * somebody else has a copy. The legitimate client rotated and holds the new pair. <b>Refusing the
 * call and stopping there would leave the thief holding a live pair</b>, renewed every fifteen
 * minutes, invisibly and indefinitely — rotation alone detects nothing. So the reuse is the signal,
 * and the answer is to retire the whole chain: both parties are logged out, and only the one who
 * can read the machine's own stdout — where the bootstrap token is printed once — or {@code
 * ~/.config/plowshare/console-token} — where the operator token is written at mode 600 — starts a
 * new session.
 *
 * <p><b>The price is that a client which double-submits logs itself out.</b> Two browser tabs
 * refreshing in the same instant is enough — one wins and the other presents a token that was spent
 * microseconds ago. That is the documented behaviour rather than an accident, and the alternative
 * was considered: a short <em>grace window</em> in which the immediately preceding refresh token
 * replays the pair it already produced instead of counting as reuse. It is rejected here because it
 * needs the store to remember what each spent token produced, and because the window is exactly the
 * interval in which a stolen token is most likely to be used — which is to say it weakens the
 * signal in the case the signal exists for. If the console turns out to double-submit, this
 * paragraph is what to revisit, and the fix belongs in the console.
 *
 * <p><b>The operator token sits on no chain a refresh can reach</b>, because it has no refresh
 * partner and {@link #acceptOperator(String)} gives it a chain of its own. So a browser that
 * double-submits cannot log the CLI out, and the CLI — which never calls {@link #refresh(String)}
 * at all — cannot log the browser out.
 *
 * <p><b>Expiry is not reuse.</b> An expired refresh token is a laptop that was shut, and it retires
 * nothing; the call is refused and the record is dropped. {@code TokenStoreTest} pins that
 * distinction with a deliberately unrealistic configuration, because with the shipping lifetimes it
 * is unobservable.
 *
 * <p><b>Rotation does not retire the access token that came with the presented refresh token.</b>
 * It expires on its own, within the access lifetime, and a request already in flight when the
 * client rotated is not evidence of anything. Killing it would need the store to remember which
 * access token was issued beside which refresh token, and would buy nothing that the short lifetime
 * does not already buy.
 *
 * <h2>{@link ReentrantLock} and not {@code synchronized}</h2>
 *
 * <p>{@link #refresh(String)} is the one compound action in this class: read the record, see that
 * it is unspent, mark it spent, issue on its chain. Interleave two of those and both callers see an
 * unspent record and both are issued a pair, which is the whole of what single-use means. So it is
 * serialised.
 *
 * <p><b>That it is needed at all is measured rather than argued — and so is how reliably the
 * measurement reproduces, which is the part that was previously asserted on a sample of five.</b>
 * With the lock taken out, {@code
 * TokenStoreTest.two_simultaneous_refreshes_of_one_token_leave_one_winner} issues more pairs than
 * it ran races on <b>13 runs out of 13</b> at the two thousand rounds it now runs. At the two
 * hundred it used to run it did so on only <b>13 of 16</b>: the same broken store came back green
 * three runs in sixteen, because the interleaving happens on about one round in a hundred and two
 * hundred rounds is a Poisson mean of two. So the round count is load-bearing rather than
 * decorative, and the arithmetic is written out in that test. A single race detects nothing at all
 * — the first version of it passed 5 of 5 against the same broken store, which is what says how
 * narrow the window is.
 *
 * <p>It is a lock and not a {@code synchronized} block, which is this repository's standing rule,
 * and the rule holds here for a reason worth stating exactly rather than by citation. <b>The usual
 * case — a thread that blocks on a socket while holding the guard — is not this one.</b> The
 * critical section holds no socket: it is map work and two mints, and even the mints take no
 * monitor of their own on this JVM — JDK 21's {@code SecureRandom.nextBytes} synchronizes only when
 * its SPI does not advertise itself thread-safe, and every {@code SecureRandom} service the
 * platform provider offers here reports {@code ThreadSafe=true}. What bites instead is
 * <em>contention</em>: a caller that has to wait to enter. Measured on the JVM this build runs
 * (JetBrains Runtime 21.0.8), with the scheduler forced to a single carrier so the effect is
 * visible:
 *
 * <ul>
 *   <li>a virtual thread that <b>blocks inside</b> a {@code synchronized} block pins its carrier —
 *       {@code jdk.tracePinnedThreads} prints {@code reason:MONITOR}, and a second virtual thread
 *       did not run at all until it finished. With a {@link ReentrantLock} the second thread ran;
 *       and
 *   <li>a virtual thread that merely <b>blocks trying to enter</b> a monitor somebody else holds
 *       does the same thing — a third virtual thread did not run while it waited. With a {@link
 *       ReentrantLock} it did.
 * </ul>
 *
 * <p>The second is this class's shape — a caller that has to wait for this lock must be able to
 * give its carrier up while it waits, and with one carrier the effect is total, while at the usual
 * parallelism it is one carrier lost per waiter. <b>But who the callers are should be said rather
 * than assumed, and it is not what the rule's usual phrasing implies</b>: {@link AuthFilter} runs
 * on Spring's container threads, and those are platform threads today — {@code application.yml}
 * does not set {@code spring.threads.virtual.enabled} and nothing else in this repository does
 * either, so what runs on virtual threads here is the jobs, not the requests. On a platform thread
 * a {@code synchronized} block would pin nothing, and the measurement above would cost nothing. The
 * lock stays anyway, and not out of ceremony: it is one field, it is correct whichever kind of
 * thread arrives, and the container is one property away from being virtual — at which point the
 * difference is a pool that shrinks under exactly the load that produced the contention, found in
 * production rather than here.
 *
 * <p>One lock for the whole class rather than one per chain: a refresh happens once per access
 * lifetime per client, and this server has one operator. Striping would be state to keep and evict
 * for contention nobody has measured.
 *
 * <p>Everything else is lock-free by construction. {@link #validAccess(String)} runs on every
 * request and takes no lock at all — it is two {@link ConcurrentHashMap} reads and a compare — and
 * {@link #spendBootstrap(String)} is a {@link AtomicReference#compareAndSet} whose javadoc
 * specifies {@code ==} against the expected value: the reference it offers is the one it just read,
 * so two callers presenting the same valid bootstrap token both verify and exactly one swap
 * succeeds.
 *
 * <h2>Expired records leave, twice over</h2>
 *
 * <p>Lazily, when a lookup finds one — the record is dropped there and then, so the commonest
 * garbage collects itself. And by a sweep, because the record nobody presents again is precisely
 * the record of an abandoned session, and lazy eviction never sees it.
 *
 * <p>The sweep rides on {@link #issuePair()} and {@link #refresh(String)}, which are the only two
 * methods that add anything. <b>So this store cannot grow without also sweeping</b>, and there is
 * no timer, no scheduled task and no Spring bean to forget to register. It deliberately does not
 * ride on {@code validAccess}, which is the per-request path.
 *
 * <p>Inside {@code refresh} it rides <em>behind</em> the check that the presented token names a
 * record at all, which is a placement rather than an accident. {@link AuthController#refresh} is an
 * <b>unauthenticated</b> endpoint in front of this method — {@link AuthFilter#OPEN} names it,
 * because a gate that refused the way through it could never be passed. So anyone who can reach the
 * port can post a value that is not a token, and a sweep in front of that check sells each such
 * request three map traversals — {@value #SWEEP_BUDGET} entries apiece — for one SHA-256 of the
 * attacker's own choosing. Behind the check, a value naming nothing costs a digest and a lookup.
 * <b>The invariant is untouched</b>, because it is about growth and a token that names no record
 * grows nothing: every path that reaches {@link #issueOn(String, Instant)} from here has passed
 * that check. It is a {@link Map#containsKey(Object)} and not the locked read below because this
 * keeps the sweep out of the critical section; the record it saw may be gone by the time the lock
 * is held, and the locked read answers that case the same way it answers any other unknown token.
 *
 * <p>It is bounded: at most {@value #SWEEP_BUDGET} entries of each map are examined per call, so no
 * single caller absorbs an unbounded pause. The honest limit of that bound, since a budget over an
 * unordered map is not a fair scheduler: if the first {@value #SWEEP_BUDGET} entries a map iterates
 * were all live, a sweep would make no progress on anything past them. What rules that out is not
 * the algorithm but the arithmetic — a live record is one issued within the last lifetime, and a
 * single operator's console issues one pair per access lifetime — so the live set is single digits
 * and the budget is three orders of magnitude above it. If this ever holds many concurrent
 * sessions, that sentence is what stops being true, and the fix is an expiry-ordered structure
 * rather than a bigger number.
 *
 * <p>Removal is the two-argument {@link Map#remove(Object, Object)} and not the iterator's own
 * {@code remove()}. Checked against the JDK 21 source rather than assumed: {@code
 * ConcurrentHashMap}'s iterator removes by calling {@code replaceNode(key, null, null)}, which
 * drops whatever is under the key at that moment regardless of what the iterator saw. The
 * value-checked form cannot drop a record that was replaced since it was read. Iterating while
 * other threads write is itself safe — those iterators are weakly consistent and do not throw
 * {@code ConcurrentModificationException}.
 *
 * <h2>No logger, and no secret in any message</h2>
 *
 * <p><b>This class has no {@code Logger} field and must not acquire one</b>, for the reason {@link
 * Tokens} gives at length: every value passing through it is a live secret or a digest of one. The
 * two exceptions it throws are about configuration and name a constructor argument, never a token.
 *
 * <h2>A {@link Clock}, and lifetimes that are arguments</h2>
 *
 * <p>Time is a constructor argument because the shortest lifetime here is minutes long and no test
 * can wait one out.
 *
 * <p>It is a {@link Clock} and not a {@code Supplier<Instant>}, and the honest form of that choice
 * is that <b>this repository spells the seam both ways, and the spelling not used here is the
 * commoner one.</b> Counted over {@code plowshare-server/src/main/java} rather than remembered:
 * {@code SessionRegistry} takes a {@link Clock} and is the only class that does; {@code Archive},
 * {@code ProposalStore} and {@code ConversationStore} each take a {@code Supplier<Instant>}; {@code
 * ProjectStore} takes neither and dates its rows with Postgres's {@code now()}. So this class
 * follows {@code SessionRegistry}, which is the one of the four it actually resembles — a {@link
 * ConcurrentHashMap} of live things in this process, stamping an instant it got from a {@link
 * Clock} — where the other three are database stores whose rows could as easily have been dated by
 * the database, as {@code ProjectStore} decided they should be. A {@link Clock} also carries a zone
 * and gives {@code Clock.fixed} and {@code Clock.offset} for the same one argument. <b>Three
 * against one is a convention worth converging on, not one that already holds</b>, and if it
 * converges the other way the change is a constructor and a test fixture here.
 *
 * <p>The lifetimes are arguments for a different reason: {@link AuthProperties} supplies them from
 * configuration, and a constant here would be a value to move rather than a value to pass. The
 * design's numbers — fifteen minutes and seven days — are that class's defaults and {@code
 * application.yml}'s {@code plowshare.auth} block, and deliberately not a literal here.
 *
 * <h2>A fifth kind, task 3 of the auth slice: the ticket</h2>
 *
 * <p>{@link #mintTicket()} and {@link #spendTicket(String)} answer a problem the bootstrap, access,
 * refresh and operator tokens do not: none of the WHATWG {@code WebSocket} constructors accept a
 * header, and a non-browser client has no cookie jar either, so a caller that is not a browser has
 * no way to present a credential on a WebSocket upgrade at all. A ticket is what an
 * already-authenticated caller trades one HTTP round trip for — {@link
 * AuthController#ticket(jakarta.servlet.http.HttpServletRequest) ticket()} mints one — and presents
 * as {@code ?ticket=...} on the upgrade, which {@link AuthFilter} reads and spends for the events
 * path alone.
 *
 * <p>It is single-use like the bootstrap token and, unlike the bootstrap token, it also expires on
 * a clock: a ticket is designed to sit in a URL, which lands in access logs, referrers and shell
 * history exactly as the class note on {@link #acceptOperator(String)} says a reusable secret there
 * would — so single-use alone is not enough of a defence and the two together are what the design
 * spec calls out as the reason it is acceptable in a URL at all. It rides on the same {@link
 * #sweep()} the other three growable maps do rather than getting a sweep of its own, for the reason
 * given at length above: this store cannot grow without also sweeping, and a second sweep would be
 * a second place to forget to call it from.
 *
 * <h2>A sixth kind: a restricted chain, and {@link #revoke(String)}</h2>
 *
 * <p>Two additions close a gap this design left open: nothing before them could take a session away
 * once issued, and nothing could tell {@link #mintTicket()} that the session asking was one this
 * store deliberately gave a shorter leash.
 *
 * <p>{@link #issuePair(boolean)} takes a {@code restricted} flag {@link AuthController#login} sets
 * from {@link AdminRecord#mustChangePassword()}. A restricted chain is recorded in {@link
 * #accountChains}, and {@link #chainIsRestricted(String)} is what {@code AuthController#ticket()}
 * asks before minting one, and what {@link AuthFilter} itself separately asks on {@code
 * EventChannelHandler.PATH} — one call for the non-browser caller that has to trade its access
 * token for a ticket to reach that socket at all, and one for the browser that reaches the same
 * socket with nothing but the cookie it already holds: an admin who still must change their
 * password gets an access token exactly as before, but neither transport can turn it into a live
 * events socket, which is this store's part of closing the same finding {@link AuthController}'s
 * own class note on {@code must_change_password} names as unfinished: a ticket, or a cookie, bought
 * inside the access lifetime could otherwise hold the events socket open past it. <b>This paragraph
 * used to say that fact was narrower than "which admin does this chain belong to", and that it was
 * one bit set once at issuance rather than the wider identity-tagging {@link AuthController}'s
 * class note named as a larger task's — see "A seventh kind" below for why that is no longer
 * true.</b>
 *
 * <p>{@link #revoke(String)} is what {@code POST /v1/auth/password} calls on success: a changed
 * password must not leave the session that changed it still live, or the endpoint would have proven
 * a caller knew the old password and then left the old session — and anyone else holding its
 * cookies — exactly as authenticated as before. It retires the presented access token's whole
 * chain, the same {@link Map#remove(Object)} on {@link #chains} that reuse detection in {@link
 * #refresh(String)} already performs, so both the access token used to reach the endpoint and its
 * paired refresh token (if any) stop validating at once, without a lock: unlike {@link
 * #refresh(String)}, nothing here needs to be a compound check-then-act, since removing a chain is
 * the one step and there is no "spent" bit to race.
 *
 * <h2>A seventh kind: a chain remembers whose it is</h2>
 *
 * <p>The gap the previous section left open, named there and closed here: a valid session proved
 * <em>a</em> caller was authenticated and said nothing about <em>which</em> admin it was, so {@code
 * POST /v1/auth/password} took the handle it changed from the request body — harmless with one
 * admin, and not with two, since any authenticated caller could name any handle.
 *
 * <p>{@link #accountChains} is widened rather than duplicated: it was already a map keyed by chain
 * id, and a chain id is exactly what a handle needs to be recorded against, so the value becomes
 * {@link AccountChain} — the handle alongside the one bit the map already held — rather than a
 * second map that could name a different handle for the same chain than this one does. {@link
 * #issuePair(String, boolean)} is what records it, and {@link #handleFor(String)} is what reads it
 * back: {@link AuthController#changePassword} calls it on the very access token that authenticated
 * the request, so the handle whose row gets updated is the one the session was issued for and not
 * one a caller can name.
 *
 * <p>{@link #issuePair()} and {@link #issuePair(boolean)} still record no handle at all — the
 * bootstrap and operator paths belong to no account, and {@link #handleFor(String)} answers empty
 * for either, which is exactly what lets {@link AuthController#changePassword} refuse such a
 * session with 401 rather than guess whose password it might mean to change.
 *
 * <p><b>There is no password recovery, and this makes it structural rather than incidental.</b>
 * Changing a password already required the current one — {@link AuthController#changePassword}
 * verifies it before writing anything, exactly as {@link AuthController#login} verifies one to sign
 * in — so a lost password was never recoverable through this endpoint. What changes here is that
 * the endpoint no longer <em>asks</em> for a handle at all: there is no field left through which a
 * caller could even attempt to name an account whose current password they do not hold, where
 * before there was a field that merely happened not to be trusted for that purpose. The bootstrap
 * URL is not a recovery path either — it mints a session with no account attached, and {@link
 * #handleFor(String)} answering empty for it is precisely what {@code changePassword} refusing such
 * a session with 401 depends on.
 */
public final class TokenStore {

  /**
   * The two tokens a console session holds at once, and what {@link #issuePair()} and {@link
   * #refresh(String)} hand back.
   *
   * <p>They are returned together because they are always issued together: there is no state in
   * which a caller holds one of them and should be given the other. A record and not two
   * out-parameters, and the components are the tokens themselves rather than their hashes — this is
   * the one moment the plain values exist, on their way to a {@code Set-Cookie} header.
   *
   * <p><b>{@code toString} is overridden here, and the thing it is overriding is the hazard.</b> A
   * record's <em>generated</em> {@code toString} interpolates every component, so the default this
   * declaration would otherwise have had prints two live secrets in the form {@code
   * Pair[access=..., refresh=...]}. Measured rather than assumed: with this override deleted, the
   * test named below fails, and it fails on the assertion that the rendering does not contain the
   * access token. "Nothing in this package logs one" was the whole defence, and it stopped being
   * one at task 8, where this record began crossing into {@link AuthController}: a {@code
   * log.debug("issued {}", pair)}, an exception constructed with it, or a Spring error page
   * rendering a handler argument each call {@code toString} without anyone deciding to. So the
   * override is the defence instead — the same "no {@code toString}" that {@link Tokens} names in
   * its own class note as part of what protects a secret. {@code
   * TokenStoreTest.a_pair_never_prints_the_tokens_it_carries} pins it.
   *
   * <p>{@code Access} and {@code Refresh} below need no equivalent: they hold a chain id and an
   * {@link Instant}, and the secrets are the map keys.
   *
   * @param access the short-lived token every request carries
   * @param refresh the long-lived, single-use token that buys the next pair
   */
  public record Pair(String access, String refresh) {

    /**
     * {@code Pair[access=&lt;redacted&gt;, refresh=&lt;redacted&gt;]}, with neither component in
     * it.
     *
     * <p>Constant rather than, say, a prefix of each token: a prefix of a 192-bit secret is still a
     * distinguisher, and there is no debugging question this record answers that knowing which pair
     * it was would help with.
     */
    @Override
    public String toString() {
      return "Pair[access=<redacted>, refresh=<redacted>]";
    }
  }

  /** What the store keeps under an access token's hash. */
  private record Access(String chain, Instant expiresAt) {}

  /**
   * What the store keeps under a refresh token's hash. {@code spent} is the reuse signal: the
   * record survives being used, because a spent token that is merely forgotten is indistinguishable
   * from one that was never issued, and those two have to be answered differently.
   */
  private record Refresh(String chain, Instant expiresAt, boolean spent) {}

  /**
   * What {@link #accountChains} keeps under a chain id — the two facts {@link #issuePair(String,
   * boolean)} learns about a chain at issuance, carried together so they cannot drift apart the way
   * two maps keyed by the same id could.
   *
   * <p>{@code handle} is {@code null} for a chain {@link #issuePair()} or {@link
   * #issuePair(boolean)} started — the bootstrap and operator paths, which belong to no account —
   * and {@link #handleFor(String)} is written to answer {@link Optional#empty()} for exactly that
   * case rather than an {@code Optional} wrapping a null. {@code restricted} is the one bit {@link
   * #chainIsRestricted(String)} already answered before this record existed, unchanged by its
   * arrival.
   *
   * @param handle the admin this chain belongs to, or {@code null} for a chain that belongs to no
   *     account
   * @param restricted whether this chain was started restricted — see {@link #issuePair(boolean)}
   */
  private record AccountChain(String handle, boolean restricted) {}

  /**
   * How many entries of one map a single sweep may examine. See the class note on what this bound
   * does and does not buy.
   */
  private static final int SWEEP_BUDGET = 1024;

  /**
   * The expiry of a record that has none: {@link Instant#MAX}, and only {@link
   * #acceptOperator(String)} writes it.
   *
   * <p>A sentinel rather than a nullable field or a second record type, because both read paths
   * already ask the same question of every record and this answers it without a branch: {@code
   * now.isBefore(Instant.MAX)} is true for every instant a clock can produce, so {@link
   * #validAccess(String)} finds it live and {@link #drop} finds it unexpired. Nothing ever adds to
   * it, so there is no arithmetic here to overflow.
   */
  private static final Instant NEVER = Instant.MAX;

  private final Clock clock;

  private final Duration accessLifetime;

  private final Duration refreshLifetime;
  private DurableSessions durable;
  private ServiceCredentials services;

  public void withServiceCredentials(ServiceCredentials services) {
    this.services = java.util.Objects.requireNonNull(services);
  }

  private Optional<Authentication> serviceAuthentication(String presented) {
    return services == null ? Optional.empty() : services.authentication(presented);
  }

  /**
   * How long a ticket minted by {@link #mintTicket()} is good for. Seconds rather than minutes,
   * because a ticket is designed to sit in a URL and its lifetime is half of what makes that
   * acceptable — the other half is that {@link #spendTicket(String)} is single-use. See the class
   * note on the fifth kind this store holds.
   */
  private final Duration ticketLifetime;

  /**
   * The longer of the two lifetimes, so a chain outlives every token that could belong to it under
   * any configuration. Without this the sweep could drop a chain out from under a live access token
   * — which cannot happen with the shipping numbers, where the refresh lifetime is far the longer,
   * and which is exactly the kind of "cannot happen" that a configurable lifetime turns into
   * "does".
   */
  private final Duration chainLifetime;

  private final Map<String, Access> accessGrants = new ConcurrentHashMap<>();

  private final Map<String, Refresh> refreshGrants = new ConcurrentHashMap<>();

  /**
   * Chain id to the instant the chain may be forgotten. Membership is what every read path checks;
   * retiring a chain is removing its entry.
   */
  private final Map<String, Instant> chains = new ConcurrentHashMap<>();

  /**
   * What a ticket carries besides its own expiry: the handle it was minted for, or {@code null} for
   * a session that belongs to none.
   */
  private record Ticket(Instant expiresAt, String handle, Long version) {}

  /** The account a ticket was minted for, or null for a session that belongs to none. */
  public record Redeemed(String handle, Long version) {
    public Redeemed(String handle) {
      this(handle, null);
    }
  }

  /**
   * The hash of an outstanding ticket to what it carries. Unlike {@link #chains}, membership is not
   * enough to answer {@link #redeemTicket(String)} — a ticket is also removed the moment it is
   * presented at all, valid or not, which is what makes {@link Map#remove(Object)} the single-use
   * step: it is atomic, so two callers presenting the same ticket at once cannot both open a socket
   * with it.
   */
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

  /**
   * Chain ids issued for a session that belongs to an account, a session that is restricted, or
   * both — see {@link #issuePair(String, boolean)}, the class note "A sixth kind", and the class
   * note "A seventh kind" below, which is what widened this map from a {@code Boolean} to {@link
   * AccountChain} rather than adding a second map keyed by the same chain id: the two facts belong
   * to one chain and are recorded in one place, so they cannot name different things for it.
   *
   * <p>A chain {@link #issuePair()} or {@link #issuePair(boolean)} started with no handle and
   * {@code restricted} false has no entry at all — the same "membership alone is the answer" shape
   * {@link #chains} has — because such a chain has nothing here worth recording: {@link
   * #handleFor(String)} would answer empty for it either way, and {@link
   * #chainIsRestricted(String)} would answer false.
   *
   * <p><b>This paragraph used to say this map holds single digits of entries for as long as this
   * server has one admin, and budgeted {@link #sweep()}'s pass over it accordingly — with no budget
   * at all, on the reasoning that a full pass over single digits costs nothing worth bounding.</b>
   * That stopped being true the moment {@link #issuePair(String, boolean)} existed: every admin
   * session gets an entry now, restricted or not, so this map's size tracks {@link #chains}'s the
   * same way {@link #chains} tracks live sessions — not the single-digit case a lone flagged admin
   * used to leave behind between a login and either the change or the token expiring. {@link
   * #dropOrphanedAccountChains()} is what sweeps it now, bounded by {@value #SWEEP_BUDGET} the same
   * call the four other growable maps get, because a map that mirrors a budgeted map earns the same
   * budget rather than a comment claiming it stays small.
   */
  private final Map<String, AccountChain> accountChains = new ConcurrentHashMap<>();

  /**
   * The hash of the one-time token, or nothing once it has been spent. An {@link AtomicReference}
   * rather than a field because "spend it" has to be one step: see the class note on the {@code ==}
   * compare-and-set.
   */
  private final AtomicReference<String> bootstrap = new AtomicReference<>();

  private final ReentrantLock rotation = new ReentrantLock();

  /**
   * @param clock where the instants come from. A parameter so that a test asserting on an expiry
   *     sets the instant it asserts on rather than sleeping through fifteen minutes; production
   *     passes {@link Clock#systemUTC()}
   * @param accessLifetime how long an access token is good for
   * @param refreshLifetime how long a refresh token is good for. Nothing here requires it to be the
   *     longer of the two, and one test depends on that to make expiry distinguishable from reuse
   * @param ticketLifetime how long a ticket minted by {@link #mintTicket()} is good for. Seconds
   *     rather than minutes — see the class note on the fifth kind this store holds
   * @throws IllegalArgumentException if any lifetime is zero or negative. These arrive from {@link
   *     AuthProperties}, and zero would mint tokens that are dead at the instant they are issued —
   *     every request unauthenticated, with nothing anywhere saying why
   * @throws NullPointerException if any argument is null
   */
  public TokenStore(
      Clock clock, Duration accessLifetime, Duration refreshLifetime, Duration ticketLifetime) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.accessLifetime = positive(accessLifetime, "accessLifetime");
    this.refreshLifetime = positive(refreshLifetime, "refreshLifetime");
    this.ticketLifetime = positive(ticketLifetime, "ticketLifetime");
    this.chainLifetime =
        accessLifetime.compareTo(refreshLifetime) >= 0 ? accessLifetime : refreshLifetime;
  }

  private static Duration positive(Duration lifetime, String name) {
    Objects.requireNonNull(lifetime, name);
    if (lifetime.isZero() || lifetime.isNegative()) {
      throw new IllegalArgumentException(
          name
              + " must be positive; a token whose lifetime is zero or negative is"
              + " refused at the moment it is issued");
    }
    return lifetime;
  }

  /** Production account sessions persist; unit fixtures retain the in-memory seam. */
  public TokenStore withDurableSessions(DurableSessions durable) {
    this.durable = durable;
    return this;
  }

  /**
   * A fresh one-time token, replacing whatever this store held before.
   *
   * <p>Called once at startup, by the code that prints the console URL — {@link AuthConfig}'s
   * announcement. Replacing rather than refusing a second call is what makes that correct across a
   * restart: the token an operator can read is the newest one, so the newest one is the one that
   * works.
   *
   * <p><b>This token is not what goes in the file.</b> That was the design's plan and it does not
   * survive contact with two clients — see {@link #acceptOperator(String)}, which is what the file
   * holds and why.
   *
   * <p><b>The returned value is the only copy of it this process will produce. It is printed once
   * and never logged again</b>, and this class never sees it after this method returns — what it
   * keeps is the hash.
   *
   * <p>It has no expiry. A server started detached may not be visited for days, and the token's
   * defence is that it is single-use, not that it is brief.
   *
   * @return the token, in the clear, for its one printing
   */
  public String mintBootstrap() {
    String token = Tokens.mint();
    bootstrap.set(Tokens.hash(token));
    return token;
  }

  /**
   * Spend the bootstrap token, if that is what was presented.
   *
   * <p>The check and the spending are one atomic step, so two callers presenting the same valid
   * token get exactly one {@code true} between them. A wrong or absent value spends nothing and
   * leaves the real token standing — which matters more than it looks: a store that cleared its
   * hash on any presentation would let anyone who can reach the port invalidate the startup line
   * before the operator has clicked it.
   *
   * @param presented whatever the request carried, including nothing
   * @return true exactly once, for the token {@link #mintBootstrap()} last returned
   */
  public boolean spendBootstrap(String presented) {
    if (presented == null || presented.isBlank()) {
      return false;
    }
    String stored = bootstrap.get();
    return Tokens.verify(presented, stored) && bootstrap.compareAndSet(stored, null);
  }

  /**
   * A fresh single-use ticket, good for {@link #ticketLifetime}.
   *
   * <p>What {@link AuthController#ticket(jakarta.servlet.http.HttpServletRequest) ticket()} hands
   * an already-authenticated caller so it can open {@code /v1/events?ticket=...} — see the class
   * note on the fifth kind this store holds for why that endpoint has to exist at all. Called on
   * every request to that endpoint rather than once at startup, so — unlike {@link
   * #mintBootstrap()} — many tickets can be outstanding at once and minting one never invalidates
   * another.
   *
   * <p>Sweeps first, for the same reason {@link #issuePair()} and {@link #acceptOperator(String)}
   * do: this is one of the methods that adds a record, and the class invariant is that this store
   * cannot grow without also sweeping.
   *
   * @return the ticket, in the clear, for its one appearance in a query string
   */
  public String mintTicket() {
    return mintTicket(null);
  }

  /**
   * A fresh single-use ticket carrying {@code handle}, good for {@link #ticketLifetime}.
   *
   * <p>What {@link AuthController}'s ticket endpoint calls with the handle the caller's own session
   * belongs to — see {@link #redeemTicket(String)} for what {@link AuthFilter} reads back on the
   * events upgrade, and the class note on the fifth kind this store holds for why a ticket exists
   * at all.
   *
   * @param handle the account this ticket belongs to, or {@code null} for a session that belongs to
   *     none — the operator and bootstrap paths
   * @return the ticket, in the clear, for its one appearance in a query string
   */
  public String mintTicket(String handle) {
    return mintTicket(
        handle, durable == null || handle == null ? null : durable.version(handle).orElse(-1L));
  }

  public String mintTicket(String handle, Long version) {
    sweep();
    String ticket = Tokens.mint();
    tickets.put(
        Tokens.hash(ticket), new Ticket(clock.instant().plus(ticketLifetime), handle, version));
    return ticket;
  }

  /**
   * Spend a ticket, if it is one this store minted and it has not expired.
   *
   * <p>Kept for the callers that only ever needed a boolean — see {@link #redeemTicket(String)},
   * which this now delegates to, for the caller that also needs to know whose the ticket was.
   *
   * @param presented whatever the request carried, including nothing
   * @return true exactly once, for a ticket {@link #mintTicket()} produced that has not outlived
   *     {@link #ticketLifetime}
   */
  public boolean spendTicket(String presented) {
    return redeemTicket(presented).isPresent();
  }

  /**
   * Spend a ticket, if it is one this store minted and it has not expired, and say whose it was.
   *
   * <p>{@link Map#remove(Object)} against the hash is the single-use step — atomic, so two callers
   * presenting the same ticket at the same instant cannot both open a socket with it — the same
   * property {@link #spendBootstrap(String)}'s compare-and-set gives the bootstrap token. A wrong,
   * absent, expired or already-spent value spends nothing; an expired one is removed regardless,
   * which is the lazy half of eviction {@link #validAccess(String)} also does.
   *
   * @param presented whatever the request carried, including nothing
   * @return the ticket's handle, possibly itself {@code null}, wrapped in a present {@link
   *     Optional} exactly once for a ticket {@link #mintTicket()} produced that has not outlived
   *     {@link #ticketLifetime}; {@link Optional#empty()} otherwise
   */
  public Optional<Redeemed> redeemTicket(String presented) {
    if (presented == null || presented.isBlank()) {
      return Optional.empty();
    }
    Ticket spent = tickets.remove(Tokens.hash(presented));
    if (spent == null || !clock.instant().isBefore(spent.expiresAt())) {
      return Optional.empty();
    }
    if (spent.version() != null
        && !durable.version(spent.handle()).filter(spent.version()::equals).isPresent())
      return Optional.empty();
    return Optional.of(new Redeemed(spent.handle(), spent.version()));
  }

  /**
   * Record a long-lived access grant for the CLI's token: a chain of its own, no refresh partner
   * and no expiry.
   *
   * <h2>Why a second credential exists at all</h2>
   *
   * <p>The slice design said the bootstrap token is printed in a URL for the browser <em>and</em>
   * written to {@code ~/.config/plowshare/console-token} so a detached server stays reachable.
   * Those two sentences cannot both hold, because there are two clients and the bootstrap token is
   * single-use:
   *
   * <ul>
   *   <li>the <b>browser</b> gets its credential as {@code Set-Cookie} on {@link
   *       AuthController#exchange}, which is the only transport it has on a WebSocket upgrade;
   *   <li>the <b>CLI</b> presents {@code Authorization: Bearer} and is never handed a cookie — that
   *       endpoint answers 204 with no body on purpose, so there is nothing for it to read; and
   *   <li>whichever of them spends the one token, the other has none.
   * </ul>
   *
   * <p>So startup mints two: the bootstrap token stays single-use and stays in the URL — single-use
   * is the whole of what makes a secret in a URL defensible — and this one goes in the file. Making
   * the URL token reusable instead was the other way out and is refused: a reusable secret in a URL
   * reaches access logs, referrers and shell history, and the design calls that out by name.
   *
   * <h2>No expiry, and what that costs</h2>
   *
   * <p>The grant and its chain are recorded at {@link Instant#MAX}, so {@link #validAccess(String)}
   * never finds it expired and {@link #sweep()} never drops it. The bound on its life is this
   * process rather than a clock: this store is a {@link ConcurrentHashMap} in memory, so a restart
   * invalidates every operator token ever accepted, and the same restart writes a fresh one over
   * the file.
   *
   * <p><b>"Bounded by the process" is weaker than it sounds, and this is the known limit rather
   * than a reassurance.</b> A server started detached and left up — which is the exact case the
   * file exists for — makes that bound months long. There is no rotation and no revocation: nothing
   * short of restarting the server invalidates a copy of this token, and an operator who suspects
   * one has leaked has no smaller instrument than that. Recorded here deliberately undesigned; a
   * rotation endpoint is a decision of its own and not a thing to grow out of a footnote.
   *
   * <p>The alternative — expiring it at {@link AuthProperties#getRefreshLifetime()} — was rejected
   * because it breaks the case the file exists for. A detached server left up for eight days would
   * answer the CLI 401 with a file on disk that reads exactly like a good credential, and no
   * message anywhere would say the credential in it had aged out.
   *
   * <h2>Why writing it to disk is a new exposure, not the {@code lm-key} one</h2>
   *
   * <p>This paragraph used to say the defences are "the ones {@code ~/.config/plowshare/lm-key} has
   * and no others", as though the file were covered by a precedent already set. <b>It is not, and
   * {@link Tokens} draws the distinction that defeats it.</b> {@code lm-key} is a credential this
   * server <em>presents</em>, so it has to be recoverable and a plaintext file is the only shape it
   * can take. A token is one this server <em>accepts</em>, and the whole reason {@link
   * #accessGrants} keeps a digest is that an accept-side secret needs no recoverable copy anywhere.
   * Writing one to disk gives back exactly the recoverability the hashing was chosen to remove.
   *
   * <p>The trade is still made, and on its own merits rather than on that precedent: the file is
   * the only way a detached server stays reachable by the CLI at all, and the exposure is one file,
   * owner-only, on the machine that is already running the server it authenticates. But it is a
   * <b>new exposure class</b> for this repository — the first accept-side secret it writes down —
   * and the honest statement of what it costs is that a copy taken from the file is good until the
   * server restarts, where a spent bootstrap token would be worth nothing. Mode 600 is the whole of
   * the defence, which is why {@link AuthConfig#writeOperatorToken} stages and renames rather than
   * writing in place.
   *
   * <p>It is on a chain of its own so that nothing which happens to a browser session reaches it,
   * and it has no refresh token so nothing can present a spent one and retire it. The CLI therefore
   * cannot log itself out.
   *
   * <h2>Why this takes the token instead of minting one</h2>
   *
   * <p>{@link #mintBootstrap()} and {@link #issuePair()} both generate and record in one call, and
   * this one deliberately does not, because the order matters here and nowhere else: <b>the grant
   * this creates never expires and {@link #sweep()} never drops it</b>, so one created for a token
   * that did not reach {@link AuthConfig#writeOperatorToken} lives as long as the process, holds a
   * credential nobody can present, and is invisible. Measured on the previous shape, where {@link
   * AuthConfig} minted and then wrote: a write that threw left {@link #trackedRecords()} at 2 while
   * the warning told the operator the CLI had no credential.
   *
   * <p>So the caller mints with {@link Tokens#mint()}, writes the file, and calls this only if that
   * succeeded. This class cannot check that the value it is handed came from {@code Tokens.mint()}
   * — a token has no structure to verify beyond being hex — so that is a precondition on the caller
   * and not a guard here; the one caller in this repository is {@code AuthConfig.announce}, and
   * {@code AuthControllerTest} asserts across a failing write that nothing is recorded.
   *
   * @param token a freshly minted token, already delivered to wherever it is to be read from. This
   *     class keeps only {@link Tokens#hash(String)} of it, and it is never printed: the startup
   *     line carries the bootstrap token, and that line is the only place this server writes a
   *     secret
   * @throws IllegalArgumentException if {@code token} is null or blank, from {@link
   *     Tokens#hash(String)}
   */
  public void acceptOperator(String token) {
    // Sweeps, like the other two methods that add records, so that the class
    // invariant "this store cannot grow without also sweeping" stays literally
    // true. At the one moment this is called the store is empty and the sweep
    // is three empty iterations.
    sweep();
    String hash = Tokens.hash(token);
    String chain = UUID.randomUUID().toString();
    chains.put(chain, NEVER);
    accessGrants.put(hash, new Access(chain, NEVER));
  }

  /**
   * A new access and refresh token on a chain of their own, unrestricted.
   *
   * <p>This is what a spent bootstrap token buys. The chain starts here, and every pair {@link
   * #refresh(String)} later hands back belongs to it, so retiring what this returns retires
   * everything descended from it.
   *
   * @return the pair, in the clear, on its way to a {@code Set-Cookie}
   */
  public Pair issuePair() {
    return issuePair(false);
  }

  /**
   * A new access and refresh token on a chain of their own, optionally marked restricted.
   *
   * <p>{@code restricted} is {@code true} for exactly one caller today: a login by an admin who
   * still must change their password. It records the chain in {@link #accountChains} before minting
   * anything on it, so there is no window in which the chain exists and {@link
   * #chainIsRestricted(String)} does not yet know it should say so.
   *
   * <p>Restriction is a property of the chain, not of one pair on it — but in practice no pair
   * after the first is ever asked, because {@link AuthController#login} withholds the refresh token
   * whenever it passes {@code true} here, so a restricted chain never has anything to present to
   * {@link #refresh(String)} in the first place.
   *
   * <p><b>Restricted with no handle is not a state any production path may create.</b> No caller
   * here does — {@code login} always passes a handle alongside {@code true} through {@link
   * #issuePair(String, boolean)}, and {@code exchange} calls the no-arg {@link #issuePair()}, never
   * this overload with {@code true} — but nothing below stops it. A chain that reached it would
   * flag the console into the password-change screen with no handle to submit the change against,
   * and every submission from there would 401 forever: the exact dead end Task 3 deleted the
   * console's other guards for, reintroduced by a caller nobody has written yet.
   *
   * @param restricted whether the chain this call starts should refuse {@link #mintTicket()} on its
   *     access token — see {@link #chainIsRestricted(String)}
   * @return the pair, in the clear, on its way to a {@code Set-Cookie}
   */
  public Pair issuePair(boolean restricted) {
    return issuePair(null, restricted);
  }

  /**
   * A new access and refresh token on a chain of their own, belonging to {@code handle} and
   * optionally marked restricted.
   *
   * <p>This is what {@link AuthController#login} calls: a browser or a script that just proved it
   * knows an admin's current password gets a chain this store can later name — see the class note
   * "A seventh kind" for why the map behind that is the same one {@link #chainIsRestricted(String)}
   * already used rather than a second one, and for the whole reason this overload exists. {@link
   * #handleFor(String)} is what reads {@code handle} back.
   *
   * <p>{@code handle} is recorded before minting anything on the chain, the same ordering {@link
   * #issuePair(boolean)} already used for {@code restricted} and for the same reason: there is no
   * window in which the chain exists and a read path does not yet know what it belongs to. <b>This
   * paragraph used to stop there, and that was not the whole of the ordering that matters.</b>
   * {@link #chains} is written first, via {@link #renewChain(String, Instant)}, and only then does
   * {@link #accountChains} get its entry — not the other way around. A concurrent {@link #sweep()}
   * drops a {@link #accountChains} entry whose chain {@link #chains} does not yet contain, so
   * writing this map before that one would let a sweep landing in the gap between the two writes
   * delete the entry this call just made, silently: a chain meant to be restricted would mint a
   * ticket anyway, or one meant to answer a handle would answer none. See {@link
   * #renewChain(String, Instant)}'s own class note for the rest of this. An entry is written only
   * if there is something worth remembering — {@code handle} is non-null, or {@code restricted} is
   * true — so a chain that is neither leaves {@link #accountChains} exactly as untouched as {@link
   * #issuePair()} always left it.
   *
   * @param handle the admin this chain belongs to, or {@code null} for a chain that belongs to no
   *     account — the bootstrap and operator paths, which is what {@link #issuePair()} and {@link
   *     #issuePair(boolean)} still pass
   * @param restricted whether the chain this call starts should refuse {@link #mintTicket()} on its
   *     access token — see {@link #chainIsRestricted(String)}
   * @return the pair, in the clear, on its way to a {@code Set-Cookie}
   */
  public Pair issuePair(String handle, boolean restricted) {
    if (handle != null && durable != null) return durable.issue(handle, restricted);
    sweep();
    String chain = UUID.randomUUID().toString();
    Instant now = clock.instant();
    renewChain(chain, now);
    if (handle != null || restricted) {
      accountChains.put(chain, new AccountChain(handle, restricted));
    }
    return issueOn(chain, now);
  }

  /**
   * Whether {@code presented} is an access token that is still good.
   *
   * <p>The per-request path, and the only method here that is on it: it takes no lock, sweeps
   * nothing, and does one digest and two map reads. Three things have to hold — the store issued
   * it, it has not expired, and its chain has not been retired. The third is what makes reuse
   * detection reach a token that was minted before the theft was noticed.
   *
   * <p>An expired record is dropped as it is found. Absence is an answer rather than an exception,
   * for the reason {@link Tokens#verify(String, String)} gives: on most requests reaching {@link
   * AuthFilter} there is no cookie at all.
   *
   * @param presented whatever the request carried, including nothing
   * @return whether it authenticates this request
   */
  public boolean validAccess(String presented) {
    if (ServiceCredentials.credential(presented))
      return serviceAuthentication(presented).isPresent();
    if (presented == null || presented.isBlank()) {
      return false;
    }
    String hash = Tokens.hash(presented);
    Access grant = accessGrants.get(hash);
    if (grant == null) {
      return durable != null && durable.valid(presented);
    }
    Instant now = clock.instant();
    if (!now.isBefore(grant.expiresAt())) {
      accessGrants.remove(hash, grant);
      return false;
    }
    return chains.containsKey(grant.chain());
  }

  /**
   * Whether {@code presented} is a live access token whose chain was marked restricted at {@link
   * #issuePair(boolean)} — see the class note "A sixth kind".
   *
   * <p>Deliberately silent about every other way this can answer {@code false}: an unknown, expired
   * or retired token is treated exactly like an unrestricted one, because both callers here —
   * {@code AuthController#ticket()}, and {@link AuthFilter} itself on {@code
   * EventChannelHandler.PATH} since the residual review of task 3 found the browser half of this
   * same check still open — ask only after {@link AuthFilter} has already accepted the very same
   * token, so by the time this runs "invalid" is not a case this method has to distinguish for its
   * caller; it only has one more question to ask of a token already known good.
   *
   * @param presented whatever the request carried, including nothing
   * @return true only if {@code presented} is a live access token on a chain {@link
   *     #issuePair(boolean)} started with {@code restricted} true
   */
  public boolean chainIsRestricted(String presented) {
    if (durable != null && presented != null && !accessGrants.containsKey(Tokens.hash(presented)))
      return durable.restricted(presented);
    if (presented == null || presented.isBlank()) {
      return false;
    }
    Access grant = accessGrants.get(Tokens.hash(presented));
    if (grant == null || !clock.instant().isBefore(grant.expiresAt())) {
      return false;
    }
    AccountChain account = accountChains.get(grant.chain());
    return account != null && account.restricted();
  }

  /**
   * The admin {@code presented} belongs to, or nothing — see the class note "A seventh kind" for
   * why this exists and what it closes.
   *
   * <p>{@code POST /v1/auth/password} is the one caller today: {@link
   * AuthController#changePassword} asks this of the very access token that authenticated the
   * request, and updates whichever admin it names rather than one the request body claims. A
   * session that belongs to no account — the bootstrap and operator paths, {@link #issuePair()} and
   * {@link #issuePair(boolean)} — answers empty, which is what lets that caller refuse such a
   * session with 401: it is not an admin, and has no password for this store to know the owner of.
   *
   * <p>Unlike {@link #chainIsRestricted(String)}, this also checks {@link #chains} membership: a
   * chain {@link #revoke(String)} has retired keeps its {@link #accountChains} entry until {@link
   * #sweep()} collects it, and a caller asking "whose is this" about a token whose session has
   * already ended must not still be told, even in the narrow window before that entry is swept.
   * {@link #chainIsRestricted(String)} can stay silent about that case because both of its callers
   * ask only after {@link AuthFilter} has already checked {@link #chains} itself; this method has
   * no such caller to rely on that check having already run.
   *
   * @param presented whatever the request carried, including nothing
   * @return the handle {@link #issuePair(String, boolean)} recorded for this chain, or {@link
   *     Optional#empty()} if {@code presented} is not a live access token on a live chain that
   *     belongs to one
   */
  public Optional<String> handleFor(String presented) {
    if (ServiceCredentials.credential(presented))
      return serviceAuthentication(presented).map(Authentication::handle);
    if (durable != null && presented != null && !accessGrants.containsKey(Tokens.hash(presented)))
      return durable.handle(presented);
    if (presented == null || presented.isBlank()) {
      return Optional.empty();
    }
    Access grant = accessGrants.get(Tokens.hash(presented));
    if (grant == null || !clock.instant().isBefore(grant.expiresAt())) {
      return Optional.empty();
    }
    if (!chains.containsKey(grant.chain())) {
      return Optional.empty();
    }
    AccountChain account = accountChains.get(grant.chain());
    return account == null ? Optional.empty() : Optional.ofNullable(account.handle());
  }

  /** Issue a login pair against the password hash validated by the caller. */
  public Pair issuePair(String handle, boolean restricted, String expectedHash) {
    return durable != null && handle != null
        ? durable.issue(handle, restricted, expectedHash)
        : issuePair(handle, restricted);
  }

  public record Authentication(String handle, boolean restricted, Long version) {}

  public Optional<Authentication> authentication(String presented) {
    if (ServiceCredentials.credential(presented)) return serviceAuthentication(presented);
    if (durable != null) {
      var found = durable.authentication(presented);
      if (found.isPresent()) return found;
    }
    if (!validAccess(presented)) return Optional.empty();
    // A durable account credential cannot fall through to an operator identity on revocation.
    return Optional.of(
        new Authentication(handleFor(presented).orElse(null), chainIsRestricted(presented), null));
  }

  /** Retire local grants and tickets after the account revocation transaction commits. */
  public void retireTransientAccount(String handle) {
    rotation.lock();
    try {
      accountChains.forEach(
          (id, account) -> {
            if (java.util.Objects.equals(account.handle(), handle)) chains.remove(id);
          });
      tickets
          .entrySet()
          .removeIf(entry -> java.util.Objects.equals(entry.getValue().handle(), handle));
    } finally {
      rotation.unlock();
    }
  }

  /**
   * Retire the chain behind {@code presented}, if it is a live access token this store issued — see
   * the class note "A sixth kind" for why {@code POST /v1/auth/password} calls this on success.
   *
   * <p>{@link Map#remove(Object)} against {@link #chains}, the exact step reuse detection in {@link
   * #refresh(String)} already performs on a stolen refresh token — proof that removing a chain id
   * is sufficient to kill everything issued on it, since that is the mechanism a security property
   * already depends on elsewhere in this class. The access and refresh records this chain's tokens
   * live under are left in their maps, inert, until {@link #sweep()} collects them on their own
   * expiry — the same "a retired chain leaves its records behind" the class note on chains
   * describes, and safe for the same reason: every read path checks chain membership before
   * trusting a record.
   *
   * <p><b>This used to take no lock, on the reasoning that there is no check-then-act here to race
   * — only a lookup and a removal, and two callers racing to revoke the same chain both remove it,
   * the second a no-op against a key already gone.</b> That reasoning considered only two callers
   * of this method racing each other. It missed the check-then-act that already lives in {@link
   * #refresh(String)}: that method reads {@code chains.containsKey(grant.chain())} to decide
   * whether the chain it is about to issue on is still live, and does not write {@link #chains}
   * again until {@link #issueOn(String, Instant)}, several lines later. A revoke landing in that
   * gap — after the read finds the chain present, before the write puts it back — removes a chain
   * that {@link #issueOn(String, Instant)} then recreates, and the password change this method
   * exists for has accomplished nothing: the very session it was meant to end is live again, with a
   * new pair, because a change of password must not be something a concurrent refresh can outrun.
   *
   * <p>So this takes {@link #rotation} too, the same lock and the same field {@link
   * #refresh(String)} already serialises on, rather than a lock of its own: a second lock would
   * leave the two methods free to interleave with each other regardless of how carefully each
   * guarded itself. Holding it here does not turn this into a second compound check-then-act — the
   * body is still one {@link Map#remove(Object)} — it closes the window by ensuring no {@link
   * #refresh(String)} critical section is ever in progress while this reads or writes {@link
   * #chains}, so whichever of the two finishes last leaves the chain removed: a revoke that lands
   * first is seen by the refresh that follows it, via the same {@code containsKey} check, and a
   * refresh that finishes first hands this method a chain {@link #issueOn(String, Instant)} already
   * put back, which this then removes exactly as it would have removed the original.
   *
   * @param presented whatever the request carried, including nothing. A value naming no live access
   *     token — absent, expired, unknown, or already on a retired chain — retires nothing,
   *     silently: {@code AuthController#changePassword} calls this after it has already verified
   *     the caller's current password, so there is always meant to be a chain here to retire, but a
   *     caller with no way to reach this method except through an authenticated request is not an
   *     oracle worth guarding with a distinguishable failure
   */
  public void revoke(String presented) {
    if (durable != null) durable.revoke(presented);
    if (presented == null || presented.isBlank()) {
      return;
    }
    Access grant = accessGrants.get(Tokens.hash(presented));
    if (grant == null) {
      return;
    }
    rotation.lock();
    try {
      chains.remove(grant.chain());
    } finally {
      rotation.unlock();
    }
  }

  /**
   * Exchange a refresh token for a new pair, retiring the one presented.
   *
   * <p>The four ways this answers with nothing, which are deliberately not distinguished to the
   * caller — {@link AuthController#refresh} answers one bare 401 for all of them, because telling a
   * caller which of these it hit is telling an attacker whether a guessed token was ever real:
   *
   * <ul>
   *   <li>the token is not one this store issued, or was swept long ago;
   *   <li>it has expired — refused, dropped, and <em>not</em> treated as reuse;
   *   <li>its chain has already been retired; or
   *   <li><b>it was already spent, which retires the chain.</b> See the class note: this is the
   *       case the whole design turns on.
   * </ul>
   *
   * @param presented whatever the request carried, including nothing
   * @return the new pair, or nothing at all
   */
  public Optional<Pair> refresh(String presented) {
    if (presented == null || presented.isBlank()) {
      return Optional.empty();
    }
    String hash = Tokens.hash(presented);
    if (durable != null && !refreshGrants.containsKey(hash)) return durable.refresh(presented);
    // Behind the "is this anything?" check, and outside the lock. See the
    // class note on where the sweep rides.
    if (refreshGrants.containsKey(hash)) {
      sweep();
    }
    rotation.lock();
    try {
      Refresh grant = refreshGrants.get(hash);
      if (grant == null) {
        return Optional.empty();
      }
      Instant now = clock.instant();
      if (!now.isBefore(grant.expiresAt())) {
        refreshGrants.remove(hash, grant);
        return Optional.empty();
      }
      if (!chains.containsKey(grant.chain())) {
        return Optional.empty();
      }
      if (grant.spent()) {
        chains.remove(grant.chain());
        return Optional.empty();
      }
      refreshGrants.put(hash, new Refresh(grant.chain(), grant.expiresAt(), true));
      renewChain(grant.chain(), now);
      return Optional.of(issueOn(grant.chain(), now));
    } finally {
      rotation.unlock();
    }
  }

  /**
   * Mint a pair and record it against {@code chain}, whose entry in {@link #chains} the caller has
   * already written or renewed — see {@link #renewChain(String, Instant)}.
   *
   * <p><b>This used to write {@link #chains} itself, first, so that a request arriving between the
   * two writes found either no token or a token whose chain is there.</b> That ordering is
   * preserved, but the write moved to the caller: {@link #issuePair(String, boolean)} has to put
   * {@code chain} in {@link #chains} before it decides whether to write {@link #accountChains} too,
   * and doing that here — after the caller may already have written {@link #accountChains} — would
   * reopen exactly the race {@link #renewChain(String, Instant)}'s own class note describes. {@link
   * #refresh(String)}, the other caller, renews the same way for a different reason: rotation must
   * not let a chain's clock run out from under a session that is still being used.
   */
  private Pair issueOn(String chain, Instant now) {
    String access = Tokens.mint();
    String refresh = Tokens.mint();
    accessGrants.put(Tokens.hash(access), new Access(chain, now.plus(accessLifetime)));
    refreshGrants.put(Tokens.hash(refresh), new Refresh(chain, now.plus(refreshLifetime), false));
    return new Pair(access, refresh);
  }

  /**
   * Write or renew {@code chain}'s entry in {@link #chains}: the instant it may be forgotten,
   * {@code chainLifetime} out from {@code now}.
   *
   * <p>Both callers of {@link #issueOn(String, Instant)} call this immediately before it, and — for
   * {@link #issuePair(String, boolean)} — before anything is written to {@link #accountChains} too.
   * That ordering is what a concurrent {@link #sweep()} depends on: {@link #sweep()} drops an
   * {@link #accountChains} entry whose chain is absent from {@link #chains}, so an entry must never
   * become visible in {@link #accountChains} before {@link #chains} already names its chain — a
   * sweep landing in that gap would otherwise delete the entry it just missed seeing, silently
   * turning a restricted session unrestricted or an account-bearing session into one {@link
   * #handleFor(String)} cannot name. Extracted to one method so the two callers cannot drift on the
   * order, the same reason {@link AccountChain} itself is one record and not two maps.
   */
  private void renewChain(String chain, Instant now) {
    chains.put(chain, now.plus(chainLifetime));
  }

  /**
   * Drop what has expired from the four maps that carry an expiry, and what has been orphaned from
   * the one that does not. See the class note for what the budget bounds and what it does not.
   */
  private void sweep() {
    Instant now = clock.instant();
    drop(accessGrants, now, Access::expiresAt);
    drop(refreshGrants, now, Refresh::expiresAt);
    // A chain's value IS its expiry, so the accessor is the identity. Spelt
    // out rather than Function.identity(), which reads as a placeholder for
    // an accessor nobody wrote.
    drop(chains, now, expiry -> expiry);
    drop(tickets, now, Ticket::expiresAt);
    dropOrphanedAccountChains();
  }

  private static <V> void drop(Map<String, V> records, Instant now, Function<V, Instant> expiry) {
    int examined = 0;
    Iterator<Map.Entry<String, V>> entries = records.entrySet().iterator();
    while (entries.hasNext() && examined++ < SWEEP_BUDGET) {
      Map.Entry<String, V> entry = entries.next();
      if (!now.isBefore(expiry.apply(entry.getValue()))) {
        records.remove(entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * Drop {@link #accountChains} entries whose chain {@link #chains} no longer names, bounded the
   * same {@value #SWEEP_BUDGET}-per-call way {@link #drop} bounds the other four maps.
   *
   * <p><b>This map used to go unbounded here, on the reasoning that it would hold single digits of
   * entries for as long as this server has one admin — see the field's own javadoc for why that
   * reasoning no longer holds.</b> It cannot simply call {@link #drop}, though its shape is the
   * same otherwise: {@link #drop}'s removal condition is an expiry compared against {@code now},
   * and {@link AccountChain} carries no {@link Instant} of its own for that method's {@code
   * Function<V, Instant>} parameter to extract — the entry to drop is one whose chain is gone, not
   * one whose own clock ran out. {@link Map#remove(Object, Object)} rather than the iterator's own
   * {@code remove()}, for the reason {@link #drop} is already written that way: checked against the
   * JDK 21 source, {@code ConcurrentHashMap}'s iterator removes by key alone regardless of what the
   * iterator saw, and the two-argument form cannot drop an entry that was replaced since it was
   * read.
   */
  private void dropOrphanedAccountChains() {
    int examined = 0;
    Iterator<Map.Entry<String, AccountChain>> entries = accountChains.entrySet().iterator();
    while (entries.hasNext() && examined++ < SWEEP_BUDGET) {
      Map.Entry<String, AccountChain> entry = entries.next();
      if (!chains.containsKey(entry.getKey())) {
        accountChains.remove(entry.getKey(), entry.getValue());
      }
    }
  }

  /**
   * How many records this store is holding, across all five maps.
   *
   * <p>Package-private and for {@code TokenStoreTest} alone, which asserts exact counts rather than
   * "fewer than before" — a sweep that dropped one record per call and never caught up would
   * satisfy "fewer". Not public because it is a number about the implementation and not about
   * authentication, and a caller outside this package that wanted it would be a caller asking the
   * wrong question.
   *
   * <p>{@link #accountChains} counts now, which it did not before "A seventh kind": every existing
   * caller of this method issues chains with no handle and unrestricted, so this addition is zero
   * for every count this method already pinned before it could name a handle at all, and it is what
   * lets {@code the_sweep_does_not_strand_a_restricted_chains_entry} assert that a chain's entry in
   * this map does not outlive the chain itself.
   */
  int trackedRecords() {
    return accessGrants.size()
        + refreshGrants.size()
        + chains.size()
        + tickets.size()
        + accountChains.size();
  }
}
