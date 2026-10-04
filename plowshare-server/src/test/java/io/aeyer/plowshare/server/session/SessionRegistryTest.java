package io.aeyer.plowshare.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The registry, without a socket anywhere near it.
 *
 * <h2>Why this file can exist at all</h2>
 *
 * <p>Nothing here starts Spring and nothing here binds a port. That is the point rather than a
 * convenience: the registry's whole job is what happens at its edges — an id nobody opened, a role
 * attached twice, a socket that closes after it has already been replaced — and a registry
 * reachable only through a WebSocket is an instrument that is hard to point at any of them. {@code
 * FileChannelTest} drives a real socket because the thing it is testing is a socket; this one is a
 * map, so it is tested as one.
 *
 * <h2>The stand-in for a socket is a record, and it is enough</h2>
 *
 * <p>{@link Attachment} carries a name so an assertion failure says which one survived. The
 * registry never calls anything on what it holds, so a fake with behaviour would be a fake with
 * nothing to do — the type is opaque to the registry by design, and {@code SessionRegistry}'s
 * javadoc argues why.
 *
 * <h2>What is measured here rather than assumed</h2>
 *
 * <ul>
 *   <li><b>the displacement WARN is a real record and not an appender recording nothing.</b>
 *       Measured by deleting the {@code log.warn}: {@code
 *       a_displacement_is_recorded_where_an_operator_can_see_it} failed and {@code
 *       an_ordinary_first_attach_leaves_no_trace_at_all} still passed. So the empty assertion in
 *       the second means absence rather than deafness — the pairing {@code ProviderRouterTest}
 *       already uses one package over;
 *   <li><b>concurrent attaches need no lock to be correct</b>, and {@code
 *       many_threads_attaching_one_role_leave_one_survivor_and_name_the_rest} is what says so:
 *       every attachment is either the survivor or is reported displaced exactly once, whatever the
 *       interleaving. <b>It is a race detector and one green run is not a proof.</b> Measured:
 *       replacing the registry's {@code computeIfAbsent} with a {@code get}-then-{@code put}
 *       check-then-act failed it on <b>3 of 5</b> runs of this class — it does catch that mistake,
 *       and it does not catch it every time.
 * </ul>
 */
class SessionRegistryTest {

  /**
   * A session id shaped like the design says a real one is.
   *
   * <p><b>Nothing mints one yet</b>, and that is worth saying rather than implying: {@code
   * ChannelClient} takes the id from whoever builds it and {@code FileChannelTest} passes plain
   * words like {@code "closing"}, so the only place UUIDs are stated is the design spec, and the
   * client that will actually mint one is later in this slice. A UUID here so the fixture is shaped
   * like the intended thing; the registry itself requires nothing of the shape beyond not being
   * blank, which is what keeps those existing tests and any future client both valid.
   */
  private static final String ID = "0d5c6b4e-5f2a-4f0b-9a2e-1c8f3d7b6a41";

  /** A second id, for the tests that have to show one session is not another. */
  private static final String OTHER = "6b1a9f30-2c44-4d8e-8f11-9e5a7c2b4d03";

  /**
   * What attaches, standing in for a socket. The name is so a failure says which one is still
   * there.
   */
  private record Attachment(String name) {}

  private final Ticking clock = new Ticking();

  private final SessionRegistry registry = new SessionRegistry(clock);

  /**
   * Detached after each test, so a test that asserts on WARN lines cannot be reading an appender an
   * earlier test left attached.
   */
  private final List<Runnable> appenders = new ArrayList<>();

  @AfterEach
  void detachAppenders() {
    appenders.forEach(Runnable::run);
    appenders.clear();
  }

  // --- the four the plan names ---------------------------------------------

