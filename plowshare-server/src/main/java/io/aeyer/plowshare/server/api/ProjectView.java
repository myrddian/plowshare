package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.ProjectRecord;
import java.nio.file.Path;
import java.util.List;

/**
 * What a project's leash looks like after it has been set.
 *
 * <p><b>{@code workspace} is where the project is; {@code workspace} and {@code lent} together are
 * what it reaches.</b> Both travel, and both have to: a view carrying only the workspace would show
 * an operator a <em>shorter</em> leash than the one they have, which is precisely the failure the
 * next paragraph forbids in the other direction. The reason they are two fields rather than one
 * list is that only the first is the project's identity — the {@code <PATH>} of {@code
 * <MACHINE>/<PATH>/<PROJ_NAME>} — and a console that rendered one list would have nothing to point
 * at when a person asks where the project is. V30 carries the argument.
 *
 * <p><b>{@code exclusions} is the effective list and never the row's.</b> {@code
 * ProjectRecord.exclusions()} carries only what the {@code projects} row stores, and its own
 * javadoc says a check built from that alone is one a row can disable; the paths no project may
 * override — the server's own directory, so that its configuration, its agent definitions, its
 * sampling profiles and its own console token are out of reach whatever a row says — are added by
 * {@code ProjectStore.effectiveExclusions}. Answering with the row would show an operator a shorter
 * leash than the one they actually have, which is the single thing a person setting a workspace
 * reads this answer to learn.
 *
 * <p><b>So what comes back here is not what {@code POST /v1/projects} takes.</b> That request
 * carries only the extra paths a project fences off; this carries those <em>and</em> the ones the
 * server applies regardless. A console that filled an edit form from a listing and posted it back
 * unchanged would write the mandatory paths into the row — which {@code ProjectStore} argues
 * against at length, because a default written into a row is a default a row can be edited out of.
 * Posting back means sending only the paths the project itself chose.
 *
 * <p>Paths as strings, because this is a wire shape and the client is a separate process whose
 * {@code Path} would be its own machine's.
 *
 * <p><b>{@code machine} is filled on the listings only</b> — {@code project.list} and {@code GET
 * /v1/projects} — and stays {@code null} everywhere else this type is rendered, per ruling 5: the
 * offer a terminal is given at startup and {@code /project} are the only readers, and nothing else
 * needs one more query per answer. A project this server itself holds the files for, and one
 * nothing has rooted, are the same {@code null}: a listing cannot tell "no client has claimed it"
 * from "this is where it lives", and ruling 5 does not ask it to.
 */
