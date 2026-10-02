package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.images.StoredImage;

/**
 * What {@code POST /v1/images} answers with: the UID, and enough about the
 * bytes for the uploader to know what was stored.
 *
 * <p>A view and not the record itself, for the reason every other {@code …View}
 * in this package exists: {@code StoredImage} carries a {@link
 * io.aeyer.plowshare.protocol.Home}, which is a two-state type whose JSON would
 * be {@code {"project":null}} for the global tier — a nested object where the
 * request had a flat field. The wire spells the tier the way the request
 * spelled it: absent is global.
 *
 * @param id the UID to name in a run. <b>The only field an agent ever sees</b>,
 *     and it is handed to one by whoever submits the run rather than discovered
 *     by the agent
 * @param project whose it is, or null for the global tier
 * @param format {@code png}, {@code jpeg}, {@code gif} or {@code webp} — what
 *     the bytes are, which is not necessarily what the upload claimed
 * @param filename what the uploader called it, echoed back so a caller
 *     uploading several can tell which id is which
 * @param bytes the size of the file, raw
 * @param at when it was stored. For a re-upload of identical bytes this is when
 *     it was <em>first</em> stored, which is the whole of what content
 *     addressing means here
 */
public record StoredImageView(
        String id, String project, String format, String filename, long bytes, String at) {

    public static StoredImageView of(StoredImage image) {
        return new StoredImageView(
                image.id(),
                image.home().project(),
                image.format().declared(),
                image.filename(),
                image.bytes(),
                image.at().toString());
    }
}
