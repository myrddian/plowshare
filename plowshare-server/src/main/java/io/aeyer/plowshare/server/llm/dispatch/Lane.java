package io.aeyer.plowshare.server.llm.dispatch;

import java.util.Locale;

/**
 * Which of a pool's two queues a request waits in.
 *
 * <p>A lane is an inference resource, not a role. Anchor's pools segment the
 * same way — {@code chat=1, embedding=2} against one LM Studio — because a box
 * that generates one token stream at a time will happily embed two batches
 * while doing it, and a single shared limit would have to be the smaller of the
 * two.
 */
public enum Lane {
    CHAT,
    EMBEDDING;

    /** Lower case, for messages and for the property name a reader has to find. */
    public String wireName() {
        // Locale.ROOT rather than the default: under a Turkish locale
        // "CHAT".toLowerCase() is "chat" but "EMBEDDING" keeps its dotless i
        // problem the moment a lane is spelled with an I, and a saturation
        // message would then name a property that does not exist.
        return name().toLowerCase(Locale.ROOT);
    }
}
