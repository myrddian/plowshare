package io.aeyer.plowshare.server.api;

import java.util.List;

/**
 * The body of {@code POST /v1/projects/{name}/lend} and of {@code POST /v1/projects/{name}/unlend}.
 *
 * <p><b>One record for both verbs, and that is a decision rather than reuse.</b> The two carry the
 * same field with the same meaning — a list of directories on the server's disk — and the only
 * thing that differs is which direction they move it, which is the path and not the body. Two
 * records would be two places for the same {@code @param} to go stale, and the failure they would
 * prevent — a caller posting a lend body to the unlend route — is not a failure: it is the same
 * request.
 *
 * <p>Not merged into one route taking {@code lend} and {@code unlend} lists either. That would make
 * "lend A and unlend A in one call" expressible, with no answer this server could defend, and would
 * let a caller who filled in the wrong field silently do the opposite of what they meant. {@code
 * ProjectController.move} draws the same line: a verb is a route.
 *
 * @param roots the directories. Required and non-empty for both verbs — {@code ProjectStore.lend}
 *     says why an empty list is a caller that lost its list rather than an operator's intent, which
 *     is the one thing an omitted key here would quietly become
 */
public record LendRequest(List<String> roots) {}
