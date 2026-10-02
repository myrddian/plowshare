package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A closed topic's resolution, delivered to whoever opened it, exactly once — spec 2026-09-29
 * §7. As an orchestration's result reaches its caller: a turn in the person's conversation the
 * bot opened it from when that conversation is idle, left for its free-drain when busy, and the
 * opener's inbox (kind {@value #KIND}) for a person-opened topic or a conversation that is gone.
 * {@code resolution_delivered_at} is written only after a delivery succeeded.
 */
public final class BoardDelivery {

    private static final Logger log = LoggerFactory.getLogger(BoardDelivery.class);

    public static final String KIND = "board";

    private final BoardStore store;
    private final PersonDelivery people;
    private final Predicate<String> speaking;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public BoardDelivery(BoardStore store, PersonDelivery people, Predicate<String> speaking) {
        this.store = Objects.requireNonNull(store, "store");
        this.people = Objects.requireNonNull(people, "people");
        this.speaking = Objects.requireNonNull(speaking, "speaking");
    }

    /** A topic just closed. */
    public void resolved(String topic) {
        deliver(topic, true);
    }

    /** A conversation came free: whatever is owed to it. */
    public void drainFor(String conversation) {
        store.undeliveredResolutionsFor(conversation).forEach(topic -> deliver(topic.id(), true));
    }

    /** Everything owed, at boot. */
    public void drainAll() {
        store.undeliveredResolutions().forEach(topic -> deliver(topic.id(), true));
        drainNotices();
    }

    /** Person-opened quiet/exhausted notices are durable inbox deliveries. */
    public void drainNotices() {
        for (BoardMessage message : store.owedNotices()) {
            if (!inFlight.add(message.id())) { continue; }
            try {
                // Recheck after taking the local guard, as another drain may have delivered it.
                if (store.owedNotices().stream().noneMatch(m -> m.id().equals(message.id()))) { continue; }
                BoardTopic topic = store.topic(message.topic()).orElseThrow();
                people.send(null, null, topic.account(), KIND,
                        BoardText.messages(topic, java.util.List.of(message)),
                        Speaker.board(topic.id()), null);
                store.noticeDelivered(message.id());
            } catch (RuntimeException failed) {
                log.warn("board notice {} stays owed", message.id(), failed);
            } finally { inFlight.remove(message.id()); }
        }
    }

    private void deliver(String topicId, boolean retryOnceFree) {
        if (!inFlight.add(topicId)) {
            return;
        }
        String busyConversation = null;
        try {
            if (!store.resolutionUndelivered(topicId)) {
                return;
            }
            BoardTopic topic = store.topic(topicId).orElseThrow();
            BoardMessage resolution = store.message(topic.resolution()).orElseThrow();
            boolean byConversation = topic.originConversation() != null;
            PersonDelivery.Sent sent = people.send(byConversation ? topic.originConversation() : null,
                    byConversation ? topic.opener() : null, topic.account(), KIND,
                    BoardText.resolution(topic, resolution), Speaker.board(topic.id()), null);
            switch (sent.result()) {
                case BUSY -> busyConversation = topic.originConversation();
                case DELIVERED -> store.resolutionDelivered(topicId);
                case NOWHERE -> {
                    log.warn("the resolution of topic {} had nowhere to go; marked delivered",
                            topicId);
                    store.resolutionDelivered(topicId);
                }
            }
        } catch (RuntimeException undelivered) {
            log.warn("the resolution of topic {} could not be delivered; it stays owed",
                    topicId, undelivered);
        } finally {
            inFlight.remove(topicId);
            // The conversation may have freed while the guard was held, and its drain found the
            // topic in flight: look once more rather than wait for a drain that already ran.
            if (retryOnceFree && busyConversation != null && !speaking.test(busyConversation)) {
                deliver(topicId, false);
            }
        }
    }
}
