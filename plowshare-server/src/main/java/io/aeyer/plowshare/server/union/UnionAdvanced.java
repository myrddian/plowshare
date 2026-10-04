package io.aeyer.plowshare.server.union;

/**
 * A union's {@code main} moved. The seam the codebase index subscribes to: it asks the hub for
 * {@code git diff --name-status oldCommit..newCommit}.
 *
 * @param oldCommit null when this is the hub's first commit
 */
public record UnionAdvanced(String project, String oldCommit, String newCommit) {}
