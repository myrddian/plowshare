package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;

/**
 * Short transaction access only; acquiring a header never implicitly refreshes or sends upstream.
 */
public final class CurrentProviderCredentialGrants implements ProviderCredentialGrants {
  private final ProviderCredentialRepository repository;
  private final ProviderCredentialAccess access;
  private final ProviderEndpointPolicy policy;
  private final CredentialCipher cipher;
  private final ProviderSecretCodec secrets;
  private final Clock clock;

  public CurrentProviderCredentialGrants(
      ProviderCredentialRepository repository,
      ProviderCredentialAccess access,
      ProviderEndpointPolicy policy,
      CredentialCipher cipher,
      ProviderSecretCodec secrets,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.policy = policy;
    this.cipher = cipher;
    this.secrets = secrets;
    this.clock = clock;
  }

  public Connection connection(String owner, Target target) {
    return connection(owner, target, false);
  }

  private Connection connection(String owner, Target target, boolean lock) {
    var c =
        repository
            .connection(target.project(), target.connection(), lock)
            .orElseThrow(() -> new CallerFault("Provider connection unavailable"));
    access.subject(owner, target);
    if (!c.enabled() || c.deleted()) throw new CallerFault("Provider connection disabled");
    policy.require(c.definition());
    return c;
  }

  public Reference inspect(String owner, Target target) {
    return reference(owner, target, false);
  }

  public Reference require(String owner, Target target) {
    return reference(owner, target, true);
  }

  private Reference reference(String owner, Target target, boolean lock) {
    var c = connection(owner, target, lock);
    String subject = target.subject() == null ? owner : target.subject();
    var grant =
        repository
            .credential(c.connection(), subject, lock)
            .orElseThrow(() -> new CallerFault("Provider credential absent"));
    var m = grant.metadata();
    if (c.definition().authentication() != Authentication.API_KEY && m.expiresAt() == null
        || m.state() != State.READY
        || !m.connectionRevision().equals(c.revision())
        || m.expiresAt() != null && !m.expiresAt().toInstant().isAfter(clock.instant()))
      throw new CallerFault("Provider credential is expired, busy, revoked or uncertain");
    return new Reference(c, m);
  }

  public Header header(Reference reference) {
    var c = reference.connection();
    var m = reference.credential();
    if (m.expiresAt() != null && !m.expiresAt().toInstant().isAfter(clock.instant()))
      throw new CallerFault("Provider credential expired");
    var stored =
        repository
            .credential(c.connection(), m.subject(), true)
            .filter(s -> s.metadata().equals(m))
            .orElseThrow(() -> new CallerFault("Provider credential changed"));
    var material = secrets.decode(cipher.open(stored.context(), stored.material()));
    if (c.definition().authentication() == Authentication.API_KEY) {
      if (material.apiKey() == null) throw new CallerFault("API key unavailable");
      return new Header(c.definition().apiKeyHeader(), material.apiKey());
    }
    if (material.access() == null
        || m.expiresAt() == null
        || !m.expiresAt().toInstant().isAfter(clock.instant()))
      throw new CallerFault("Obtain a current OAuth grant before provider discovery or execution");
    return new Header("Authorization", "Bearer " + material.access());
  }
}
