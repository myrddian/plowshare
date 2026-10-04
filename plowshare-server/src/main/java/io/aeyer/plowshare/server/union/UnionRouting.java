package io.aeyer.plowshare.server.union;

import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one decision a union adds to a run's filesystems: the client's live files once it is {@code
 * LIVE}, otherwise the server's copy. Spec §5.2. Exactly one provider either way, so {@code
 * ProviderRouter} never sees two claiming a root.
 */
public final class UnionRouting {

  public static final UnionRouting NONE = new UnionRouting(null, null, null);

  private final UnionStore unions;
  private final Hubs hubs;
  private final UnionGate gate;

  public UnionRouting(UnionStore unions, Hubs hubs, UnionGate gate) {
    this.unions = unions;
    this.hubs = hubs;
    this.gate = gate;
  }

  public Optional<List<FileProvider>> providers(
      String project,
      List<Grant> grants,
      Optional<String> servingSession,
      Function<String, FileProvider> remoteFor) {
    Optional<Mirrored> mirrored = mirrored(project);
    if (mirrored.isEmpty()) {
      return Optional.empty();
    }
    if (servingSession.isPresent() && gate.live(project, servingSession.get())) {
      return Optional.of(List.of(remoteFor.apply(servingSession.get())));
    }
    Mirrored union = mirrored.get();
    List<Path> exclusions = union.union().exclusions().stream().map(Path::of).toList();
    return Optional.of(
        List.of(
            new MirrorProvider(
                project,
                Path.of(union.union().workspace()),
                exclusions,
                union.hub().tree(),
                gate,
                grants)));
  }

  public Optional<String> mirrorPlace(String project) {
    return mirrored(project)
        .filter(m -> gate.state(project) != UnionGate.State.LIVE)
        .map(
            m -> {
              String synced = m.hub().lastClientCommitTime().map(Object::toString).orElse("never");
              return "the server's copy of the files on the machine '"
                  + m.union().machine()
                  + "' at "
                  + m.union().workspace()
                  + ", last synced "
                  + synced
                  + " (changes made here reach '"
                  + m.union().machine()
                  + "' when it reconnects)";
            });
  }

  public String conflictNote(String project) {
    Optional<Mirrored> mirrored = mirrored(project);
    if (mirrored.isEmpty()) {
      return "";
    }
    List<UnionStore.Conflict> open = unions.open(mirrored.get().union().projectId());
    if (open.isEmpty()) {
      return "";
    }
    return " Open sync conflicts, where the machine's version was kept and another was set aside: "
        + open.stream()
            .map(c -> c.path() + " (" + c.theirsAuthor() + "'s version set aside)")
            .collect(Collectors.joining(", "))
        + ". Do not redo that work without saying so.";
  }

  private record Mirrored(UnionStore.Union union, Hub hub) {}

  private Optional<Mirrored> mirrored(String project) {
    if (unions == null || project == null) {
      return Optional.empty();
    }
    return unions
        .find(project)
        .filter(UnionStore.Union::enabled)
        .flatMap(
            union -> hubs.of(project).filter(Hub::exists).map(hub -> new Mirrored(union, hub)));
  }
}
