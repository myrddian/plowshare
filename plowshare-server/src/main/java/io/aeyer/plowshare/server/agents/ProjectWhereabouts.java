package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.union.UnionRouting;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link Whereabouts} worked out from what this server already holds in memory and in the {@code
 * projects} row — never from a disk or a client, so saying it cannot slow a turn down or fail one.
 *
 * <h2>The place, in {@code runProviders}' order</h2>
 *
 * <ol>
 *   <li>A connected machine roots the project: its presence names the machine and the root, and it
 *       counts only while its session holds the file channel — {@code runProviders}' own gate, so
 *       the note never promises a filesystem the tools will not reach.
 *   <li>This server holds the workspace: {@link ProjectStore#find}.
 *   <li>Neither: unreachable, naming the machine the row says holds it, if any.
 * </ol>
 *
 * <h2>Once per change, per conversation</h2>
 *
 * <p>The notice is logged, so the conversation carries it from then on and saying it every turn
 * would only repeat the history. What was last said is kept in memory and deliberately not in the
 * archive: a restart forgets it, and the cost is one repeated sentence per conversation, which
 * after a restart is also the moment it is most worth hearing again. Bounded, since a server lives
 * through more conversations than it needs to remember.
 */
public final class ProjectWhereabouts implements Whereabouts {

  /** The tools whose reach this describes. A run holding none is told nothing. */
  private static final Set<String> FILE_TOOLS = FileTools.NAMES;

  private static final int REMEMBERED = 10_000;

  private final ProjectStore projects;
  private final SessionRegistry sessions;
  private final PresenceRegistry presences;
  private final UnionRouting unions;

  private final Map<String, String> told =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
          return size() > REMEMBERED;
        }
      };

  public ProjectWhereabouts(
      ProjectStore projects,
      SessionRegistry sessions,
      PresenceRegistry presences,
      UnionRouting unions) {
    this.projects = Objects.requireNonNull(projects, "projects");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.presences = Objects.requireNonNull(presences, "presences");
    this.unions = Objects.requireNonNull(unions, "unions");
  }

  @Override
  public Optional<String> noticeFor(AgentDefinition definition, Home home, String conversation) {
    if (home.isGlobal()
        || conversation == null
        || definition.tools().stream().noneMatch(FILE_TOOLS::contains)) {
      return Optional.empty();
    }
    String place = placeOf(home.project());
    synchronized (told) {
      if (place.equals(told.get(conversation))) {
        return Optional.empty();
      }
      told.put(conversation, place);
    }
    return Optional.of(place);
  }

  private String placeOf(String project) {
    Optional<String> mirrored = unions.mirrorPlace(project);
    if (mirrored.isPresent()) {
      return reachable(project, mirrored.get()) + unions.conflictNote(project);
    }
    Optional<Presence> served =
        presences
            .serving(project)
            .filter(
                presence ->
                    sessions
                        .find(presence.session())
                        .filter(session -> session.has(Role.FILE_PROVIDER))
                        .isPresent());
    var recorded = projects.find(project);
    if (served.isPresent()
        && recorded
            .filter(io.aeyer.plowshare.server.archive.ProjectRecord::serverProject)
            .isPresent()) {
      return "Project '"
          + project
          + "' has a server workspace at "
          + recorded.orElseThrow().workspace()
          + " and an independent checkout at "
          + served.get().root()
          + ". Only runs submitted from that checkout use its files; other runs use server files. These copies are not synchronized.";
    }
    if (served.isPresent()) {
      return reachable(
          project, "on the machine '" + served.get().machine() + "' at " + served.get().root());
    }
    Optional<String> row = recorded.map(record -> "on this server at " + record.workspace());
    if (row.isPresent()) {
      return reachable(project, row.get());
    }
    return projects
        .rootedElsewhere(project)
        .map(
            machine ->
                "The files of project '"
                    + project
                    + "' are on the machine '"
                    + machine
                    + "', which is not connected right now, so the file tools"
                    + " cannot reach them until a client there roots the project. Say so"
                    + " rather than guessing at their contents.")
        .orElse(
            "Project '"
                + project
                + "' has no files anywhere this server can reach,"
                + " so the file tools have nothing to read in it.");
  }

  private static String reachable(String project, String where) {
    return "The files of project '"
        + project
        + "' are "
        + where
        + ", and the file tools"
        + " reach them. To see what is there, call "
        + FileTools.ROOTS_NAME
        + " and then "
        + FileTools.GLOB_NAME
        + " with **/* rather than asking for files to be sent.";
  }
}
