package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.applications.ApplicationDeployments;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.springframework.stereotype.Component;

/** Strict package conversion completes before any source or durable state is written. */
@Component
public final class ApplicationDeploymentFrames implements FrameArea {
  private final ApplicationDeployments applications;

  public ApplicationDeploymentFrames(ApplicationDeployments applications) {
    this.applications = applications;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.APPLICATION_DEPLOY,
        this::deploy,
        FrameTypes.APPLICATION_ACTIVATE,
        this::activate,
        FrameTypes.APPLICATION_DEPLOYMENT_STATUS,
        this::status,
        FrameTypes.APPLICATION_DEPLOYMENT_RECEIPT,
        this::receipt);
  }

  private Outcome deploy(Map<String, Object> payload, Asking asking) {
    fields(
        payload,
        Set.of(
            "project", "requestId", "expectedRevision", "destination", "writableAreas", "files"));
    if (!(payload.get("writableAreas") instanceof List<?>))
      throw new CallerFault("Specify explicit writableAreas");
    var root = FileStoreRequests.reference(payload.get("destination"));
    var placement =
        FileStoreRequests.placement(
            Map.of(
                "applicationRoot", rootMap(root), "writableAreas", payload.get("writableAreas")));
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
    return Outcome.ok(
        applications.deploy(
            asking.requireHandle(FrameTypes.APPLICATION_DEPLOY),
            new io.aeyer.plowshare.protocol.ApplicationDeployment.Deploy(
                project(payload),
                uuid(payload, "requestId", false),
                uuid(payload, "expectedRevision", true),
                root,
                placement.writableAreas(),
                converted)));
  }

  private Outcome activate(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("project", "requestId", "expectedRevision", "revision"));
    return Outcome.ok(
        applications.activate(
            asking.requireHandle(FrameTypes.APPLICATION_ACTIVATE),
            new io.aeyer.plowshare.protocol.ApplicationDeployment.Activate(
                project(payload),
                uuid(payload, "requestId", false),
                uuid(payload, "expectedRevision", false),
                uuid(payload, "revision", false))));
  }

  private Outcome status(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("project"));
    return Outcome.ok(
        applications.status(
            asking.requireHandle(FrameTypes.APPLICATION_DEPLOYMENT_STATUS), project(payload)));
  }

  private Outcome receipt(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("project", "requestId"));
    return Outcome.ok(
        applications.receipt(
            asking.requireHandle(FrameTypes.APPLICATION_DEPLOYMENT_RECEIPT),
            project(payload),
            uuid(payload, "requestId", false)));
  }

  private static Map<String, String> rootMap(io.aeyer.plowshare.protocol.FileStoreReference root) {
    return Map.of("store", root.store(), "path", root.path());
  }

  private static String project(Map<String, Object> payload) {
    return Payloads.required(
        payload, "project", FrameTypes.APPLICATION_DEPLOY, "an Application project name");
  }

  private static UUID uuid(Map<String, Object> payload, String field, boolean nullable) {
    Object value = payload.get(field);
    if (value == null && nullable) return null;
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
