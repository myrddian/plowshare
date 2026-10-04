package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileAccess;

/**
 * A provider could not be asked, so nothing can be concluded from the answer.
 *
 * <p>The workspace a {@code projects} row names is no longer on the disk; the client that owned the
 * files has gone; the tier a job runs in can never have a workspace at all. <b>Nothing the model
 * does next will help</b>, so this must end the run rather than become a tool result it will spend
 * its turns on.
 *
 * <p><b>The ending is wired.</b> {@code JobRuntime.dependencyFailure} names this type alongside
 * {@code EmbeddingException}, {@code LlmException} and {@code ArchiveUnavailableException}, so a
 * tool that raises it ends the run as {@code UNAVAILABLE} rather than handing the model something
 * to answer around. {@code a_workspace_that_cannot_be_reached_ends_the_run} holds it, and {@code
 * a_path_the_agent_had_no_business_naming_is_a_tool_result_and_not_an_ending} is the other half of
 * the pair: a clause widened to "anything out of {@code files/}" passes the first and fails the
 * second.
 *
 * <p>(<b>"The ending is wired", immediately above, said the opposite for three commits</b>, and the
 * correction is left visible rather than tidied away: the type existed and the list did not name
 * it, so the design was right and its effect was pending. A comment claiming an effect nothing
 * produces is the same fault as a test asserting one. <b>The paragraph it is about is named because
 * a later commit inserted another one between the two</b>, and the wrong referent read perfectly
 * well — it made a deliberate choice look like an error since corrected, which is the worse kind of
 * drift because nothing about it looks wrong.)
 *
 * <p>The split from {@link WorkspaceRefusedException} is what made that line expressible at all — a
 * single merged type could not have been added to the list without dragging every mistyped path
 * onto the outage side with it.
 *
 * <p><b>The further division has landed.</b> {@link SessionGoneException} extends this type for a
 * <em>client</em> that went away rather than a disk that did, and {@code JobRuntime} picks it out
 * with an {@code instanceof} one clause above the list named here. The list is unchanged and
 * deliberately does not name the subtype: this stays the fallback, so a caller that never learned
 * about the narrowing still ends the run.
 *
 * <h2>Why an empty result is not an option here</h2>
 *
 * <p>A root that has vanished, reported as "no roots", turns a {@code file_glob} into a confident
 * {@code (no matches)} — which reads as <em>there are no Java files</em> and is the
 * confident-empty-answer failure this project exists to avoid. Excalibur's rule from {@code
 * FileAccess.resolve}: name the state rather than let it pass as a bare {@code (no matches)}. The
 * state is named here because {@link FileAccess} cannot name it — its roots are resolved when it is
 * built, so it compares a directory that has since disappeared exactly as it compared it before,
 * and its own javadoc says a root that vanishes is a provider's problem to report.
 *
 * <h2>Not {@code ArchiveUnavailableException}, and not a relative of {@link
 * WorkspaceRefusedException}</h2>
 *
 * <p>{@code ArchiveUnavailableException.translating} is package-private to {@code archive} and
 * translates a {@code DataAccessException}; a disk is neither. That type is still the right one
 * when {@code files/} genuinely means <em>the database is gone</em> — {@link LocalProvider} reads
 * the {@code projects} table and lets {@code ProjectStore}'s own translation raise it untouched,
 * because "the archive could not be reached" and "a workspace could not be reached" are two
 * different sentences an operator acts on differently. This one is the disk's.
 *
 * <p>The split from {@link WorkspaceRefusedException} is argued there. Both extend {@code
 * RuntimeException} directly and share no supertype, <b>which is what lets {@code
 * dependencyFailure} name one without the other</b> — no {@code catch} of a {@code files/} type can
 * take them together.
 *
 * <p><b>That is not the same as saying no catch takes them together, and an earlier version of this
 * sentence said it.</b> The {@code catch (RuntimeException failed)} inside {@code JobRuntime.run}'s
 * tool-dispatch loop is <em>the</em> production caller on the tool path: both types land in it, as
 * every runtime failure does. What separates them again is the {@code instanceof} list in {@code
 * JobRuntime.dependencyFailure} — a list a shared supertype would have made unable to distinguish
 * them. The absence of the supertype is what keeps that list expressible; it is not a guarantee
 * about anyone's {@code catch}.
 *
 * <p><b>Both of those were line numbers until now, and both had already rotted — shifted by the
 * very commit that added this type to that list, which existed to correct stale claims about {@code
 * JobRuntime}.</b> A line number is a claim about another file that goes stale in silence, with
 * nothing in either file holding it, which is this project's trap 9 in the one form no test can
 * catch. A method name is the citation that cannot rot: rename it and the compiler says so at every
 * call site, while {@code :544-548} stays quietly wrong.
 */
public class WorkspaceUnavailableException extends RuntimeException {

  public WorkspaceUnavailableException(String message) {
    super(message);
  }

  public WorkspaceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