  @Test
  void an_unknown_id_is_absent_rather_than_created_on_demand() {
    // Absent is an ORDINARY ANSWER here, which is why this returns an
    // Optional where JobStore.get throws: a job submitted with a session id
    // nothing ever attached to is the browser case and the plain-HTTP case,
    // and both must work. A lookup that created the session would make
    // "this id has a session" true of every id anybody ever typed, and the
    // question task 4 asks — does this session have a provider — would then
    // be asked of a session invented by the asking.
    assertTrue(registry.find(ID).isEmpty(), "nothing has attached to this id");
    assertEquals(0, registry.count(), "and asking about it must not be what brings it into being");
  }

  @Test
  void attaching_a_role_twice_displaces_the_first_and_says_so() {
    Attachment first = new Attachment("first");
    Attachment second = new Attachment("second");

    assertEquals(
        Optional.empty(),
        registry.attach(ID, Role.FILE_PROVIDER, first),
        "the first attach displaces nothing, so there is nothing to hand back");

    // SAYS SO BY HANDING IT BACK. This is FileChannelHandler's story and not
    // a second one — it is now the only copy of it, since that class attaches
    // here rather than to a map of its own. The newer attachment wins and the
    // older is returned; the returned one is failed and closed by the caller
    // that displaced it, which stays with the role's own handler, because the
    // registry holds no socket and so cannot close one.
    assertEquals(
        Optional.of(first),
        registry.attach(ID, Role.FILE_PROVIDER, second),
        "the second attach hands back exactly what it displaced");

    Session session = registry.find(ID).orElseThrow();
    assertEquals(
        Optional.of(second),
        session.attached(Role.FILE_PROVIDER, Attachment.class),
        "and the newer one is what the session holds afterwards");
    assertEquals(
        1,
        registry.count(),
        "displacement replaces an attachment, not the session it is attached to");
  }

  @Test
  void detaching_a_role_leaves_the_session_and_its_other_role() {
    Attachment provider = new Attachment("provider");
    Attachment listener = new Attachment("listener");
    registry.attach(ID, Role.FILE_PROVIDER, provider);
    registry.attach(ID, Role.LISTENER, listener);

    assertTrue(
        registry.detach(ID, Role.FILE_PROVIDER, provider),
        "the role that was attached is the role that detaches");

    assertTrue(registry.find(ID).isPresent(), "the session outlives the role");
    Session session = registry.find(ID).orElseThrow();
    assertFalse(session.has(Role.FILE_PROVIDER), "the detached role is gone");
    assertEquals(
        Optional.of(listener),
        session.attached(Role.LISTENER, Attachment.class),
        "and the other role is untouched — a listener disconnecting must not"
            + " take the file provider with it, and vice versa");
  }

  @Test
  void a_session_with_no_roles_is_still_a_session_because_nothing_reaps_it() {
    // THE PLAN CALLS THIS ONE "...until it is reaped". Nothing reaps, on
    // purpose and with the reason recorded in SessionRegistry's javadoc, so
    // the name says what holds instead of naming a mechanism that is not
    // there. A test named after absent machinery is a sentence that has
    // already drifted from its code on the day it is written.
    Attachment only = new Attachment("only");
    registry.attach(ID, Role.LISTENER, only);
    Instant created = registry.find(ID).orElseThrow().created();

    clock.advance(Duration.ofHours(6));
    assertTrue(registry.detach(ID, Role.LISTENER, only));

    Session bare = registry.find(ID).orElseThrow();
    assertEquals(Set.of(), bare.roles(), "no roles left");
    assertEquals(
        created, bare.created(), "and it is the same session, not a new one made by asking");
    assertEquals(1, registry.count(), "still counted, because nothing removes it");

    // AND IT CAN BE COME BACK TO, which is the property that makes a session
    // an object rather than a connection: a client that reattaches under the
    // id it already had gets the session it already had.
    Attachment again = new Attachment("again");
    assertEquals(
        Optional.empty(),
        registry.attach(ID, Role.LISTENER, again),
        "a role attaching to a bare session displaces nothing");
    assertEquals(created, registry.find(ID).orElseThrow().created());
  }

  // --- what the design needs on top of them --------------------------------

