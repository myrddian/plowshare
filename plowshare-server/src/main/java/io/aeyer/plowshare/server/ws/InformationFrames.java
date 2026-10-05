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
  private final InformationCatalogue catalogue;
  private final InformationAccess access;
  private final DocumentStore documents;
  private final RetrievalService retrieval;
  private final Deliberation deliberation;
  private final JobStore jobs;
  private final DocumentsProperties properties;
  private final PageFetcher fetcher;

  public InformationFrames(
      InformationCatalogue catalogue,
      InformationAccess access,
      DocumentStore documents,
      RetrievalService retrieval,
      Deliberation deliberation,
      JobStore jobs,
      DocumentsProperties properties,
      PageFetcher fetcher) {
    this.catalogue = catalogue;
    this.access = access;
    this.documents = documents;
    this.retrieval = retrieval;
    this.deliberation = deliberation;
    this.jobs = jobs;
    this.properties = properties;
    this.fetcher = fetcher;
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
          type, (payload, asking) -> handle(verb, payload == null ? Map.of() : payload, asking));
    }
    return Map.copyOf(handlers);
  }

  private Outcome handle(String verb, Map<String, Object> payload, Asking asking) {
    InformationContext context =
        access
            .resolve(
                asking.requireHandle("information." + verb),
                verb.startsWith("migration.") ? null : InformationScopes.from(payload))
            .withCorpus(
                io.aeyer.plowshare.server.information.InformationInputs.corpus(
                    payload.get("corpus")));
    if (List.of("list", "facets", "search", "rank").contains(verb))
      context =
          context.withFacets(
              io.aeyer.plowshare.server.information.InformationInputs.facets(
                  payload.get("filter")));
    if (io.aeyer.plowshare.server.access.ProjectAuthorization.required("information." + verb)
            != io.aeyer.plowshare.server.archive.ProjectRole.VIEWER
        && !verb.startsWith("migration.")) access.requireWork(context);
    Object result;
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
            InformationScopes.from(payload),
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
                asking.sessionId());
        catalogue.callerSession(admitted, asking.sessionId());
        return new Outcome(Code.ACCEPTED, null, admitted);
      }
      case "acquire", "refresh" -> {
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
                context, uuid(payload, "requestId"), url, name, asking.sessionId()));
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
              retrieval
                  .scoped(access, context)
                  .retrieve(
                      required(payload, "query"),
                      payload.containsKey("revision") ? uuid(payload, "revision") : null,
                      number(payload, "limit", 10));
      case "rank" ->
          result =
              retrieval
                  .scoped(access, context)
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
                null);
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
            uuid(payload, "requestId"));
        result = Map.of("queued", true);
      }
      case "retry" -> {
        if (payload.containsKey("acquisition")) {
          acquisitions.retry(context, uuid(payload, "acquisition"));
          return Outcome.ok(Map.of("queued", true));
        }
        catalogue.retry(context, uuid(payload, "revision"), uuid(payload, "requestId"));
        result = Map.of("queued", true);
      }
      default -> throw new IllegalStateException("unregistered information operation");
    }
    return Outcome.ok(result);
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
