package io.aeyer.plowshare.server.personal;

import java.util.List;
import java.util.Optional;

/** Durable Personal identity ownership, distinct from creating its filesystem skeleton. */
public interface PersonalSpaceRepository {
  record Reserved(long id, boolean initialized) {}

  Optional<String> owner(long id);

  Optional<Long> id(String handle);

  Optional<Long> projectId(String project);

  List<String> accounts();

  boolean serviceAccount(String handle);

  /** Reserves and locks the account's project row inside the caller's transaction. */
  Reserved reserve(String handle);

  /** Records storage readiness and membership together inside the same transaction. */
  /**
   * Grants the owner its role on its own personal project. Separate from {@link #initialized(long,
   * String)} because the identity must not depend on storage being allocated.
   */
  void owns(long project, String handle);

  void initialized(long project, String handle);
}
