package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Strict, offline configuration boundary for the outbound named-provider executable. Paths resolve
 * against the configuration file; parsing neither reads credentials nor creates journal owners.
 */
public record HomeAssistantProviderConfiguration(
    String plowshare,
    String binding,
    String policyRevision,
    HomeAssistantSettings homeAssistant,
    Provider read,
    Optional<Control> control,
    Timing timing) {

  /** One logical provider and its private durable receipt owner. Both providers share one login. */
  public record Provider(
      String project,
      String provider,
      String account,
      String prefix,
      String tokenEnv,
      Path journal,
      int leaseSeconds) {
    public Provider {
      new RelayTools.Binding(project, provider, account);
      new HomeAssistantToolProvider.ProviderPermission(provider, account, prefix, leaseSeconds);
      environmentName(tokenEnv);
      Objects.requireNonNull(journal);
      journal = journal.toAbsolutePath().normalize();
    }

    public HomeAssistantToolProvider.Configuration runtime(Timing timing) {
      return new HomeAssistantToolProvider.Configuration(
          new RelayTools.Binding(project, provider, account),
          prefix,
          leaseSeconds,
          timing.requestTimeout(),
          timing.cleanupTimeout());
    }
  }

  /** An explicit local control ceiling; listing an action in discovery does not enable it. */
  public record Control(Provider provider, List<String> allowedActions) {
    public Control {
      Objects.requireNonNull(provider);
      allowedActions = List.copyOf(allowedActions);
      if (allowedActions.isEmpty()
          || allowedActions.size() > HomeAssistantResources.MAX_ACTIONS
          || allowedActions.stream().distinct().count() != allowedActions.size())
        throw new IllegalArgumentException("Control requires distinct selected actions");
      allowedActions.forEach(value -> IntegrationContracts.identity(value, 256));
    }
  }

  /** Resource-owned budgets and cadence, never an accepted agent workflow lifetime. */
  public record Timing(
      Duration connectTimeout,
      Duration requestTimeout,
      Duration cleanupTimeout,
      Duration pollInterval,
      Duration renewEvery,
      Duration metadataMaxAge) {
    public Timing {
      bounded(connectTimeout, 100, 30000);
      bounded(requestTimeout, 100, 30000);
      bounded(cleanupTimeout, 100, 30000);
      bounded(pollInterval, 10, 30000);
      bounded(metadataMaxAge, 1, 300000);
      bounded(renewEvery, 1, 150000);
      if (pollInterval.compareTo(renewEvery) >= 0)
        throw new IllegalArgumentException("Poll cadence must precede renewal cadence");
    }

    private static void bounded(Duration value, long minimum, long maximum) {
      Objects.requireNonNull(value);
      if (value.compareTo(Duration.ofMillis(minimum)) < 0
          || value.compareTo(Duration.ofMillis(maximum)) > 0)
        throw new IllegalArgumentException("Provider timing is outside its finite bound");
    }
  }

  public HomeAssistantProviderConfiguration {
    origin(plowshare);
    IntegrationContracts.identity(binding, 256);
    IntegrationContracts.identity(policyRevision, 256);
    Objects.requireNonNull(homeAssistant);
    Objects.requireNonNull(read);
    Objects.requireNonNull(control);
    Objects.requireNonNull(timing);
    if (!homeAssistant.subscriptions().isEmpty())
      throw new IllegalArgumentException("Provider mode does not admit workflow subscriptions");
    long minimumLease = read.leaseSeconds();
    if (control.isPresent()) {
      var selected = control.orElseThrow();
      var other = selected.provider();
      if (!read.project().equals(other.project())
          || !read.account().equals(other.account())
          || !read.tokenEnv().equals(other.tokenEnv())
          || read.provider().equals(other.provider())
          || read.prefix().startsWith(other.prefix())
          || other.prefix().startsWith(read.prefix())
          || read.journal().startsWith(other.journal())
          || other.journal().startsWith(read.journal()))
        throw new IllegalArgumentException(
            "Providers require one project/login and separate names, prefixes and journals");
      minimumLease = Math.min(minimumLease, other.leaseSeconds());
      var disclosed =
          homeAssistant.entities().values().stream()
              .map(HomeAssistantSettings.Entity::entity)
              .toList();
      for (String alias : selected.allowedActions()) {
        var action = homeAssistant.actions().get(alias);
        if (action == null) throw new IllegalArgumentException("Unknown selected control action");
        HomeAssistantControlServices.requireSupported(action);
        if (action.targets().isEmpty()
            || action.targets().size() > ActionExecutor.MAX_TARGETS
            || !disclosed.containsAll(action.targets()))
          throw new IllegalArgumentException("Control requires completely disclosed fixed targets");
      }
    }
    Duration halfLease = Duration.ofMillis(minimumLease * 500);
    if (timing.renewEvery().compareTo(halfLease) >= 0
        || timing.requestTimeout().compareTo(halfLease) >= 0)
      throw new IllegalArgumentException(
          "Provider pass and renewal must fit within half its lease");
  }

  /** Read at most the public integration byte bound before strict UTF-8 and DTO validation. */
  public static HomeAssistantProviderConfiguration read(Path file) throws IOException {
    Objects.requireNonNull(file);
    byte[] bytes;
    try (InputStream input = Files.newInputStream(file)) {
      bytes = input.readNBytes(Json.MAX_MESSAGE + 1);
    }
    if (bytes.length > Json.MAX_MESSAGE)
      throw new IOException("Provider configuration exceeds limit");
    try {
      String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      JsonNode root = Json.parse(text);
      Json.fields(
          root,
          "version",
          "plowshare",
          "binding",
          "policyRevision",
          "homeAssistant",
          "read",
          "control",
          "timing");
      if (integer(root, "version") != 1)
        throw new IllegalArgumentException("Provider configuration version 1 required");
      Path base = file.toAbsolutePath().normalize().getParent();
      Provider read = provider(root.get("read"), base, false);
      Optional<Control> control = Optional.empty();
      if (root.has("control")) {
        JsonNode selected = root.get("control");
        Provider other = provider(selected, base, true);
        JsonNode actions = selected.get("allowedActions");
        if (actions == null || !actions.isArray())
          throw new IllegalArgumentException("Control action array required");
        var aliases = new ArrayList<String>();
        for (JsonNode action : actions) {
          if (!action.isTextual()) throw new IllegalArgumentException("Action alias must be text");
          aliases.add(action.textValue());
        }
        control = Optional.of(new Control(other, aliases));
      }
      var time = root.get("timing");
      Json.fields(
          time,
          "connectTimeoutMillis",
          "requestTimeoutMillis",
          "cleanupTimeoutMillis",
          "pollIntervalMillis",
          "renewEveryMillis",
          "metadataMaxAgeMillis");
      var timing =
          new Timing(
              millis(time, "connectTimeoutMillis"),
              millis(time, "requestTimeoutMillis"),
              millis(time, "cleanupTimeoutMillis"),
              millis(time, "pollIntervalMillis"),
              millis(time, "renewEveryMillis"),
              millis(time, "metadataMaxAgeMillis"));
      JsonNode ha = root.get("homeAssistant");
      if (ha == null || !ha.isObject()) throw new IllegalArgumentException("HA settings required");
      return new HomeAssistantProviderConfiguration(
          Json.text(root, "plowshare"),
          Json.text(root, "binding"),
          Json.text(root, "policyRevision"),
          new HomeAssistantFactory().decode(ha.toString()),
          read,
          control,
          timing);
    } catch (IOException | RuntimeException invalid) {
      // Parser/URI/path errors can contain supplied values. Do not echo private configuration.
      throw new IOException(
          "Invalid provider configuration; inspect its schema and selected policy");
    }
  }

  private static Provider provider(JsonNode node, Path base, boolean control) {
    if (node == null) throw new IllegalArgumentException("Provider object required");
    if (control)
      Json.fields(
          node,
          "project",
          "provider",
          "account",
          "prefix",
          "tokenEnv",
          "journal",
          "leaseSeconds",
          "allowedActions");
    else
      Json.fields(
          node, "project", "provider", "account", "prefix", "tokenEnv", "journal", "leaseSeconds");
    String journal = Json.text(node, "journal");
    IntegrationContracts.identity(journal, 4096);
    return new Provider(
        Json.text(node, "project"),
        Json.text(node, "provider"),
        Json.text(node, "account"),
        Json.text(node, "prefix"),
        Json.text(node, "tokenEnv"),
        base.resolve(journal),
        Math.toIntExact(integer(node, "leaseSeconds")));
  }

  private static long integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
      throw new IllegalArgumentException("Integral provider setting required");
    return value.longValue();
  }

  private static Duration millis(JsonNode node, String field) {
    return Duration.ofMillis(integer(node, field));
  }

  private static void environmentName(String name) {
    if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,127}"))
      throw new IllegalArgumentException("Credential reference must name an environment variable");
  }

  private static void origin(String value) {
    URI uri = URI.create(Objects.requireNonNull(value));
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !List.of("", "/").contains(uri.getPath())
        || uri.getPort() == 0
        || uri.getPort() > 65535)
      throw new IllegalArgumentException("Explicit HTTP(S) Plowshare origin required");
  }

  /** The selected local control ceiling, independent of server-owned tool grants. */
  public Map<String, HomeAssistantSettings.Action> selectedActions() {
    Map<String, HomeAssistantSettings.Action> result = new LinkedHashMap<>();
    control.ifPresent(
        value ->
            value
                .allowedActions()
                .forEach(alias -> result.put(alias, homeAssistant.actions().get(alias))));
    return Map.copyOf(result);
  }
}
