package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a path, or a list of them, off a request — and the refusals a string that never becomes a
 * {@link Path} at all deserves, which no store below this surface can make. Beside them, one
 * factory that is not a path at all: {@link #to}, a project's destination name on a move, kept here
 * because it is the other refusal {@code ProjectController} used to throw directly, alongside the
 * four that read paths.
 *
 * <h2>Four factories, one parse, and the composition among them</h2>
 *
 * <p>{@link #one} is the parse the other three build on. {@link #each} maps it across a list,
 * treating an absent list as empty rather than as a mistake — that is {@link #roots}'s job, which
 * is the one place an empty list is a caller error rather than an empty lend. {@link #workspace}
 * adds the third refusal: a workspace is required, and a blank one is refused before it is ever
 * handed to {@link #one}, because {@code Path.of(" ")} is a perfectly valid relative path and would
 * otherwise be resolved against this process's own working directory instead of being read as a
 * missing field. {@link #to} needs no parse at all — a project's destination name is never turned
 * into a {@link Path} on this door, only checked for presence — so it stands apart from the four
 * above rather than building on {@link #one}.
 *
 * <p>{@link #each} taking {@code List<String>} rather than a request shape was argued for here on
 * the grounds that a future dispatcher reaching this surface off a WebSocket frame would have no
 * {@code LendRequest} to hand in, only the list itself. <b>That future arrived, and the answer
 * turned out to be stronger than the one written down</b>: {@link #roots} does not take a {@code
 * LendRequest} either. It takes the field, and this class no longer names an HTTP body shape at all
 * — see this package's own documentation for why that is the rule here rather than a preference.
 *
 * <h2>{@link CallerFault}, not {@code BadRequestException}</h2>
 *
 * <p>These five moved out of {@code ProjectController}. This class has no request and no status of
 * its own, so it throws {@link CallerFault} instead — the same 400 by the time {@code
 * ApiExceptionHandler} answers it, from code that does not import HTTP to say so.
 */
public final class RequestedPaths {

  private RequestedPaths() {}

  /**
   * The roots a lend or an unlend was given, refused here when there are none.
   *
   * <p>Not a duplicate of {@code ProjectStore}'s own refusal of an empty list. {@code null} is what
   * an omitted key arrives as over the wire and is a shape the store never sees, since {@link
   * #each} turns {@code null} into "none" for the callers where that is what it means. Here "none"
   * is not a thing to mean, so both spellings of it are refused, and the sentence names the field a
   * person left out rather than the empty list the store would have been handed.
   *
   * <p><b>The list arrives as a parameter rather than the request it was read off.</b> This took
   * {@code LendRequest} while it lived in {@code api}, which is a Jackson-bound body shape
   * belonging to one endpoint on one surface — and a parser that cannot be called without one is a
   * parser only that surface can call. The caller unpacks the field, on {@link
   * RequestedBudget#in}'s reasoning and {@link RequestedTurnCap#in}'s shape.
   *
   * @param given the {@code roots} field, {@code null} when it was omitted
   */
  public static List<Path> roots(List<String> given) {
    if (given == null || given.isEmpty()) {
      throw new CallerFault(
          "'roots' is required and must name at least one directory: it is"
              + " the list of directories on this server's disk to lend or to"
              + " take back. Nothing was written.");
    }
    return each(given);
  }

  /** An absent list is an empty one, which {@code ProjectController.define} explains. */
  public static List<Path> each(List<String> given) {
    return given == null ? List.of() : given.stream().map(RequestedPaths::one).toList();
  }

  /**
   * A workspace this endpoint can hand on as a {@link Path}.
   *
   * <p>Two refusals the store cannot make, because both are about a string that never becomes a
   * path at all. Everything a path <em>can</em> be wrong about — absent, not a directory, a
   * dangling symlink — is the store's, and is deliberately not second-guessed here.
   */
  public static Path workspace(String workspace) {
    if (workspace == null || workspace.isBlank()) {
      throw new CallerFault(
          "'workspace' is required: it is the directory on this server's disk that this"
              + " project's jobs may reach. Nothing was written.");
    }
    return one(workspace);
  }

  /**
   * The destination name a move was given, refused here when there is none.
   *
   * <p>Not a path. {@code ProjectController.move} never hands {@code to} to {@link #one} — it is a
   * project name, resolved by {@code ProjectStore} further down, not a location on this server's
   * disk — so this factory is a plain presence check and nothing more.
   *
   * @param to the {@code to} field, {@code null} when it was omitted
   */
  public static String to(String to) {
    if (to == null || to.isBlank()) {
      throw new CallerFault(
          "'to' is required: it is the name the project should have after the move."
              + " Nothing was written.");
    }
    return to;
  }

  /** The path a caller wrote, or a refusal that says why it is not one. */
  public static Path one(String text) {
    try {
      return Path.of(text);
    } catch (InvalidPathException notAPath) {
      // Measured on JDK 21: Path.of("a\0b") raises this, and it is
      // unchecked, so without the catch it leaves this surface as a 500
      // about a caller's typo. FileTools makes the same translation for
      // the same reason at the other end of the system.
      throw new CallerFault(
          "'"
              + text
              + "' cannot be read as a path: "
              + notAPath.getReason()
              + ". Nothing was written.",
          notAPath);
    }
  }
}
