package io.aeyer.plowshare.server.acting;

import io.aeyer.plowshare.server.archive.TurnRecord;
import java.util.List;

/** Synchronous turn history under explicit subject authority; never starts or resumes work. */
public interface ActingHistory {
  List<TurnRecord> turns(RequestAuthority.Delegated authority, String conversation);

  /** Recheck current target authority without rereading a retained history projection. */
  void requireReadable(RequestAuthority.Delegated authority, String conversation);
}
