package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Pinned local configuration. Project/account authority comes from the SDK session. */
public record Configuration(
    String plowshare,
    String tokenEnv,
    Path journal,
    Map<String, Binding> bindings,
    Retention retention) {
  public Configuration {
    IntegrationContracts.identity(plowshare, 4096);
    java.net.URI origin = java.net.URI.create(plowshare);
    if (!Set.of("http", "https").contains(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !Set.of("", "/").contains(origin.getPath())
        || origin.getPort() == 0
        || origin.getPort() > 65535)
      throw new IllegalArgumentException("explicit Plowshare HTTP(S) origin required");
    if (tokenEnv == null || !tokenEnv.matches("[A-Z][A-Z0-9_]{0,127}"))
      throw new IllegalArgumentException("tokenEnv must name an environment variable");
    Objects.requireNonNull(journal);
    Objects.requireNonNull(retention);
    bindings = Map.copyOf(bindings);
    if (bindings.isEmpty() || bindings.size() > 32)
      throw new IllegalArgumentException("one to 32 bindings required");
    Set<String> peers = new HashSet<>();
    for (var e : bindings.entrySet()) {
      Binding b = e.getValue();
      if (!e.getKey().equals(b.name()) || !peers.add(b.project() + ":" + b.peer()))
        throw new IllegalArgumentException("inconsistent or duplicate binding");
      for (String target : b.allowBindings())
        if (!bindings.containsKey(target) || !bindings.get(target).project().equals(b.project()))
          throw new IllegalArgumentException("allowed bindings must exist in the same project");
    }
  }

  public Configuration(
      String plowshare, String tokenEnv, Path journal, Map<String, Binding> bindings) {
    this(plowshare, tokenEnv, journal, bindings, Retention.disabled());
  }

  public record Retention(long settledSeconds, long dedupeSeconds) {
    public Retention {
      if (settledSeconds < -1
          || settledSeconds > 31_536_000
          || dedupeSeconds < 1
          || dedupeSeconds > 31_536_000)
        throw new IllegalArgumentException("invalid retention duration");
    }

    public static Retention disabled() {
      return new Retention(-1, 604800);
    }

    public boolean enabled() {
      return settledSeconds >= 0;
    }
  }

  public record QueuePolicy(String coalesce, int maxPendingEvents) {
    public QueuePolicy {
      if (!Set.of("none", "same-state").contains(coalesce)
          || maxPendingEvents < 1
          || maxPendingEvents > Journal.MAX_ENTRIES)
        throw new IllegalArgumentException("invalid event queue policy");
    }

    public static QueuePolicy defaults() {
      return new QueuePolicy("none", Journal.MAX_ENTRIES);
    }
  }

  public record FeedbackPolicy(boolean suppressPipelineStarts, long windowSeconds, int maxDepth) {
    public FeedbackPolicy(boolean suppressPipelineStarts, long windowSeconds) {
      this(suppressPipelineStarts, windowSeconds, 1);
    }

    public FeedbackPolicy {
      if (windowSeconds < 1 || windowSeconds > 86400)
        throw new IllegalArgumentException("feedback window requires 1 to 86400 seconds");
      if (maxDepth < 1 || maxDepth > Feedback.MAX_DEPTH)
        throw new IllegalArgumentException("feedback depth requires 1 to 16 links");
    }

    public static FeedbackPolicy disabled() {
      return new FeedbackPolicy(false, 300);
    }
  }

  public record Route(
      String name,
      String entity,
      Double above,
      String unit,
      long cooldownSeconds,
      String definition,
      String agent,
      String request,
      String completionAction,
      long holdSeconds) {
    public Route {
      Configuration.name(name);
      if (entity != null) Configuration.name(entity);
      if ((entity == null) != (above == null) || above != null && !Double.isFinite(above))
        throw new IllegalArgumentException("finite threshold and entity must be supplied together");
      if (unit != null) IntegrationContracts.identity(unit, 256);
      if (cooldownSeconds < 0 || cooldownSeconds > 86400)
        throw new IllegalArgumentException("invalid cooldown");
      IntegrationContracts.identity(definition, 1024);
      IntegrationContracts.identity(agent, 1024);
      if (request == null || request.isBlank())
        throw new IllegalArgumentException("route request required");
      IntegrationContracts.text(request, 1048576);
      if (completionAction != null) Configuration.name(completionAction);
      if (holdSeconds < 0
          || holdSeconds > 86400
          || holdSeconds > 0 && (entity == null || above == null))
        throw new IllegalArgumentException(
            "held duration requires a threshold and 0 to 86400 seconds");
    }

    public Route(
        String name,
        String entity,
        Double above,
        String unit,
        long cooldownSeconds,
        String definition,
        String agent,
        String request,
        String completionAction) {
      this(
          name,
          entity,
          above,
          unit,
          cooldownSeconds,
          definition,
          agent,
          request,
          completionAction,
          0);
    }
  }

  public record Binding(
      String name,
      String adapter,
      String project,
      String peer,
      AdapterConfiguration configuration,
      Map<String, Route> routes,
      Set<String> allowBindings,
      String script,
      String fingerprint,
      QueuePolicy queue,
      FeedbackPolicy feedback) {
    public Binding {
      Configuration.name(name);
      Configuration.name(adapter);
      Configuration.name(peer);
      IntegrationContracts.identity(project, 1024);
      IntegrationContracts.identity(fingerprint, 1024);
      Objects.requireNonNull(configuration);
      Objects.requireNonNull(queue);
      Objects.requireNonNull(feedback);
      routes = Map.copyOf(routes);
      allowBindings = Set.copyOf(allowBindings);
      if (routes.size() > 256 || allowBindings.size() > 32 || !allowBindings.contains(name))
        throw new IllegalArgumentException("invalid binding policy bounds");
      for (var e : routes.entrySet())
        if (!e.getKey().equals(e.getValue().name()))
          throw new IllegalArgumentException("route name mismatch");
      allowBindings.forEach(Configuration::name);
      IntegrationContracts.text(script, Json.MAX_MESSAGE / 2);
      for (Route route : routes.values())
        if (route.above() == null && script == null)
          throw new IllegalArgumentException(
              "unconditional routes require an explicit script handler");
    }

    public Binding(
        String name,
        String adapter,
        String project,
        String peer,
        AdapterConfiguration configuration,
        Map<String, Route> routes,
        Set<String> allowBindings,
        String script,
        String fingerprint,
        QueuePolicy queue) {
      this(
          name,
          adapter,
          project,
          peer,
          configuration,
          routes,
          allowBindings,
          script,
          fingerprint,
          queue,
          FeedbackPolicy.disabled());
    }

    public Binding(
        String name,
        String adapter,
        String project,
        String peer,
        AdapterConfiguration configuration,
        Map<String, Route> routes,
        Set<String> allowBindings,
        String script,
        String fingerprint) {
      this(
          name,
          adapter,
          project,
          peer,
          configuration,
          routes,
          allowBindings,
          script,
          fingerprint,
          QueuePolicy.defaults());
    }
  }

  public static Configuration read(Path file) throws IOException {
    Map<String, AdapterFactory> factories = new HashMap<>();
    for (AdapterFactory factory : ServiceLoader.load(AdapterFactory.class))
      if (factories.put(factory.name(), factory) != null)
        throw new IllegalArgumentException("duplicate adapter factory");
    return read(file, factories);
  }

  /** Decode every binding before any adapter or credential-bearing transport is created. */
  public static Configuration read(Path file, Map<String, AdapterFactory> factories)
      throws IOException {
    Path base = file.toAbsolutePath().getParent();
    JsonNode root = Json.parse(Files.readString(file));
    Json.fields(root, "version", "plowshare", "tokenEnv", "journal", "bindings", "retention");
    Retention retention = Retention.disabled();
    if (root.has("retention")) {
      JsonNode r = root.path("retention");
      Json.fields(r, "settledSeconds", "dedupeSeconds");
      for (String field : List.of("settledSeconds", "dedupeSeconds"))
        if (!r.path(field).isIntegralNumber()
            || !r.path(field).canConvertToLong()
            || r.path(field).asLong() < 0)
          throw new IllegalArgumentException("nonnegative retention seconds required");
      retention =
          new Retention(r.path("settledSeconds").asLong(), r.path("dedupeSeconds").asLong());
    }
    java.net.URI origin = java.net.URI.create(Json.text(root, "plowshare"));
    if (!Set.of("http", "https").contains(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
      throw new IllegalArgumentException(
          "Plowshare requires an HTTP(S) origin without credentials/path/query/fragment");
    if (!root.path("version").isIntegralNumber()
        || !root.path("version").canConvertToInt()
        || root.path("version").asInt() != 1)
      throw new IllegalArgumentException("configuration version 1 required");
    String token = Json.text(root, "tokenEnv");
    if (!token.matches("[A-Z][A-Z0-9_]*"))
      throw new IllegalArgumentException("tokenEnv must name an environment variable");
    if (!root.path("bindings").isObject()
        || root.path("bindings").isEmpty()
        || root.path("bindings").size() > 32)
      throw new IllegalArgumentException("one to 32 bindings required");
    Map<String, Binding> bindings = new LinkedHashMap<>();
    Set<String> peers = new HashSet<>();
    var entries = root.path("bindings").fields();
    while (entries.hasNext()) {
      var entry = entries.next();
      String name = entry.getKey();
      JsonNode n = entry.getValue();
      name(name);
      Json.fields(
          n,
          "adapter",
          "project",
          "peer",
          "configuration",
          "routes",
          "allowBindings",
          "script",
          "queue",
          "feedback");
      String project = Json.text(n, "project"), peer = Json.text(n, "peer");
      name(peer);
      if (!peers.add(project + ":" + peer))
        throw new IllegalArgumentException("duplicate project/peer binding");
    }
    // Parse named routes without treating their names as configuration field names.
    entries = root.path("bindings").fields();
    while (entries.hasNext()) {
      var entry = entries.next();
      String name = entry.getKey();
      JsonNode n = entry.getValue();
      FeedbackPolicy feedback = FeedbackPolicy.disabled();
      if (n.has("feedback")) {
        JsonNode f = n.path("feedback");
        Json.fields(f, "suppressPipelineStarts", "windowSeconds", "maxDepth");
        if (!f.path("suppressPipelineStarts").isBoolean())
          throw new IllegalArgumentException("explicit feedback suppression boolean required");
        long window = 300;
        if (f.has("windowSeconds")) {
          if (!f.path("windowSeconds").isIntegralNumber()
              || !f.path("windowSeconds").canConvertToLong())
            throw new IllegalArgumentException("integral feedback window required");
          window = f.path("windowSeconds").asLong();
        }
        int depth = 1;
        if (f.has("maxDepth")) {
          if (!f.path("maxDepth").isIntegralNumber() || !f.path("maxDepth").canConvertToInt())
            throw new IllegalArgumentException("integral feedback depth required");
          depth = f.path("maxDepth").asInt();
        }
        feedback = new FeedbackPolicy(f.path("suppressPipelineStarts").asBoolean(), window, depth);
      }
      QueuePolicy queue = QueuePolicy.defaults();
      if (n.has("queue")) {
        JsonNode q = n.path("queue");
        Json.fields(q, "coalesce", "maxPendingEvents");
        int limit = 128;
        if (q.has("maxPendingEvents")) {
          if (!q.path("maxPendingEvents").isIntegralNumber()
              || !q.path("maxPendingEvents").canConvertToInt())
            throw new IllegalArgumentException("integral event queue bound required");
          limit = q.path("maxPendingEvents").asInt();
        }
        queue = new QueuePolicy(q.has("coalesce") ? Json.text(q, "coalesce") : "none", limit);
        if (queue.coalesce().equals("same-state") && !retention.enabled())
          throw new IllegalArgumentException(
              "coalescing requires an explicit retention/deduplication policy");
      }
      Map<String, Route> routes = new LinkedHashMap<>();
      JsonNode rn = n.path("routes");
      if (!rn.isMissingNode() && !rn.isObject())
        throw new IllegalArgumentException("routes object required");
      var re = rn.fields();
      while (re.hasNext()) {
        var e = re.next();
        name(e.getKey());
        JsonNode r = e.getValue();
        Json.fields(
            r,
            "entity",
            "above",
            "unit",
            "cooldownSeconds",
            "holdSeconds",
            "definition",
            "agent",
            "request",
            "completionAction");
        Double above = r.has("above") ? r.path("above").doubleValue() : null;
        if (above != null && (!r.path("above").isNumber() || !Double.isFinite(above)))
          throw new IllegalArgumentException("finite threshold required");
        long cooldown = r.path("cooldownSeconds").asLong(0);
        if (r.has("cooldownSeconds")
            && (!r.path("cooldownSeconds").isIntegralNumber()
                || !r.path("cooldownSeconds").canConvertToLong()
                || cooldown < 0
                || cooldown > 86400)) throw new IllegalArgumentException("invalid cooldown");
        long hold = r.path("holdSeconds").asLong(0);
        if (r.has("holdSeconds")
            && (!r.path("holdSeconds").isIntegralNumber()
                || !r.path("holdSeconds").canConvertToLong()))
          throw new IllegalArgumentException("integral held duration required");
        if ((above != null) != r.has("entity"))
          throw new IllegalArgumentException("entity and threshold must be supplied together");
        if (above == null && !n.has("script"))
          throw new IllegalArgumentException(
              "unconditional routes require an explicit script handler");
        routes.put(
            e.getKey(),
            new Route(
                e.getKey(),
                r.has("entity") ? Json.text(r, "entity") : null,
                above,
                r.has("unit") ? Json.text(r, "unit") : null,
                cooldown,
                Json.text(r, "definition"),
                Json.text(r, "agent"),
                Json.text(r, "request"),
                r.has("completionAction") ? Json.text(r, "completionAction") : null,
                hold));
      }
      Set<String> allowed = new HashSet<>();
      allowed.add(name);
      if (n.has("allowBindings")) {
        if (!n.path("allowBindings").isArray())
          throw new IllegalArgumentException("allowBindings array required");
        for (JsonNode target : n.path("allowBindings")) {
          if (!target.isTextual()) throw new IllegalArgumentException("binding name required");
          allowed.add(target.asText());
        }
      }
      String script = null;
      if (n.has("script")) {
        Path path = base.resolve(Json.text(n, "script")).normalize();
        if (!path.startsWith(base)
            || Files.isSymbolicLink(path)
            || !path.toRealPath().startsWith(base.toRealPath()))
          throw new IllegalArgumentException("script must be within configuration directory");
        if (Files.size(path) > Json.MAX_MESSAGE / 2)
          throw new IllegalArgumentException("script exceeds limit");
        script = Files.readString(path);
      }
      if (!n.path("configuration").isObject())
        throw new IllegalArgumentException("adapter configuration object required");
      String adapterName = Json.text(n, "adapter");
      AdapterFactory factory = factories.get(adapterName);
      if (factory == null)
        throw new IllegalArgumentException("configured adapter module is not installed");
      AdapterConfiguration settings = factory.decode(n.path("configuration").toString());
      bindings.put(
          name,
          new Binding(
              name,
              Json.text(n, "adapter"),
              Json.text(n, "project"),
              Json.text(n, "peer"),
              settings,
              Map.copyOf(routes),
              Set.copyOf(allowed),
              script,
              Json.hash(n) + ":" + Json.hash(script == null ? "" : script),
              queue,
              feedback));
    }
    for (Binding b : bindings.values())
      for (String target : b.allowBindings()) {
        Binding t = bindings.get(target);
        if (t == null || !t.project().equals(b.project()))
          throw new IllegalArgumentException("allowed bindings must exist in the same project");
      }
    return new Configuration(
        Json.text(root, "plowshare"),
        token,
        base.resolve(Json.text(root, "journal")).normalize(),
        Map.copyOf(bindings),
        retention);
  }

  public static void name(String name) {
    if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"))
      throw new IllegalArgumentException("invalid binding/peer name");
  }
}
