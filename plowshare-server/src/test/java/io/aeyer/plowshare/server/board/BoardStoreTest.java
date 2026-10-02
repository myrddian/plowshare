package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.events.FiringStore;
import java.time.Instant;
import java.util.List;
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

/** Every statement {@link BoardStore} makes, against a real V74 schema. */
@Testcontainers
class BoardStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private static final Instant T0 = Instant.parse("2026-09-30T09:00:00Z");
    // A strictly advancing clock and not a fixed `now`, as DispatcherTest's is: MemoryIds.mint
    // breaks ties within the same millisecond with three random bytes, so several rows minted at
    // one fixed instant would sort by that randomness rather than by arrival order. One
    // millisecond per read keeps every minted id's embedded timestamp distinct, so ids sort by
    // arrival the way messages_come_back_in_the_order_they_were_said_and_unread_counts_after_a_mark
    // below depends on.
    private final AtomicLong ticks = new AtomicLong();
    private final Supplier<Instant> clock = () -> T0.plusMillis(ticks.getAndIncrement());

    private ConversationStore conversations;
    private BoardStore store;
    private FiringStore firings;

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
        conversations = new ConversationStore(jdbc);
        store = new BoardStore(jdbc, clock);
        firings = new FiringStore(jdbc);
    }

    private BoardTopic root() {
        return store.openRoot(new BoardStore.NewTopic("payments", "sync between devices",
                "BAD SPEC / NEED INFO", "enzo", BoardTopic.BY_PERSON, "enzo", null, 20, 2));
    }

    private BoardMessage say(BoardTopic topic, String author, String replyTo, String... mentions) {
        return store.post(new BoardStore.NewMessage(topic.id(), replyTo, BoardMessage.BY_MEMBER,
                author, null, null, BoardMessage.POST, null, "said by " + author, false,
                List.of(mentions)));
    }

    private String seatConversation(String agent) {
        return conversations.log(Origin.BOARD, Home.global(), agent, null, null, "enzo").id();
    }

    @Test
    void a_root_is_its_own_root_at_depth_zero_and_holds_the_pot() {
        BoardTopic topic = root();
        assertTrue(topic.id().startsWith("bdt_"));
        assertEquals(topic.id(), topic.root());
        assertEquals(0, topic.depth());
        assertTrue(topic.isRoot());
        assertEquals(BoardTopic.OPEN, topic.state());
        assertEquals(20, topic.potTotal());
        assertEquals(0, topic.potSpent());
        assertEquals(2, topic.reserve());
    }

    @Test
    void a_message_reads_back_whole_with_its_mentions() {
        BoardTopic topic = root();
        BoardMessage said = say(topic, "researcher", null, "critic", "spec_writer");
        BoardMessage read = store.message(said.id()).orElseThrow();
        assertTrue(read.id().startsWith("bdm_"));
        assertEquals("said by researcher", read.body());
        assertEquals(List.of("critic", "spec_writer"), read.mentions());
        assertEquals("researcher", read.authorOccupant());
    }

    @Test
    void a_reply_stays_on_its_own_topic() {
        BoardTopic one = root();
        BoardTopic two = root();
        BoardMessage there = say(two, "critic", null);
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> say(one, "researcher", there.id()));
        assertTrue(refused.getMessage().contains("stays on its own topic"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> say(one, "researcher", "bdm_nothing"));
    }

    @Test
    void messages_come_back_in_the_order_they_were_said_and_unread_counts_after_a_mark() {
        BoardTopic topic = root();
        BoardMessage first = say(topic, "a", null);
        BoardMessage second = say(topic, "b", null);
        say(topic, "c", null);
        assertEquals(List.of(first.id(), second.id()),
                store.messages(topic.id()).stream().limit(2).map(BoardMessage::id).toList());
        assertEquals(3, store.countAfter(topic.id(), null));
        assertEquals(1, store.countAfter(topic.id(), second.id()));
    }

    @Test
    void a_seat_is_taken_once_and_found_by_its_conversation() {
        BoardTopic topic = root();
        String conversation = seatConversation("researcher");
        assertTrue(store.seatIfAbsent(topic.id(), "researcher", conversation));
        assertFalse(store.seatIfAbsent(topic.id(), "researcher", seatConversation("researcher")));
        BoardSeat seat = store.seatByConversation(conversation).orElseThrow();
        assertEquals("researcher", seat.occupant());
        assertFalse(seat.isOpener());
        assertEquals(List.of(seat), store.seats(topic.id()));
    }

    @Test
    void a_seat_sends_one_alert_and_no_second() {
        BoardTopic topic = root();
        store.seatIfAbsent(topic.id(), "critic", seatConversation("critic"));
        assertTrue(store.useAlert(topic.id(), "critic"));
        assertFalse(store.useAlert(topic.id(), "critic"));
    }

    @Test
    void failures_and_silences_are_counted_on_the_seat() {
        BoardTopic topic = root();
        store.seatIfAbsent(topic.id(), "critic", seatConversation("critic"));
        store.recordSilent(topic.id(), "critic");
        store.recordFailure(topic.id(), "critic", "STUCK");
        BoardSeat seat = store.seat(topic.id(), "critic").orElseThrow();
        assertEquals(1, seat.silentWakes());
        assertEquals("STUCK", seat.failedEnding());
        store.clearFailure(topic.id(), "critic");
        assertNull(store.seat(topic.id(), "critic").orElseThrow().failedEnding());
    }

    @Test
    void the_pot_only_grows_its_spend_and_exhausts_once() {
        BoardTopic topic = root();
        store.spend(topic.id(), 3);
        store.spend(topic.id(), 0);
        store.spend(topic.id(), 2);
        assertEquals(5, store.topic(topic.id()).orElseThrow().potSpent());
        assertTrue(store.exhaust(topic.id()));
        assertFalse(store.exhaust(topic.id()));
        assertEquals(BoardTopic.EXHAUSTED, store.topic(topic.id()).orElseThrow().state());
    }

    @Test
    void messages_since_a_moment_count_only_what_a_conversation_wrote() {
        BoardTopic topic = root();
        String conversation = seatConversation("researcher");
        Instant before = clock.get();
        store.post(new BoardStore.NewMessage(topic.id(), null, BoardMessage.BY_MEMBER,
                "researcher", conversation, 7, BoardMessage.POST, null, "found it", false,
                List.of()));
        say(topic, "critic", null);
        assertEquals(1, store.messagesSince(topic.id(), conversation, before));
    }

    @Test
    void messages_sort_by_insertion_even_when_ids_mint_at_the_same_instant() {
        // A clock that never advances: every id minted in this test embeds the same millisecond,
        // so MemoryIds' random tie-break, not arrival, would decide `id` order — the exact
        // scenario `seq` exists to be immune to (V74__the_project_board.sql).
        Supplier<Instant> frozen = () -> T0;
        BoardStore frozenStore = new BoardStore(jdbc, frozen);
        BoardTopic topic = root();
        BoardMessage first = frozenStore.post(new BoardStore.NewMessage(topic.id(), null,
                BoardMessage.BY_MEMBER, "a", null, null, BoardMessage.POST, null, "one", false,
                List.of()));
        BoardMessage second = frozenStore.post(new BoardStore.NewMessage(topic.id(), null,
                BoardMessage.BY_MEMBER, "b", null, null, BoardMessage.POST, null, "two", false,
                List.of()));
        BoardMessage third = frozenStore.post(new BoardStore.NewMessage(topic.id(), null,
                BoardMessage.BY_MEMBER, "c", null, null, BoardMessage.POST, null, "three", false,
                List.of()));
        assertEquals(List.of(first.id(), second.id(), third.id()),
                frozenStore.messages(topic.id()).stream().map(BoardMessage::id).toList());
        assertEquals(2, frozenStore.countAfter(topic.id(), first.id()));
    }

    @Test
    void a_topic_closes_once_naming_its_resolution() {
        BoardTopic topic = root();
        BoardMessage resolution = say(topic, "researcher", null);
        assertTrue(store.close(topic.id(), resolution.id()));
        assertFalse(store.close(topic.id(), resolution.id()));
        BoardTopic closed = store.topic(topic.id()).orElseThrow();
        assertEquals(BoardTopic.CLOSED, closed.state());
        assertEquals(resolution.id(), closed.resolution());
        assertNotNull(closed.closedAt());
    }

    @Test
    void a_resolution_is_undelivered_until_marked_and_marked_once() {
        BoardTopic topic = root();
        store.close(topic.id(), say(topic, "researcher", null).id());
        assertTrue(store.resolutionUndelivered(topic.id()));
        assertEquals(List.of(topic.id()),
                store.undeliveredResolutions().stream().map(BoardTopic::id).toList());
        assertTrue(store.resolutionDelivered(topic.id()));
        assertFalse(store.resolutionDelivered(topic.id()));
        assertFalse(store.resolutionUndelivered(topic.id()));
        assertTrue(store.undeliveredResolutions().isEmpty());
    }

    @Test
    void undelivered_resolutions_are_found_by_the_conversation_they_go_to() {
        String chat = conversations.open(Home.of("payments"), Budget.of(5)).id();
        BoardTopic topic = store.openRoot(new BoardStore.NewTopic("payments", "sync", "BAD SPEC",
                "enzo", BoardTopic.BY_BOT, "aristoxenus", chat, 20, 2));
        store.close(topic.id(), say(topic, "researcher", null).id());
        assertEquals(1, store.undeliveredResolutionsFor(chat).size());
        assertTrue(store.undeliveredResolutionsFor("cnv_other").isEmpty());
    }

    @Test
    void messages_after_a_watermark_and_the_watermark_only_moves_forward() {
        BoardTopic topic = root();
        store.seatIfAbsent(topic.id(), "critic", seatConversation("critic"));
        BoardMessage first = say(topic, "a", null);
        BoardMessage second = say(topic, "b", null);
        BoardMessage third = say(topic, "c", null);
        assertEquals(List.of(first.id(), second.id(), third.id()),
                store.messagesAfter(topic.id(), null).stream().map(BoardMessage::id).toList());
        store.advanceSeen(topic.id(), "critic", second.id());
        assertEquals(second.id(), store.seat(topic.id(), "critic").orElseThrow().seenThrough());
        store.advanceSeen(topic.id(), "critic", first.id());
        assertEquals(second.id(), store.seat(topic.id(), "critic").orElseThrow().seenThrough(),
                "the watermark moved backwards");
        assertEquals(List.of(third.id()), store.messagesAfter(topic.id(), second.id()).stream()
                .map(BoardMessage::id).toList());
    }

    @Test
    void a_seat_passes_and_open_topics_leave_out_closed_ones() {
        BoardTopic open = root();
        BoardTopic closing = root();
        store.seatIfAbsent(open.id(), "critic", seatConversation("critic"));
        store.pass(open.id(), "critic");
        assertTrue(store.seat(open.id(), "critic").orElseThrow().passed());
        store.close(closing.id(), say(closing, "a", null).id());
        assertEquals(List.of(open.id()), store.openTopics().stream().map(BoardTopic::id).toList());
    }

    @Test
    void closing_refuses_a_topics_queued_wakes_and_nothing_else() {
        BoardTopic topic = root();
        BoardTopic other = root();
        String one = firings.owe(topic.id(), "conversation:c1", "{}", clock.get()).orElseThrow().id();
        String two = firings.owe(other.id(), "conversation:c2", "{}", clock.get()).orElseThrow().id();
        assertEquals(1, firings.refuseWakes(topic.id(), "topic closed"));
        assertEquals("refused", firings.find(one).orElseThrow().status());
        assertEquals("topic closed", firings.find(one).orElseThrow().reason());
        assertEquals("queued", firings.find(two).orElseThrow().status());
    }
}
