package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.providers.ProviderCredentialRepository.Stored;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Connection-first transactions fence configuration and grants. Network I/O follows a committed
 * single-send intent; a lost rotation response becomes UNCERTAIN and is never retried implicitly.
 * Authority is rechecked after row-lock waits before any transition is committed.
 */
public final class OwnedProviderConnections implements ProviderConnections {
  private final ProviderCredentialRepository repository;
  private final ProviderCredentialAccess access;
  private final ProviderEndpointPolicy policy;
  private final CredentialCipher cipher;
  private final ProviderSecretCodec secrets;
  private final ProviderOAuth oauth;
  private final UnitOfWork work;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public OwnedProviderConnections(
      ProviderCredentialRepository repository,
      ProviderCredentialAccess access,
      ProviderEndpointPolicy policy,
      CredentialCipher cipher,
      ProviderSecretCodec secrets,
      ProviderOAuth oauth,
      UnitOfWork work,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.policy = policy;
    this.cipher = cipher;
    this.secrets = secrets;
    this.oauth = oauth;
    this.work = work;
    this.clock = clock;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  private Connection find(String project, UUID id, boolean lock) {
    return repository
        .connection(project, id, lock)
        .orElseThrow(() -> new CallerFault("Provider connection not found in this project"));
  }

  private void usable(Connection connection) {
    if (!connection.enabled() || connection.deleted())
      throw new CallerFault("Provider connection is disabled or deleted");
    policy.require(connection.definition());
  }

  private void fresh(UUID id) {
    if (repository.receipt(id).isPresent())
      throw new CallerFault("Mutation UUID already committed; inspect its receipt without replay");
  }

  private Receipt commit(
      String actor, UUID request, String operation, Connection connection, Credential credential) {
    var receipt = new Receipt(request, operation, connection, credential);
    repository.commit(actor, receipt);
    return receipt;
  }

  @Override
  public List<Connection> list(String actor, String project) {
    access.read(actor, project);
    var rows = repository.list(project);
    if (rows.size() > 256)
      throw new CallerFault("Too many retained provider connections; inspect a known connection");
    return rows;
  }

  @Override
  public Connection status(String actor, String project, UUID connection) {
    access.read(actor, project);
    return find(project, connection, false);
  }

  @Override
  public Receipt save(String actor, Save request) {
    access.configure(actor, request.project());
    policy.require(request.definition());
    return work.inTransaction(
        () -> {
          repository.lockProject(request.project());
          access.configure(actor, request.project());
          fresh(request.requestId());
          var prior = repository.connection(request.project(), request.connection(), true);
          if (!Objects.equals(
              prior.map(Connection::revision).orElse(null), request.expectedRevision()))
            throw new CallerFault("Provider connection revision changed; inspect status");
          if (prior.isPresent() && prior.get().deleted())
            throw new CallerFault("Deleted connection IDs cannot be adopted again");
          if (prior.isEmpty() && repository.list(request.project()).size() >= 256)
            throw new CallerFault("Provider connection limit reached");
          var next =
              new Connection(
                  request.connection(),
                  request.project(),
                  UUID.randomUUID(),
                  true,
                  false,
                  request.definition());
          if (prior.isEmpty()) repository.create(next);
          else {
            repository.update(next, request.expectedRevision());
            repository.revokeAll(next.connection(), next.revision());
          }
          return commit(actor, request.requestId(), "provider.connection.save", next, null);
        });
  }

  @Override
  public Receipt change(String actor, Change request) {
    access.configure(actor, request.project());
    return work.inTransaction(
        () -> {
          repository.lockProject(request.project());
          access.configure(actor, request.project());
          fresh(request.requestId());
          var prior = find(request.project(), request.connection(), true);
          if (!prior.revision().equals(request.expectedRevision()) || prior.deleted())
            throw new CallerFault(
                "Provider connection revision changed or was deleted; inspect status");
          boolean enabled = request.action() == ConnectionAction.ENABLE;
          if (enabled) policy.require(prior.definition());
          var next =
              new Connection(
                  prior.connection(),
                  prior.project(),
                  UUID.randomUUID(),
                  enabled,
                  request.action() == ConnectionAction.DELETE,
                  prior.definition());
          repository.update(next, prior.revision());
          repository.revokeAll(next.connection(), next.revision());
          return commit(actor, request.requestId(), "provider.connection.change", next, null);
        });
  }

  private Credential absent(Connection c, String subject) {
    return new Credential(
        c.connection(), c.project(), subject, null, c.revision(), State.NONE, null, null);
  }

  private Stored expired(Stored current) {
    if (current.intentExpiresAt() != null && !current.intentExpiresAt().isAfter(now())) {
      var m = current.metadata();
      var next =
          new Stored(
              new Credential(
                  m.connection(),
                  m.project(),
                  m.subject(),
                  UUID.randomUUID(),
                  m.connectionRevision(),
                  State.UNCERTAIN,
                  null,
                  null),
              null,
              null,
              null,
              null,
              null);
      repository.store(next, m.generation());
      return next;
    }
    return current;
  }

  @Override
  public Credential credential(String actor, Target target) {
    String subject = access.subject(actor, target);
    return work.inTransaction(
        () -> {
          var c = find(target.project(), target.connection(), true);
          access.subject(actor, target);
          return repository
              .credential(c.connection(), subject, true)
              .map(this::expired)
              .map(Stored::metadata)
              .orElseGet(() -> absent(c, subject));
        });
  }

  private Optional<Stored> expected(Connection c, String subject, Mutation request) {
    var prior = repository.credential(c.connection(), subject, true);
    if (!Objects.equals(
        prior.map(v -> v.metadata().generation()).orElse(null), request.expectedGeneration()))
      throw new CallerFault("Provider credential generation changed; inspect status");
    return prior;
  }

  private Stored sealed(
      Connection c,
      String subject,
      UUID generation,
      State state,
      OffsetDateTime expires,
      ProviderSecretMaterial material,
      UUID attempt,
      String stateHash,
      String initiator,
      OffsetDateTime until) {
    String context =
        "provider-credential/1/"
            + c.connection()
            + "/"
            + subject
            + "/"
            + c.revision()
            + "/"
            + generation;
    var sealed = cipher.seal(context, secrets.encode(material));
    return new Stored(
        new Credential(
            c.connection(),
            c.project(),
            subject,
            generation,
            c.revision(),
            state,
            expires,
            sealed.keyId()),
        sealed,
        attempt,
        stateHash,
        initiator,
        until);
  }

  private ProviderSecretMaterial open(Stored record) {
    if (record.material() == null)
      throw new CallerFault("Credential has no usable material; authorize again");
    return secrets.decode(cipher.open(record.context(), record.material()));
  }

  @Override
  public Receipt put(String actor, Put request) {
    var mutation = request.mutation();
    String subject = access.subject(actor, mutation.target());
    return work.inTransaction(
        () -> {
          var c = find(request.project(), request.connection(), true);
          access.subject(actor, mutation.target());
          fresh(request.requestId());
          usable(c);
          expected(c, subject, mutation);
          var kind = c.definition().authentication();
          if (kind == Authentication.OAUTH_PKCE)
            throw new CallerFault(
                "OAuth grants require authorization; access-token passthrough is refused");
          var material =
              kind == Authentication.API_KEY
                  ? new ProviderSecretMaterial(null, null, null, request.secret(), null)
                  : new ProviderSecretMaterial(null, null, request.secret(), null, null);
          var next =
              sealed(
                  c,
                  subject,
                  UUID.randomUUID(),
                  State.READY,
                  null,
                  material,
                  null,
                  null,
                  null,
                  null);
          repository.store(next, request.expectedGeneration());
          return commit(actor, request.requestId(), "provider.credential.put", c, next.metadata());
        });
  }

  @Override
  public Receipt revoke(String actor, Mutation request) {
    String subject = access.subject(actor, request.target());
    return work.inTransaction(
        () -> {
          var c = find(request.project(), request.connection(), true);
          access.subject(actor, request.target());
          fresh(request.requestId());
          expected(c, subject, request);
          var next =
              new Stored(
                  new Credential(
                      c.connection(),
                      c.project(),
                      subject,
                      UUID.randomUUID(),
                      c.revision(),
                      State.REVOKED,
                      null,
                      null),
                  null,
                  null,
                  null,
                  null,
                  null);
          repository.store(next, request.expectedGeneration());
          return commit(
              actor, request.requestId(), "provider.credential.revoke", c, next.metadata());
        });
  }

  @Override
  public Receipt rekey(String actor, Mutation request) {
    String subject = access.subject(actor, request.target());
    return work.inTransaction(
        () -> {
          var c = find(request.project(), request.connection(), true);
          access.subject(actor, request.target());
          fresh(request.requestId());
          usable(c);
          var prior =
              expected(c, subject, request)
                  .orElseThrow(() -> new CallerFault("Credential is absent"));
          if (prior.metadata().state() != State.READY)
            throw new CallerFault("Only a ready credential can change encryption key");
          var next =
              sealed(
                  c,
                  subject,
                  prior.metadata().generation(),
                  State.READY,
                  prior.metadata().expiresAt(),
                  open(prior),
                  null,
                  null,
                  null,
                  null);
          repository.store(next, request.expectedGeneration());
          return commit(
              actor, request.requestId(), "provider.credential.rekey", c, next.metadata());
        });
  }

  private String nonce() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  @Override
  public Authorization authorize(String actor, Mutation request) {
    String subject = access.subject(actor, request.target());
    fresh(request.requestId());
    var offered = find(request.project(), request.connection(), false);
    usable(offered);
    if (offered.definition().authentication() != Authentication.OAUTH_PKCE)
      throw new CallerFault("Connection does not use user OAuth");
    oauth.discover(offered.definition().oauth(), true);
    String state = nonce(), verifier = nonce();
    OffsetDateTime until = now().plusMinutes(10);
    String url = oauth.authorizationUrl(offered.definition().oauth(), state, verifier);
    var receipt =
        work.inTransaction(
            () -> {
              var c = find(request.project(), request.connection(), true);
              access.subject(actor, request.target());
              fresh(request.requestId());
              usable(c);
              if (!c.equals(offered))
                throw new CallerFault("Provider configuration changed during discovery");
              expected(c, subject, request);
              var next =
                  sealed(
                      c,
                      subject,
                      UUID.randomUUID(),
                      State.PENDING,
                      null,
                      new ProviderSecretMaterial(null, null, null, null, verifier),
                      request.requestId(),
                      hash(state),
                      actor,
                      until);
              repository.store(next, request.expectedGeneration());
              return commit(
                  actor, request.requestId(), "provider.credential.authorize", c, next.metadata());
            });
    return new Authorization(receipt, url, until);
  }

  private record Rotation(Connection connection, Stored source, Stored intent) {}

  @Override
  public Credential callback(String actor, String state, String issuer, String code) {
    if (state == null
        || !state.matches("[A-Za-z0-9_-]{43}")
        || code == null
        || code.isEmpty()
        || code.length() > 8192
        || code.chars().anyMatch(c -> c < 32 || c > 126))
      throw new CallerFault("Invalid OAuth callback");
    var offered =
        repository
            .authorization(hash(state))
            .orElseThrow(() -> new CallerFault("OAuth state is absent or already consumed"));
    if (!Objects.equals(offered.initiator(), actor))
      throw new CallerFault("OAuth state belongs to another authenticated owner");
    var m = offered.metadata();
    var rotation =
        work.inTransaction(
            () -> {
              var c = find(m.project(), m.connection(), true);
              access.subject(
                  actor,
                  new Target(
                      m.project(), m.connection(), m.subject().equals(actor) ? null : m.subject()));
              usable(c);
              var current = repository.credential(c.connection(), m.subject(), true).orElseThrow();
              if (!current.equals(offered)
                  || !c.revision().equals(m.connectionRevision())
                  || !current.intentExpiresAt().isAfter(now())
                  || c.definition().oauth() == null
                  || !c.definition().oauth().issuer().equals(issuer))
                throw new CallerFault(
                    "OAuth state expired, issuer mismatched or configuration changed");
              // Clear the state digest before sending: an HTTP retry cannot exchange the same code
              // twice.
              var intent =
                  sealed(
                      c,
                      m.subject(),
                      UUID.randomUUID(),
                      State.REFRESHING,
                      null,
                      open(current),
                      current.attempt(),
                      null,
                      actor,
                      now().plusSeconds(60));
              repository.store(intent, m.generation());
              return new Rotation(c, current, intent);
            });
    return exchange(
        actor,
        rotation,
        () ->
            oauth.exchange(
                rotation.connection().definition().oauth(),
                code,
                open(rotation.source()).verifier()));
  }

  @Override
  public Receipt refresh(String actor, Mutation request) {
    String subject = access.subject(actor, request.target());
    fresh(request.requestId());
    var offered = find(request.project(), request.connection(), false);
    usable(offered);
    if (offered.definition().authentication() == Authentication.API_KEY)
      throw new CallerFault("API keys have no refresh operation");
    oauth.discover(
        offered.definition().oauth(),
        offered.definition().authentication() == Authentication.OAUTH_PKCE);
    var rotation =
        work.inTransaction(
            () -> {
              var c = find(request.project(), request.connection(), true);
              access.subject(actor, request.target());
              fresh(request.requestId());
              usable(c);
              if (!c.equals(offered))
                throw new CallerFault("Provider configuration changed during discovery");
              var prior =
                  expected(c, subject, request)
                      .orElseThrow(() -> new CallerFault("Credential is absent"));
              if (prior.metadata().state() != State.READY
                  || !c.revision().equals(prior.metadata().connectionRevision()))
                throw new CallerFault(
                    "Credential is busy, revoked or uncertain; inspect status and authorize again");
              var material = open(prior);
              if (c.definition().authentication() == Authentication.OAUTH_PKCE
                  && material.refresh() == null)
                throw new CallerFault("OAuth provider did not grant refresh; authorize again");
              if (c.definition().authentication() == Authentication.CLIENT_CREDENTIALS
                  && material.clientSecret() == null)
                throw new CallerFault("Machine client secret is absent");
              var intent =
                  sealed(
                      c,
                      subject,
                      UUID.randomUUID(),
                      State.REFRESHING,
                      null,
                      material,
                      request.requestId(),
                      null,
                      actor,
                      now().plusSeconds(60));
              repository.store(intent, prior.metadata().generation());
              commit(
                  actor, request.requestId(), "provider.credential.refresh", c, intent.metadata());
              return new Rotation(c, prior, intent);
            });
    Credential result =
        exchange(
            actor,
            rotation,
            () -> {
              var config = rotation.connection().definition();
              var material = open(rotation.source());
              return config.authentication() == Authentication.CLIENT_CREDENTIALS
                  ? oauth.machine(config.oauth(), material.clientSecret())
                  : oauth.refresh(config.oauth(), material.refresh());
            });
    // Receipt remains the committed intent, while status reports the completed generation.
    return new Receipt(
        request.requestId(), "provider.credential.refresh", rotation.connection(), result);
  }

  private Credential exchange(
      String actor, Rotation rotation, java.util.function.Supplier<ProviderOAuth.Tokens> call) {
    ProviderOAuth.Tokens tokens;
    try {
      tokens = call.get();
    } catch (RuntimeException failed) {
      uncertain(rotation);
      throw failed;
    }
    try {
      return work.inTransaction(
          () -> {
            var m = rotation.intent().metadata();
            var c = find(m.project(), m.connection(), true);
            access.subject(
                actor,
                new Target(
                    m.project(), m.connection(), m.subject().equals(actor) ? null : m.subject()));
            usable(c);
            var current = repository.credential(c.connection(), m.subject(), true).orElseThrow();
            if (!current.equals(rotation.intent())
                || !c.equals(rotation.connection())
                || !current.intentExpiresAt().isAfter(now()))
              throw new CallerFault(
                  "Credential grant changed or expired during exchange; inspect status");
            var old = open(rotation.source());
            String refresh = tokens.refresh() != null ? tokens.refresh() : old.refresh();
            var next =
                sealed(
                    c,
                    m.subject(),
                    UUID.randomUUID(),
                    State.READY,
                    tokens.expiresAt(),
                    new ProviderSecretMaterial(
                        tokens.access(), refresh, old.clientSecret(), null, null),
                    null,
                    null,
                    null,
                    null);
            repository.store(next, m.generation());
            return next.metadata();
          });
    } catch (RuntimeException failed) {
      uncertain(rotation);
      throw failed;
    }
  }

  private void uncertain(Rotation rotation) {
    work.inTransaction(
        () -> {
          var m = rotation.intent().metadata();
          find(m.project(), m.connection(), true);
          var current = repository.credential(m.connection(), m.subject(), true);
          // A concurrent revoke/configuration update wins; late responses never restore the old
          // grant.
          if (current.isPresent() && current.get().equals(rotation.intent())) {
            var next =
                new Stored(
                    new Credential(
                        m.connection(),
                        m.project(),
                        m.subject(),
                        UUID.randomUUID(),
                        m.connectionRevision(),
                        State.UNCERTAIN,
                        null,
                        null),
                    null,
                    null,
                    null,
                    null,
                    null);
            repository.store(next, m.generation());
          }
          return true;
        });
  }

  @Override
  public Receipt receipt(String actor, String project, UUID requestId) {
    access.read(actor, project);
    var retained =
        repository
            .receipt(requestId)
            .orElseThrow(
                () ->
                    new CallerFault(
                        "Provider mutation receipt not found; absence does not authorize replay"));
    if (!retained.actor().equals(actor)
        || !retained.receipt().connection().project().equals(project))
      throw new CallerFault(
          "Provider mutation receipt belongs to another authenticated owner or project");
    if (retained.receipt().credential() != null) {
      String subject = retained.receipt().credential().subject();
      access.subject(
          actor,
          new Target(
              project,
              retained.receipt().connection().connection(),
              subject.equals(actor) ? null : subject));
    }
    return retained.receipt();
  }
}