  @Test
  void a_displaced_attachment_detaching_does_not_detach_the_one_that_replaced_it() {
    // THE OTHER HALF OF DISPLACEMENT, and the half a registry can get wrong
    // while looking right. FileChannelHandler makes exactly this check twice
    // — `live.socket != socket` in handleTextMessage, and this method, which
    // its afterConnectionClosed calls — because a displaced socket still gets
    // its own close event, and a detach that matched on the role alone would
    // let that late event unhook the socket a human is actually looking at.
    //
    // There it is a correctness repair rather than a bug fix, because the
    // request ids that could be misapplied are UUIDs minted per request by
    // RemoteProvider.id() and so match nothing in the newer socket's
    // outstanding map. HERE THE SAME MISTAKE WOULD BITE TODAY: there is
    // exactly one slot per role, and a role-only detach would empty the live
    // one.
    Attachment displaced = new Attachment("displaced");
    Attachment live = new Attachment("live");
    registry.attach(ID, Role.FILE_PROVIDER, displaced);
    registry.attach(ID, Role.FILE_PROVIDER, live);

    assertFalse(
        registry.detach(ID, Role.FILE_PROVIDER, displaced),
        "the one that was already replaced detaches nothing");
    assertEquals(
        Optional.of(live),
        registry.find(ID).orElseThrow().attached(Role.FILE_PROVIDER, Attachment.class),
        "and the one that replaced it is still attached");
  }

  @Test
  void the_two_roles_are_independent_so_attaching_one_does_not_displace_the_other() {
    Attachment provider = new Attachment("provider");
    assertEquals(Optional.empty(), registry.attach(ID, Role.FILE_PROVIDER, provider));
    assertEquals(
        Optional.empty(),
        registry.attach(ID, Role.LISTENER, new Attachment("listener")),
        "a second role is a second slot, not a second connection on the first");
    assertEquals(
        Optional.of(provider),
        registry.find(ID).orElseThrow().attached(Role.FILE_PROVIDER, Attachment.class));
  }

  @Test
  void a_session_with_a_listener_and_no_provider_reports_exactly_that() {
    // The spec's third row, and it is the browser in slice 4 rather than
    // padding. Task 3 has to keep the two apart: a job submitted with this
    // session must get the providers a job with NO session gets, because a
    // session existing is not a provider being attached.
    registry.attach(ID, Role.LISTENER, new Attachment("browser"));

    Session session = registry.find(ID).orElseThrow();
    assertTrue(session.has(Role.LISTENER));
    assertFalse(
        session.has(Role.FILE_PROVIDER),
        "there is nothing on the far end that could answer a file request");
    assertEquals(Optional.empty(), session.attached(Role.FILE_PROVIDER, Attachment.class));
  }

  @Test
  void attaching_and_detaching_stamp_last_seen_and_leave_creation_alone() {
    Attachment only = new Attachment("only");
    registry.attach(ID, Role.LISTENER, only);
    Session session = registry.find(ID).orElseThrow();
    Instant created = session.created();
    assertEquals(
        created,
        session.lastSeen(),
        "a session first seen when it was created has one instant, not two");

    clock.advance(Duration.ofMinutes(7));
    registry.attach(ID, Role.FILE_PROVIDER, new Attachment("provider"));
    Instant afterAttach = registry.find(ID).orElseThrow().lastSeen();
    assertNotEquals(created, afterAttach, "attaching is being seen");
    assertEquals(created.plus(Duration.ofMinutes(7)), afterAttach);

    clock.advance(Duration.ofMinutes(3));
    registry.detach(ID, Role.LISTENER, only);
    assertEquals(
        created.plus(Duration.ofMinutes(10)),
        registry.find(ID).orElseThrow().lastSeen(),
        "and so is detaching — a client that closed one socket cleanly is a client"
            + " that was there a moment ago");
    assertEquals(
        created,
        registry.find(ID).orElseThrow().created(),
        "creation is when it started, and nothing moves it");
  }

