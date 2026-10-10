package io.aeyer.plowshare.server.applications;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import io.aeyer.plowshare.server.relay.tools.RelayToolDefinition;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Project-owned authority, never a Spring overlay. Only these declared capabilities are accepted.
 */
public record ApplicationServerSettings(
    List<RelayToolDefinition> tools,
    List<RelayPortProperties.Binding> ports,
    List<Provider> providers,
    Optional<Worker> worker) {
  public static final ApplicationServerSettings EMPTY =
      new ApplicationServerSettings(List.of(), List.of(), List.of(), Optional.empty());
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public ApplicationServerSettings {
    tools = List.copyOf(tools);
    ports = List.copyOf(ports);
    providers = List.copyOf(providers);
    Objects.requireNonNull(worker);
    if (tools.size() > 128
        || ports.size() > 128
        || providers.size() > 32
        || tools.stream().map(RelayToolDefinition::name).distinct().count() != tools.size()
        || providers.stream().map(Provider::provider).distinct().count() != providers.size()
        || new HashSet<>(ports).size() != ports.size())
      throw new IllegalArgumentException("Invalid Application server declarations");
  }

  public ApplicationServerSettings(
      List<RelayToolDefinition> tools,
      List<RelayPortProperties.Binding> ports,
      List<Provider> providers) {
    this(tools, ports, providers, Optional.empty());
  }

  /** Explicit Application execution identity, never inferred from a manager or connected user. */
  public record Worker(int version, String account) {
    public Worker {
      if (version != 1)
        throw new IllegalArgumentException("Unsupported Application worker version");
      new io.aeyer.plowshare.server.relay.RelayWorkerProperties.Project("validation", account);
    }
  }

  public record Provider(String provider, String account, String prefix, int leaseSeconds) {
    public Provider {
      new RelayToolDefinition(
          "validation", provider, account, "validation", "validation", List.of(), 30);
      if (prefix == null
          || !prefix.matches("[a-z][a-z0-9_]*_")
          || prefix.length() > 48
          || leaseSeconds < 1
          || leaseSeconds > 300)
        throw new IllegalArgumentException("Invalid dynamic tool provider authority");
    }

    public String topic() {
      return "tool." + provider + ".catalog";
    }
  }

  public record Declaration(
      String name,
      String description,
      List<RelayToolDefinition.Parameter> parameters,
      int timeoutSeconds) {
    public Declaration {
      new RelayToolDefinition(
          "validation", "validation", "validation", name, description, parameters, timeoutSeconds);
      parameters = List.copyOf(parameters);
    }

    public RelayToolDefinition bind(String project, Provider provider) {
      return new RelayToolDefinition(
          project,
          provider.provider(),
          provider.account(),
          name,
          description,
          parameters,
          timeoutSeconds);
    }
  }

  public record Tool(
      String provider,
      String account,
      String name,
      String description,
      List<RelayToolDefinition.Parameter> parameters,
      int timeoutSeconds) {
    public Tool {
      new RelayToolDefinition(
          "validation", provider, account, name, description, parameters, timeoutSeconds);
      parameters = List.copyOf(parameters);
    }

    public RelayToolDefinition bind(String project) {
      return new RelayToolDefinition(
          project, provider, account, name, description, parameters, timeoutSeconds);
    }
  }

  public record Port(
      String topic, String account, RelayPortProperties.Direction direction, List<String> groups) {
    public Port {
      var checked =
          new RelayPortProperties.Binding("validation", topic, account, direction, groups);
      groups = checked.groups();
    }

    public RelayPortProperties.Binding bind(String project) {
      return new RelayPortProperties.Binding(project, topic, account, direction, groups);
    }
  }

  public record Tools(int version, List<Tool> bindings, List<Provider> providers) {
    public Tools {
      if (version != 1) throw new IllegalArgumentException("Unsupported Application tools version");
      bindings = List.copyOf(bindings);
      providers = providers == null ? List.of() : List.copyOf(providers);
    }
  }

  public record Ports(int version, List<Port> bindings) {
    public Ports {
      if (version != 1) throw new IllegalArgumentException("Unsupported Application ports version");
      bindings = List.copyOf(bindings);
    }
  }

  public record Catalogue(String version, List<Declaration> tools) {
    public Catalogue {
      if (!"plowshare-tool-catalog/1".equals(version))
        throw new IllegalArgumentException("Unsupported tool catalogue version");
      tools = List.copyOf(tools);
      if (tools.size() > 128
          || tools.stream().map(Declaration::name).distinct().count() != tools.size())
        throw new IllegalArgumentException("Invalid tool catalogue");
    }
  }

  public static Catalogue catalogue(String text) {
    try {
      if (text.length() > 65536)
        throw new IllegalArgumentException("Tool catalogue exceeds 64 KiB");
      return JSON.readValue(text, Catalogue.class);
    } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
      throw new CallerFault("Invalid tool catalogue declaration");
    }
  }

  /**
   * A missing directory means no additional authority; an invalid replacement never keeps old
   * grants.
   */
  public static ApplicationServerSettings read(String project, Path root) {
    Path directory = root.resolve("server");
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return EMPTY;
    try {
      if (Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid server directory");
      try (var entries = Files.list(directory)) {
        if (entries.anyMatch(
            p ->
                !Set.of("tools.json", "ports.json", "relay-workers.json", "README.md")
                        .contains(p.getFileName().toString())
                    || Files.isSymbolicLink(p)
                    || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)))
          throw new IOException("Unknown server declaration");
      }
      Tools tools =
          readFile(
              directory.resolve("tools.json"), Tools.class, new Tools(1, List.of(), List.of()));
      Ports ports = readFile(directory.resolve("ports.json"), Ports.class, new Ports(1, List.of()));
      return new ApplicationServerSettings(
          tools.bindings().stream().map(t -> t.bind(project)).toList(),
          ports.bindings().stream().map(p -> p.bind(project)).toList(),
          tools.providers(),
          Files.notExists(directory.resolve("relay-workers.json"), LinkOption.NOFOLLOW_LINKS)
              ? Optional.empty()
              : Optional.of(
                  readRequiredFile(directory.resolve("relay-workers.json"), Worker.class)));
    } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
      throw new CallerFault("Invalid Application server/ configuration");
    }
  }

  private static <T> T readFile(Path file, Class<T> type, T absent) throws IOException {
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return absent;
    return readRequiredFile(file, type);
  }

  private static <T> T readRequiredFile(Path file, Class<T> type) throws IOException {
    if (Files.isSymbolicLink(file)
        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) > 65536)
      throw new IOException("Declaration must be a bounded regular JSON file");
    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(65537);
      if (bytes.length > 65536) throw new IOException("Declaration exceeds 64 KiB");
      return Objects.requireNonNull(JSON.readValue(bytes, type), "Declaration cannot be null");
    }
  }
}
