package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.sdk.Plowshare;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** External runtime launcher. Adapter modules are explicit classpath dependencies. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    boolean check = args.length == 2 && args[0].equals("--check");
    boolean preflight = args.length == 2 && args[0].equals("--preflight");
    boolean inspect = args.length == 2 && args[0].equals("--inspect");
    boolean prune = args.length >= 3 && args[0].equals("--prune");
    if (args.length != 1 && !check && !inspect && !prune && !preflight)
      throw new IllegalArgumentException(
          "Usage: plowshare-integrations [--check|--inspect|--prune|--preflight] CONFIG.json"
              + " [settled-entry-id ...]");
    Configuration config = Configuration.read(Path.of(args[args.length == 1 ? 0 : 1]));
    if (preflight) {
      if (!Files.isRegularFile(config.journal().resolve("journal.json")))
        throw new IllegalArgumentException("preflight requires an existing journal copy");
      try (Journal journal = new Journal(config.journal())) {
        journal.verifyDtos();
        System.out.println(
            "Retained integration journal DTOs are compatible; no transports opened or records rewritten.");
      }
      return;
    }
    if (inspect || prune) {
      try (Journal journal = new Journal(config.journal())) {
        if (prune) journal.prune(new HashSet<>(Arrays.asList(args).subList(2, args.length)));
        System.out.println(
            Json.object()
                .put("records", journal.records().size())
                .put("retainedIdentities", journal.tombstoneCount())
                .put("causalContexts", journal.causeCount()));
        for (var entry : journal.records()) {
          var record = entry.getValue();
          var view = Json.object().put("id", entry.getKey());
          if (record.has("causalDiagnostic"))
            view.set("causalDiagnostic", record.path("causalDiagnostic"));
          for (String field : List.of("kind", "binding", "status", "run", "diagnostic", "state"))
            if (record.has(field)) view.set(field, record.path(field));
          System.out.println(view);
        }
      }
      return;
    }
    String token = System.getenv(config.tokenEnv());
    if (!check && (token == null || token.isBlank()))
      throw new IllegalArgumentException("configured Plowshare credential is missing");
    Map<String, AdapterFactory> factories = new HashMap<>();
    for (AdapterFactory f : ServiceLoader.load(AdapterFactory.class))
      if (factories.put(f.name(), f) != null)
        throw new IllegalArgumentException("duplicate adapter factory");
    Map<String, IntegrationAdapter> adapters = new LinkedHashMap<>();
    try {
      for (var b : config.bindings().values()) {
        AdapterFactory f = factories.get(b.adapter());
        if (f == null)
          throw new IllegalArgumentException("configured adapter module is not installed");
        adapters.put(
            b.name(),
            f.create(
                b.configuration(), check ? ignored -> "validation-placeholder" : System::getenv));
        for (var route : b.routes().values()) {
          if (route.entity() != null)
            adapters
                .get(b.name())
                .validate(
                    "states.read",
                    new io.aeyer.plowshare.protocol.IntegrationPayload.Arguments(
                        List.of(route.entity()), null, null));
          if (route.completionAction() != null)
            adapters
                .get(b.name())
                .validate(
                    "actions.execute",
                    new io.aeyer.plowshare.protocol.IntegrationPayload.Arguments(
                        null,
                        route.completionAction(),
                        Map.of(
                            "message",
                            new io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter(
                                "Configuration check"))));
        }
      }
      if (check) {
        ScriptHost scripts = new ScriptHost(Duration.ofSeconds(5));
        for (var b : config.bindings().values())
          if (b.script() != null)
            scripts.evaluate(
                b.script(),
                "",
                new IntegrationContracts.EventEnvelope(new IntegrationContracts.Empty()),
                IntegrationContracts.ScriptContext.empty());
        System.out.println(
            "Configuration and registered scripts are valid; no connections opened.");
        return;
      }
      try (Journal journal = new Journal(config.journal());
          var gateway = connect(config, token, journal);
          var runtime =
              new IntegrationRuntime(
                  config,
                  adapters,
                  gateway,
                  journal,
                  new ScriptHost(Duration.ofSeconds(5)),
                  Instant::now)) {
        runtime.start();
        while (!Thread.currentThread().isInterrupted()) {
          runtime.tick();
          Thread.sleep(500);
        }
      }
    } finally {
      adapters.values().forEach(IntegrationAdapter::close);
    }
  }

  private static Gateway connect(Configuration config, String token, Journal journal)
      throws Exception {
    journal.verifyDtos();
    journal.bindOwner(Json.hash(config.plowshare() + "\n" + token));
    return new Gateway.Sdk(
        Plowshare.connect(config.plowshare(), token, Duration.ofSeconds(30), null));
  }
}