  @Test
  void attaching_the_same_thing_again_displaces_nothing() {
    // Identity, not the role's occupancy. A caller that acts on a
    // displacement closes what it is handed — that is what
    // FileChannelHandler does with the socket it displaces — so returning
    // the object just attached would have it close the connection it had
    // this instant registered.
    Attachment same = new Attachment("same");
    registry.attach(ID, Role.FILE_PROVIDER, same);

    assertEquals(
        Optional.empty(),
        registry.attach(ID, Role.FILE_PROVIDER, same),
        "it did not replace itself");
    assertEquals(
        Optional.of(same),
        registry.find(ID).orElseThrow().attached(Role.FILE_PROVIDER, Attachment.class),
        "and it is still attached");
  }

  @Test
  void a_second_attachment_that_is_merely_equal_still_displaces() {
    // The other side of the line above, and the reason it is drawn at
    // identity: two records with the same components are equal, and two
    // sockets are two sockets. If this said "nothing displaced", the second
    // connection would be silently dropped and the first left holding a role
    // its client believes it has replaced.
    Attachment first = new Attachment("twin");
    Attachment second = new Attachment("twin");
    assertEquals(first, second, "the fixture is two equal, distinct objects");
    assertFalse(first == second, "distinct — otherwise this measures nothing");

    registry.attach(ID, Role.LISTENER, first);
    Optional<Object> displaced = registry.attach(ID, Role.LISTENER, second);

    assertTrue(displaced.isPresent(), "a different object displaced the first");
    assertSame(first, displaced.orElseThrow(), "and it is the first one, by identity");
    assertSame(
        second,
        registry.find(ID).orElseThrow().attached(Role.LISTENER, Attachment.class).orElseThrow());
  }

  @Test
  void detaching_something_that_was_never_attached_says_no_and_creates_nothing() {
    assertFalse(
        registry.detach(ID, Role.LISTENER, new Attachment("never")),
        "there is no session, so there is no role on it to detach");
    assertEquals(0, registry.count(), "and asking did not make one");

    registry.attach(ID, Role.LISTENER, new Attachment("listener"));
    assertFalse(
        registry.detach(ID, Role.FILE_PROVIDER, new Attachment("never")),
        "the session exists and that role is empty");
    assertTrue(
        registry.find(ID).orElseThrow().has(Role.LISTENER),
        "and the role that is filled is untouched");
  }

  @Test
  void one_session_is_not_another() {
    registry.attach(ID, Role.FILE_PROVIDER, new Attachment("mine"));
    assertTrue(
        registry.find(OTHER).isEmpty(),
        "an id nobody attached to is absent even while another id is live");
    assertEquals(1, registry.count());
  }

  @Test
  void an_id_that_names_nothing_is_refused_where_the_wiring_bug_is() {
    // FileChannelHandler refuses a socket opened with no session parameter
    // because a session nothing can name is one no job can be routed to. The
    // same sentence applies one layer down, and refusing here is what keeps
    // an empty string from becoming a real, shared, attachable session that
    // every mis-wired caller lands in together.
    Attachment attachment = new Attachment("a");
    assertThrows(
        NullPointerException.class, () -> registry.attach(null, Role.LISTENER, attachment));
    assertThrows(
        IllegalArgumentException.class, () -> registry.attach("  ", Role.LISTENER, attachment));
    assertThrows(NullPointerException.class, () -> registry.attach(ID, null, attachment));
    assertThrows(NullPointerException.class, () -> registry.attach(ID, Role.LISTENER, null));
    assertThrows(
        IllegalArgumentException.class, () -> registry.detach("", Role.LISTENER, attachment));
    assertEquals(0, registry.count(), "and none of them left a session behind");

    // find is the lenient one, deliberately: a caller asking about an id it
    // was handed is asking a question, and "no" is the answer for a null the
    // same as for an id nobody opened. Task 3's nullable session id arrives
    // here.
    assertTrue(registry.find(null).isEmpty());
    assertTrue(registry.find("").isEmpty());
  }

