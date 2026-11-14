package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.InformationDiscovery;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.information.InformationActor;
import io.aeyer.plowshare.server.information.InformationDiscoveryInputs;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Audit;
import io.aeyer.plowshare.server.information.InformationDiscoveryRepository.Reader;
import io.aeyer.plowshare.server.information.InformationDiscoveryService;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Small model-facing read tools over the same authenticated discovery capability as WebSocket. */
public final class InformationDiscoveryTool implements AgentTool {
  public static final Set<String> NAMES =
      Set.of("information_scopes", "information_list", "information_search", "information_facets");
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final String operation;
  private final Supplier<InformationDiscoveryService> service;
  private InformationActor actor;
  private InformationJobs inputs;
  private String log;
  private String boundaryHome;
  private Runnable validity;

  public InformationDiscoveryTool(String operation, Supplier<InformationDiscoveryService> service) {
    if (!NAMES.contains("information_" + operation))
      throw new IllegalArgumentException("Unknown discovery tool");
    this.operation = operation;
    this.service = service;
  }

  public InformationDiscoveryTool forRun(
      InformationActor actor,
      InformationJobs inputs,
      String log,
      String boundaryHome,
      Runnable validity) {
    var bound = new InformationDiscoveryTool(operation, service);
    bound.actor = actor;
    bound.inputs = inputs;
    bound.log = log;
    bound.boundaryHome = boundaryHome;
    bound.validity = validity;
    return bound;
  }

  public ToolSchema schema() {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 10));
    fields.put(
        "cursor",
        Map.of(
            "type",
            "string",
            "maxLength",
            2092,
            "description",
            "Opaque nextCursor from the same query. Omit on the first page."));
    if (!operation.equals("scopes")) {
      fields.put(
          "scopes",
          Map.of(
              "type",
              "array",
              "items",
              Map.of("type", "string"),
              "minItems",
              1,
              "maxItems",
              32,
              "description",
              "Exact readable scope IDs returned by information_scopes; selection grants no access."));
      fields.put(
          "filter",
          Map.of(
              "type",
              "object",
              "description",
              "Intersect kind source/report, tags (all), autoTag, tagGroup, author, documentAuthor, when (UTC year/month), subtype, contentType, contentTypeVersion or untyped. Use query on information_search for text."));
    }
    if (operation.equals("search"))
      fields.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 512));
    if (operation.equals("facets"))
      fields.put(
          "facet",
          Map.of(
              "type",
              "string",
              "enum",
              List.of("kind", "tags", "autoTag", "tagGroup", "documentAuthor", "subtype", "when")));
    List<String> required =
        switch (operation) {
          case "scopes" -> List.of();
          case "search" -> List.of("scopes", "query");
          case "facets" -> List.of("scopes", "facet");
          default -> List.of("scopes");
        };
    String description =
        switch (operation) {
          case "scopes" ->
              "Discover readable information scopes and their project names under current RBAC and credential limits. Page with nextCursor; this does not change your execution or write home.";
          case "list" ->
              "Browse current document and report heads across explicit readable scopes. Returns bounded metadata, real tags, report status and indexing coverage. Follow nextCursor until hasMore=false before claiming a complete inventory or absence; drafts are reports too.";
          case "search" ->
              "Find current documents and reports across explicit readable scopes by lexical names, titles, tags and indexed content. Results are newest first, not relevance-ranked passages. Page with nextCursor; contentIndexed=false means content search cannot establish absence. Read exact revisions using information_read with operation read and the same scopes.";
          default ->
              "Discover actual facet values and counts across current readable document heads in selected scopes and filters. Select one facet and page values with nextCursor. Counts reflect live authorization, not inferred keywords or headings.";
        };
    return ToolSchema.from(
        "information_" + operation,
        description,
        Map.of(
            "type",
            "object",
            "properties",
            fields,
            "required",
            required,
            "additionalProperties",
            false));
  }

  public String run(String arguments, Home home) {
    try {
      if (actor == null || inputs == null || validity == null || log == null)
        throw new CallerFault("Information discovery requires an authenticated durable run");
      validity.run();
      inputs.requireLog(log, actor.attribution());
      Map<String, Object> fields =
          JSON.readValue(arguments, new TypeReference<Map<String, Object>>() {});
      var reader = new Reader(actor.acting(), boundaryHome, new Audit(log, actor.attribution()));
      Object result;
      if (operation.equals("scopes")) {
        result =
            service
                .get()
                .scopes(
                    reader,
                    InformationDiscoveryInputs.decode(
                        fields, InformationDiscovery.ScopesRequest.class));
      } else {
        var query = InformationDiscoveryInputs.query(operation, fields);
        result =
            switch (operation) {
              case "list" -> service.get().list(reader, query);
              case "search" -> service.get().search(reader, query);
              default -> service.get().facets(reader, query);
            };
      }
      inputs.requireLog(log, actor.attribution());
      return JSON.writeValueAsString(result);
    } catch (CallerFault | NotFoundFault invalid) {
      return invalid.getMessage();
    } catch (IOException invalid) {
      return "Invalid information discovery JSON";
    }
  }
}
