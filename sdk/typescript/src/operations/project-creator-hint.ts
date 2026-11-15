/**
 * What a person is told, after the server's own words, when it refused a file
 * claim with close code 1008: the server uses that code both for an
 * unregistered name without the creator role and for an existing project the
 * account is not a member of, so the hint is conditional and names only what an
 * administrator can change. Shared so every client says it identically.
 */
export const PROJECT_CREATOR_HINT =
  'If this is a new project, a server administrator must grant you the project creator role.';
