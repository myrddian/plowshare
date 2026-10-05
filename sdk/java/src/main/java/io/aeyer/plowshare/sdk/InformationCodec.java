package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.*;
import java.io.IOException;
import java.util.*;

/** CLI/tool serialization boundary. Only validated commands and results leave this codec. */
public final class InformationCodec {
  private static final ObjectMapper JSON = SdkJson.mapper();
  private static final ObjectMapper REQUESTS =
      SdkJson.mapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private InformationCodec() {}

  /** Decode an operation's serialized external input before contacting the server. */
  public static InformationRequest decode(String operation, String input) throws IOException {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(input, "input");
    if (input.length() > 10 * 1024 * 1024)
      throw new IOException("information input exceeds 10 MiB");
    JsonNode parsed = REQUESTS.readTree(input);
    if (!(parsed instanceof ObjectNode fields))
      throw new IOException("information input must be an object");
    if (input.length() > 10 * 1024 * 1024)
      throw new IOException("information input exceeds 10 MiB");
    ObjectNode body = fields.deepCopy();
    JsonNode scope = body.remove("scope"), project = body.remove("project");
    if (scope == null || scope.isNull()) {
      var selected = REQUESTS.createObjectNode();
      selected.put("kind", project == null || project.isNull() ? "personal" : "project");
      if (project != null && !project.isNull()) selected.set("project", project);
      scope = selected;
    } else if (project != null && !project.isNull())
      throw new IOException("use scope or project, not both");
    var selection = REQUESTS.createObjectNode();
    selection.set("scope", scope);
    JsonNode corpus = body.remove("corpus"), filter = body.remove("filter");
    if (corpus != null) selection.set("corpus", corpus);
    if (filter != null) selection.set("filter", filter);
    body.set("selection", selection);
    Class<? extends InformationRequest> shape =
        switch (operation) {
          case "upload", "revise", "replace" -> {
            action(body, operation);
            yield InformationRequest.Admission.class;
          }
          case "acquire", "refresh" -> {
            action(body, operation);
            yield InformationRequest.Acquisition.class;
          }
          case "list", "inventory", "acquisitions", "migration.list" -> {
            action(body, operation);
            yield InformationRequest.Browse.class;
          }
          case "facets" -> InformationRequest.Facets.class;
          case "status" -> InformationRequest.Status.class;
          case "await" -> InformationRequest.Await.class;
          case "read" -> InformationRequest.Read.class;
          case "outline" -> InformationRequest.Outline.class;
          case "symbols" -> InformationRequest.Symbols.class;
          case "search" -> InformationRequest.Search.class;
          case "rank" -> InformationRequest.Rank.class;
          case "ask" -> InformationRequest.Ask.class;
          case "evidence.record" -> InformationRequest.EvidenceRecord.class;
          case "evidence.read" -> InformationRequest.EvidenceRead.class;
          case "record.report" -> {
            var details = REQUESTS.createObjectNode();
            for (String field : List.of("objectives", "findings", "reviews", "scopeChanges")) {
              JsonNode value = body.remove(field);
              if (value != null) details.set(field, value);
            }
            if (!details.isEmpty()) body.set("details", details);
            yield InformationRequest.ReportRecord.class;
          }
          case "finalise",
              "share",
              "unshare",
              "withdraw",
              "exclude",
              "unexclude",
              "restore",
              "delete" -> {
            action(body, operation);
            yield InformationRequest.RevisionChange.class;
          }
          case "link", "unlink" -> {
            action(body, operation);
            yield InformationRequest.Link.class;
          }
          case "tags" -> InformationRequest.Tags.class;
          case "tags.groups" -> {
            if (!body.has("groups"))
              throw new IOException("groups is required, including null for automatic grouping");
            yield InformationRequest.TagGroups.class;
          }
          case "retry" -> InformationRequest.Retry.class;
          case "rebuild" -> InformationRequest.Rebuild.class;
          case "allowance" -> InformationRequest.Allowance.class;
          case "events" -> InformationRequest.Events.class;
          case "migration.inspect" -> InformationRequest.MigrationInspect.class;
          case "migration.release" -> InformationRequest.MigrationRelease.class;
          case "migration.adopt" -> InformationRequest.MigrationAdopt.class;
          default -> throw new IOException("unsupported information operation");
        };
    return SdkJson.decode(REQUESTS, body, shape);
  }

  private static void action(ObjectNode body, String operation) throws IOException {
    if (body.has("action")) throw new IOException("action is derived from the operation");
    body.put("action", operation.toUpperCase(Locale.ROOT).replace('.', '_'));
  }

  /** Serialize the validated command in the existing flattened WebSocket payload shape. */
  public static String encode(InformationRequest request) throws IOException {
    return JSON.writeValueAsString(fields(request));
  }

  static Map<String, Object> fields(InformationRequest request) {
    ObjectNode body = JSON.valueToTree(request);
    body.remove("action");
    body.remove("selection");
    ObjectNode selection = JSON.valueToTree(request.selection());
    body.setAll(selection);
    if (request instanceof InformationRequest.ReportRecord report) {
      body.remove("details");
      body.setAll((ObjectNode) JSON.valueToTree(report.details()));
    }
    // Optional nulls do not become supplied parameters, except groups whose null means automatic.
    var names = new ArrayList<String>();
    body.fieldNames().forEachRemaining(names::add);
    for (String name : names)
      if (body.get(name).isNull() && !name.equals("groups")) body.remove(name);
    return JSON.convertValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
  }

