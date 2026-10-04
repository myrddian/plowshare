package io.aeyer.plowshare.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Which live session roots which project, without a socket anywhere near it.
 *
 * <p>{@code SessionRegistryTest}'s shape and for its reason: this is a map with edges — a project
 * two machines both claim, a session that goes away, a client that reconnects — and a registry
 * reachable only through a WebSocket is an instrument that is hard to point at any of them.
 *
 * <h2>What is measured here rather than assumed</h2>
 *
 * <ul>
 *   <li><b>the mapping is one-to-one in both directions.</b> One presence per project is the
 *       owner's decision and the refusal is where it is enforced; one presence per session is this
 *       registry's own, and it is what stops a reconnecting client leaving the project it used to
 *       root pointing at a socket that has gone;
 *   <li><b>a conflict names the holder.</b> A refusal that only said "taken" would leave an
 *       operator with two terminals open and no way to tell which of them is the one to close;
 *   <li><b>a re-declaration by the same session is not a conflict</b>, which is the reconnect the
 *       design spec asks about: a session id is the identity, so the same id arriving again is the
 *       same machine.
 * </ul>
 */
class PresenceRegistryTest {

  private static final String LEDGER = "ledger";

  private final PresenceRegistry presences = new PresenceRegistry();

  // --- the canonical name --------------------------------------------------

  @Test
  void a_presence_composes_the_canonical_name_the_spec_describes() {
    Presence rooted = new Presence("s1", "bench.local", "/Users/example/code/ledger", LEDGER);

    assertEquals(
        "bench.local/Users/example/code/ledger/ledger",
        rooted.canonicalName(),
        "<MACHINE>/<PATH>/<PROJ_NAME>, with the path's leading separator spent as the"
            + " one between the machine and it");
  }

  @Test
  void a_root_spelled_with_a_trailing_separator_composes_the_same_name() {
    assertEquals(
        new Presence("s1", "bench", "/srv/ledger", LEDGER).canonicalName(),
        new Presence("s2", "bench", "/srv/ledger/", LEDGER).canonicalName(),
        "a trailing slash is a spelling of the same place, and two spellings of one"
            + " place must not be two identities");
  }

