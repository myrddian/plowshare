package io.aeyer.plowshare.server.board;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable topic-owned definition. It identifies participants, never confers tool authority. */
public record SwarmSelection(
    String name, String revision, String description, List<String> members, int budget) {
  public SwarmSelection {
    requireName(name);
    if (revision == null || !revision.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Swarm revision must be a SHA-256 digest");
    if (description == null || description.length() > 4096 || description.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Swarm description must fit within 4096 characters");
    members = List.copyOf(Objects.requireNonNull(members, "members"));
    if (members.isEmpty()
        || members.size() > 64
        || members.stream().distinct().count() != members.size()
        || members.stream()
            .anyMatch(
                member -> member.isBlank() || member.length() > 128 || member.indexOf('\0') >= 0)
        || budget < 2)
      throw new IllegalArgumentException(
          "Swarm needs unique bounded members and a budget of at least two");
  }

  public static void requireName(String name) {
    if (name == null || name.length() > 64 || !name.matches("[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*"))
      throw new Board.Refused(
          "Swarm names use lowercase letters, digits, hyphens or underscores, at most 64 characters");
  }

  static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
    }
  }
}
