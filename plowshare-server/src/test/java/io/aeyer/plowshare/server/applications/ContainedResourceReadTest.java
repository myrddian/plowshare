package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * A container lists the source resources of what it contains, as metadata and never as content.
 *
 * <p>The read goes through the same ancestor gate {@code trajectory} does, so the assertions that
 * matter are the refusals: a child naming its container, a sibling naming a sibling and a caller
 * naming a destroyed child each find nothing on disk read. Every collaborator is a mocked
 * interface; the staged source is a real temporary directory because listing it is the behaviour.
 */
class ContainedResourceReadTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  OperationAuthority authority = mock(OperationAuthority.class);

  AccountId account = new AccountId(UUID.randomUUID());
  GrantRecipient recipient = GrantRecipient.account(account);

  ScopeId parentScope = new ScopeId(UUID.randomUUID());
  ScopeId firstScope = new ScopeId(UUID.randomUUID());
  ScopeId secondScope = new ScopeId(UUID.randomUUID());
  ScopeId grandchildScope = new ScopeId(UUID.randomUUID());

  Path root;
  SourceContainedApplications service;

  void conversation(String id, ScopeId scope, String project) {
    var admission =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            "@service/parent",
            account,
            scope,
            Home.of(project),
            recipient,
            List.of(
                new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
                new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID())));
    when(authority.conversation(id))
        .thenReturn(
            Optional.of(
                new OperationAuthority.Conversation(
                    id,
                    "worker",
                    admission,
                    null,
                    new AllowanceId(UUID.randomUUID()),
                    "0".repeat(64))));
  }

  ContainedApplicationRepository.Retained child(ScopeId scope, ScopeId container, boolean live) {
    var retained =
        new ContainedApplicationRepository.Retained(
            UUID.randomUUID(),
            scope,
            42,
            "contained:" + scope.value(),
            container,
            UUID.randomUUID(),
            "a".repeat(64),
            UUID.randomUUID(),
            new AllowanceId(UUID.randomUUID()),
            5,
            live);
    when(records.find(retained.id())).thenReturn(Optional.of(retained));
    when(records.ofScope(scope)).thenReturn(Optional.of(retained));
    return retained;
  }

  /** Writes a child's staged source where the creation path puts it. */
  void stage(ContainedApplicationRepository.Retained child, String path, String text)
      throws IOException {
    Path file =
        root.resolve(child.scope().value().toString())
            .resolve("revisions")
            .resolve(child.revision().toString())
            .resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  SourceContainedApplications service(ContainmentConfig config) {
    return new SourceContainedApplications(
        records,
        mock(ProjectWorkspaces.class),
        mock(ApplicationPackageValidator.class),
        authority,
        mock(io.aeyer.plowshare.server.access.IdentityRepository.class),
        mock(ContainedPrincipals.class),
        mock(AllowanceRepository.class),
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(java.util.function.Supplier<T> body) {
            return body.get();
          }
        },
        config,
        new InProcessContainedStaging(),
        mock(io.aeyer.plowshare.server.agents.Runs.class),
        // No run is going in any conversation here; stopping one is ContainedCancellationTest's.
        conversation -> 0);
  }

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("containment"));
    when(records.ofScope(any())).thenReturn(Optional.empty());
    when(records.find(any())).thenReturn(Optional.empty());
    service = service(new ContainmentConfig(root.toString(), 3, 4, null));
  }

  @Test
  void aContainerListsItsChildsResources() throws Exception {
    conversation("c-parent", parentScope, "parent");
    var first = child(firstScope, parentScope, true);
    stage(first, "plowshare.json", "{\"name\":\"x\"}");
    stage(first, "docs/notes.md", "hello");

    var resources = service.resources("@service/parent", "c-parent", first.id());

    assertEquals(
        List.of(
            new ContainedApplications.Resource("docs/notes.md", "file", first.revision(), 5),
            new ContainedApplications.Resource("plowshare.json", "manifest", first.revision(), 12)),
        resources);
  }

  @Test
  void aGrandparentListsThroughTheChain() throws Exception {
    conversation("c-parent", parentScope, "parent");
    child(firstScope, parentScope, true);
    var grandchild = child(grandchildScope, firstScope, true);
    stage(grandchild, "a.txt", "abc");

    var resources = service.resources("@service/parent", "c-parent", grandchild.id());

    assertEquals(1, resources.size());
    assertEquals("a.txt", resources.get(0).path());
    assertEquals(3, resources.get(0).bytes());
  }

  @Test
  void aChildCannotListItsContainersResources() throws Exception {
    var middle = child(firstScope, parentScope, true);
    var grandchild = child(grandchildScope, firstScope, true);
    stage(middle, "secret.txt", "the container's own source");
    conversation("c-grandchild", grandchildScope, grandchild.project());

    var refused =
        assertThrows(
            CallerFault.class,
            () -> service.resources("@service/parent", "c-grandchild", middle.id()));

    assertEquals("This Application does not contain that Application", refused.getMessage());
  }

  @Test
  void aSiblingCannotListASiblingsResources() throws Exception {
    var first = child(firstScope, parentScope, true);
    var second = child(secondScope, parentScope, true);
    stage(second, "secret.txt", "a sibling's source");
    conversation("c-first", firstScope, first.project());

    var refused =
        assertThrows(
            CallerFault.class, () -> service.resources("@service/parent", "c-first", second.id()));

    assertEquals("This Application does not contain that Application", refused.getMessage());
  }

  @Test
  void aDestroyedChildRefusesRatherThanReturningHistory() throws Exception {
    conversation("c-parent", parentScope, "parent");
    var gone = child(firstScope, parentScope, false);
    // Even if a source area survived an interrupted collection, a destroyed child has no resources.
    stage(gone, "plowshare.json", "{}");

    assertEquals(
        "That contained Application has been destroyed",
        assertThrows(
                CallerFault.class,
                () -> service.resources("@service/parent", "c-parent", gone.id()))
            .getMessage());
  }

  /**
   * After a <em>real</em> destruction the two reads refuse identically, and ADR 0042's claim that
   * {@code trajectory} answers for a destroyed child where {@code resources} refuses one is void.
   *
   * <p>The claim was never true of this code. Destruction deletes the child's project row and
   * {@code contained_applications.project_id} is {@code ON DELETE CASCADE} (V219), so afterwards
   * {@code records.find} answers empty — and <em>both</em> methods open with {@code
   * records.find(child).orElseThrow(...)} before anything else. The retained conversation lineage
   * does survive the destruction, which is what the record said; what it got wrong is that nothing
   * can read it, because the row that resolves the child is gone.
   *
   * <p>{@link #aDestroyedChildRefusesRatherThanReturningHistory()} above exercises the {@code
   * live=false} record instead, which is a state production never produces: V219 declares the
   * column {@code NOT NULL DEFAULT TRUE} and nothing writes it false. So that test pins a guard and
   * this one pins the path a destruction actually takes.
   *
   * <p>Controls first, in the same fixture: both reads answer for the live child.
   */
  @Test
  void aRealDestructionMakesBothReadsRefuseIdentically() throws Exception {
    conversation("c-parent", parentScope, "parent");
    var gone = child(firstScope, parentScope, true);
    stage(gone, "plowshare.json", "{}");

    // CONTROL: while the row resolves, resources lists and trajectory answers.
    assertEquals(1, service.resources("@service/parent", "c-parent", gone.id()).size());
    assertEquals(List.of(), service.trajectory("@service/parent", "c-parent", gone.id()));

    // The post-destruction state the cascade produces: no record, with the source area left on
    // disk exactly as an interrupted collection would leave it.
    when(records.find(gone.id())).thenReturn(Optional.empty());

    String trajectory =
        assertThrows(
                CallerFault.class,
                () -> service.trajectory("@service/parent", "c-parent", gone.id()))
            .getMessage();
    String resources =
        assertThrows(
                CallerFault.class,
                () -> service.resources("@service/parent", "c-parent", gone.id()))
            .getMessage();

    assertEquals("No such contained Application", trajectory);
    assertEquals(trajectory, resources, "the two reads refuse a destroyed child identically");
  }

  @Test
  void anUnconfiguredContainmentRootRefuses() {
    var unconfigured = service(ContainmentConfig.absent());
    conversation("c-parent", parentScope, "parent");
    var first = child(firstScope, parentScope, true);

    // The configuration guard is what fires, not the ancestor gate's depth bound.
    assertEquals(
        "Containment is not configured",
        assertThrows(
                CallerFault.class,
                () -> unconfigured.resources("@service/parent", "c-parent", first.id()))
            .getMessage());
  }

  @Test
  void aSourceAreaPastThePackageLimitRefusesRatherThanBeingListed() throws Exception {
    conversation("c-parent", parentScope, "parent");
    var first = child(firstScope, parentScope, true);
    for (int n = 0; n < 128; n++) stage(first, "f" + n + ".txt", "x");
    assertEquals(128, service.resources("@service/parent", "c-parent", first.id()).size());

    stage(first, "one-too-many.txt", "x");

    assertThrows(
        IllegalStateException.class,
        () -> service.resources("@service/parent", "c-parent", first.id()));
  }

  @Test
  void aLiveChildWithNoSourceAreaRefusesRatherThanAnsweringEmpty() {
    conversation("c-parent", parentScope, "parent");
    var first = child(firstScope, parentScope, true);

    assertThrows(
        IllegalStateException.class,
        () -> service.resources("@service/parent", "c-parent", first.id()));
  }

  @Test
  void aLinkInsideTheSourceIsNeitherFollowedNorListed() throws Exception {
    conversation("c-parent", parentScope, "parent");
    var first = child(firstScope, parentScope, true);
    stage(first, "real.txt", "x");
    Path outside = Files.writeString(directory.resolve("outside.txt"), "outside the area");
    Path staged =
        root.resolve(firstScope.value().toString())
            .resolve("revisions")
            .resolve(first.revision().toString());
    Files.createSymbolicLink(staged.resolve("link.txt"), outside);

    var resources = service.resources("@service/parent", "c-parent", first.id());

    assertEquals(List.of("real.txt"), resources.stream().map(r -> r.path()).toList());
  }

  @Test
  void aResourceCarriesNoContent() {
    var components =
        Arrays.stream(ContainedApplications.Resource.class.getRecordComponents())
            .map(c -> c.getName())
            .toList();
    assertEquals(List.of("path", "kind", "revision", "bytes"), components);
  }
}
