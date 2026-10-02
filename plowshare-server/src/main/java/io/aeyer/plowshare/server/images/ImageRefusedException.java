package io.aeyer.plowshare.server.images;

/**
 * The file is there and this tier may no longer read it.
 *
 * <p>Raised only for a <b>workspace</b> image, and only after liveness has
 * already been established: an exclusion has been added, a root has been
 * unlent, the workspace has moved, or the path now sits below a dot-prefixed
 * component that {@code FileAccess.hiddenBelow} refuses. The id outlived the
 * permission, which is exactly what {@link ImageFence} exists to stop.
 *
 * <p><b>It must not say "gone".</b> The bytes are sitting right there and an
 * operator can put the root back; telling a run the picture had disappeared
 * would send whoever reads the transcript looking for a file that never moved.
 * {@link ImageVanishedException} is the opposite sentence and deliberately
 * shares no supertype with this one.
 *
 * <p><b>And it carries no bytes and no path this tier may not see.</b> The
 * message names the id and says the permission changed; it does not quote the
 * file, because a refusal that repeated the path would answer half of the
 * question the refusal exists to close.
 */
public class ImageRefusedException extends RuntimeException {

    public ImageRefusedException(String message) {
        super(message);
    }
}
