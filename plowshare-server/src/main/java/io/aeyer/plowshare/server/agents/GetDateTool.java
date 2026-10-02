package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** A live clock for agents whose fixed {@code log.open} date may have gone stale. */
public final class GetDateTool implements AgentTool {

    public static final String NAME = "get_date";

    static final String DESCRIPTION = """
            Get the current date and time. Omit time_zone to use the server's configured system \
            time zone, or pass an IANA zone such as Australia/Melbourne or America/New_York. \
            Use this when relative dates, deadlines, schedules, or a long-running conversation \
            make the date captured at log open insufficient. This reports the clock; it does not \
            make time-sensitive world knowledge current, so verify changing facts with a live source.""";

    private final Supplier<Instant> clock;
    private final ZoneId defaultZone;
    private final ToolSchema schema;

    public GetDateTool(Supplier<Instant> clock, ZoneId defaultZone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultZone = Objects.requireNonNull(defaultZone, "defaultZone");
        this.schema = new ToolSchema(NAME, DESCRIPTION, schemaMap());
    }

    @Override
    public ToolSchema schema() {
        return schema;
    }

    @Override
    public String run(String argumentsJson, Home home) {
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        Objects.requireNonNull(home, "home");
        try {
            JsonNode args = ToolArguments.parse(argumentsJson, NAME,
                    "{\"time_zone\": \"Australia/Melbourne\"}");
            String asked = ToolArguments.optionalText(args, "time_zone", GetDateTool::badZoneType);
            ZoneId zone = asked == null ? defaultZone : readZone(asked);
            Instant now = clock.get();
            return "Current date and time:\n"
                    + "- Time zone: " + zone.getId() + "\n"
                    + "- Local: " + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))
                    + "\n- UTC: " + DateTimeFormatter.ISO_INSTANT.format(now)
                    + "\n- Unix epoch seconds: " + now.getEpochSecond();
        } catch (BadArguments unusable) {
            return unusable.getMessage();
        }
    }

    private static ZoneId readZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (DateTimeException invalid) {
            throw new BadArguments(NAME + " could not read 'time_zone': '" + value
                    + "' is not a recognised IANA time zone. Use a name such as"
                    + " Australia/Melbourne, America/New_York, or UTC.");
        }
    }

    private static BadArguments badZoneType(JsonNode value) {
        return new BadArguments(NAME + " could not read 'time_zone': it must be an IANA time"
                + " zone name as a string, not " + value + ". Omit it to use the server's"
                + " system time zone.");
    }

    private static Map<String, Object> schemaMap() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("time_zone", ToolArguments.string(
                "Optional IANA time zone, such as Australia/Melbourne. Omit it to use the"
                        + " server's system time zone."));
        return ToolArguments.object(properties, List.of());
    }
}
