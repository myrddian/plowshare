package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** Resumed delegates of a seat draw a fresh lease from that seat's root, never a row budget. */
public final class BoardDelegates {
    private final BoardStore store;
    private final Board board;
    private final BoardPot pot;
    private final ConversationStore conversations;
    private final IntSupplier wakeCap;
    private final Consumer<String> drain;
    private final Map<Budget, String> running = new ConcurrentHashMap<>();

    public BoardDelegates(BoardStore store, Board board, BoardPot pot,
            ConversationStore conversations, IntSupplier wakeCap, Consumer<String> drain,
            Predicate<String> speaking) {
        this.store = store;
        this.board = board;
        this.pot = pot;
        this.conversations = conversations;
        this.wakeCap = wakeCap;
        this.drain = drain;
        board.useRunning(topic -> running.containsValue(topic)
                || store.seats(topic).stream().anyMatch(seat -> speaking.test(seat.conversation())));
        board.whenClosed(topic -> running.forEach((budget, owner) -> {
            if (topic.equals(owner)) { budget.changeTo(Math.max(1, budget.spent())); }
        }));
    }

    public Turn.DelegatedAllowance lease(ConversationRecord parent) {
        String id = parent.id();
        Optional<BoardSeat> seat = store.seatByConversation(id);
        while (seat.isEmpty()) {
            ConversationRecord row = conversations.find(id).orElseThrow();
            if (row.origin() != Origin.DELEGATION || row.parentId() == null) {
                throw new Turn.Refused("the delegation's parent has no board allowance");
            }
            id = row.parentId();
            seat = store.seatByConversation(id);
        }
        BoardSeat owner = seat.get();
        BoardTopic topic = store.topic(owner.topic()).orElseThrow();
        requireAvailable(topic, owner);
        Budget lease = pot.lease(topic.root(), owner.isOpener(), Math.max(1, wakeCap.getAsInt()))
                .orElseThrow(() -> new Turn.Refused("the topic has no available allowance;"
                        + " top up its budget or wait for another wake to finish"));
        running.put(lease, topic.id());
        try {
            // A close racing the lease either saw the registered budget or is caught here.
            requireAvailable(store.topic(topic.id()).orElseThrow(), owner);
        } catch (RuntimeException closed) {
            finished(topic.root(), lease);
            throw closed;
        }
        return new Turn.DelegatedAllowance(lease, () -> finished(topic.root(), lease));
    }

    private void requireAvailable(BoardTopic topic, BoardSeat seat) {
        BoardTopic root = store.topic(topic.root()).orElseThrow();
        if (topic.isClosed() || root.isClosed()
                || (!seat.isOpener() && !BoardTopic.OPEN.equals(root.state()))) {
            throw new Turn.Refused("the board topic is closed or its member budget is exhausted");
        }
    }

    private void finished(String root, Budget lease) {
        pot.settle(root, lease);
        running.remove(lease);
        store.queuedWakeTargets(root).forEach(drain);
        board.settled(root);
    }
}