  // --- the record an operator reads ----------------------------------------

  @Test
  void a_displacement_is_recorded_where_an_operator_can_see_it() {
    ListAppender<ILoggingEvent> recorded = recording();

    registry.attach(ID, Role.FILE_PROVIDER, new Attachment("first"));
    registry.attach(ID, Role.FILE_PROVIDER, new Attachment("second"));

    List<ILoggingEvent> warnings =
        recorded.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    assertEquals(1, warnings.size(), "one displacement, one record — " + recorded.list);
    String said = warnings.get(0).getFormattedMessage();
    assertTrue(said.contains(ID), "which session — " + said);
    assertTrue(said.contains("FILE_PROVIDER"), "which role — " + said);
  }

  @Test
  void an_ordinary_first_attach_leaves_no_trace_at_all() {
    // The other half of the pair, and the reason the level is warn: a line
    // per attach is noise an operator filters out, and filtering it out
    // filters out the one case it exists for. It is also what makes the
    // assertion above mean something — an appender that recorded nothing
    // would fail that test rather than pass this one.
    ListAppender<ILoggingEvent> recorded = recording();

    registry.attach(ID, Role.LISTENER, new Attachment("only"));
    registry.detach(ID, Role.LISTENER, new Attachment("never attached"));

    assertEquals(
        List.of(), recorded.list.stream().filter(event -> event.getLevel() == Level.WARN).toList());
  }

