/**
 * What a caller asked for, read off whatever named it: one request field turned into a domain
 * value, or into a {@link io.aeyer.plowshare.server.faults.CallerFault} saying what that field
 * looks like.
 *
 * <h2>Why these are not in {@code api}</h2>
 *
 * <p>They were, and it was the same accident that put the WebSocket handlers there: {@code api}
 * means <em>the HTTP surface</em>, and these landed in it because the HTTP surface was the only
 * surface. It is no longer going to be. {@code ws} is being built to route frames into the same
 * subsystems, and a frame naming a project, a document, a budget or a turn cap must reach the same
 * parse and the same refusal — not a second one written from the first. <b>A dispatcher that
 * imported {@code api} to read a field would make the accident permanent</b>, and would do it in
 * the package whose whole purpose is to be a surface that is not HTTP.
 *
 * <p>The leak was already real rather than hypothetical: {@code agents.Callers}, a domain service,
 * imported {@code api.RequestedAgent} and {@code api.RequestedProjectId} — a service below the
 * surface reaching up into it, for types that were never about HTTP.
 *
 * <h2>These are not the {@code *Request} types, which stay in {@code api}</h2>
 *
 * <p>The names are close enough to be worth separating deliberately. {@code
 * api.OpenConversationRequest} and {@code api.LendRequest} are Jackson-bound body shapes: one
 * endpoint, one surface, one wire format, and they belong to the surface that binds them. A {@code
 * Requested*} type reads <em>fields</em> — an {@code Integer}, a {@code String}, a {@code
 * List<String>} — never an HTTP body shape. Some also take an injected collaborator besides the
 * fields — {@link RequestedAgent} an {@code ObjectProvider<AgentRegistry>}, {@link
 * RequestedProjectId} a {@code ProjectStore} — and that is a different axis entirely: the rule is
 * about not taking a wire shape, not about taking no collaborators.
 *
 * <p><b>That is a rule and not an observation.</b> Two of these took a body shape as a parameter
 * while they lived in {@code api}, and a parser that cannot be called without one is a parser only
 * the HTTP surface can call. {@code RequestedBudget.in} and {@code RequestedPaths.roots} now take
 * their fields, which is the shape {@code RequestedTurnCap.in} already had. Anything added here
 * takes fields too.
 *
 * <h2>They raise {@code CallerFault}, never {@code BadRequestException}</h2>
 *
 * <p>{@code api.BadRequestException} is the HTTP surface's own type; both map to 400 with an
 * identical body through {@code ApiExceptionHandler}, so the choice is about what a package is
 * allowed to import rather than about what a caller sees. {@code InvariantsTest} holds the line,
 * and it is the reason three of these could not simply be moved: {@code RequestedSession}, {@code
 * RequestedTurnCap} and {@code RequestedAgent} still threw the HTTP type and had to be converted
 * first.
 *
 * <h2>Everything here is public, and that is a cost this move charges</h2>
 *
 * <p>{@code RequestedSession} and {@code RequestedTurnCap} were package-private classes, with
 * package-private {@code in} methods on each, and {@code RequestedAgent.toRun} and {@code toRead}
 * were package-private methods too — six members saying "only this package calls me" while this
 * package was {@code api}. A second surface is exactly what that visibility refused, so it could
 * not survive the move. The narrower statement is gone and is not recoverable by a future
 * rearrangement.
 */
package io.aeyer.plowshare.server.requests;