public record ProjectView(
    String name,
    String workspace,
    List<String> lent,
    List<String> exclusions,
    String machine,
    List<String> members,
    String kind,
    String type,
    boolean readOnly,
    List<String> writePaths,
    String displayName,
    String routingIdentity,
    io.aeyer.plowshare.server.archive.ProjectRole role,
    io.aeyer.plowshare.protocol.FileStoreReference applicationRoot,
    List<io.aeyer.plowshare.protocol.FileStoreReference> writableAreas) {

  /** Compatibility construction for projects without an admitted FileStore placement. */
  public ProjectView(
      String name,
      String workspace,
      List<String> lent,
      List<String> exclusions,
      String machine,
      List<String> members,
      String kind,
      String type,
      boolean readOnly,
      List<String> writePaths,
      String displayName,
      String routingIdentity,
      io.aeyer.plowshare.server.archive.ProjectRole role) {
    this(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        kind,
        type,
        readOnly,
        writePaths,
        displayName,
        routingIdentity,
        role,
        null,
        null);
  }

  public ProjectView(
      String name,
      String workspace,
      List<String> lent,
      List<String> exclusions,
      String machine,
      List<String> members,
      String kind,
      String type,
      boolean readOnly,
      List<String> writePaths,
      String displayName,
      String routingIdentity) {
    this(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        kind,
        type,
        readOnly,
        writePaths,
        displayName,
        routingIdentity,
        null);
  }

  public ProjectView withRole(String role) {
    return new ProjectView(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        kind,
        type,
        readOnly,
        writePaths,
        displayName,
        routingIdentity,
        role == null ? null : io.aeyer.plowshare.server.archive.ProjectRole.parse(role),
        applicationRoot,
        writableAreas);
  }

  /** Applications are identified by a validated manifest, independently of workspace ownership. */
  public ProjectView application(boolean application) {
    return new ProjectView(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        application ? "application" : kind,
        type,
        readOnly,
        writePaths,
        displayName,
        routingIdentity,
        role,
        applicationRoot,
        writableAreas);
  }

  public ProjectView(
      String name,
      String workspace,
      List<String> lent,
      List<String> exclusions,
      String machine,
      List<String> members,
      String kind,
      String type,
      boolean readOnly,
      List<String> writePaths,
      String displayName) {
    this(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        kind,
        type,
        readOnly,
        writePaths,
        displayName,
        name);
  }

  public ProjectView(
      String name,
      String workspace,
      List<String> lent,
      List<String> exclusions,
      String machine,
      List<String> members,
      String kind,
      String type,
      boolean readOnly,
      List<String> writePaths) {
    this(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        kind,
        type,
        readOnly,
        writePaths,
        io.aeyer.plowshare.server.archive.ClientProjects.label(name));
  }

  public ProjectView(
      String name,
      String workspace,
      List<String> lent,
      List<String> exclusions,
      String machine,
      List<String> members,
      String kind) {
    this(
        name, workspace, lent, exclusions, machine, members, kind, "STANDARD", false, List.of("."));
  }

  public ProjectView(
      String name,
      String workspace,
      List<String> lent,
      List<String> exclusions,
      String machine,
      List<String> members) {
    this(name, workspace, lent, exclusions, machine, members, "project");
  }

  public ProjectView personal() {
    return new ProjectView(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        "personal",
        type,
        readOnly,
        writePaths);
  }

  public ProjectView personal(String owner) {
    return new ProjectView(
        name,
        workspace,
        lent,
        exclusions,
        machine,
        members,
        "personal",
        type,
        readOnly,
        writePaths,
        "Personal",
        io.aeyer.plowshare.server.personal.PersonalSpaces.address(owner));
  }

  /**
   * @param project the row as it was written
   * @param effective what {@code ProjectStore.effectiveExclusions} answers for it — asked by the
   *     caller rather than here, so that this record never holds a store and can never answer from
   *     the row by accident
   */
  public static ProjectView of(ProjectRecord project, List<Path> effective) {
    return of(project, effective, null);
  }

  /**
   * @param project the row as it was written
   * @param effective what {@code ProjectStore.effectiveExclusions} answers for it — asked by the
   *     caller rather than here, so that this record never holds a store and can never answer from
   *     the row by accident
   * @param machine what the rooting client calls its machine, or {@code null} when this server
   *     holds the files or nothing does
   */
  public static ProjectView of(ProjectRecord project, List<Path> effective, String machine) {
    return of(project, effective, machine, List.of());
  }

  public static ProjectView of(
      ProjectRecord project, List<Path> effective, String machine, List<String> members) {
    return new ProjectView(
        project.name(),
        project.workspace().toString(),
        // The row's own list, unlike exclusions two lines down, and the
        // asymmetry is the whole of what these two components mean.
        // There is no mandatory-lending rule for a `lent` equivalent of
        // effectiveExclusions to add: the server fences paths off, it
        // does not lend them. So this is complete as the row has it,
        // while exclusions is not and never was.
        project.lent().stream().map(Path::toString).toList(),
        effective.stream().map(Path::toString).toList(),
        machine,
        members,
        "project",
        project.type(),
        project.readOnly(),
        project.writePaths(),
        io.aeyer.plowshare.server.archive.ClientProjects.label(project.name()),
        project.name(),
        null,
        project.placement() == null ? null : project.placement().applicationRoot(),
        project.placement() == null ? null : project.placement().writableAreas());
  }
}
