package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.applications.ContainedApplications;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * A container's own typed operations over the Applications confined inside it.
 *
 * <p>Strict conversion completes before any durable state or staged source is written, exactly as
 * {@code ApplicationDeploymentFrames.deploy} does: the payload becomes a typed {@code Create} whose
 * own constructor enforces its invariants, and a raw map never travels past this class. A missing,
 * mistyped or unexpected field is a refusal here, not a validation failure three layers down with a
 * half-built child behind it.
 *
 * <p>No field of the request names an authority. The creating Application's admission and the
 * allowance it is spending both come from its own retained conversation record on the server side,
 * because a request that supplied either would be authorizing itself. {@code destination} is
 * accepted and ignored: the server allocates a child's source area under its configured containment
 * root.
 */
@Component
public final class ContainedApplicationFrames implements FrameArea {
  private final ContainedApplications children;

  public ContainedApplicationFrames(ContainedApplications children) {
    this.children = children;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.APPLICATION_CHILD_CREATE,
        this::create,
        FrameTypes.APPLICATION_CHILD_CEILING,
        this::ceiling,
        FrameTypes.APPLICATION_CHILD_DESTROY,
        this::destroy,
        FrameTypes.APPLICATION_CHILD_LIST,
        this::list,
        FrameTypes.APPLICATION_CHILD_TRAJECTORY,
        this::trajectory);
  }

  /**
   * What this container holds. The payload names a conversation and nothing else, so the scope
   * listed is the one the caller is already admitted as and no field can redirect the read.
   */
  private Outcome list(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("conversation"));
    return Outcome.ok(
        Map.of(
            "children",
            children.list(
                asking.requireHandle(FrameTypes.APPLICATION_CHILD_LIST),
                Payloads.required(
                    payload,
                    "conversation",
                    FrameTypes.APPLICATION_CHILD_LIST,
                    "the container's conversation"))));
  }

  /**
   * One child's retained conversation lineage. {@code child} is the only target, and it is proved
   * against the caller's own admitted scope through the stored containment chain before any
   * envelope is read, so naming a sibling or a container is refused rather than answered.
   */
  private Outcome trajectory(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("conversation", "child"));
    return Outcome.ok(
        Map.of(
            "steps",
            children.trajectory(
                asking.requireHandle(FrameTypes.APPLICATION_CHILD_TRAJECTORY),
                Payloads.required(
                    payload,
                    "conversation",
                    FrameTypes.APPLICATION_CHILD_TRAJECTORY,
                    "the container's conversation"),
                uuid(payload, "child"))));
  }

  private Outcome create(Map<String, Object> payload, Asking asking) {
    fields(
        payload,
        Set.of("conversation", "requestId", "ceiling", "destination", "writableAreas", "files"));
    if (!(payload.get("writableAreas") instanceof List<?> areas))
      throw new CallerFault("Specify explicit writableAreas");
    var destination =
        payload.get("destination") == null
            ? null
            : FileStoreRequests.reference(payload.get("destination"));
    if (!(payload.get("files") instanceof List<?> files) || files.isEmpty() || files.size() > 128)
      throw new CallerFault("Use a bounded Application file list");
    List<io.aeyer.plowshare.protocol.ApplicationDeployment.File> converted = new ArrayList<>();
    for (Object value : files) {
      if (!(value instanceof Map<?, ?> file)
          || !file.keySet().equals(Set.of("path", "text"))
          || !(file.get("path") instanceof String path)
          || !(file.get("text") instanceof String text))
        throw new CallerFault("Application files need exactly path and text fields");
      converted.add(new io.aeyer.plowshare.protocol.ApplicationDeployment.File(path, text));
    }
    io.aeyer.plowshare.server.applications.SourceApplicationDeployments.validateFiles(converted);
    List<io.aeyer.plowshare.protocol.FileStoreReference> writableAreas = new ArrayList<>();
    for (Object area : areas) writableAreas.add(FileStoreRequests.reference(area));
    try {
      return Outcome.ok(
          children.create(
              asking.requireHandle(FrameTypes.APPLICATION_CHILD_CREATE),
              new ContainedApplications.Create(
                  Payloads.required(
                      payload,
                      "conversation",
                      FrameTypes.APPLICATION_CHILD_CREATE,
                      "the creating Application's conversation"),
                  uuid(payload, "requestId"),
                  calls(payload, "ceiling"),
                  destination,
                  writableAreas,
                  converted)));
    } catch (IllegalArgumentException invalid) {
      // The DTO's own invariants are caller errors, not server faults: an absent ceiling is the
      // one that matters, because absent means unlimited and a confined child is never that.
      throw new CallerFault(invalid.getMessage());
    }
  }

  private Outcome ceiling(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("conversation", "child", "calls"));
    children.ceiling(
        asking.requireHandle(FrameTypes.APPLICATION_CHILD_CEILING),
        Payloads.required(
            payload,
            "conversation",
            FrameTypes.APPLICATION_CHILD_CEILING,
            "the container's conversation"),
        uuid(payload, "child"),
        calls(payload, "calls"));
    return Outcome.ok(Map.of("child", uuid(payload, "child").toString()));
  }

  /**
   * Destroys a child and its descendants. {@code child} is the only target and is proved against
   * the caller's own admitted scope before anything is removed, exactly as a ceiling change is.
   *
   * <p>The answer carries what was collected, deepest first, and how many source areas were removed
   * before it was given. {@code sources} may be fewer than {@code collected}: the filesystem cannot
   * join the transaction, so an interrupted removal leaves recorded work the server's own collector
   * finishes. Fewer is recoverable, never lost, and reporting it is what makes collection
   * observable to the container rather than merely claimed.
   */
  private Outcome destroy(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("conversation", "child"));
    var destroyed =
        children.destroy(
            asking.requireHandle(FrameTypes.APPLICATION_CHILD_DESTROY),
            Payloads.required(
                payload,
                "conversation",
                FrameTypes.APPLICATION_CHILD_DESTROY,
                "the container's conversation"),
            uuid(payload, "child"));
    return Outcome.ok(
        Map.of(
            "child",
            destroyed.child().toString(),
            "collected",
            destroyed.collected().stream().map(UUID::toString).toList(),
            "sources",
            destroyed.sources()));
  }

  /** A call count is an explicit non-negative integer; null would mean unlimited. */
  private static Integer calls(Map<String, Object> payload, String field) {
    Object value = payload.get(field);
    if (!(value instanceof Integer || value instanceof Long)
        || ((Number) value).longValue() < 0
        || ((Number) value).longValue() > Integer.MAX_VALUE)
      throw new CallerFault(
          field + " must be an explicit non-negative call count; zero disables calls");
    return (int) ((Number) value).longValue();
  }

  private static UUID uuid(Map<String, Object> payload, String field) {
    Object value = payload.get(field);
    if (!(value instanceof String text)
        || !text.matches("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
      throw new CallerFault(field + " must be a UUID");
    return UUID.fromString(text);
  }

  private static void fields(Map<String, Object> payload, Set<String> required) {
    if (!payload.keySet().equals(required))
      throw new CallerFault("Specify exactly " + String.join(", ", new TreeSet<>(required)));
  }
}
