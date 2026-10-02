package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * What an uploaded image should be recorded as — the caller's chosen name, or
 * the filename the bytes arrived as.
 *
 * <h2>Blank is not absent, and the distinction is the whole rule</h2>
 *
 * <p>{@link RequestedDocumentName}'s rule with this feature's own reason: a
 * missing {@code name} is a caller with no opinion and the uploaded filename is
 * the right answer for it, while a blank one is a caller that meant to name this
 * picture and sent the field empty. Falling back would file it under something
 * they did not choose and never say so — and <b>a name is the only human-shaped
 * thing an image record carries</b>, since the id is a hash, so one nobody chose
 * is one nobody can recognise in a directory of {@code img_} files.
 *
 * <h2>Why this is in {@code requests} although no frame reads it</h2>
 *
 * <p>{@link RequestedDocumentName}'s answer, for the second of the two binary
 * uploads: {@code POST /v1/images} is a multipart upload and the breadth plan
 * rules that it gets no frame, so this has exactly one caller today and will
 * keep it. It moved anyway because the rule is a fact about two request fields
 * and nothing else — no store, no domain object, nothing beyond two strings —
 * and leaving it inline would have left {@code ImageController} holding a throw
 * for a reason about transport rather than about the rule.
 */
public final class RequestedImageName {

    private RequestedImageName() {
    }

    /**
     * The name to record an upload under.
     *
     * @param name the request's own {@code name} part, or null for the filename
     * @param filename what the uploaded part was called, which may itself be
     *     null — the container does not promise one, and an image recorded
     *     under nothing is {@code ImageStore}'s question rather than this one's
     * @throws CallerFault if {@code name} is present and blank
     */
    public static String in(String name, String filename) {
        if (name != null && name.isBlank()) {
            throw new CallerFault(
                    "the `name` field is blank. Leave it out to record the uploaded filename;"
                            + " a name nobody chose is one nobody can recognise in a directory"
                            + " of img_ files");
        }
        return name == null ? filename : name;
    }
}
