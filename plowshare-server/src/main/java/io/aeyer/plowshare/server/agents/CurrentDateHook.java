package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.LogOpen;
import io.aeyer.plowshare.server.hooks.LogOpening;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The small temporal fact frozen into every log's opening system block.
 *
 * <p>{@code log.open} is the right seam because its additions are stored once
 * and sent byte-identically on every turn. That gives a model a trustworthy
 * year and local date without putting a changing timestamp in every request
 * and destroying the stable prompt prefix. A long-lived log can cross
 * midnight, so the text says it is an opening-date snapshot and points at
 * {@link GetDateTool} for a live reading.
 */
final class CurrentDateHook implements Hooks {

    private final Supplier<Instant> clock;
    private final ZoneId zone;

    CurrentDateHook(Supplier<Instant> clock, ZoneId zone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public LogOpen logOpen(HookContext context, LogOpening opening) {
        LocalDate date = clock.get().atZone(zone).toLocalDate();
        String text = "Current date: " + date + ". Time zone: " + zone.getId()
                + ". This date was captured when the log opened; use get_date for the live"
                + " date and time if this is a long-running conversation. For facts that may"
                + " have changed, verify them using a live source.";
        return new LogOpen(List.of(text), List.of());
    }
}
