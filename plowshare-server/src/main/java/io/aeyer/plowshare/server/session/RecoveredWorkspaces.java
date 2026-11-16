package io.aeyer.plowshare.server.session;

import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** A recovery secret proves attachment continuity, separately from current account authority. */
public final class RecoveredWorkspaces implements WorkspaceRecovery {
  private final WorkspaceBindingRepository bindings;
  private final SessionRegistry sessions;

  public RecoveredWorkspaces(WorkspaceBindingRepository bindings, SessionRegistry sessions) {
    this.bindings = Objects.requireNonNull(bindings);
    this.sessions = Objects.requireNonNull(sessions);
  }

  @Override
  public String attach(
      String key, String session, String account, String machine, String root, String project) {
    if (key == null) {
      String canonical = sessions.canonical(session);
      if (!Objects.equals(canonical, session))
        throw new CallerFault("Private workspace recovery proof is required");
      return session;
    }
    if (!key.matches("[a-f0-9]{64}") || account == null || session == null)
      throw new CallerFault("A valid authenticated workspace recovery key is required");
    // Presence validates the foreign path without interpreting it using this server's OS.
    Presence claim = new Presence(session, machine, root, project);
    String canonical =
        bindings.bind(
            hash(key),
            LOGICAL_PREFIX + UUID.randomUUID(),
            account,
            claim.machine(),
            claim.root(),
            ClientProjects.label(claim.project()));
    sessions.recover(session, canonical, account);
    return canonical;
  }

  private static String hash(String key) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
