package io.aeyer.plowshare.server.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.FrameJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Where every event arrives, whoever raised it: the ticker, event.fire, a future adapter. */
public class Intake {

    private static final Logger log = LoggerFactory.getLogger(Intake.class);

    private final TriggerStore triggers;
    private final FiringStore firings;
    private final Dispatcher dispatcher;
    private final Supplier<Instant> clock;

    public Intake(TriggerStore triggers, FiringStore firings, Dispatcher dispatcher,
            Supplier<Instant> clock) {
        this.triggers = Objects.requireNonNull(triggers, "triggers");
        this.firings = Objects.requireNonNull(firings, "firings");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<FiringRecord> emit(String event, Map<String, Object> data) {
        return emit(event, data, null, null);
    }

    public List<FiringRecord> emit(String event, Map<String, Object> data, String schedule,
            Instant fireAt) {
        if (event == null || event.isBlank()) {
            throw new CallerFault("an event needs a name; triggers listen for it by that name");
        }
        String json = json(data == null ? Map.of() : data);
        Instant at = clock.get();
        List<TriggerRecord> listening = triggers.listening(event);
        List<FiringRecord> created = new ArrayList<>();
        if (listening.isEmpty()) {
            firings.arrive(event, json, schedule, fireAt, null, at).ifPresent(created::add);
            return created;
        }
        // Every firing is recorded before any is dispatched, and each dispatch is its own try: a
        // tick is claimed once, so a trigger whose dispatch throws must not cost the triggers
        // after it their firing. What was recorded and not started is drained later like any
        // waiting firing.
        List<TriggerRecord> arrivedFor = new ArrayList<>();
        for (TriggerRecord trigger : listening) {
            firings.arrive(event, json, schedule, fireAt, trigger, at).ifPresent(firing -> {
                created.add(firing);
                arrivedFor.add(trigger);
            });
        }
        for (int i = 0; i < created.size(); i++) {
            FiringRecord firing = created.get(i);
            TriggerRecord trigger = arrivedFor.get(i);
            try {
                dispatcher.dispatch(firing, trigger);
            } catch (RuntimeException undispatched) {
                log.warn("firing {} of trigger {} was recorded but could not be dispatched",
                        firing.id(), trigger.name(), undispatched);
            }
        }
        return created;
    }

    private static String json(Map<String, Object> data) {
        try {
            return FrameJson.answering().writeValueAsString(data);
        } catch (JsonProcessingException unwritable) {
            throw new CallerFault("that event's data could not be written as JSON: "
                    + unwritable.getOriginalMessage());
        }
    }
}
