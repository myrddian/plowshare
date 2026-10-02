package io.aeyer.plowshare.server.hooks;

import java.util.List;
import java.util.Objects;

/**
 * What {@code fold.post} decided (spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29):
 * text kept verbatim after the folder's own summary, notices for the log owner, and the record of
 * every decision. It cannot block a fold or change the folder's words, and fails open.
 *
 * <p><b>A keep names its hook</b> because the kept text is capped after the chain has answered,
 * and a keep the cap drops is recorded against the hook that asked for it.
 */
public record FoldPost(List<Kept> kept, List<Notified.Notice> notices, List<HookRecord> records) {

    public static final FoldPost NOTHING = new FoldPost(List.of(), List.of(), List.of());

    /** One {@code { keep }}, and which hook asked, as its {@link HookRecord} names it. */
    public record Kept(String hook, String file, Tier tier, String text) {
        public Kept {
            Objects.requireNonNull(hook, "hook");
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(text, "text");
        }
    }

    public FoldPost {
        kept = List.copyOf(kept);
        notices = List.copyOf(notices);
        records = List.copyOf(records);
    }
}
