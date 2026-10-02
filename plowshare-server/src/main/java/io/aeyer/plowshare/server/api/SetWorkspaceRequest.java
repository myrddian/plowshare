package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/projects/{name}/workspace}.
 *
 * <p>One field, and the project is in the path rather than the body: this
 * endpoint moves a project that already exists, so the name is what is being
 * addressed rather than what is being set. {@code ProjectStore.moveWorkspace}
 * says why moving is not {@code define} with the old exclusions passed back in.
 *
 * @param workspace the directory to point it at
 */
public record SetWorkspaceRequest(String workspace) {}
