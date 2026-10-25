package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import java.util.Objects;

/**
 * The Relay topic names this subsystem publishes to, derived from the scope that homes them.
 *
 * <p>Two families use it: fired events ({@code event.fire}) and scheduled occurrences ({@code
 * schedule.due}). They share one derivation under separate namespaces, for the reasons on {@link
 * #derive}.
 *
 * <p><b>A topic name is private to the system that owns it.</b> Relay is a mechanism, not a policy:
 * it carries a contract, stores a publication opaquely, and deliberately reserves no names for
 * consumers. A consumer's topic name is therefore an implementation detail of that consumer and not
 * an identifier any other party holds. Were it a literal it would become three things nobody meant
 * to offer: an address anything able to publish into the scope could spell, a value an operator
 * could configure as an external ingress port in {@code plowshare.relay.ports.bindings} — {@code
 * RelayPortProperties.permits} is pure data and no code reserves topic names — and so part of a
 * contract. Deriving it withdraws all three: it is computed only here, and nothing outside this
 * package can construct it. {@code InformationTopics} established this shape for information
 * events; this is the same reasoning applied to the second family to move.
 *
 * <p><b>Derived, not secret.</b> The opaque segment is a digest, so anyone who knows the recipe and
 * the scope can recompute it. What is removed is the name as a <i>published</i> address — spelled
 * in configuration, in another module's constant, or in an operator's port binding — and with it
 * the possibility of a deployment opening the topic to an external publisher by naming it. {@code
 * ScopedRelayInspection.topics} still lists a scope's topics, so a reader admitted to the scope can
 * discover the name; opacity is an abstraction boundary, not a confidentiality guarantee. The
 * mechanism that stops a forged notice being acted on is {@link RelayEventPublication} verifying a
 * publication's publisher before decoding it. Both, not either.
 *
 * <p><b>Deterministic and scope-separating.</b> Publisher and consumer derive the name
 * independently and must agree forever, so the digest is over the scope's identity alone: the same
 * scope always yields the same name, two scopes yield different ones, and two families yield
 * different ones. The digest is {@link RelayPort#hash} — SHA-256 over UTF-8 as lowercase hex —
 * reused rather than reinvented, which is also what keeps the result inside the schema's bounds.
 *
 * <p><b>The bounds it has to clear, read out of the migrations rather than assumed.</b> {@code
 * event.fired.} plus 64 hex characters is 76, and {@code schedule.occurrence.} plus 64 is 84. Every
 * topic-name column in the schema applies {@code ^[a-z][a-z0-9]*([._-][a-z0-9]+)*$}, which all
 * three segments satisfy because hex digits are lowercase alphanumerics. The lengths differ: {@code
 * relay_topics.name} and {@code relay_deliveries.publish_to} allow 1024 (V117 and V119's 160,
 * widened by V141), and {@code relay_source_events.topic} allows 160 (V124), which is the tightest
 * bound a topic name faces anywhere. 76 clears all of them, and {@code
 * RelayEventPublicationDatabaseTest} asserts acceptance by storing the name through the real schema
 * rather than by restating the rule here.
 *
 * <p><b>Nothing in the name is legible.</b> The input is the scope's identity, never a project name
 * or an account handle, and the output is a digest of it. The publisher identity is namespaced and
 * hashed for the same reason — see {@link RelayEventPublication} — so neither the topic's name nor
 * its log names an account.
 */
final class EventTopics {
  /**
   * The fired-event family's namespace, then the opaque segment. The prefix is kept legible on
   * purpose: an operator reading a topic list or a log page should be able to tell which subsystem
   * owns a topic and that its name is derived, without being able to reproduce it. It is
   * deliberately not {@code event.fire}: that is the operation's name, and a topic that spelled it
   * would invite exactly the reading this class exists to prevent — that the topic is the
   * operation's public address.
   */
  private static final String FIRED_PREFIX = "event.fired.";

  /**
   * Domain separation for the fired-event digest. It binds the derivation to this family, so a
   * scope identity hashed for some other purpose can never collide with a fired-event topic, and it
   * is versioned so a future change of recipe is a new name with its own migration rather than a
   * silent reinterpretation of the old one.
   */
  private static final String FIRED_NAMESPACE = "plowshare.event.fired/1:";

  /**
   * The scheduled family's namespace. Deliberately not {@code schedule.due}, the literal being
   * withdrawn, and deliberately not {@code schedule.define} either: that is the operation's name
   * and a genuine public contract, while this is the private address occurrences travel to.
   */
  private static final String SCHEDULED_PREFIX = "schedule.occurrence.";

  /** Domain separation for the scheduled digest, read the same way as the fired one above. */
  private static final String SCHEDULED_NAMESPACE = "plowshare.schedule.occurrence/1:";

  private EventTopics() {}

  /** The topic name fired events for {@code scope} are published to and consumed from. */
  static String fired(Relay.Scope scope) {
    return derive(FIRED_PREFIX, FIRED_NAMESPACE, scope);
  }

  /** The topic name a schedule's due occurrences for {@code scope} are published to. */
  static String scheduled(Relay.Scope scope) {
    return derive(SCHEDULED_PREFIX, SCHEDULED_NAMESPACE, scope);
  }

  /**
   * The one derivation both families use.
   *
   * <p>Shared rather than written twice, and shared <em>here</em> rather than anywhere more
   * reachable: both publishers live in this package, so a package-private class already puts the
   * recipe where both need it and nowhere else. A helper lifted into a module both subsystems could
   * import would make the recipe addressable again by anything that could import it, which is the
   * property these names exist to withdraw.
   *
   * <p>The namespaces keep the two families' names disjoint even though the scope identity and the
   * digest are the same, so one family's topic can never collide with the other's, and a change of
   * recipe for one is not a change of recipe for both.
   */
  private static String derive(String prefix, String namespace, Relay.Scope scope) {
    return prefix + RelayPort.hash(namespace + identity(scope));
  }

  /**
   * This subsystem's canonical identity for a Relay scope.
   *
   * <p>Total over the sealed hierarchy, and both cases are live. Every fired event resolves to
   * {@link Relay.SystemScope#SERVER} — see {@link RelayEventPublication} for the authority finding
   * behind that — while a schedule resolves to either: {@code JdbcScheduleStore.claimPublication}
   * returns the owning project for a file-managed schedule and the server scope for a projectless
   * one. The spelling deliberately coincides with {@code relay_topics.scope_key} ({@code
   * project:<id>} or {@code system}, V120) so that SQL can derive the same name from the column it
   * already has, which is what lets a migration rename rows without reconstructing a scope.
   */
  private static String identity(Relay.Scope scope) {
    Objects.requireNonNull(scope, "scope");
    return scope instanceof Relay.ProjectScope project
        ? "project:" + project.projectId()
        : "system";
  }
}
