package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.information.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * All information controls use authenticated WebSocket frames. HTTP carries only existing multipart
 * bytes.
 */
@Component
public final class InformationFrames implements FrameArea {
  private final InformationNavigationService navigation;
  private final InformationInterchangeService interchange;

  private final InformationCatalogue catalogue;
  private final InformationAccess access;
  private final DocumentStore documents;
  private final RetrievalService retrieval;
  private final Deliberation deliberation;
  private final JobStore jobs;
  private final DocumentsProperties properties;
  private final PageFetcher fetcher;
  private final InformationContentTypes contentTypes;
  private final InformationContentSettings contentSettings;

  public InformationFrames(
      InformationCatalogue catalogue,
      InformationAccess access,
      DocumentStore documents,
      RetrievalService retrieval,
      Deliberation deliberation,
      JobStore jobs,
      DocumentsProperties properties,
      PageFetcher fetcher,
      InformationContentTypes contentTypes,
      InformationContentSettings contentSettings,
      InformationNavigationService navigation,
      InformationInterchangeService interchange) {
    this.interchange = java.util.Objects.requireNonNull(interchange);
    this.navigation = java.util.Objects.requireNonNull(navigation);
    this.catalogue = catalogue;
    this.access = access;
    this.documents = documents;
    this.retrieval = retrieval;
    this.deliberation = deliberation;
    this.jobs = jobs;
    this.properties = properties;
    this.fetcher = fetcher;
    this.contentTypes = java.util.Objects.requireNonNull(contentTypes);
    this.contentSettings = java.util.Objects.requireNonNull(contentSettings);
  }

  private InformationAcquisitions acquisitions;

  @org.springframework.beans.factory.annotation.Autowired
  public void useAcquisitions(InformationAcquisitions acquisitions) {
    this.acquisitions = acquisitions;
  }

  private DocumentPolicyAssignments migration;

  @org.springframework.beans.factory.annotation.Autowired
  public void useMigration(DocumentPolicyAssignments migration) {
    this.migration = migration;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    Map<String, FrameHandler> handlers = new LinkedHashMap<>();
    for (String verb : io.aeyer.plowshare.protocol.frames.InformationOperations.ALL) {
      String type = "information." + verb;
      handlers.put(
          type,
          (payload, asking) -> {
            try {
              return handle(verb, payload == null ? Map.of() : payload, asking);
            } catch (InformationMetadataFault invalid) {
              return new Outcome(
                  Code.VALIDATION_FAILED, invalid.getMessage(), invalid.validation());
            }
          });
    }
    return Map.copyOf(handlers);
  }

