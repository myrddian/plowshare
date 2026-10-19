package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import java.util.Objects;

/**
 * Server-owned read boundary shared by memory and document consumers. This value grants nothing:
 * repositories additionally require live scoped READ and current resource policy. Global means
 * generally readable knowledge, not execution or WRITE in the Global account.
 */
public record KnowledgeReadScope(Home home, boolean globalRead) {
  public KnowledgeReadScope {
    Objects.requireNonNull(home);
  }

  public static KnowledgeReadScope retained(OperationAuthority.Admission admission) {
    return new KnowledgeReadScope(admission.home(), admission.globalRead());
  }

  public static KnowledgeReadScope homeOnly(Home home) {
    return new KnowledgeReadScope(home, false);
  }

  /** Nested consumers may narrow this ceiling, never enlarge it or change its home. */
  public KnowledgeReadScope intersect(KnowledgeReadScope selected) {
    Objects.requireNonNull(selected);
    requireHome(selected.home());
    return new KnowledgeReadScope(home, globalRead && selected.globalRead());
  }

  public List<Home> memoryHomes() {
    return home.isGlobal() || !globalRead ? List.of(home) : List.of(home, Home.global());
  }

  public void requireHome(Home selected) {
    if (!home.equals(selected))
      throw new IllegalArgumentException("Read boundary cannot change execution home");
  }
}
