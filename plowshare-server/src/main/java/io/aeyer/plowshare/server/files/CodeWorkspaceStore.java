package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.CodeTrackingStatus;
import io.aeyer.plowshare.protocol.Home;
import java.time.Duration;
import java.util.*;

/**
 * Durable code tracking registrations and index references. Generation-fenced publication rejects
 * scans made stale by mutations or revocation; claims and capacity admission are atomic. Services
 * never see tables or SQL and recheck workspace grants independently of stored registrations.
 */
public interface CodeWorkspaceStore {
  public record Scope(Home home, String owner, String agent, String session) {
    public Scope {
      Objects.requireNonNull(home);
      owner = WorkspaceValues.identity(owner, "owner");
      agent = WorkspaceValues.identity(agent, "agent");
      session = session == null ? null : WorkspaceValues.identity(session, "session");
    }

    public String id() {
      return WorkspaceValues.scopeId(home, owner, agent, session);
    }
  }

  public record Scan(CodeMapObservations.Ticket ticket, Scope scope, String pattern) {
    public Scan {
      Objects.requireNonNull(ticket, "ticket");
      Objects.requireNonNull(scope, "scope");
      pattern = WorkspaceValues.path(pattern, "pattern");
    }
  }

  public static final class CapacityReached extends WorkspaceRefusedException {
    public CapacityReached() {
      super("code tracking registration capacity reached");
    }
  }

  Duration interval();

  CodeMapObservations.Ticket begin(Scope scope, String pattern);

  Optional<Scan> claim();

  boolean publish(CodeMapObservations.Ticket ticket, WorkspaceCodeMap.View view);

  void unavailable(CodeMapObservations.Ticket ticket, String reason);

  /** Capacity deferral keeps retained observations and makes the claimed generation retryable. */
  void defer(CodeMapObservations.Ticket ticket);

  String mutationStarted(Scope scope);

  void mutationFinished(Scope scope, String token);

  void invalidate(Scope scope);

  /** At most 64 coalesced, freshly authorized hints; dirty work is not rescheduled repeatedly. */
  void changed(List<WorkspaceChanges.Change> changes);

  CodeTrackingStatus status(Scope scope);

  void forget(Scope scope);

  UUID indexed(Scope scope, String key, String hash, String parser);

  /** Live manifest key plus stable retained-source key; neither is an authorization lease. */
  record IndexSource(
      String key, String retainedKey, io.aeyer.plowshare.protocol.FileSource source) {
    public IndexSource {
      WorkspaceValues.checkedKey(key);
      WorkspaceValues.checkedKey(retainedKey);
      Objects.requireNonNull(source);
    }
  }

  /** Exact source/hash/parser matches, at most 100 per page; no content or symbols are loaded. */
  Map<String, UUID> indexed(Scope scope, List<IndexSource> sources, String parser);

  void index(Scope scope, String key, String hash, String parser, UUID revision);

  static String sourceKey(String key) {
    return WorkspaceValues.sourceKey(key);
  }
}
