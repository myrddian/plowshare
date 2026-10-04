package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceConflictException;
import io.aeyer.plowshare.server.session.PresenceRegistry;

/**
 * The one domain rule a project's move is refused under: not while a live session roots it, at
 * either end.
 *
 * <h2>Why this left {@code ProjectController} and not the rest of it</h2>
 *
 * <p>Every other private helper {@code ProjectController} held before this moved was a request's
 * own field, parsed and refused — {@code RequestedPaths}'s shape. {@link #refuseIfRooted} is the
 * other shape {@code Conversations}' own javadoc names: it reads a store — here, {@link
 * PresenceRegistry#serving} — and <em>decides</em> something about what it read, refusing with a
 * reason that argues from the data rather than from the request. That is what lets a dispatcher
 * which is not {@code ProjectController} reach the same rule without going through HTTP to get it.
 *
 * <h2>{@link PresenceConflictException} and not {@link
 * io.aeyer.plowshare.server.faults.CallerFault}</h2>
 *
 * <p>Unlike {@code RequestedPaths}, this is not a caller mistake to answer 400 for. What it means
 * is "a live presence is in the way of this claim on a project, and here is which one" — {@code
 * ApiExceptionHandler} already maps {@link PresenceConflictException} to 409 for exactly this
 * sentence, thrown from {@link PresenceRegistry#declare} for the other claimant. A second type
 * saying the same thing would be the duplication that goes stale on one side.
 *
 * <h2>A plain constructor argument, not a component this class looks up</h2>
 *
 * <p>{@link PresenceRegistry} is itself the whole of the state this rule reads, so this class is a
 * thin, stateless wrapper around one — constructed wherever the rule is needed, by {@code
 * ProjectController} today and by a future dispatcher tomorrow, from the same registry either
 * holds. Nothing here requires the two to share one instance of this class, only one instance of
 * the registry, which {@code FileChannelConfig.presenceRegistry} already wires as a singleton.
 *
 * <h2>A new edge: {@code archive} importing {@code session}</h2>
 *
 * <p>This is the one file in {@code archive/} that imports {@code session/} — {@link Presence},
 * {@link PresenceConflictException} and {@link PresenceRegistry} above. {@code session/} imports
 * nothing back from {@code archive/}, so there is no cycle, but the edge itself is new and is
 * produced by the same "subject, not collaborators" rule this class's own javadoc argues from:
 * {@link #refuseIfRooted} reads a live session's claim on a project, and a project is this
 * package's subject, so the rule moved here rather than staying beside {@link PresenceRegistry} or
 * {@code ProjectController}. Accepted rather than re-argued around, because the alternative —
 * filing this beside {@code session/} instead — would invert which package is subject and which is
 * collaborator for no gain: nothing in {@code session/} needs this rule, and a project's own rename
 * staying a fact {@code archive/} can state about itself is worth one new import.
 */
public final class Projects {

  private final PresenceRegistry presences;

  public Projects(PresenceRegistry presences) {
    this.presences = presences;
  }

  /**
   * {@code project} is not being addressed by a live session right now, or the refusal naming the
   * machine that is.
   *
   * <p>{@code ProjectController.move} calls this at both ends of a rename: a presence declares
   * {@code project=} on its file channel and {@link PresenceRegistry} keys on that name, so a
   * rename that went through under a live presence would leave three things disagreeing — the
   * session would root a name no row holds, every run in the new name would find no presence, and
   * the client would re-declare the <em>old</em> name at its next reconnect, undoing the move for
   * the registry and for nothing else.
   *
   * <p>The canonical name first, because it is the thing an operator acts on — it says which
   * machine to go and close a client on — and the session id after it, for the operator who has two
   * clients on one box.
   *
   * @param verb what this claim is called in the message: {@code "moved"} for the source of a
   *     rename, {@code "moved onto"} for the destination
   * @throws PresenceConflictException if a live session roots {@code project}
   */
  public void refuseIfRooted(String project, String verb) {
    Presence held = presences.serving(project).orElse(null);
    if (held != null) {
      throw new PresenceConflictException(
          held.canonicalName()
              + " is rooting the project"
              + " '"
              + project
              + "' right now, so it cannot be "
              + verb
              + " it."
              + " A move rewrites the name a live session declared, and nothing can"
              + " rewrite that session's own claim — it would come back under the old"
              + " name at its next reconnect. Close the client on that machine, move the"
              + " project, then start it again pointing at the new name."
              + " (session '"
              + held.session()
              + "')");
    }
  }
}
