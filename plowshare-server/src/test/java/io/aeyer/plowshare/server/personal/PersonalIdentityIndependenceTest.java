package io.aeyer.plowshare.server.personal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import org.junit.jupiter.api.Test;

/**
 * A personal space's <b>identity</b> — its project row and the owner's grant on it — must not
 * depend on its <b>storage</b> being allocated on disk.
 *
 * <p>An account is an identity and the personal space is the resource it resolves to. Adding a
 * document to that resource is a database operation: it needs the project and the owner's
 * permission on it, and nothing on disk. Allocating the union is a separate concern, for workspace
 * and file operations.
 *
 * <p>These two were coupled, and the coupling only became load-bearing once a personal selection
 * started being governed by the personal project's permissions. {@code ensure} refused outright
 * without a data directory:
 *
 * <pre>{@code
 * if (!data.keepsAnything())
 *   throw new IllegalStateException("Personal spaces require PLOWSHARE_DATA_DIR");
 * }</pre>
 *
 * <p>and the owner's MANAGER grant was inserted by {@code initialized}, which runs <em>after</em>
 * the skeleton is written and the tree committed. So on a server with no data directory, or for an
 * account whose storage provisioning failed, there was no project and no grant — and therefore no
 * way to add a personal document at all, through the CLI or anything else.
 *
 * <p>{@code DataLayout.keepsAnything()} is {@code root != null}, so a server without a data
 * directory is a supported configuration rather than a hypothetical one.
 */
class PersonalIdentityIndependenceTest {
  private final PersonalSpaceRepository repository = mock(PersonalSpaceRepository.class);

  /** A layout that keeps nothing: the supported no-data-directory configuration. */
  private static DataLayout withoutDataDirectory() {
    var layout = mock(DataLayout.class);
    when(layout.keepsAnything()).thenReturn(false);
    return layout;
  }

  private PersonalSpaces spaces(DataLayout layout) {
    return new PersonalSpaces(repository, UnitOfWork.NONE, layout);
  }

  @Test
  void the_personal_identity_is_provisioned_without_a_data_directory() {
    when(repository.serviceAccount("alice")).thenReturn(false);
    when(repository.reserve("alice")).thenReturn(new PersonalSpaceRepository.Reserved(7L, false));

    // The identity is a database fact and must not require storage.
    assertDoesNotThrow(() -> spaces(withoutDataDirectory()).ensureIdentity("alice"));
    verify(repository).reserve("alice");
    verify(repository).owns(7L, "alice");
  }

  @Test
  void a_service_account_still_has_no_personal_identity() {
    when(repository.serviceAccount("@service/fixture")).thenReturn(true);
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> spaces(withoutDataDirectory()).ensureIdentity("@service/fixture"));
    verify(repository, never()).reserve(anyString());
  }

  /**
   * The project is available as soon as the account is created. Whether its contents get deployed
   * is a separate matter: the identity commits in its own transaction before storage is attempted,
   * so a storage failure at creation leaves an account that can already hold documents, and the
   * boot-time reconciliation retries the allocation.
   */
  @Test
  void account_creation_leaves_the_project_available_even_if_contents_cannot_be_deployed() {
    when(repository.serviceAccount("alice")).thenReturn(false);
    when(repository.reserve("alice")).thenReturn(new PersonalSpaceRepository.Reserved(7L, false));
    var spaces = spaces(withoutDataDirectory());

    assertThrows(
        IllegalStateException.class,
        () -> spaces.created(new PersonalSpaces.AccountCreated("alice")));

    // The identity is there regardless of the contents.
    verify(repository).reserve("alice");
    verify(repository).owns(7L, "alice");
  }

  /**
   * Storage allocation keeps its own requirement, stated separately. The identity is still
   * provisioned first and survives, so the account can hold documents even though its union could
   * not be allocated — which is the separation this class exists for.
   */
  @Test
  void allocating_storage_still_requires_a_data_directory_after_the_identity_exists() {
    when(repository.serviceAccount("alice")).thenReturn(false);
    when(repository.reserve("alice")).thenReturn(new PersonalSpaceRepository.Reserved(7L, false));
    assertThrows(IllegalStateException.class, () -> spaces(withoutDataDirectory()).ensure("alice"));
    verify(repository).owns(7L, "alice");
    verify(repository, never()).initialized(anyLong(), anyString());
  }
}