  /**
   * A logback appender on {@link SessionRegistry}'s own logger, detached when the test method ends.
   */
  private ListAppender<ILoggingEvent> recording() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SessionRegistry.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    appenders.add(() -> logger.detachAppender(appender));
    return appender;
  }

  // --- the shape the concurrency argument rests on -------------------------

  @Test
  void many_threads_attaching_one_role_leave_one_survivor_and_name_the_rest()
      throws InterruptedException {
    // WHY THIS IS THE INVARIANT AND NOT "no exception was thrown": the thing
    // that matters about a displaced socket is that EXACTLY ONE party is
    // told to close it. Told twice and a handler closes a socket somebody
    // else already closed; told never and a Tomcat session stays alive until
    // its peer goes, which FileChannelHandler's own javadoc says can be
    // minutes on a half-open connection — so a client in a reconnect loop
    // accumulates them without bound.
    //
    // Attaches arrive on Spring container threads and reads happen on the
    // virtual threads running jobs, so this is the ordinary case rather than
    // a corner. It holds here with no lock anywhere in the registry, because
    // every step is one ConcurrentHashMap operation that is already atomic.
    int attachers = 32;
    List<Attachment> all = new ArrayList<>();
    for (int i = 0; i < attachers; i++) {
      all.add(new Attachment("attacher-" + i));
    }
    ConcurrentLinkedQueue<Object> displaced = new ConcurrentLinkedQueue<>();
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(attachers);

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (Attachment attachment : all) {
        threads.execute(
            () -> {
              try {
                go.await();
                registry.attach(ID, Role.FILE_PROVIDER, attachment).ifPresent(displaced::add);
              } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      go.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS), "every attacher finished");
    }

    assertEquals(1, registry.count(), "one id, one session, however many attached to it");
    Object survivor =
        registry
            .find(ID)
            .orElseThrow()
            .attached(Role.FILE_PROVIDER, Attachment.class)
            .orElseThrow();

    List<Object> reported = new ArrayList<>(displaced);
    assertFalse(
        reported.contains(survivor), "the one still attached was never reported as displaced");
    assertEquals(
        attachers - 1,
        reported.size(),
        "every attachment but the survivor was reported, and none of them twice");
    assertEquals(
        Set.copyOf(all),
        Set.copyOf(withSurvivor(reported, survivor)),
        "and between them they account for every attachment, so none was lost");
  }

  private static List<Object> withSurvivor(List<Object> reported, Object survivor) {
    List<Object> everything = new ArrayList<>(reported);
    everything.add(survivor);
    return everything;
  }

  /**
   * A clock that moves only when a test moves it.
   *
   * <p>So an instant this file asserts on is one this file set, rather than one a sleep hoped for.
   * The registry takes a {@link Clock} for this reason alone — the same shape, for the same reason,
   * as {@code FileChannelHandler}'s package-private constructor taking its deadline.
   */
  // --- one account per session (Enzo's decision of 2026-09-30) -------------

  /**
   * The first account to claim a session holds it while the registry holds the session: another
   * account's attach is refused on either role, displaces nothing, and is refused still once the
   * holder has detached. The holder's own account displaces, as ever.
   */
  @Test
  void a_session_is_held_by_its_first_account_on_both_roles_after_it_detaches() {
    Attachment mine = new Attachment("mine");
    Attachment again = new Attachment("again");
    assertEquals(
        new SessionRegistry.Attached(false, Optional.empty()),
        registry.attach(ID, Role.FILE_PROVIDER, mine, "enzo"));

    SessionRegistry.Attached theirs =
        registry.attach(ID, Role.FILE_PROVIDER, new Attachment("theirs"), "mallory");
    assertTrue(theirs.refused());
    assertSame(
        mine,
        registry
            .find(ID)
            .orElseThrow()
            .attached(Role.FILE_PROVIDER, Attachment.class)
            .orElseThrow(),
        "and displaced nothing");

    assertTrue(registry.detach(ID, Role.FILE_PROVIDER, mine));
    assertFalse(registry.claim(ID, "mallory"));
    assertTrue(
        registry.attach(ID, Role.LISTENER, new Attachment("listening"), "mallory").refused(),
        "the listener's role too");
    assertTrue(
        registry.attach(ID, Role.FILE_PROVIDER, new Attachment("x"), null).refused(),
        "and nobody's");
    assertEquals(Optional.of("enzo"), registry.accountOf(ID));

    assertEquals(
        new SessionRegistry.Attached(false, Optional.empty()),
        registry.attach(ID, Role.FILE_PROVIDER, again, "enzo"));
    assertEquals(
        Optional.of(again),
        registry.attach(ID, Role.FILE_PROVIDER, mine, "enzo").displaced(),
        "the same account displaces");
  }

  /** Two accounts claiming one id at once: exactly one holds it. */
  @Test
  void of_two_accounts_claiming_at_once_exactly_one_holds_the_session() throws Exception {
    for (int round = 0; round < 200; round++) {
      String id = ID + "-" + round;
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService both = Executors.newFixedThreadPool(2);
      try {
        var enzo =
            both.submit(
                () -> {
                  go.await();
                  return registry.claim(id, "enzo");
                });
        var mallory =
            both.submit(
                () -> {
                  go.await();
                  return registry.claim(id, "mallory");
                });
        go.countDown();
        assertTrue(
            enzo.get(5, TimeUnit.SECONDS) ^ mallory.get(5, TimeUnit.SECONDS), "round " + round);
      } finally {
        both.shutdownNow();
      }
    }
  }

  /** A session nothing named an account for, and an unknown one, are held by no account. */
  @Test
  void an_unknown_or_nobody_s_session_has_no_account_and_nobody_s_attach_cannot_join_one() {
    registry.attach(ID, Role.LISTENER, new Attachment("nobody's"));

    assertEquals(Optional.empty(), registry.accountOf(ID));
    assertEquals(Optional.empty(), registry.accountOf("never-seen"));
    assertTrue(registry.claim(ID, null), "held by nobody, and nobody may come back");
    assertFalse(registry.claim(ID, "enzo"));
    registry.claim("enzo-s", "enzo");
    assertThrows(
        IllegalStateException.class,
        () -> registry.attach("enzo-s", Role.LISTENER, new Attachment("nobody's")));
  }

  private static final class Ticking extends Clock {

    private Instant now = Instant.parse("2026-08-31T09:00:00Z");

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
