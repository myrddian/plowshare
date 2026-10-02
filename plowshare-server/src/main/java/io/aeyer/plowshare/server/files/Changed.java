package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileResult;

/**
 * What a change to a file did, as the provider that made it reported it.
 *
 * <p>{@link #facts} from every provider built now: the file side's {@link
 * FileResult}, which {@link FileWords} words. <b>From a client built before
 * facts</b>, there are none, and {@link #oldSentence} carries what that client
 * wrote instead — an edit's view, or nothing — which the caller passes through
 * exactly as it always did, so a person mid-upgrade keeps working.
 *
 * @param facts what the file side reported, or null from an old client
 * @param oldSentence an old client's own words for an ok change, or null
 */
public record Changed(FileResult facts, String oldSentence) {

    /** A change reported as facts. */
    public static Changed of(FileResult facts) {
        return new Changed(facts, null);
    }

    /** A change an old client answered with its own words, or with none. */
    public static Changed fromOldClient(String sentence) {
        return new Changed(null, sentence);
    }

    /** Whether the answer is every line of the file, uncut — a read in all but name. */
    public boolean showedWholeFile() {
        return facts != null && facts.excerpt() != null && facts.excerpt().whole();
    }
}
