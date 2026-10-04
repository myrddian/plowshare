package io.aeyer.plowshare.server.union;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.ws.Asking;
import io.aeyer.plowshare.server.ws.FrameArea;
import io.aeyer.plowshare.server.ws.FrameHandler;
import io.aeyer.plowshare.server.ws.FrameTypes;
import io.aeyer.plowshare.server.ws.Payloads;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The frames a client uses to make a project a union and keep it reconciled. Spec §4, §6, §11.6.
 * Sent on {@code /v1/events}; the session id is the same one the client's file channel declared its
 * presence with.
 */
@Component
public class UnionFrames implements FrameArea {

  private static final Set<String> RESOLUTIONS = Set.of("mine", "theirs", "merged");

  private final UnionStore unions;
  private final Hubs hubs;
  private final UnionGate gate;
  private final PresenceRegistry presences;
  private final Inbox inbox;
  private final long maxFileBytes;

  public UnionFrames(
      UnionStore unions,
      Hubs hubs,
      UnionGate gate,
      PresenceRegistry presences,
      Inbox inbox,
      @Value("${plowshare.union.max-file-bytes:5242880}") long maxFileBytes) {
    this.unions = Objects.requireNonNull(unions, "unions");
    this.hubs = Objects.requireNonNull(hubs, "hubs");
    this.gate = Objects.requireNonNull(gate, "gate");
    this.presences = Objects.requireNonNull(presences, "presences");
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.maxFileBytes = maxFileBytes;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(FrameTypes.UNION_STATUS, this::status),
        Map.entry(FrameTypes.UNION_ENABLE, this::enable),
        Map.entry(FrameTypes.UNION_BEGIN, this::begin),
        Map.entry(FrameTypes.UNION_READY, this::ready),
        Map.entry(FrameTypes.UNION_ABORT, this::abort),
        Map.entry(FrameTypes.UNION_DISABLE, this::disable),
        Map.entry(FrameTypes.UNION_HIDDEN, this::hidden),
        Map.entry(FrameTypes.UNION_CONFLICT_OPEN, this::openConflict),
        Map.entry(FrameTypes.UNION_CONFLICT_LIST, this::listConflicts),
        Map.entry(FrameTypes.UNION_CONFLICT_RESOLVE, this::resolveConflict));
  }

  private Outcome status(Map<String, Object> payload, Asking asking) {
    String project = project(payload, FrameTypes.UNION_STATUS);
    return unions
        .find(project)
        .<Outcome>map(
            union -> {
              Map<String, Object> said = new LinkedHashMap<>();
              said.put("eligible", true);
              said.put("enabled", union.enabled());
              said.put("state", gate.state(project).name());
              said.put("syncHidden", union.syncHidden());
              said.put("maxFileBytes", maxFileBytes);
              said.put("openConflicts", unions.open(union.projectId()).size());
              said.put("url", url(project));
              return Outcome.ok(said);
            })
        .orElseGet(() -> Outcome.ok(Map.of("eligible", false, "enabled", false)));
  }

  private Outcome enable(Map<String, Object> payload, Asking asking) {
    String project = claimed(payload, asking, FrameTypes.UNION_ENABLE);
    UnionStore.Union union = union(project);
    if (union.workspace() == null || !union.workspace().startsWith("/")) {
      throw new CallerFault(
          "unions need a POSIX workspace path in this version; '"
              + union.workspace()
              + "' is not one");
    }
    Hub hub =
        hubs.of(project)
            .orElseThrow(
                () ->
                    new CallerFault(
                        "this server keeps no data directory, so it cannot hold a union"));
    gate.locked(
        project,
        () -> {
          // A hub left by an enable that never reached union.ready (or by an
          // earlier /sync off that could not delete it) holds history no client
          // shares; starting over is the only state a first push can land on.
          if (hub.exists() && !union.enabled()) {
            hub.delete();
          }
          if (!hub.exists()) {
            hub.create();
          }
          return null;
        });
    gate.begin(project, asking.sessionId());
    return Outcome.ok(Map.of("url", url(project)));
  }

  private Outcome begin(Map<String, Object> payload, Asking asking) {
    String project = claimed(payload, asking, FrameTypes.UNION_BEGIN);
    if (!union(project).enabled()) {
      throw new CallerFault("project '" + project + "' is not a union; /sync on makes it one");
    }
    try {
      gate.begin(project, asking.sessionId());
    } catch (IllegalStateException refused) {
      return Outcome.failed(Code.CONFLICT, refused.getMessage());
    }
    return Outcome.ok(Map.of("url", url(project)));
  }

  /** A client whose sync failed after {@code union.begin} lets go of the claim. */
  private Outcome abort(Map<String, Object> payload, Asking asking) {
    String project = claimed(payload, asking, FrameTypes.UNION_ABORT);
    gate.abort(project, asking.sessionId());
    return Outcome.ok(Map.of());
  }

  private Outcome ready(Map<String, Object> payload, Asking asking) {
    String project = claimed(payload, asking, FrameTypes.UNION_READY);
    String commit =
        Payloads.required(
            payload,
            "commit",
            FrameTypes.UNION_READY,
            "the commit this client just pushed to main.");
    UnionStore.Union union = union(project);
    try {
      gate.ready(project, asking.sessionId(), commit);
    } catch (IllegalStateException refused) {
      return Outcome.failed(Code.CONFLICT, refused.getMessage());
    }
    if (!union.enabled()) {
      unions.enable(project, Instant.now());
    }
    return Outcome.ok(Map.of());
  }

  private Outcome disable(Map<String, Object> payload, Asking asking) {
    String project = claimed(payload, asking, FrameTypes.UNION_DISABLE);
    if (project.startsWith("personal:"))
      throw new CallerFault("Personal space always keeps its union on the server");
    union(project);
    // Only a LIVE machine has everything the hub holds; deleting the hub before it
    // has synced would destroy agent work committed on the server since.
    if (!gate.live(project, asking.sessionId())) {
      return Outcome.failed(
          Code.CONFLICT,
          "project '"
              + project
              + "' has server changes this"
              + " machine has not synced yet; sync first (reconnect), then /sync off");
    }
    gate.locked(
        project,
        () -> {
          hubs.of(project).ifPresent(Hub::delete);
          unions.disable(project);
          return null;
        });
    gate.forget(project);
    return Outcome.ok(Map.of());
  }

  private Outcome hidden(Map<String, Object> payload, Asking asking) {
    String project = claimed(payload, asking, FrameTypes.UNION_HIDDEN);
    union(project);
    Object raw = payload.get("paths");
    if (!(raw instanceof List<?> listed)) {
      throw new CallerFault("union.hidden needs 'paths': a list of hidden paths to sync");
    }
    List<String> paths = new ArrayList<>();
    for (Object item : listed) {
      if (!(item instanceof String path)) {
        throw new CallerFault("union.hidden needs 'paths': a list of hidden paths to sync");
      }
      if (!validHiddenPath(path)) {
        throw new CallerFault(
            "'"
                + path
                + "' cannot be synced: give a relative hidden path,"
                + " never .git or .plowshare");
      }
      paths.add(path);
    }
    unions.setHidden(project, paths);
    return Outcome.ok(Map.of("syncHidden", paths, "replaced", true));
  }

  /**
   * Whether a hidden-path entry can ever be stored — mirrors what {@link SyncRules#allowed} refuses
   * at push time, so the allowlist the hub's push check trusts can never carry a path that check
   * would reject anyway. A trailing slash marks a directory prefix and drops one empty trailing
   * segment; every other segment must be non-empty, not {@code .} or {@code ..}, and not name
   * {@code .git} or {@code .plowshare} in any case, Unicode-folded form, 8.3 alias, or with an NTFS
   * alternate-data-stream suffix — see {@link SyncRules#reservedSegment}. A segment that merely
   * contains {@code ..}, such as {@code ..foo}, or a bare {@code :} in an otherwise ordinary name,
   * is a legal hidden name (macOS and Linux allow both).
   */
  private static boolean validHiddenPath(String path) {
    if (path.startsWith("/")) {
      return false;
    }
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    for (String segment : trimmed.split("/", -1)) {
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || SyncRules.reservedSegment(segment)) {
        return false;
      }
    }
    return true;
  }

  private Outcome openConflict(Map<String, Object> payload, Asking asking) {
    String type = FrameTypes.UNION_CONFLICT_OPEN;
    String project = claimed(payload, asking, type);
    String handle = asking.requireHandle(type);
    String path = Payloads.required(payload, "path", type, "the conflicted path.");
    String author =
        Payloads.required(payload, "theirsAuthor", type, "who wrote the server's side.");
    UnionStore.Union union = union(project);
    int n =
        unions.openConflict(
            union.projectId(),
            new UnionStore.NewConflict(
                path,
                text(payload, "baseBlob"),
                text(payload, "oursBlob"),
                text(payload, "theirsBlob"),
                author,
                text(payload, "runId")),
            Instant.now());
    inbox.notify(
        handle,
        InboxStore.KIND_SYNC_CONFLICT,
        project
            + ": "
            + path
            + " changed on both"
            + " sides. Your version was kept; "
            + author
            + "'s is set aside."
            + " Resolve it with /sync resolve "
            + path);
    return Outcome.ok(Map.of("n", n));
  }

  private Outcome listConflicts(Map<String, Object> payload, Asking asking) {
    String project = project(payload, FrameTypes.UNION_CONFLICT_LIST);
    List<Map<String, Object>> rows =
        unions.open(union(project).projectId()).stream()
            .map(
                c -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  row.put("n", c.n());
                  row.put("path", c.path());
                  row.put("baseBlob", c.baseBlob());
                  row.put("oursBlob", c.oursBlob());
                  row.put("theirsBlob", c.theirsBlob());
                  row.put("theirsAuthor", c.theirsAuthor());
                  row.put("runId", c.runId());
                  row.put("openedAt", c.openedAt().toString());
                  return row;
                })
            .toList();
    return Outcome.ok(Map.of("conflicts", rows));
  }

  private Outcome resolveConflict(Map<String, Object> payload, Asking asking) {
    String type = FrameTypes.UNION_CONFLICT_RESOLVE;
    String project = claimed(payload, asking, type);
    String resolution = Payloads.required(payload, "resolution", type, "mine, theirs or merged.");
    if (!RESOLUTIONS.contains(resolution)) {
      throw new CallerFault(
          "a conflict is resolved as mine, theirs or merged, not '" + resolution + "'");
    }
    if (!(payload.get("n") instanceof Number n)) {
      throw new CallerFault(type + " needs 'n': the conflict's number");
    }
    boolean resolved =
        unions.resolve(union(project).projectId(), n.intValue(), resolution, Instant.now());
    return resolved
        ? Outcome.ok(Map.of())
        : Outcome.failed(Code.NOT_FOUND, "conflict " + n + " of '" + project + "' is not open");
  }

  private String project(Map<String, Object> payload, String type) {
    return Payloads.required(payload, "project", type, "the project this is about.");
  }

  private String claimed(Map<String, Object> payload, Asking asking, String type) {
    String project = project(payload, type);
    boolean roots =
        presences
            .rootedBy(asking.sessionId())
            .filter(presence -> presence.project().equals(project))
            .isPresent();
    if (!roots) {
      throw new CallerFault(
          "this session does not root project '"
              + project
              + "'; root it on the machine that holds its files first");
    }
    return project;
  }

  private UnionStore.Union union(String project) {
    return unions
        .find(project)
        .orElseThrow(
            () ->
                new CallerFault(
                    "project '"
                        + project
                        + "' is not rooted on a client, so it cannot be a union"));
  }

  private static String text(Map<String, Object> payload, String key) {
    Object value = payload.get(key);
    return value == null ? null : String.valueOf(value);
  }

  /**
   * The hub's path for {@code project}, each {@code /}-separated segment percent-encoded: RFC 3986
   * unreserved characters kept, every other byte of the UTF-8 name written as {@code %XX}. The
   * servlet's resolver is handed the decoded name.
   */
  static String url(String project) {
    StringBuilder path = new StringBuilder("/v1/sync/");
    String[] segments = project.split("/", -1);
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        path.append('/');
      }
      for (byte b : segments[i].getBytes(StandardCharsets.UTF_8)) {
        int c = b & 0xff;
        if ((c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9')
            || c == '-'
            || c == '.'
            || c == '_'
            || c == '~') {
          path.append((char) c);
        } else {
          path.append('%')
              .append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
              .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
        }
      }
    }
    return path.append(".git").toString();
  }
}