  /** Decode a successful wire payload. The request selects the response contract, never a peer. */
  public static InformationResponse response(InformationRequest request, String input)
      throws IOException {
    if (input.length() > 16 * 1024 * 1024)
      throw new IOException("information response exceeds 16 MiB");
    return response(request, JSON.readTree(input));
  }

  static InformationResponse response(InformationRequest request, JsonNode node)
      throws IOException {
    var result =
        switch (request) {
          case InformationRequest.Admission ignored ->
              read(node, InformationResponse.Admission.class);
          case InformationRequest.ReportRecord ignored ->
              read(node, InformationResponse.Admission.class);
          case InformationRequest.Acquisition ignored ->
              new InformationResponse.Acquisition(read(node, InformationAcquisition.class));
          case InformationRequest.Browse browse ->
              switch (browse.action()) {
                case LIST, INVENTORY ->
                    new InformationResponse.Revisions(list(node, Information.Revision.class));
                case ACQUISITIONS ->
                    new InformationResponse.Acquisitions(list(node, InformationAcquisition.class));
                case MIGRATION_LIST ->
                    new InformationResponse.MigrationInventory(
                        read(node, InformationMigration.Inventory.class));
              };
          case InformationRequest.Facets ignored ->
              new InformationResponse.Facets(read(node, Information.Facets.class));
          case InformationRequest.Status status ->
              status.acquisition() == null
                  ? new InformationResponse.Revision(read(node, Information.Revision.class))
                  : new InformationResponse.Acquisition(read(node, InformationAcquisition.class));
          case InformationRequest.Await ignored ->
              new InformationResponse.Readiness(read(node, Information.Readiness.class));
          case InformationRequest.Read ignored ->
              new InformationResponse.Window(read(node, Information.Window.class));
          case InformationRequest.Outline ignored ->
              new InformationResponse.Outline(read(node, Information.Outline.class));
          case InformationRequest.Symbols ignored ->
              new InformationResponse.Symbols(read(node, Information.Symbols.class));
          case InformationRequest.Search ignored ->
              new InformationResponse.SearchResult(list(node, InformationResponse.Retrieved.class));
          case InformationRequest.Rank ignored ->
              new InformationResponse.RankResult(read(node, InformationResponse.Ranking.class));
          case InformationRequest.Ask ignored -> read(node, InformationResponse.Asked.class);
          case InformationRequest.EvidenceRecord ignored ->
              read(node, InformationResponse.EvidenceRecorded.class);
          case InformationRequest.EvidenceRead ignored ->
              new InformationResponse.Evidence(read(node, Information.Evidence.class));
          case InformationRequest.Events ignored ->
              new InformationResponse.Events(read(node, Information.Events.class));
          case InformationRequest.RevisionChange changed ->
              switch (changed.action()) {
                case FINALISE -> read(node, InformationResponse.Finalised.class);
                case SHARE, UNSHARE -> read(node, InformationResponse.Changed.class);
                default -> read(node, InformationResponse.Availability.class);
              };
          case InformationRequest.Link ignored -> read(node, InformationResponse.Changed.class);
          case InformationRequest.Tags ignored -> read(node, InformationResponse.Changed.class);
          case InformationRequest.TagGroups ignored ->
              read(node, InformationResponse.Changed.class);
          case InformationRequest.Retry ignored -> read(node, InformationResponse.Queued.class);
          case InformationRequest.Rebuild ignored -> read(node, InformationResponse.Queued.class);
          case InformationRequest.Allowance ignored ->
              read(node, InformationResponse.Changed.class);
          case InformationRequest.MigrationInspect ignored ->
              new InformationResponse.MigrationInspection(
                  read(node, InformationMigration.Inspection.class));
          case InformationRequest.MigrationRelease ignored ->
              read(node, InformationResponse.Released.class);
          case InformationRequest.MigrationAdopt ignored ->
              read(node, InformationResponse.Adopted.class);
        };
    UUID expected =
        switch (request) {
          case InformationRequest.Status status ->
              status.revision() == null ? status.acquisition() : status.revision();
          case InformationRequest.Read read -> read.revision();
          case InformationRequest.Outline outline -> outline.revision();
          case InformationRequest.EvidenceRead evidence -> evidence.evidence();
          case InformationRequest.Ask ask -> ask.revision();
          default -> null;
        };
    UUID actual =
        switch (result) {
          case InformationResponse.Revision revision -> revision.value().id();
          case InformationResponse.Acquisition acquisition -> acquisition.value().id();
          case InformationResponse.Window window -> window.value().revision();
          case InformationResponse.Outline outline -> outline.value().revision();
          case InformationResponse.Evidence evidence -> evidence.value().id();
          case InformationResponse.Asked asked -> asked.revision();
          default -> null;
        };
    if (expected != null && !expected.equals(actual))
      throw new IOException("foreign information identity");
    return result;
  }

  private static <T> T read(JsonNode node, Class<T> shape) throws IOException {
    if (node == null || node.isNull())
      throw new IOException("required information result is absent");
    return SdkJson.decode(JSON, node, shape);
  }

  private static <T> List<T> list(JsonNode node, Class<T> shape) throws IOException {
    if (node == null || !node.isArray() || node.size() > 100)
      throw new IOException("invalid information page");
    var values = new ArrayList<T>();
    for (JsonNode item : node) values.add(read(item, shape));
    return List.copyOf(values);
  }
}
