package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import java.util.Objects;

/**
 * The Relay topic name this subsystem publishes information events to, derived from the scope that
 * homes them.
 *
 * <p><b>A topic name is private to the system that owns it.</b> Relay is a mechanism, not a policy:
 * it carries a contract, stores a publication opaquely, and deliberately does not reserve names for
 * consumers. It follows that a consumer's topic name is an implementation detail of that consumer
 * and not an identifier any other party holds. The family published to the literal {@code
 * information.changed} instead, which made the name three things it was never meant to be: an
 * address anything able to publish to a project topic could spell, a value an operator could
 * configure as an external ingress port in {@code plowshare.relay.ports.bindings} — {@code
 * RelayPortProperties.permits} is pure data and there is no code-owned reservation of topic names —
 * and therefore part of a contract nobody intended to offer. Deriving the name withdraws all three
 * at once: it is computed only here, and nothing outside this package can construct it.
 *
 * <p><b>Derived, not secret.</b> The opaque segment is a digest, so an attacker who already knows
 * the recipe and the scope can recompute it; that is not what this defends. What it removes is the
 * name as a <i>published</i> address — one spelled in configuration, in another module's constant,
 * in a client, or in an operator's binding — and with it the possibility of a deployment opening
 * the topic to an external publisher by naming it. The structural barrier on the read side stays
 * where it was: {@link RelayInformationEvents} verifies a publication's publisher before decoding
 * it, and a derived name is defence in depth over that, not a replacement for it.
 *
 * <p><b>Deterministic and scope-separating.</b> Publisher and consumer derive the name
 * independently, in different requests and different processes, and must agree forever: the digest
 * is over the scope's identity alone, so the same scope always yields the same name and two scopes
 * yield different ones. The digest is {@link RelayPort#hash} — SHA-256 over UTF-8 rendered as
 * lowercase hex — reused rather than reinvented, which is also what keeps the result inside the
 * schema's bounds: {@code information.events.} plus 64 hex characters is 83 characters, well under
 * the 160-character limit {@code relay_subscriptions} applies, and every segment matches the shared
 * {@code ^[a-z][a-z0-9]*([._-][a-z0-9]+)*$} rule because hex digits are lowercase alphanumerics.
 *
 * <p><b>Nothing in the name is legible.</b> A project name and an account handle are the two things
 * the old literal was in danger of acquiring as topics multiplied, and neither enters the digest in
 * cleartext: the input is the scope's numeric identity, and the output is a digest of it. The
 * publisher identity is namespaced and hashed for the same reason (see {@link
 * RelayInformationEvents}), so neither the topic's name nor its log names an account.
 *
 * <p><b>Why the scope identity is spelled the way it is.</b> The digest input is this subsystem's
 * own canonical identity for a scope, not Relay's persisted encoding — the two are separate
 * concerns and {@code RelayScopeCodec} is private to the broker. It is nevertheless written to
 * coincide with that encoding, character for character, so that {@code
 * V190__information_topic_derived_name.sql} can derive the same name in SQL from the {@code
 * scope_key} column it already has rather than reconstructing one. The agreement between the two is
 * not assumed: {@code RelayInformationEventsDatabaseTest} evaluates the migration's expression in
 * PostgreSQL and asserts it equals what this class returns.
 */
final class InformationTopics {
  /**
   * The family's own namespace, then the opaque segment. The prefix is kept legible on purpose: an
   * operator reading a topic list or a log page should be able to tell which subsystem owns a topic
   * and that its name is derived, without being able to reproduce it.
   */
  private static final String PREFIX = "information.events.";

  /**
   * Domain separation for the digest. It binds the derivation to this family, so a scope identity
   * hashed for some other purpose can never collide with an information topic, and it is versioned
   * so a future change of recipe is a new name with its own migration rather than a silent
   * reinterpretation of the old one.
   */
  private static final String NAMESPACE = "plowshare.information.events/1:";

  private InformationTopics() {}

  /** The topic name information events for {@code scope} are published to and consumed from. */
  static String of(Relay.Scope scope) {
    return PREFIX + RelayPort.hash(NAMESPACE + identity(scope));
  }

  /**
   * This subsystem's canonical identity for a Relay scope.
   *
   * <p>Total over the sealed hierarchy rather than refusing the server scope. No information
   * resource homes there — {@link InformationEventRecords} maps a home scope to a project or fails
   * loudly — but a derivation that threw for a scope it can be handed would make the migration's
   * SQL and this method disagree about rows the migration must still be able to rename.
   */
  private static String identity(Relay.Scope scope) {
    Objects.requireNonNull(scope, "scope");
    return scope instanceof Relay.ProjectScope project
        ? "project:" + project.projectId()
        : "system";
  }
}
