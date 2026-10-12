package io.aeyer.plowshare.protocol;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Server-held upstream grants, distinct from Plowshare authentication and provider tool scopes. */
public final class ProviderCredentials {
  private ProviderCredentials() {}

  public enum Authentication {
    API_KEY,
    OAUTH_PKCE,
    CLIENT_CREDENTIALS
  }

  public enum ConnectionAction {
    ENABLE,
    DISABLE,
    DELETE
  }

  public enum State {
    NONE,
    PENDING,
    READY,
    REFRESHING,
    UNCERTAIN,
    REVOKED
  }

  /** Every network destination is explicitly selected by an operator, then policy checked. */
  public record OAuth(
      String issuer,
      String resource,
      String resourceMetadataUrl,
      String issuerMetadataUrl,
      String authorizationEndpoint,
      String tokenEndpoint,
      String clientId,
      String redirectUri,
      List<String> scopes) {
    public OAuth {
      https(issuer);
      https(resource);
      https(resourceMetadataUrl);
      https(issuerMetadataUrl);
      https(authorizationEndpoint);
      https(tokenEndpoint);
      https(redirectUri);
      text(clientId, 256);
      scopes = List.copyOf(scopes);
      if (scopes.size() > 64 || Set.copyOf(scopes).size() != scopes.size())
        throw new IllegalArgumentException("Choose at most 64 unique OAuth scopes");
      for (String scope : scopes) {
        if (scope.isEmpty()
            || scope.length() > 128
            || scope.chars().anyMatch(c -> c < 33 || c > 126 || c == 34 || c == 92))
          throw new IllegalArgumentException("Invalid OAuth scope");
      }
    }
  }

