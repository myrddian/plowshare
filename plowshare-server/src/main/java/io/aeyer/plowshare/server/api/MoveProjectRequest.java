package io.aeyer.plowshare.server.api;

/**
 * The body of {@code POST /v1/projects/{name}/move}.
 *
 * <p>One field, and the project being moved is in the path rather than the body for {@link
 * SetWorkspaceRequest}'s reason: the name it has now is what is being addressed, and the name it is
 * getting is what is being set.
 *
 * <p><b>{@code to} and not {@code name}</b>, although the column it lands in is {@code
 * projects.name}. A body holding {@code name} beside a path segment holding a different name is two
 * fields with one word for two things, and the request that gets them the wrong way round is a
 * project silently renamed to itself while the one somebody meant to move is untouched.
 *
 * @param to what the project should be called. Not checked for shape here: a project's canonical
 *     name is {@code <MACHINE>/<PATH>/<PROJ_NAME>}, and nothing on this server composes one — a
 *     presence does, on the machine that holds the files — so this endpoint takes the name it is
 *     given and {@code ProjectStore.rename} refuses only what no row could hold
 */
public record MoveProjectRequest(String to) {}
