package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Characterization pins for how a PERSONAL selection resolves today, taken before making {@code
 * kind:personal} an alias for the account's personal project.
 *
 * <p><b>The intended model, which these pins do not yet implement.</b> An account is an account: an
 * identity, not a container. What gives a user account things to hold is that it resolves to a
 * personal space, and a personal space is fundamentally a <b>project</b> — as are Applications.
 * Projects in any form are the resources; accounts are the identities that reach them. So {@code
 * kind:personal} is an <em>alias</em> for {@code {kind:"project", project:"personal:<hex>"}}, and
 * the {@code ACCOUNT_KNOWLEDGE} scope is the same alias to the same resource rather than a separate
 * home.
 *
 * <p><b>What today does instead.</b> {@link InformationContext.Selection} forbids a project on any
 * non-PROJECT scope, so a PERSONAL selection carries {@code project() == null}, and {@link
 * InformationContext#namespace()} spells it {@code "account:" + account} — the same family as
 * ACCOUNT. The live SQL resolver keys the stored scope off that namespace:
 *
 * <pre>{@code
 * -- information_pre_account_home
 * ($1 IS NOT NULL AND s.project_id=$1 AND … $2='project:'||(SELECT name FROM projects WHERE id=$1))
 * OR ($1 IS NULL AND s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=(…legacy_handle=$3)
 *     AND $2='account:'||$3)
 * }</pre>
 *
 * <p>A null project with an {@code account:} namespace therefore takes the second branch, and a
 * personal document is filed under the account's knowledge scope instead of its personal project.
 * Verified against the deployed server: ten documents uploaded as {@code {kind:"personal"}} are all
 * {@code ACCOUNT_KNOWLEDGE} with a null project, while the same home addressed as a project is
 * {@code PERSONAL} with a real project.
 *
 * <p><b>Why these pins matter beyond the namespace.</b> Resolving the alias moves a personal
 * selection onto the PROJECT branch of {@link InformationAccess#requireSelection} and {@link
 * InformationAccess#requireWork}, which demand project READ and CONTRIBUTOR respectively. Those
 * checks do not run for a PERSONAL selection today. The pins below record both the current refusals
 * and what the project checks answer for a personal project, so that making the alias cannot
 * silently open or close access.
 */
class PersonalSelectionAliasPinTest {
  private static final String ALICE = "alice";

  /** The personal project's internal name: {@code personal:} plus the hex-encoded handle. */
  private static final String ALICE_PERSONAL = PersonalSpaces.name(ALICE);

  private final ProjectMembers members = mock(ProjectMembers.class);
  private final InformationAccess access =
      new InformationAccess(new EffectiveProjectPermissions(members));

  private InformationContext resolve(InformationContext.Selection selection) {
    return access.resolve(ALICE, selection);
  }

  // ------------------------------------------------- the two spellings, as they are today

  /**
   * The defect in one assertion: a PERSONAL selection names no project and is spelled into the
   * {@code account:} namespace family, which is what sends it to ACCOUNT_KNOWLEDGE in storage.
   */
  @Test
  void a_personal_selection_carries_no_project_and_an_account_namespace() {
    var context = resolve(InformationContext.Selection.personal());
    assertEquals(InformationContext.Scope.PERSONAL, context.selection().scope());
    assertNull(context.selection().project(), "Selection forbids a project on a non-PROJECT scope");
    assertEquals("account:" + ALICE, context.namespace());
  }

  /** The same home addressed as a project — the shape the alias should produce. */
  @Test
  void the_same_home_as_a_project_selection_carries_the_project_and_a_project_namespace() {
    when(members.role(ALICE_PERSONAL, ALICE)).thenReturn(Optional.of(ProjectRole.MANAGER));
    var context = resolve(InformationContext.Selection.project(ALICE_PERSONAL));
    assertEquals(InformationContext.Scope.PROJECT, context.selection().scope());
    assertEquals(ALICE_PERSONAL, context.selection().project());
    assertEquals("project:" + ALICE_PERSONAL, context.namespace());
  }

  /** Both spellings name one resource, and today they disagree about how to say so. */
  @Test
  void the_two_spellings_of_one_home_produce_different_namespaces() {
    when(members.role(ALICE_PERSONAL, ALICE)).thenReturn(Optional.of(ProjectRole.MANAGER));
    assertNotEquals(
        resolve(InformationContext.Selection.personal()).namespace(),
        resolve(InformationContext.Selection.project(ALICE_PERSONAL)).namespace());
  }

  // ------------------------------------------- guards that must survive resolving the alias

  /**
   * A service token may not select Personal. This is expressed against {@code Scope.PERSONAL}, so
   * resolving the alias before this check would stop it firing — it must run first, or be
   * re-expressed against the resolved personal project.
   */
  @Test
  void a_service_token_may_not_select_personal() {
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () -> access.resolve("@service/fixture", InformationContext.Selection.personal()));
    assertEquals("Service tokens cannot select Personal information", refused.getMessage());
  }

  /**
   * Today a PERSONAL selection reaches no project permission check at all: it resolves with no
   * project role configured. After the alias it will take the PROJECT branch, which demands READ,
   * so this pin records the gap the change has to cover rather than open.
   */
  @Test
  void a_personal_selection_needs_no_project_role_today() {
    when(members.role(anyString(), anyString())).thenReturn(Optional.empty());
    assertDoesNotThrow(() -> resolve(InformationContext.Selection.personal()));
  }

  /** The project spelling already demands READ, and refuses a caller with no role. */
  @Test
  void the_project_spelling_already_demands_read_on_that_project() {
    when(members.role(ALICE_PERSONAL, ALICE)).thenReturn(Optional.empty());
    assertThrows(
        CallerFault.class, () -> resolve(InformationContext.Selection.project(ALICE_PERSONAL)));
  }

  /**
   * The owner of a personal project holds MANAGER, so the PROJECT checks the alias introduces are
   * satisfiable for its owner — both the READ in {@code requireSelection} and the CONTRIBUTOR in
   * {@code requireWork}. Without this the alias would close personal writes.
   */
  @Test
  void the_owner_of_a_personal_project_passes_both_project_checks() {
    when(members.role(ALICE_PERSONAL, ALICE)).thenReturn(Optional.of(ProjectRole.MANAGER));
    var context = resolve(InformationContext.Selection.project(ALICE_PERSONAL));
    assertDoesNotThrow(() -> access.requireSelection(context));
    assertDoesNotThrow(() -> access.requireWork(context));
  }

  /** Another account's personal project is refused through the project spelling. */
  @Test
  void another_accounts_personal_project_is_refused() {
    String bobPersonal = PersonalSpaces.name("bob");
    when(members.role(bobPersonal, ALICE)).thenReturn(Optional.empty());
    assertThrows(
        CallerFault.class, () -> resolve(InformationContext.Selection.project(bobPersonal)));
  }

  // --------------------------------------------------------------- the ACCOUNT spelling

  /**
   * {@code Scope.ACCOUNT} can only ever name GLOBAL — any other id is refused outright — so the
   * ACCOUNT spelling is not a general per-account home at the request boundary even though {@code
   * ACCOUNT_KNOWLEDGE} scope rows exist per account in storage.
   */
  @Test
  void an_account_selection_may_only_name_global() {
    assertThrows(
        IllegalArgumentException.class,
        () -> InformationContext.Selection.account(java.util.UUID.randomUUID()));
    assertDoesNotThrow(
        () ->
            InformationContext.Selection.account(
                io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value()));
  }

  /**
   * GLOBAL's namespace is the {@code account:} family keyed by its UUID, which the live resolver
   * special-cases to {@code GLOBAL_KNOWLEDGE}. Pinned because GLOBAL has no project row, so
   * "ACCOUNT_KNOWLEDGE is an alias for the account's personal project" has no destination for
   * GLOBAL and that case needs deciding separately.
   */
  @Test
  void the_global_account_selection_is_spelled_in_the_account_namespace_family() {
    var global =
        resolve(
            InformationContext.Selection.account(
                io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value()));
    assertEquals(InformationContext.Scope.ACCOUNT, global.selection().scope());
    assertEquals(
        "account:" + io.aeyer.plowshare.server.access.BuiltInIdentities.GLOBAL.value(),
        global.namespace());
  }
}
