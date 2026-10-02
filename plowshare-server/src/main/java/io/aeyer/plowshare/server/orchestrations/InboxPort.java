package io.aeyer.plowshare.server.orchestrations;

/**
 * An account's inbox, behind a seam: where a question or an ending goes when the caller's own
 * conversation cannot hear it. {@code events.Inbox} in production, through {@code
 * OrchestrationsConfig.inboxPort}. Used by {@code Delivery} and {@code StallSweep}.
 */
public interface InboxPort {

    void notify(String handle, String kind, String text);

    /**
     * A notice asking the person something: {@code about} names the question, so {@link #settle}
     * can take the notice out of the inbox once it is settled (V68). Null is news. By default the
     * notice is written without it, for a seam that settles nothing.
     */
    default void notify(String handle, String kind, String text, String about) {
        notify(handle, kind, text);
    }

    default void notifyFromLog(String handle,String kind,String text,String about,String source) { notify(handle,kind,text,about); }

    /** The question {@code about} names is settled; its notices leave the inbox. Nothing by
     *  default. */
    default void settle(String about) {
    }
}
