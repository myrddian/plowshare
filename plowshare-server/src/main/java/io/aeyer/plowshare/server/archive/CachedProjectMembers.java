package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Stored project membership and roles decided in server memory.
 *
 * <p>Reads that previously ran {@code service_project_role}, {@code authority_project_role}, the
 * service-owner lookup and the administrator check per call are evaluated from the current {@link
 * AuthoritySnapshot}, with the same rules as {@link JdbcProjectMembers}. When no snapshot may be
 * used for this caller, each read runs the original SQL instead. Mutations, membership listings,
 * administrative control and access reports always delegate: they lock rows or record history.
 */
public final class CachedProjectMembers implements ProjectMembers {
  private final ProjectMembers stored;
  private final AuthoritySnapshots snapshots;

  public CachedProjectMembers(ProjectMembers stored, AuthoritySnapshots snapshots) {
    this.stored = Objects.requireNonNull(stored);
    this.snapshots = Objects.requireNonNull(snapshots);
  }

  private <T> T decide(Function<AuthoritySnapshot, T> memory, Supplier<T> sql) {
    return snapshots.current().map(memory).orElseGet(sql);
  }

  private static boolean service(String handle) {
    return ServiceCredentials.principal(handle);
  }

  @Override
  public Optional<String> authorityAccount(String principal) {
    if (!service(principal)) return Optional.ofNullable(principal);
    return decide(
        snapshot -> snapshot.serviceOwner(principal), () -> stored.authorityAccount(principal));
  }

  @Override
  public boolean isServerAdmin(String handle) {
    if (handle == null) return false;
    return decide(snapshot -> snapshot.serverAdmin(handle), () -> stored.isServerAdmin(handle));
  }

  @Override
  public boolean isMember(String project, String handle) {
    if (handle == null) return false;
    return decide(
        snapshot ->
            snapshot.memberRole(project, handle).isPresent()
                && (project.startsWith("client:")
                    || snapshot.authorityProjectRole(project, handle).isPresent()),
        () -> stored.isMember(project, handle));
  }

  /** {@link JdbcProjectMembers#role}, rule for rule. */
  @Override
  public Optional<ProjectRole> role(String project, String handle) {
    if (project == null || handle == null) return Optional.empty();
    return decide(snapshot -> role(snapshot, project, handle), () -> stored.role(project, handle));
  }

  private static Optional<ProjectRole> role(
      AuthoritySnapshot snapshot, String project, String handle) {
    if (service(handle))
      return snapshot.serviceProjectRole(project, handle).map(ProjectRole::parse);
    // PersonalSpaces.name refuses a blank handle; call it exactly where the SQL path does.
    if (project.startsWith("personal:") && !project.equals(PersonalSpaces.name(handle)))
      return Optional.empty();
    var role =
        ClientProjects.privateProject(project)
            ? snapshot.memberRole(project, handle)
            : snapshot.authorityProjectRole(project, handle);
    if (role.isEmpty()) return Optional.empty();
    return Optional.of(
        project.equals(PersonalSpaces.name(handle))
            ? ProjectRole.MANAGER
            : ProjectRole.parse(role.get()));
  }

  /** One snapshot for every project in the operation; otherwise the batched SQL statement. */
  @Override
  public Map<String, ProjectRole> roles(Collection<String> projects, String account) {
    var selection = new ReadSelection(account, projects == null ? null : new ArrayList<>(projects));
    if (selection.projects().isEmpty()) return Map.of();
    return decide(
        snapshot -> {
          var result = new LinkedHashMap<String, ProjectRole>();
          for (String project : selection.projects())
            role(snapshot, project, selection.account())
                .ifPresent(role -> result.put(project, role));
          return Map.copyOf(result);
        },
        () -> stored.roles(selection.projects(), selection.account()));
  }

  @Override
  public Set<String> readableProjects(Collection<String> projects, String account) {
    var selection = new ReadSelection(account, projects == null ? null : new ArrayList<>(projects));
    if (selection.projects().isEmpty()) return Set.of();
    return decide(
        snapshot ->
            selection.projects().stream()
                .filter(project -> readable(snapshot, project, selection.account()))
                .collect(Collectors.toUnmodifiableSet()),
        () -> stored.readableProjects(selection.projects(), selection.account()));
  }

  /** The CASE of {@link JdbcProjectMembers#readableProjects}, which tests "client:" literally. */
  private static boolean readable(AuthoritySnapshot snapshot, String project, String account) {
    if (service(account)) return snapshot.serviceProjectRole(project, account).isPresent();
    if (project.startsWith("personal:") && !project.equals(PersonalSpaces.name(account)))
      return false;
    if (project.startsWith("client:")) return snapshot.memberRole(project, account).isPresent();
    return snapshot.authorityProjectRole(project, account).isPresent();
  }

  @Override
  public boolean mayUse(String project, String handle) {
    return role(project, handle).isPresent();
  }

  @Override
  public boolean mayWork(String project, String handle) {
    return role(project, handle).filter(role -> role.allows(ProjectRole.CONTRIBUTOR)).isPresent();
  }

  @Override
  public boolean mayManage(String project, String handle) {
    return role(project, handle).filter(role -> role.allows(ProjectRole.MANAGER)).isPresent();
  }

  @Override
  public void requireRole(String project, String handle, ProjectRole required) {
    if (role(project, handle).filter(role -> role.allows(required)).isEmpty())
      throw new CallerFault(
          "This account needs " + required + " access to project '" + project + "'");
  }

  // Delegated unchanged: listings, locking mutations, history and administrative control.

  @Override
  public boolean application(String project) {
    return stored.application(project);
  }

  @Override
  public boolean mayAdminister(String project, String actor) {
    return stored.mayAdminister(project, actor);
  }

  @Override
  public List<String> members(String project) {
    return stored.members(project);
  }

  @Override
  public void add(String project, String handle) {
    stored.add(project, handle);
  }

  @Override
  public void remove(String project, String handle) {
    stored.remove(project, handle);
  }

  @Override
  public Access access(String project, String handle) {
    return stored.access(project, handle);
  }

  @Override
  public Access managementAccess(String project, String actor) {
    return stored.managementAccess(project, actor);
  }

  @Override
  public void assign(String project, String handle, ProjectRole role, String actor, boolean add) {
    stored.assign(project, handle, role, actor, add);
  }

  @Override
  public void remove(String project, String handle, String actor) {
    stored.remove(project, handle, actor);
  }
}
