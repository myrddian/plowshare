package io.aeyer.plowshare.server.llm.counting;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** Context size, never consumed usage. Gaps use fixed codes, never provider text. */
public record PromptCount(@JsonSerialize(using = ToStringSerializer.class) Long tokens,
        Basis basis, String source, String pool, String model, String revision,
        Instant countedAt, long elapsedMillis, boolean cached, List<String> gaps) {
    public enum Basis { MEASURED, ESTIMATED, UNKNOWN }
    public PromptCount {
        gaps = List.copyOf(gaps);
        if (tokens != null && tokens < 0 || elapsedMillis < 0) throw new IllegalArgumentException("invalid context count");
    }
    public static PromptCount unknown(String pool, String model, String gap) {
        return new PromptCount(null, Basis.UNKNOWN, "NONE", pool, model, null, Instant.now(), 0, false, List.of(gap));
    }
    public PromptCount asCached() {
        return new PromptCount(tokens, basis, source, pool, model, revision, countedAt, elapsedMillis, true, gaps);
    }
}
