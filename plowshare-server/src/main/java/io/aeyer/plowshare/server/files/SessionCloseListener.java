package io.aeyer.plowshare.server.files;

/**
 * Something that must forget whatever it cached for one session once that session's file channel
 * closes.
 *
 * <h2>Why this lives here and not beside its one caller or its one implementor</h2>
 *
 * <p>{@code FileChannelHandler} (in {@code ws/}) is the one place a close is an event, and {@code
 * DefinitionResolver} (in {@code agents/}) is the one thing today that needs to hear about it —
 * {@code ChannelDefinitions}' own lifetime is the socket's, so a project's cached tier must not
 * outlive the session that built it. Putting the interface in either package would point a
 * dependency the wrong way: {@code ws/} does not know about {@code agents/}, on the same reasoning
 * {@link SessionChannel}'s own javadoc gives for why <em>that</em> seam lives here rather than in
 * {@code ws/} — {@code files/} is beneath both, so a type here can be depended on by each without
 * either depending on the other.
 *
 * <p><b>Spring, not a direct reference, is what connects the two sides.</b> {@code
 * FileChannelHandler} holds every bean of this type the context can find and calls each on close;
 * {@code DefinitionResolver} is discovered as one of them by implementing this interface, with
 * neither class importing the other. A direct reference would be circular in the object graph and
 * not merely in the package diagram: {@code DefinitionResolver} is built from a {@link
 * SessionChannel}, which is {@code FileChannelHandler} itself, so a constructor-injected path the
 * other way — {@code FileChannelHandler} needing a fully-built {@code DefinitionResolver} to
 * construct itself — could not be satisfied by either bean going first.
 *
 * <h2>Why a session close and not merely "evict this cache entry"</h2>
 *
 * <p>Named for the event rather than for what a listener does with it, because the interface has
 * exactly one implementor today and may gain others whose cached state has nothing to do with an
 * {@code AgentRegistry} — a listener describes what happened, not what to do about it.
 */
public interface SessionCloseListener {

  /**
   * The file channel for {@code sessionId} has closed. Drop anything kept under that session's
   * name; it must not be answered from again.
   *
   * @param sessionId the id the client registered under when it opened the socket — the same string
   *     {@link SessionChannel#ask} takes
   */
  void sessionClosed(String sessionId);
}
