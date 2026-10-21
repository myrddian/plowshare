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
    if (operation.startsWith("interchange.")
        && (fields.has("selection") || !fields.hasNonNull("scope")))
      throw new IOException("Interchange requires explicit scope");
    if (operation.startsWith("documents.")
        || operation.startsWith("relationships.")
        || operation.startsWith("policy.")
        || operation.startsWith("views.")
        || operation.equals("navigation.capabilities")) {
      if (!fields.has("scope")) throw new IOException("navigation requires explicit scope");
      navigationFields(fields);
    }
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
    JsonNode corpus = body.remove("corpus"),
        filter = body.remove("filter"),
        view = body.remove("view");
    if (view != null) selection.set("view", view);
    if (corpus != null) selection.set("corpus", corpus);
    if (filter != null) selection.set("filter", filter);
    body.set("selection", selection);
    JsonNode typing = body.get("typing");
    if (typing != null
        && typing.isObject()
        && (typing.has("metadata") && typing.get("metadata").isNull()
            || typing.has("version") && typing.get("version").isNull()))
      throw new IOException("Typing members cannot be explicitly null");
    Class<? extends InformationRequest> shape =
        switch (operation) {
          case "interchange.capabilities" -> InformationRequest.InterchangeCapabilities.class;
          case "interchange.export" -> InformationRequest.InterchangeExport.class;
          case "interchange.readable" -> InformationRequest.InterchangeReadable.class;
          case "interchange.import" -> InformationRequest.InterchangeImport.class;
          case "interchange.origin" -> InformationRequest.InterchangeOrigin.class;
          case "navigation.capabilities" -> InformationRequest.NavigationCapabilities.class;
          case "documents.list" -> InformationRequest.DocumentList.class;
          case "documents.facets" -> InformationRequest.DocumentFacets.class;
          case "documents.get" -> InformationRequest.DocumentGet.class;
          case "relationships.list" -> InformationRequest.RelationshipList.class;
          case "relationships.create" -> InformationRequest.RelationshipCreate.class;
          case "relationships.remove" -> InformationRequest.RelationshipRemove.class;
          case "policy.get" -> InformationRequest.NavigationPolicyGet.class;
          case "policy.set" -> InformationRequest.NavigationPolicySet.class;
          case "views.list" -> InformationRequest.NavigationViews.class;
          case "views.get" -> InformationRequest.NavigationViewGet.class;
          case "views.save" -> InformationRequest.NavigationViewSave.class;
          case "views.delete" -> InformationRequest.NavigationViewDelete.class;
          case "types.capabilities" -> InformationRequest.ContentCapabilities.class;
          case "content.get" -> InformationRequest.ContentRead.class;
          case "content.revise" -> InformationRequest.ContentRevise.class;
          case "types.list" -> InformationRequest.TypeList.class;
          case "types.get" -> InformationRequest.TypeRead.class;
          case "types.validate" -> InformationRequest.TypeValidate.class;
          case "types.version.create" -> InformationRequest.TypeDraft.class;
          case "types.activate", "types.deprecate" -> {
            body.put("activate", operation.equals("types.activate"));
            yield InformationRequest.TypeTransition.class;
          }
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
          case "tags.replace" -> InformationRequest.TagsReplace.class;
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

  private static void navigationFields(JsonNode value) throws IOException {
    if (value == null || value.isNull())
      throw new IOException("navigation members cannot be explicitly null");
    if (value.isObject()) {
      var entries = value.fields();
      while (entries.hasNext()) {
        var entry = entries.next();
        if (Set.of(
                    "resource",
                    "revision",
                    "source",
                    "requestId",
                    "expectedSourceRevision",
                    "relationship",
                    "id",
                    "relatedTo",
                    "otherResource")
                .contains(entry.getKey())
            && (!entry.getValue().isTextual()
                || !entry
                    .getValue()
                    .textValue()
                    .matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")))
          throw new IOException("navigation identifier must be a UUID");
        navigationFields(entry.getValue());
      }
    } else if (value.isArray()) {
      for (var item : value) navigationFields(item);
    }
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
    if (request instanceof InformationRequest.TypeTransition) body.remove("activate");
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
          case InformationRequest.InterchangeCapabilities ignored ->
              new InformationResponse.InterchangeCapabilities(
                  read(node, InformationInterchange.Capabilities.class));
          case InformationRequest.InterchangeExport ignored ->
              new InformationResponse.InterchangeBundle(
                  read(node, InformationInterchange.Bundle.class));
          case InformationRequest.InterchangeReadable ignored ->
              new InformationResponse.InterchangeReadable(
                  read(node, InformationInterchange.Readable.class));
          case InformationRequest.InterchangeImport ignored ->
              new InformationResponse.InterchangeImported(
                  read(node, InformationInterchange.Imported.class));
          case InformationRequest.InterchangeOrigin ignored ->
              new InformationResponse.InterchangeOrigin(
                  read(node, InformationInterchange.Origin.class));
          case InformationRequest.NavigationCapabilities ignored ->
              new InformationResponse.NavigationCapabilities(
                  read(node, InformationNavigation.Capabilities.class));
          case InformationRequest.DocumentList ignored ->
              new InformationResponse.NavigationDocuments(
                  read(node, InformationNavigation.Documents.class));
          case InformationRequest.DocumentGet ignored ->
              new InformationResponse.NavigationSelection(
                  read(node, InformationNavigation.Selection.class));
          case InformationRequest.RelationshipList ignored ->
              new InformationResponse.NavigationConnections(
                  read(node, InformationNavigation.Connections.class));
          case InformationRequest.RelationshipCreate ignored ->
              new InformationResponse.NavigationRelationship(
                  read(node, InformationNavigation.Relationship.class));
          case InformationRequest.RelationshipRemove ignored ->
              new InformationResponse.NavigationChanged(
                  read(node, InformationNavigation.Changed.class));
          case InformationRequest.NavigationPolicyGet ignored ->
              new InformationResponse.NavigationPolicy(
                  read(node, InformationNavigation.PolicyView.class));
          case InformationRequest.NavigationPolicySet ignored ->
              new InformationResponse.NavigationPolicy(
                  read(node, InformationNavigation.PolicyView.class));
          case InformationRequest.NavigationViews ignored ->
              new InformationResponse.NavigationViews(
                  read(node, InformationNavigation.Views.class));
          case InformationRequest.NavigationViewGet ignored ->
              new InformationResponse.NavigationView(
                  read(node, InformationNavigation.SavedView.class));
          case InformationRequest.NavigationViewSave ignored ->
              new InformationResponse.NavigationView(
                  read(node, InformationNavigation.SavedView.class));
          case InformationRequest.NavigationViewDelete ignored ->
              new InformationResponse.NavigationChanged(
                  read(node, InformationNavigation.Changed.class));
          case InformationRequest.DocumentFacets ignored ->
              new InformationResponse.Facets(read(node, Information.Facets.class));
          case InformationRequest.ContentCapabilities ignored ->
              new InformationResponse.ContentCapabilities(
                  read(node, InformationContent.Capabilities.class));
          case InformationRequest.ContentRead ignored ->
              new InformationResponse.Content(read(node, InformationContent.View.class));
          case InformationRequest.ContentRevise ignored ->
              read(node, InformationResponse.Admission.class);
          case InformationRequest.TypeList ignored ->
              new InformationResponse.Types(read(node, InformationContent.Versions.class));
          case InformationRequest.TypeRead ignored ->
              new InformationResponse.Type(read(node, InformationContent.Contract.class));
          case InformationRequest.TypeDraft ignored ->
              new InformationResponse.TypeVersion(read(node, InformationContent.Version.class));
          case InformationRequest.TypeTransition ignored ->
              new InformationResponse.TypeVersion(read(node, InformationContent.Version.class));
          case InformationRequest.TypeValidate ignored ->
              new InformationResponse.MetadataValidation(read(node, SchemaValidation.Result.class));
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
          case InformationRequest.TagsReplace ignored ->
              read(node, InformationResponse.Changed.class);
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
    if (request instanceof InformationRequest.InterchangeExport asked
        && result instanceof InformationResponse.InterchangeBundle exported) {
      var returned =
          exported.value().documents().stream()
              .map(doc -> doc.descriptor().revision())
              .collect(java.util.stream.Collectors.toSet());
      if (!returned.containsAll(asked.revisions()))
        throw new IOException("Portable export omitted a requested exact revision");
    }
    if (request instanceof InformationRequest.InterchangeImport asked
        && result instanceof InformationResponse.InterchangeImported imported) {
      var expectedForeign =
          asked.plan().bundle().documents().stream()
              .map(doc -> doc.descriptor().revision())
              .collect(java.util.stream.Collectors.toSet());
      var returned =
          imported.value().mappings().stream()
              .map(io.aeyer.plowshare.protocol.InformationInterchange.Mapping::foreignRevision)
              .collect(java.util.stream.Collectors.toSet());
      if (!imported.value().bundle().equals(asked.plan().bundle().id())
          || !expectedForeign.equals(returned)
          || returned.size() != imported.value().mappings().size())
        throw new IOException("Portable import receipt differs from requested bundle");
    }
    UUID expected =
        switch (request) {
          case InformationRequest.InterchangeReadable read -> read.revision();
          case InformationRequest.ContentRead read -> read.revision();
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
          case InformationResponse.InterchangeReadable readable ->
              readable.value().sidecar().revision();
          case InformationResponse.Content content -> content.value().revision();
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
