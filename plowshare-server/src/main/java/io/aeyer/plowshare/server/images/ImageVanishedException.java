package io.aeyer.plowshare.server.images;

/**
 * The id was real and the picture is not there any more.
 *
 * <p>Raised only for a <b>workspace</b> image — one this server named out of a project's own file
 * and never copied. The file has been deleted, or moved, or the bytes at that path are no longer
 * the bytes the id names.
 *
 * <p><b>It must not say "refused", and that is the whole reason it is a type of its own.</b> {@code
 * ClientEnforcer.Vanished} draws the identical line one module over and states why: a session whose
 * workspace has been deleted is not a session that should be told a path is "outside every root".
 * The same holds here from the other end — a run told its id was refused goes looking for a
 * permission to fix, and there is nothing to fix; the picture is gone.
 *
 * <p><b>No supertype shared with {@link ImageRefusedException}</b>, on {@code ClientEnforcer}'s
 * argument: the two mean opposite things to whoever reads them, and a shared parent is a catch
 * clause waiting to collapse them into one sentence that is right about neither.
 *
 * <p>An image that vanishes is <em>not</em> an error in the log. A transcript entry saying an agent
 * answered about {@code img_…} stays truthful when the file has moved — the property {@code
 * StoredResults.Result} carries with {@code ejectedAt}. This exception is about a resolution
 * happening now, and nothing else.
 */
public class ImageVanishedException extends RuntimeException {

  public ImageVanishedException(String message) {
    super(message);
  }
}