  @Test
  void a_presence_refuses_the_pieces_it_could_not_compose_a_name_from() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Presence("s1", " ", "/srv/ledger", LEDGER),
        "a blank machine");
    assertThrows(
        IllegalArgumentException.class,
        () -> new Presence("s1", "bench", " ", LEDGER),
        "a blank root");
    assertThrows(
        IllegalArgumentException.class,
        () -> new Presence("s1", "bench", "/srv/ledger", " "),
        "a blank project");
    assertThrows(
        IllegalArgumentException.class,
        () -> new Presence(" ", "bench", "/srv/ledger", LEDGER),
        "a blank session");
  }

  @Test
  void a_relative_root_is_refused_because_it_names_no_place_on_another_machine() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Presence("s1", "bench", "code/ledger", LEDGER),
        "a path with no root on it is a place only the machine that typed it could"
            + " find, and the machine that typed it is not this one");
  }

  // --- one presence per project --------------------------------------------

  @Test
  void a_project_is_served_by_the_session_that_declared_it() {
    Presence declared = presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));

    assertEquals(Optional.of(declared), presences.serving(LEDGER));
    assertEquals(
        Optional.empty(),
        presences.serving("payments"),
        "a project nobody has declared is rooted nowhere, which is an ordinary state");
  }

  /**
   * The other direction of the same one-to-one mapping: what a session roots, which is what {@code
   * DefinitionResolver} asks before it reads a session's own {@code .plowshare/} on a project's
   * behalf.
   */
  @Test
  void a_session_roots_the_last_project_it_declared_and_nothing_once_withdrawn() {
    presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));
    Presence moved = presences.declare(at("laptop-a", "bench", "/srv/notes", "notes"));

    assertEquals(Optional.of(moved), presences.rootedBy("laptop-a"));
    assertEquals(Optional.empty(), presences.rootedBy("laptop-b"));
    presences.withdraw("laptop-a");
    assertEquals(Optional.empty(), presences.rootedBy("laptop-a"));
  }

  @Test
  void a_second_session_claiming_a_rooted_project_is_refused_and_told_who_holds_it() {
    presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));

    PresenceConflictException refused =
        assertThrows(
            PresenceConflictException.class,
            () -> presences.declare(at("laptop-b", "desk", "/home/example/ledger", LEDGER)));

    assertTrue(
        refused.getMessage().contains("desk/home/example/ledger/ledger"),
        "the claim that was refused is named: " + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("bench/srv/ledger/ledger"),
        "and so is the presence that holds it, which is the one to close: " + refused.getMessage());
    assertEquals(
        "laptop-a",
        presences.serving(LEDGER).orElseThrow().session(),
        "and the refusal changed nothing");
  }

  @Test
  void the_same_session_declaring_again_is_a_reconnect_and_not_a_conflict() {
    presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));

    Presence again = presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));

    assertEquals(Optional.of(again), presences.serving(LEDGER));
  }

  // --- one presence per session --------------------------------------------

  @Test
  void a_session_declaring_a_second_project_stops_rooting_the_first() {
    presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));

    presences.declare(at("laptop-a", "bench", "/srv/payments", "payments"));

    assertEquals(
        Optional.empty(),
        presences.serving(LEDGER),
        "one socket carries one project, so the earlier claim was replaced rather than"
            + " left pointing at a session that no longer says it roots it");
    assertEquals("laptop-a", presences.serving("payments").orElseThrow().session());
    assertEquals(
        1,
        presences.count(),
        "and the old claim is gone rather than merely unreachable — a stale entry"
            + " would end every run in 'ledger' with SESSION_GONE, naming a socket"
            + " nobody is at");
  }

  @Test
  void a_session_that_withdraws_leaves_its_project_rooted_nowhere() {
    presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));

    assertTrue(presences.withdraw("laptop-a"));

    assertEquals(Optional.empty(), presences.serving(LEDGER));
    assertFalse(presences.withdraw("laptop-a"), "and withdrawing twice removes nothing");
    assertFalse(
        presences.withdraw("never-here"),
        "as does withdrawing a session that never declared anything, which is every"
            + " listener-only client there has ever been");
  }

  @Test
  void a_withdrawal_frees_the_project_for_the_machine_that_was_refused() {
    presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));
    presences.withdraw("laptop-a");

    Presence second = presences.declare(at("laptop-b", "desk", "/home/example/ledger", LEDGER));

    assertEquals(
        Optional.of(second),
        presences.serving(LEDGER),
        "'at most one at a time' permits a project being re-rooted after the first"
            + " presence has gone; the canonical name records that it moved");
  }

  @Test
  void a_withdrawal_by_a_session_that_no_longer_holds_the_project_takes_nothing() {
    presences.declare(at("laptop-a", "bench", "/srv/ledger", LEDGER));
    presences.declare(at("laptop-a", "bench", "/srv/payments", "payments"));

    assertFalse(presences.withdraw("laptop-b"));
    assertEquals("laptop-a", presences.serving("payments").orElseThrow().session());
  }

  // --- the edges -----------------------------------------------------------

  @Test
  void an_unnamed_project_is_rooted_nowhere_rather_than_a_fault() {
    assertEquals(
        Optional.empty(),
        presences.serving(null),
        "the global tier is the absence of a project, and asking whether it is rooted"
            + " is the ordinary question a run with no project asks");
    assertEquals(Optional.empty(), presences.serving(" "));
  }

  /**
   * Two machines racing for one project leave exactly one holder, and the loser is told.
   *
   * <p>The lock-free-versus-locked question {@code SessionRegistry} answers for itself, asked here
   * where the compound action is real: a declaration both frees whatever the session used to root
   * and claims a project, and two of them interleaving must not leave a project claimed by nobody
   * or by two.
   */
  @Test
  void many_machines_racing_for_one_project_leave_exactly_one_holder() throws Exception {
    int racers = 16;
    CountDownLatch go = new CountDownLatch(1);
    ConcurrentLinkedQueue<String> won = new ConcurrentLinkedQueue<>();
    AtomicInteger refused = new AtomicInteger();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch done = new CountDownLatch(racers);
      for (int i = 0; i < racers; i++) {
        String session = "laptop-" + i;
        threads.execute(
            () -> {
              try {
                go.await();
                presences.declare(at(session, "m" + session, "/srv/ledger", LEDGER));
                won.add(session);
              } catch (PresenceConflictException lost) {
                refused.incrementAndGet();
              } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      go.countDown();
      assertTrue(done.await(10, TimeUnit.SECONDS), "every racer finished");
    }

    assertEquals(1, won.size(), "exactly one declaration was accepted, and it was told so");
    assertEquals(racers - 1, refused.get(), "and every other racer was refused, not ignored");
    assertEquals(
        List.copyOf(won),
        List.of(presences.serving(LEDGER).orElseThrow().session()),
        "the holder is the one that was told it had won");
  }

  @Test
  void the_registry_refuses_a_presence_it_was_handed_nothing_for() {
    assertThrows(NullPointerException.class, () -> presences.declare(null));
    assertThrows(NullPointerException.class, () -> presences.withdraw(null));
  }

  /**
   * Two presences describing the same claim are the same value, so a re-declaration can be compared
   * rather than merely accepted.
   */
  @Test
  void a_presence_is_a_value() {
    assertEquals(at("s", "m", "/srv/ledger", LEDGER), at("s", "m", "/srv/ledger", LEDGER));
    assertEquals(LEDGER, at("s", "m", "/srv/ledger", LEDGER).project());
  }

  private static Presence at(String session, String machine, String root, String project) {
    return new Presence(session, machine, root, project);
  }
}
