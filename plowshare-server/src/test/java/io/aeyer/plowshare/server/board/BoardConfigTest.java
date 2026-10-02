package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.swarm.SwarmProperties;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The bound values the board is built with, refused at boot when they cannot mean anything — as
 * {@code SwarmConfigTest} does for the scheduler's. Each refusal comes before any collaborator is
 * touched, so none is needed to see it. And {@link BoardConfig#shareOf}, which decides which runs
 * the swarm scheduler rations.
 */
class BoardConfigTest {

    @Test
    void a_wake_cap_below_one_is_refused_at_boot() {
        SwarmProperties properties = new SwarmProperties();
        properties.setWakeCap(0);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new BoardConfig().seatRunner(null, null, null, null, null, null,
                        properties, null));
        assertEquals("plowshare.swarm.wake-cap is 0; a wake cap is a number of steps, at least 1",
                refused.getMessage());
    }

    @Test
    void a_closing_reserve_outside_zero_to_ninety_nine_is_refused_at_boot() {
        for (int bad : new int[] {-1, 100}) {
            SwarmProperties properties = new SwarmProperties();
            properties.setClosingReserve(bad);
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new BoardConfig().board(null, null, null, null, null, null, null,
                            properties, null), String.valueOf(bad));
            assertEquals("plowshare.swarm.closing-reserve is " + bad
                    + "; a closing reserve is a percent of the pot, 0 to 99", refused.getMessage());
        }
    }

    /**
     * Two seats on one topic, answered from memory: shareOf reads nothing but {@link
     * BoardStore#seatByConversation} and {@link BoardStore#topic}, so this needs no database.
     */
    private static final class TwoSeats extends BoardStore {

        private final BoardTopic topic = new BoardTopic("bdt_1", "payments", null, "bdt_1", 0,
                "sync", "L", "enzo", BoardTopic.BY_BOT, "aristoxenus", "con_chat", BoardTopic.OPEN,
                null, 20, 0, 2, null, Instant.EPOCH, null);
        private final Map<String, BoardSeat> seats = Map.of(
                "con_opener", new BoardSeat("bdt_1", BoardSeat.OPENER, "con_opener", false, null,
                        0, null, 0),
                "con_researcher", new BoardSeat("bdt_1", "researcher", "con_researcher", false,
                        null, 0, null, 0));

        TwoSeats() {
            super(new JdbcTemplate(), Instant::now);
        }

        @Override
        public Optional<BoardSeat> seatByConversation(String conversation) {
            return Optional.ofNullable(seats.get(conversation));
        }

        @Override
        public Optional<BoardTopic> topic(String id) {
            return topic.id().equals(id) ? Optional.of(topic) : Optional.empty();
        }
    }

    private static RunExtras.Context in(String conversation) {
        return new RunExtras.Context(null, conversation, null, null);
    }

    /**
     * The final review's I-3: the swarm's slots ration members, and only members. An opener seat
     * is the person's own bot, whose model swarm.md never checked against the swarm pools — so
     * scheduling it could leave its run waiting forever for a slot no pool offers. It runs
     * unscheduled, as the bot's own turns do.
     */
    @Test
    void only_a_member_seats_run_is_scheduled_and_as_its_topics_share() {
        Function<RunExtras.Context, Optional<SwarmScheduler.Share>> shareOf =
                BoardConfig.shareOf(new TwoSeats());
        assertEquals(Optional.of(new SwarmScheduler.Share("enzo", "bdt_1", "researcher")),
                shareOf.apply(in("con_researcher")));
        assertEquals(Optional.empty(), shareOf.apply(in("con_opener")),
                "an opener seat's run was scheduled as a swarm member's");
        assertEquals(Optional.empty(), shareOf.apply(in("con_chat")));
        assertEquals(Optional.empty(), shareOf.apply(in(null)));
    }

    @Test
    void recovery_defaults_to_on_and_can_be_disabled() {
        SwarmProperties properties = new SwarmProperties();
        org.junit.jupiter.api.Assertions.assertTrue(properties.isRecoverAtBoot());
        properties.setRecoverAtBoot(false);
        org.junit.jupiter.api.Assertions.assertFalse(properties.isRecoverAtBoot());
    }
    @Test
    void a_negative_maximum_depth_is_refused_before_wiring_the_board() {
        SwarmProperties properties = new SwarmProperties();
        properties.setMaxDepth(-1);
        assertThrows(IllegalStateException.class, () -> new BoardConfig().board(null, null,
                null, null, null, null, null, properties, null));
    }

}
