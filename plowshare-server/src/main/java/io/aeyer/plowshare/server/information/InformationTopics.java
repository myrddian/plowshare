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
 * schema's bounds: {@code information.events.} plus 64 hex characters is 83 characters, and every
 * segment matches the {@code ^[a-z][a-z0-9]*([._-][a-z0-9]+)*$} rule every topic-name column
 * applies, because hex digits are lowercase alphanumerics. Every topic-name column bounds the name
 * at 1024 characters, which 83 clears; {@code RelayInformationEventsDatabaseTest} asserts
 * acceptance by storing the name rather than by restating the rule here.
 *
 * <p><b>Correction, on the bound.</b> An earlier version of this comment said "the tightest length
 * a topic name faces in the schema is the 160 characters {@code relay_source_events.topic} allows",
 * and {@code V190__information_topic_derived_name.sql}'s header says the same at greater length.
 * <b>Both are wrong, and were already wrong when they were written.</b> 160 was the original bound
 * in three places — {@code relay_topics.name} (V117), {@code relay_deliveries.publish_to} (V119)
 * and {@code relay_source_events.topic} (V124) — and {@code V141__relay_topic_name_capacity.sql}
 * drops and recreates all three at 1024 in one migration. So there is no 160-character topic bound
 * anywhere, and no column is tighter than another. V190's header cannot be corrected, because a
 * shipped migration is never edited; the correction lives here instead, which is where a reader
 * following the derivation will actually reach it. Nothing about the derivation changes: 83 clears
 * 1024 as comfortably as it cleared 160. {@code EventTopics} in {@code server/events}, which
 * derives the fired-event and scheduled-occurrence names the same way, states the real bound and
 * has its own pin read it out of {@code pg_constraint} rather than restating a number a migration
 * can move — which is the shape to prefer if this comment ever needs a number again.
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