  public record Definition(
      String name,
      String endpoint,
      Authentication authentication,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) String apiKeyHeader,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) OAuth oauth) {
    public Definition {
      text(name, 128);
      https(endpoint);
      Objects.requireNonNull(authentication);
      if (authentication == Authentication.API_KEY) {
        if (oauth != null
            || apiKeyHeader == null
            || !Set.of("Authorization", "X-API-Key", "Api-Key").contains(apiKeyHeader))
          throw new IllegalArgumentException(
              "API key needs an allowed header and no OAuth settings");
      } else if (apiKeyHeader != null || oauth == null) {
        throw new IllegalArgumentException("OAuth needs explicit settings and no API key header");
      }
    }
  }

  public record Connection(
      UUID connection,
      String project,
      UUID revision,
      boolean enabled,
      boolean deleted,
      Definition definition) {
    public Connection {
      Objects.requireNonNull(connection);
      ProviderCredentials.project(project);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(definition);
      if (deleted && enabled)
        throw new IllegalArgumentException("Deleted connection cannot be enabled");
    }
  }

  /** A subject is an authenticated human or the exact retained @service/token-UUID principal. */
  public record Credential(
      UUID connection,
      String project,
      String subject,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) UUID generation,
      UUID connectionRevision,
      State state,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) OffsetDateTime expiresAt,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) String keyId) {
    public Credential {
      Objects.requireNonNull(connection);
      ProviderCredentials.project(project);
      ProviderCredentials.subject(subject);
      Objects.requireNonNull(connectionRevision);
      Objects.requireNonNull(state);
      if ((state == State.NONE) != (generation == null))
        throw new IllegalArgumentException("Credential state and generation disagree");
      boolean sealed = state == State.READY || state == State.PENDING || state == State.REFRESHING;
      if (sealed != (keyId != null) || (state != State.READY && expiresAt != null))
        throw new IllegalArgumentException(
            "Credential state and encryption/expiry metadata disagree");
      if (keyId != null) text(keyId, 64);
    }
  }

  public record Project(String project) {
    public Project {
      ProviderCredentials.project(project);
    }
  }

  public record StatusRequest(String project, UUID connection) {
    public StatusRequest {
      ProviderCredentials.project(project);
      Objects.requireNonNull(connection);
    }
  }

  public record ReceiptRequest(String project, UUID requestId) {
    public ReceiptRequest {
      ProviderCredentials.project(project);
      Objects.requireNonNull(requestId);
    }
  }

  public record Save(
      String project,
      UUID connection,
      UUID requestId,
      UUID expectedRevision,
      Definition definition) {
    public Save {
      ProviderCredentials.project(project);
      Objects.requireNonNull(connection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(definition);
    }
  }

  public record Change(
      String project,
      UUID connection,
      UUID requestId,
      UUID expectedRevision,
      ConnectionAction action) {
    public Change {
      ProviderCredentials.project(project);
      Objects.requireNonNull(connection);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(expectedRevision);
      Objects.requireNonNull(action);
    }
  }

  /**
   * Null subject means the authenticated caller; only an administrator may select a service UUID.
   */
  public record Target(String project, UUID connection, String subject) {
    public Target {
      ProviderCredentials.project(project);
      Objects.requireNonNull(connection);
      if (subject != null
          && !subject.matches("@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
        throw new IllegalArgumentException("Only an exact service principal can be selected");
    }
  }

  public record Mutation(
      String project, UUID connection, String subject, UUID requestId, UUID expectedGeneration) {
    public Mutation {
      new Target(project, connection, subject);
      Objects.requireNonNull(requestId);
    }

    public Target target() {
      return new Target(project, connection, subject);
    }
  }

  public record Put(
      String project,
      UUID connection,
      String subject,
      UUID requestId,
      UUID expectedGeneration,
      String secret) {
    public Put {
      new Mutation(project, connection, subject, requestId, expectedGeneration);
      ProviderCredentials.secret(secret);
    }

    public Mutation mutation() {
      return new Mutation(project, connection, subject, requestId, expectedGeneration);
    }

    @Override
    public String toString() {
      return "Put[connection=" + connection + ", secret=<redacted>]";
    }
  }

  /** Receipts retain redacted committed outcomes. They never authorize resubmission. */
  public record Receipt(
      UUID requestId,
      String operation,
      Connection connection,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) Credential credential) {
    public Receipt {
      Objects.requireNonNull(requestId);
      text(operation, 64);
      Objects.requireNonNull(connection);
      if (!Set.of(
              "provider.connection.save",
              "provider.connection.change",
              "provider.credential.put",
              "provider.credential.revoke",
              "provider.credential.rekey",
              "provider.credential.authorize",
              "provider.credential.refresh")
          .contains(operation))
        throw new IllegalArgumentException("Invalid provider mutation operation");
      if (credential != null
          && (!credential.connection().equals(connection.connection())
              || !credential.project().equals(connection.project())))
        throw new IllegalArgumentException("Foreign credential receipt");
    }
  }

  /** Authorization URL is returned once. Inspect redacted receipts if the reply is lost. */
  public record Authorization(Receipt receipt, String authorizationUrl, OffsetDateTime expiresAt) {
    public Authorization {
      Objects.requireNonNull(receipt);
      Objects.requireNonNull(expiresAt);
      if (authorizationUrl == null
          || authorizationUrl.length() > 16384
          || !authorizationUrl.startsWith("https://"))
        throw new IllegalArgumentException("Invalid authorization URL");
    }

    @Override
    public String toString() {
      return "Authorization[receipt=" + receipt + ", authorizationUrl=<redacted>]";
    }
  }

  public static void project(String value) {
    ApplicationIdentity.project(value);
  }

  public static void subject(String value) {
    text(value, 128);
    if (!value.matches(
        "(?:[A-Za-z0-9][A-Za-z0-9_.-]{0,63}|@service/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})"))
      throw new IllegalArgumentException("Invalid credential subject");
  }

  public static void text(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > maximum
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid bounded text");
  }

  public static void secret(String value) {
    if (value == null
        || value.isEmpty()
        || value.length() > 8192
        || value.chars().anyMatch(c -> c < 32 || c > 126)
        || value.startsWith("pss_")
        || value.startsWith("pst_"))
      throw new IllegalArgumentException(
          "Invalid upstream secret; Plowshare credentials are not accepted");
  }

  public static URI https(String value) {
    text(value, 2048);
    if (!value.matches(
        "https://[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?(?::[1-9][0-9]{0,4})?(?:/[A-Za-z0-9._~!$&'()*+,;=:@/-]*)?"))
      throw new IllegalArgumentException("Choose a supported HTTPS endpoint");
    try {
      URI uri = URI.create(value);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || value.contains("\\")
          || value.contains("%")) throw new IllegalArgumentException();
      return uri;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Choose an absolute HTTPS URL without userinfo, query or fragment");
    }
  }
}