  private Outcome handle(String verb, Map<String, Object> payload, Asking asking) {
    // Validate the original selector before typed DTO construction can normalize an identity.
    // Navigation/interchange still require their own explicit scope and command contracts.
    var selection = verb.startsWith("migration.") ? null : InformationScopes.from(payload);
    if (selection != null
        && selection.scope() == InformationContext.Scope.ACCOUNT
        && (!java.util.Set.of(
                    "upload",
                    "list",
                    "status",
                    "await",
                    "read",
                    "tags",
                    "tags.groups",
                    "exclude",
                    "unexclude",
                    "withdraw",
                    "restore",
                    "delete",
                    "retry",
                    "rebuild",
                    "allowance",
                    "share",
                    "unshare")
                .contains(verb)
            || payload.containsKey("typing")
            || payload.containsKey("expectedRevision")))
      throw new CallerFault(
          "This information operation does not support account knowledge targets");
    if (verb.startsWith("interchange.")) {
      return Outcome.ok(
          interchange.execute(
              asking.requireHandle("information." + verb),
              InformationInterchangeInputs.command(verb, payload),
              asking.sessionId()));
    }
    if (InformationNavigationInputs.supports(verb)) {
      return Outcome.ok(
          navigation.execute(
              asking.requireHandle("information." + verb),
              InformationNavigationInputs.command(verb, payload)));
    }

    InformationContext context =
        access
            .resolve(asking.requireHandle("information." + verb), selection)
            .withCorpus(
                io.aeyer.plowshare.server.information.InformationInputs.corpus(
                    payload.get("corpus")))
            .withView(InformationContentInputs.view(payload.get("view")));
    if (List.of("list", "facets", "search", "rank").contains(verb))
      context =
          context.withFacets(
              io.aeyer.plowshare.server.information.InformationInputs.facets(
                  payload.get("filter")));
    if (!context.typedView()
        && (context.facets().contentType() != null
            || context.facets().contentTypeVersion() != null
            || context.facets().untyped() != null)) throw new CallerFault("unsupported-view");
    if (verb.equals("share") || verb.equals("unshare")) access.requireAudience(context);
    else if (io.aeyer.plowshare.server.access.ProjectAuthorization.required("information." + verb)
            != io.aeyer.plowshare.server.archive.ProjectRole.VIEWER
        && !verb.startsWith("migration.")) access.requireWork(context);
    Object result;
    if (verb.equals("types.capabilities"))
      return Outcome.ok(
          new io.aeyer.plowshare.protocol.InformationContent.Capabilities(
              io.aeyer.plowshare.protocol.InformationContent.CAPABILITY,
              io.aeyer.plowshare.protocol.InformationContent.VIEW,
              io.aeyer.plowshare.protocol.DocumentMetadata.PROFILE,
              contentSettings.writesEnabled()));
    if (verb.startsWith("types.") || verb.startsWith("content.")) {
      var command = InformationContentInputs.command(verb, payload, context.selection());
      return Outcome.ok(
          switch (command) {
            case io.aeyer.plowshare.protocol.InformationRequest.TypeList q ->
                contentTypes.list(
                    context,
                    Boolean.TRUE.equals(q.drafts()),
                    q.limit() == null ? 50 : q.limit(),
                    q.offset() == null ? 0 : q.offset());
            case io.aeyer.plowshare.protocol.InformationRequest.TypeRead q ->
                Boolean.TRUE.equals(q.publishedOnly())
                    ? contentTypes.published(context, q.version(), q.attachedRevision())
                    : contentTypes.get(context, q.version(), q.attachedRevision());
            case io.aeyer.plowshare.protocol.InformationRequest.TypeValidate q ->
                Boolean.TRUE.equals(q.publishedOnly())
                    ? io.aeyer.plowshare.protocol.DocumentMetadata.validate(
                        contentTypes.published(context, q.version(), q.attachedRevision()).schema(),
                        q.metadata())
                    : contentTypes.validate(
                        context, q.version(), q.attachedRevision(), q.metadata());
            case io.aeyer.plowshare.protocol.InformationRequest.TypeDraft q ->
                contentTypes.create(
                    context,
                    q.requestId(),
                    q.type(),
                    q.expectedControlRevision(),
                    q.slug(),
                    q.label(),
                    q.description(),
                    q.schema(),
                    q.presentation(),
                    asking.sessionId());
            case io.aeyer.plowshare.protocol.InformationRequest.TypeTransition q ->
                contentTypes.transition(
                    context,
                    q.requestId(),
                    q.version(),
                    q.expectedControlRevision(),
                    q.activate(),
                    asking.sessionId());
            case io.aeyer.plowshare.protocol.InformationRequest.ContentRead q ->
                catalogue.content(context, q.revision());
            case io.aeyer.plowshare.protocol.InformationRequest.ContentRevise q ->
                catalogue.reviseMetadata(
                    context, q.requestId(), q.expectedRevision(), q.typing(), asking.sessionId());
            default -> throw new IllegalStateException("unexpected authored-information request");
          });
    }
    switch (verb) {
      case "migration.list" -> {
        if (migration == null) throw new CallerFault("migration is unavailable");
        result =
            migration.inventory(
                context.account(), number(payload, "limit", 50), number(payload, "offset", 0));
      }
      case "migration.inspect" ->
          result =
              migration.inspect(
                  context.account(),
                  required(payload, "payload"),
                  required(payload, "reason"),
                  number(payload, "limit", 100),
                  number(payload, "offset", 0));
      case "migration.release" -> {
        migration.release(
            context.account(),
            required(payload, "payload"),
            required(payload, "owner"),
            access.resolve(context.account(), InformationScopes.from(payload)).selection(),
            uuids(payload, "inputs"),
            required(payload, "reason"),
            uuid(payload, "requestId"),
            asking.sessionId());
        result = Map.of("released", true);
      }
      case "migration.adopt" -> {
        if (migration == null) throw new CallerFault("migration is unavailable");
        InformationContext.Scope visibility;
        try {
          visibility =
              InformationContext.Scope.valueOf(
                  required(payload, "visibility").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
          throw new CallerFault("visibility must be personal, project or shared");
        }
        migration.assign(
            context.account(),
            uuid(payload, "revision"),
            required(payload, "owner"),
            visibility,
            payload.get("collectionProject") instanceof String p ? p : null,
            required(payload, "reason"),
            uuid(payload, "requestId"),
            asking.sessionId());
        result = Map.of("adopted", true);
      }
      case "upload", "revise", "replace" -> {
        if (!verb.equals("upload")) {
          UUID revision = uuid(payload, "revision");
          String name = catalogue.revisionName(context, revision);
          if (payload.containsKey("name") && !name.equals(required(payload, "name")))
            throw new CallerFault("revision name must match the existing resource");
          payload = new LinkedHashMap<>(payload);
          payload.put("name", name);
        }
        var admitted =
            catalogue.admitForSession(
                context,
                uuid(payload, "requestId"),
                required(payload, "name"),
                required(payload, "text").getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8",
                null,
                asking.sessionId(),
                InformationContentInputs.expected(payload),
                InformationContentInputs.typing(payload));
        catalogue.callerSession(admitted, asking.sessionId());
        return new Outcome(Code.ACCEPTED, null, admitted);
      }
      case "acquire", "refresh" -> {
        if (verb.equals("acquire")
            && (payload.containsKey("typing") || payload.containsKey("expectedRevision")))
          throw new CallerFault(
              "URL acquisition creates an untyped source; assign metadata after import");
        if (verb.equals("refresh")
            && payload.containsKey("typing")
            && !uuid(payload, "revision").equals(InformationContentInputs.expected(payload)))
          throw new CallerFault("expectedRevision must match refresh revision");
        if (verb.equals("refresh")) catalogue.revisionName(context, uuid(payload, "revision"));
        String url =
            verb.equals("refresh")
                ? catalogue.status(context, uuid(payload, "revision")).sourceUri()
                : required(payload, "url");
        if (url == null || url.isBlank())
          throw new CallerFault("this revision has no acquisition URL");
        String name =
            verb.equals("refresh")
                ? catalogue.revisionName(context, uuid(payload, "revision"))
                : payload.get("name") instanceof String n && !n.isBlank() ? n : url;
        if (acquisitions == null) throw new CallerFault("durable acquisition is unavailable");
        return new Outcome(
            Code.ACCEPTED,
            null,
            acquisitions.submit(
                context,
                uuid(payload, "requestId"),
                url,
                name,
                asking.sessionId(),
                InformationContentInputs.expected(payload),
                InformationContentInputs.typing(payload)));
      }
      case "inventory" ->
          result =
              catalogue.inventory(
                  context, number(payload, "limit", 20), number(payload, "offset", 0));
      case "acquisitions" ->
          result =
              acquisitions.list(
                  context, number(payload, "limit", 20), number(payload, "offset", 0));
      case "tags.groups" -> {
        if (!payload.containsKey("groups"))
          throw new CallerFault(
              "tagGroups requires groups (an object or null for automatic grouping)");
        catalogue.tagGroups(
            context,
            uuid(payload, "revision"),
            payload.get("groups") == null
                ? null
                : io.aeyer.plowshare.server.information.InformationTagGroups.from(
                    payload.get("groups"), null),
            uuid(payload, "requestId"));
        result = Map.of("changed", true);
      }
      case "tags" -> {
        catalogue.tags(
            context,
            uuid(payload, "revision"),
            io.aeyer.plowshare.server.information.InformationFacets.tags(payload.get("tags")),
            uuid(payload, "requestId"));
        result = Map.of("changed", true);
      }
      case "facets" ->
          result =
              catalogue.facets(
                  context, payload.containsKey("kind") ? required(payload, "kind") : null);
      case "list" ->
          result =
              catalogue.list(
                  context,
                  number(payload, "limit", 20),
                  number(payload, "offset", 0),
                  payload.containsKey("kind") ? required(payload, "kind") : null);
      case "status" ->
          result =
              payload.containsKey("acquisition")
                  ? acquisitions.status(context, uuid(payload, "acquisition"))
                  : catalogue.status(context, uuid(payload, "revision"));
      case "await" ->
          result =
              InformationReadiness.await(
                  catalogue,
                  context,
                  io.aeyer.plowshare.server.information.InformationReadinessDecoder.sources(
                      payload.get("sources")),
                  number(payload, "waitMs", 30000));
      case "read" ->
          result =
              catalogue.window(
                  context,
                  uuid(payload, "revision"),
                  number(payload, "offset", 0),
                  number(payload, "limit", 8192));
      case "outline" ->
          result =
              catalogue.outline(
                  context,
                  uuid(payload, "revision"),
                  number(payload, "offset", 0),
                  number(payload, "limit", 50));
      case "symbols" ->
          result =
              catalogue.symbols(
                  context,
                  required(payload, "query"),
                  payload.containsKey("revision") ? uuid(payload, "revision") : null,
                  number(payload, "offset", 0),
                  number(payload, "limit", 20));
      case "search" ->
          result =
              queryReader(context, asking)
                  .retrieve(
                      required(payload, "query"),
                      payload.containsKey("revision") ? uuid(payload, "revision") : null,
                      number(payload, "limit", 10));
      case "rank" ->
          result =
              queryReader(context, asking)
                  .rank(required(payload, "query"), number(payload, "limit", 10));
      case "ask" -> {
        UUID revision = uuid(payload, "revision");
        catalogue.requireReadable(context, revision);
        Corpus.theOneToAsk(documents.scoped(access, context), revision);
        String question = required(payload, "question");
        var pass = deliberation.scoped(access, context, asking.sessionId());
        Budget budget = Budget.of(number(payload, "maxModelCalls", properties.getAskBudget()));
        Home home =
            context.selection().project() == null
                ? Home.global()
                : Home.of(context.selection().project());
        String job =
            jobs.submitInformation(
                "ask",
                home,
                context,
                List.of(revision),
                cancelled -> pass.ask(revision, question, budget, cancelled));
        return new Outcome(Code.ACCEPTED, null, Map.of("job", job, "revision", revision));
      }
      case "evidence.record" ->
          result =
              Map.of(
                  "evidence",
                  catalogue.evidenceForSession(
                      context,
                      uuid(payload, "revision"),
                      number(payload, "start", -1),
                      number(payload, "end", -1),
                      required(payload, "quote"),
                      required(payload, "locator"),
                      uuid(payload, "requestId"),
                      asking.sessionId()));
      case "evidence.read" -> result = catalogue.evidence(context, uuid(payload, "evidence"));
      case "record.report" -> {
        var admitted =
            catalogue.reportDetailsForSession(
                context,
                uuid(payload, "requestId"),
                required(payload, "name"),
                required(payload, "text"),
                uuids(payload, "inputs"),
                uuids(payload, "evidence"),
                payload.containsKey("feedback") ? uuid(payload, "feedback") : null,
                asking.sessionId(),
                io.aeyer.plowshare.server.information.InformationReportDetailsDecoder.from(payload),
                null,
                InformationContentInputs.expected(payload),
                InformationContentInputs.typing(payload));
        catalogue.callerSession(admitted, asking.sessionId());
        return new Outcome(Code.ACCEPTED, null, admitted);
      }
      case "finalise" -> {
        catalogue.finalise(context, uuid(payload, "revision"), uuid(payload, "requestId"));
        result = Map.of("finalised", true);
      }
      case "link", "unlink" -> {
        catalogue.link(
            context,
            uuid(payload, "revision"),
            required(payload, "collectionProject"),
            verb.equals("unlink"),
            uuid(payload, "requestId"));
        result = Map.of("changed", true);
      }
      case "share", "unshare" -> {
        catalogue.share(
            context, uuid(payload, "revision"), verb.equals("share"), uuid(payload, "requestId"));
        result = Map.of("changed", true);
      }
      case "withdraw", "exclude", "unexclude", "restore", "delete" -> {
        String state =
            switch (verb) {
              case "withdraw" -> "withdrawn";
              case "exclude" -> "excluded";
              case "unexclude" -> "included";
              case "delete" -> "deleted";
              default -> "active";
            };
        catalogue.availability(
            context, uuid(payload, "revision"), state, uuid(payload, "requestId"));
        result = Map.of("availability", state);
      }
      case "events" ->
          result =
              catalogue.events(context, number(payload, "after", 0), number(payload, "limit", 50));
      case "allowance" -> {
        catalogue.allowance(
            context,
            uuid(payload, "revision"),
            number(payload, "maxModelCalls", -1),
            uuid(payload, "requestId"));
        result = Map.of("changed", true);
      }
      case "rebuild" -> {
        catalogue.rebuild(
            context,
            uuid(payload, "revision"),
            required(payload, "stage"),
            uuid(payload, "requestId"),
            stages(payload));
        result = Map.of("queued", true);
      }
      case "retry" -> {
        if (payload.containsKey("acquisition")) {
          acquisitions.retry(context, uuid(payload, "acquisition"));
          return Outcome.ok(Map.of("queued", true));
        }
        catalogue.retry(
            context, uuid(payload, "revision"), uuid(payload, "requestId"), stages(payload));
        result = Map.of("queued", true);
      }
      default -> throw new IllegalStateException("unregistered information operation");
    }
    return Outcome.ok(result);
  }

  private RetrievalService queryReader(InformationContext context, Asking asking) {
    var reader = retrieval.scoped(access, context);
    return reader.queriesEnabled()
        ? reader.forQueries(
            io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin.request(
                asking.queryValidity()))
        : reader;
  }

  private static List<String> stages(Map<String, Object> payload) {
    if (!payload.containsKey("stages")) return null;
    if (!(payload.get("stages") instanceof List<?> values)
        || values.isEmpty()
        || values.size() > 7
        || values.stream().anyMatch(value -> !(value instanceof String)))
      throw new CallerFault("invalid selected stages");
    return values.stream().map(String.class::cast).toList();
  }

  private static String required(Map<String, Object> payload, String key) {
    return Payloads.required(payload, key, "information", "a nonblank string");
  }

  private static UUID uuid(Map<String, Object> payload, String key) {
    try {
      return UUID.fromString(required(payload, key));
    } catch (IllegalArgumentException malformed) {
      throw new CallerFault(key + " must be a UUID");
    }
  }

  private static int number(Map<String, Object> payload, String key, int fallback) {
    Object value = payload.get(key);
    if (value == null) return fallback;
    if (!(value instanceof Number n) || n.doubleValue() != n.intValue())
      throw new CallerFault(key + " must be an integer");
    return n.intValue();
  }

  private static List<UUID> uuids(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    if (value == null) return List.of();
    if (!(value instanceof List<?> list))
      throw new CallerFault(key + " must be a list of revision or evidence UUIDs");
    return list.stream()
        .map(
            id -> {
              try {
                return UUID.fromString((String) id);
              } catch (RuntimeException invalid) {
                throw new CallerFault(key + " must contain UUID strings");
              }
            })
        .distinct()
        .toList();
  }
}
