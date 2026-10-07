package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.ApplicationFiles;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Authenticated Application source views; account and project authority are rechecked by the
 * service.
 */
@Component
public final class ApplicationFileFrames implements FrameArea {
  private final ApplicationFiles files;

  public ApplicationFileFrames(ApplicationFiles files) {
    this.files = files;
  }

  record Body(
      String project,
      String path,
      String text,
      String revision,
      io.aeyer.plowshare.protocol.FileStoreReference location) {}

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.APPLICATION_FILES,
        this::list,
        FrameTypes.APPLICATION_FILE_READ,
        this::read,
        FrameTypes.APPLICATION_FILE_SAVE,
        this::save);
  }

  private Body body(Map<String, Object> payload, Set<String> allowed) {
    if (!allowed.containsAll(payload.keySet()))
      throw new CallerFault("Unknown Application file field");
    for (var field : payload.entrySet())
      if (!field.getKey().equals("location") && !(field.getValue() instanceof String))
        throw new CallerFault("Application file fields must be text");
    if (payload.containsKey("location")) FileStoreRequests.reference(payload.get("location"));
    var body = Payloads.as(payload, Body.class, "Application files");
    if (body.project() == null
        || body.project().isBlank()
        || body.project().length() > 512
        || body.project().codePoints().anyMatch(Character::isISOControl))
      throw new CallerFault("Application files need a project");
    return body;
  }

  private ApplicationFiles.Caller caller(Body body, Asking asking) {
    return new ApplicationFiles.Caller(
        body.project(), asking.requireHandle("Application files"), body.location());
  }

  Outcome list(Map<String, Object> payload, Asking asking) {
    var body = body(payload, Set.of("project", "path", "location"));
    return Outcome.ok(files.list(caller(body, asking), body.path() == null ? "" : body.path()));
  }

  Outcome read(Map<String, Object> payload, Asking asking) {
    var body = body(payload, Set.of("project", "path", "location"));
    if (body.path() == null || body.path().isEmpty()) throw new CallerFault("Choose a file path");
    return Outcome.ok(files.read(caller(body, asking), body.path()));
  }

  Outcome save(Map<String, Object> payload, Asking asking) {
    var body = body(payload, Set.of("project", "path", "text", "revision", "location"));
    if (body.path() == null || body.path().isEmpty()) throw new CallerFault("Choose a file path");
    return Outcome.ok(files.save(caller(body, asking), body.path(), body.text(), body.revision()));
  }
}
