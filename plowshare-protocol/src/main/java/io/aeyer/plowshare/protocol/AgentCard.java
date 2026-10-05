package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded peer discovery data. Declared URLs and security schemes describe a peer; they never
 * replace an adapter's configured endpoint, credentials or grants. Unsupported extension fields
 * must be refused by the discovery codec rather than passed to application code as a property bag.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCard(
    String name,
    String description,
    String version,
    List<String> defaultInputModes,
    List<String> defaultOutputModes,
    Capabilities capabilities,
    List<Skill> skills,
    List<SupportedInterface> supportedInterfaces,
    Provider provider,
    String documentationUrl,
    String iconUrl,
    Map<String, SecurityScheme> securitySchemes,
    List<SecurityRequirement> securityRequirements) {
  public AgentCard {
    name = ContractValues.identity(name, "card name", 1024);
    description = ContractValues.text(description, "card description", 32768, false);
    Objects.requireNonNull(description, "description");
    version = ContractValues.optionalIdentity(version, "card version", 128);
    defaultInputModes = strings(defaultInputModes, "input modes", 128, 256, false);
    defaultOutputModes = strings(defaultOutputModes, "output modes", 128, 256, false);
    skills = ContractValues.list(skills, "skills", 256);
    supportedInterfaces = list(supportedInterfaces, "supported interfaces", 32);
    documentationUrl = url(documentationUrl, "documentationUrl", false);
    iconUrl = url(iconUrl, "iconUrl", false);
    securitySchemes = dictionary(securitySchemes, "security schemes", 32);
    securityRequirements = list(securityRequirements, "security requirements", 32);
    if (securityRequirements != null)
      for (var requirement : securityRequirements)
        if (securitySchemes == null
            || !securitySchemes.keySet().containsAll(requirement.schemes().keySet()))
          throw new IllegalArgumentException("security requirement names an undeclared scheme");
  }

  public record Skill(
      String id,
      String name,
      String description,
      List<String> tags,
      List<String> examples,
      List<String> inputModes,
      List<String> outputModes,
      SkillMetadata metadata) {
    public Skill {
      id = ContractValues.identity(id, "skill id", 1024);
      name = ContractValues.identity(name, "skill name", 1024);
      description = ContractValues.text(description, "skill description", 32768, false);
      Objects.requireNonNull(description, "description");
      tags = strings(tags, "tags", 128, 1024, true);
      examples = strings(examples, "examples", 128, 32768, false);
      inputModes = strings(inputModes, "input modes", 128, 256, false);
      outputModes = strings(outputModes, "output modes", 128, 256, false);
    }
  }

  /** The supported discovery extension carries the server's already validated command DTO. */
  public record SkillMetadata(Incoming.Command plowshareCommand) {
    public SkillMetadata {
      Objects.requireNonNull(plowshareCommand, "plowshareCommand");
    }
  }

  public record Capabilities(
      Boolean streaming,
      Boolean pushNotifications,
      Boolean extendedAgentCard,
      Boolean stateTransitionHistory,
      List<Extension> extensions) {
    public Capabilities {
      extensions = list(extensions, "extensions", 32);
    }
  }

  public record Extension(String uri, String description, Boolean required) {
    public Extension {
      uri = AgentCard.uri(uri, "extension uri");
      description = ContractValues.text(description, "extension description", 32768, false);
    }
  }

  public record SupportedInterface(
      String url, String protocolBinding, String protocolVersion, String tenant) {
    public SupportedInterface {
      url = AgentCard.url(url, "interface url", true);
      protocolBinding = ContractValues.identity(protocolBinding, "protocol binding", 128);
      protocolVersion = ContractValues.identity(protocolVersion, "protocol version", 128);
      tenant = ContractValues.optionalIdentity(tenant, "tenant", 1024);
    }
  }

  public record Provider(String organization, String url) {
    public Provider {
      organization = ContractValues.identity(organization, "provider organization", 1024);
      url = AgentCard.url(url, "provider url", true);
    }
  }

  /** A2A security schemes use a closed one-of envelope, not arbitrary named JSON objects. */
  public record SecurityScheme(
      ApiKey apiKeySecurityScheme,
      HttpAuth httpAuthSecurityScheme,
      OAuth2 oauth2SecurityScheme,
      OpenIdConnect openIdConnectSecurityScheme,
      MutualTls mtlsSecurityScheme) {
    public SecurityScheme {
      int count =
          (apiKeySecurityScheme == null ? 0 : 1)
              + (httpAuthSecurityScheme == null ? 0 : 1)
              + (oauth2SecurityScheme == null ? 0 : 1)
              + (openIdConnectSecurityScheme == null ? 0 : 1)
              + (mtlsSecurityScheme == null ? 0 : 1);
      if (count != 1)
        throw new IllegalArgumentException("security scheme requires exactly one kind");
    }
  }

  public record ApiKey(String description, String location, String name) {
    public ApiKey {
      description = ContractValues.text(description, "security description", 32768, false);
      if (!java.util.Set.of("header", "query", "cookie").contains(location == null ? "" : location))
        throw new IllegalArgumentException("invalid API key location");
      name = ContractValues.identity(name, "API key name", 1024);
    }
  }

  public record HttpAuth(String description, String scheme, String bearerFormat) {
    public HttpAuth {
      description = ContractValues.text(description, "security description", 32768, false);
      scheme = ContractValues.identity(scheme, "HTTP auth scheme", 128);
      bearerFormat = ContractValues.optionalIdentity(bearerFormat, "bearer format", 1024);
    }
  }

  public record OpenIdConnect(String description, String openIdConnectUrl) {
    public OpenIdConnect {
      description = ContractValues.text(description, "security description", 32768, false);
      openIdConnectUrl = tlsUrl(openIdConnectUrl, "OpenID Connect URL", true);
    }
  }

  public record MutualTls(String description) {
    public MutualTls {
      description = ContractValues.text(description, "security description", 32768, false);
    }
  }

  public record OAuth2(String description, OAuthFlows flows, String oauth2MetadataUrl) {
    public OAuth2 {
      description = ContractValues.text(description, "security description", 32768, false);
      Objects.requireNonNull(flows, "flows");
      oauth2MetadataUrl = tlsUrl(oauth2MetadataUrl, "OAuth metadata URL", false);
    }
  }

  public record OAuthFlows(
      AuthorizationCode authorizationCode,
      ClientCredentials clientCredentials,
      Implicit implicit,
      Password password,
      DeviceCode deviceCode) {
    public OAuthFlows {
      int kinds =
          (authorizationCode == null ? 0 : 1)
              + (clientCredentials == null ? 0 : 1)
              + (implicit == null ? 0 : 1)
              + (password == null ? 0 : 1)
              + (deviceCode == null ? 0 : 1);
      if (kinds != 1) throw new IllegalArgumentException("OAuth requires exactly one flow");
    }
  }

  public record AuthorizationCode(
      String authorizationUrl,
      String tokenUrl,
      String refreshUrl,
      Map<String, String> scopes,
      Boolean pkceRequired) {
    public AuthorizationCode {
      authorizationUrl = tlsUrl(authorizationUrl, "authorizationUrl", true);
      tokenUrl = tlsUrl(tokenUrl, "tokenUrl", true);
      refreshUrl = tlsUrl(refreshUrl, "refreshUrl", false);
      scopes = checkedScopes(scopes);
    }
  }

  public record ClientCredentials(String tokenUrl, String refreshUrl, Map<String, String> scopes) {
    public ClientCredentials {
      tokenUrl = tlsUrl(tokenUrl, "tokenUrl", true);
      refreshUrl = tlsUrl(refreshUrl, "refreshUrl", false);
      scopes = checkedScopes(scopes);
    }
  }

  public record Implicit(String authorizationUrl, String refreshUrl, Map<String, String> scopes) {
    public Implicit {
      authorizationUrl = tlsUrl(authorizationUrl, "authorizationUrl", true);
      refreshUrl = tlsUrl(refreshUrl, "refreshUrl", false);
      scopes = checkedScopes(scopes);
    }
  }

  public record Password(String tokenUrl, String refreshUrl, Map<String, String> scopes) {
    public Password {
      tokenUrl = tlsUrl(tokenUrl, "tokenUrl", true);
      refreshUrl = tlsUrl(refreshUrl, "refreshUrl", false);
      scopes = checkedScopes(scopes);
    }
  }

  public record DeviceCode(
      String deviceAuthorizationUrl,
      String tokenUrl,
      String refreshUrl,
      Map<String, String> scopes) {
    public DeviceCode {
      deviceAuthorizationUrl = tlsUrl(deviceAuthorizationUrl, "deviceAuthorizationUrl", true);
      tokenUrl = tlsUrl(tokenUrl, "tokenUrl", true);
      refreshUrl = tlsUrl(refreshUrl, "refreshUrl", false);
      scopes = checkedScopes(scopes);
    }
  }

  public record SecurityRequirement(Map<String, StringList> schemes) {
    public SecurityRequirement {
      schemes = dictionary(Objects.requireNonNull(schemes, "schemes"), "required schemes", 32);
    }
  }

  public record StringList(List<String> list) {
    public StringList {
      list = strings(list, "required scopes", 128, 1024, true);
    }
  }

  private static <T> List<T> list(List<T> value, String field, int max) {
    return value == null ? null : ContractValues.list(value, field, max);
  }

  private static List<String> strings(
      List<String> value, String field, int max, int length, boolean required) {
    if (value == null && !required) return null;
    return ContractValues.list(value, field, max).stream()
        .map(item -> ContractValues.text(item, field, length, true))
        .toList();
  }

  private static <T> Map<String, T> dictionary(Map<String, T> value, String field, int max) {
    if (value == null) return null;
    if (value.size() > max) throw new IllegalArgumentException(field + " has too many entries");
    for (var key : value.keySet()) {
      ContractValues.identity(key, field, 1024);
      if (!key.equals(key.strip()))
        throw new IllegalArgumentException(field + " key must not have edge whitespace");
    }
    return Map.copyOf(value);
  }

  private static Map<String, String> checkedScopes(Map<String, String> scopes) {
    scopes = dictionary(Objects.requireNonNull(scopes, "scopes"), "OAuth scopes", 128);
    for (var description : scopes.values())
      ContractValues.text(description, "scope description", 32768, false);
    return scopes;
  }

  private static String tlsUrl(String value, String field, boolean required) {
    value = url(value, field, required);
    if (value != null && !URI.create(value).getScheme().equalsIgnoreCase("https"))
      throw new IllegalArgumentException(field + " requires HTTPS");
    return value;
  }

  private static String uri(String value, String field) {
    value = ContractValues.identity(value, field, 8192);
    try {
      if (!new URI(value).isAbsolute())
        throw new IllegalArgumentException(field + " must be absolute");
    } catch (URISyntaxException invalid) {
      throw new IllegalArgumentException("invalid " + field, invalid);
    }
    return value;
  }

  private static String url(String value, String field, boolean required) {
    if (value == null && !required) return null;
    value = uri(value, field);
    var address = URI.create(value);
    if (!java.util.Set.of("http", "https")
            .contains(address.getScheme().toLowerCase(java.util.Locale.ROOT))
        || address.getHost() == null
        || address.getRawUserInfo() != null
        || address.getRawFragment() != null)
      throw new IllegalArgumentException(
          field + " must be an HTTP(S) URL without credentials or fragment");
    return value;
  }
}
