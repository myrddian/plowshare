package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.*;
import java.util.*;

final class ProviderFixtures {
  static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final String ACTOR = "owner", PROJECT = "fixture";
  static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  static final OAuth OAUTH =
      new OAuth(
          "https://issuer.example",
          "https://provider.example/mcp",
          "https://provider.example/.well-known/oauth-protected-resource/mcp",
          "https://issuer.example/.well-known/oauth-authorization-server",
          "https://issuer.example/authorize",
          "https://issuer.example/token",
          "fixture-client",
          "https://plowshare.example/provider-credentials/oauth/callback",
          List.of("tools"));

  static Definition api() {
    return new Definition(
        "Fixture", "https://provider.example/mcp", Authentication.API_KEY, "X-API-Key", null);
  }

  static Definition oauth() {
    return new Definition(
        "Fixture", "https://provider.example/mcp", Authentication.OAUTH_PKCE, null, OAUTH);
  }

  static ProviderEndpointPolicy policy() {
    return new ConfiguredProviderEndpoints(
        Set.of("https://provider.example", "https://issuer.example"), OAUTH.redirectUri());
  }

  static class Time extends Clock {
    Instant current = Instant.parse("2026-10-12T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException();
      return this;
    }

    public Instant instant() {
      return current;
    }

    void advance(long seconds) {
      current = current.plusSeconds(seconds);
    }
  }

  /**
   * Behavior fake only; actual conditional writes/transactions are checked in the tagged DB suite.
   */
  static class Memory implements ProviderCredentialRepository {
    Connection connection = new Connection(ID, PROJECT, UUID.randomUUID(), true, false, api());
    final Map<String, Stored> grants = new HashMap<>();
    final Map<UUID, Committed> receipts = new HashMap<>();

    public void lockProject(String project) {}

    public List<Connection> list(String project) {
      return project.equals(connection.project()) ? List.of(connection) : List.of();
    }

    public Optional<Connection> connection(String project, UUID id, boolean lock) {
      return project.equals(connection.project()) && id.equals(connection.connection())
          ? Optional.of(connection)
          : Optional.empty();
    }

    public void create(Connection value) {
      connection = value;
    }

    public void update(Connection value, UUID expected) {
      if (!connection.revision().equals(expected)) throw new CallerFault("Stale revision");
      connection = value;
    }

    public Optional<Stored> credential(UUID id, String subject, boolean lock) {
      return Optional.ofNullable(grants.get(subject));
    }

    public void store(Stored value, UUID expected) {
      var old = grants.get(value.metadata().subject());
      if (!Objects.equals(old == null ? null : old.metadata().generation(), expected))
        throw new CallerFault("Stale generation");
      grants.put(value.metadata().subject(), value);
    }

    public void revokeAll(UUID id, UUID revision) {
      grants.replaceAll(
          (subject, g) ->
              new Stored(
                  new Credential(
                      id, PROJECT, subject, UUID.randomUUID(), revision, State.REVOKED, null, null),
                  null,
                  null,
                  null,
                  null,
                  null));
    }

    public Optional<Stored> authorization(String hash) {
      return grants.values().stream().filter(g -> hash.equals(g.stateHash())).findFirst();
    }

    public void commit(String actor, Receipt receipt) {
      if (receipts.putIfAbsent(receipt.requestId(), new Committed(actor, receipt)) != null)
        throw new CallerFault("Duplicate request");
    }

    public Optional<Committed> receipt(UUID id) {
      return Optional.ofNullable(receipts.get(id));
    }
  }
}
