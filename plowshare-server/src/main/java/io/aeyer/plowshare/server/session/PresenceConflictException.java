package io.aeyer.plowshare.server.session;

/**
 * A live presence is in the way of a claim on a project.
 *
 * <p>Two claimants reach this. A <b>second session</b> tried to root a project another live session
 * already roots — the case below, and the one this type was written for. An <b>operator</b> tried
 * to move a project a live session is rooting, which is the second heading.
 *
 * <p><b>A conflict and not redundancy</b>, which is the owner's decision of 2026-09-03 arriving as
 * a type:
 *
 * <blockquote>
 *
 * The mirroring case - is about complications if we allowed a project to exist in one or more
 * locations - I for one do not want to go that route
 *
 * </blockquote>
 *
 * <p>So this is thrown rather than the second claim being accepted alongside the first, and rather
 * than the newer one displacing the older the way a socket does in {@code SessionRegistry}. The two
 * cases are not alike: a second socket under one session id is one client reconnecting, and the
 * newer one is the one a human is looking at; a second <em>machine</em> claiming one project is two
 * places asserting they are one, and nothing can check which of them is right.
 *
 * <p><b>The message names both canonical names.</b> An operator hitting this has two clients
 * running and needs to know which one to close, and "that project is taken" tells them neither.
 * {@code PresenceRegistry.declare} composes it.
 *
 * <h2>A second thrower, and why it is this type rather than another</h2>
 *
 * <p>{@code ProjectController.move} refuses to rename a project a live session roots, at either end
 * of the move. That is not a second session claiming a project — but it is the same
 * <em>sentence</em>: <b>a live presence is in the way of a claim on a project, and here is which
 * machine holds it</b>. What an operator does about it is identical too: close the client on the
 * machine this message names, then do the thing again.
 *
 * <p>So this type widened rather than a second one appearing beside it. The two sites differ only
 * in which claim is refused — a socket's, or an operator's — and a type per claimant would be two
 * classes carrying one argument, with the next edit landing on one of them.
 *
 * <p><b>The 125-byte rule is the registry's and not this type's.</b> {@code FileChannelHandler}
 * sends a declaration's refusal back in a WebSocket close reason, whose control payload is that
 * long; an HTTP body has no such limit. Putting the names first is still the right shape for both,
 * and it is what both throwers do — but the constraint travels with the socket, not with the class.
 */
public final class PresenceConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PresenceConflictException(String message) {
    super(message);
  }
}
