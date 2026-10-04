package io.aeyer.plowshare.server.files;

/**
 * The kinds of place an agent can be granted access to.
 *
 * <p><b>One value, and the second one from the source deliberately did not come.</b> Excalibur's
 * catalogue also has {@code ARCHIVE} — its {@code data/archive/} directory, the memory files as
 * files, for an agent that greps them rather than going through the memory tools. Plowshare's
 * archive is Postgres. There is no directory to name, so that scope would be a grant nothing could
 * ever satisfy: it would read as access in a definition, pass the load-time check, and resolve to
 * no root at run time — which is worse than no grant at all, because the definition looks like it
 * was honoured and the agent reports an empty result rather than a refusal. {@code
 * the_scope_catalogue_names_only_the_workspace} is what holds this to one value.
 *
 * <p><b>Re-read since the server got a data directory, because half of that argument stopped being
 * true and the conclusion did not.</b> "There is no directory to name" was a claim about the whole
 * server, and it has been false since {@code DataLayout}: {@code
 * &lt;data-dir&gt;/projects/&lt;id&gt;/exports/} is a real directory, holding real files, keyed by
 * a real project. What survives, and is the part that was ever load-bearing, is that <b>the archive
 * is still rows</b> — memories are what an {@code ARCHIVE} scope would have been a grant over, and
 * no scope could resolve to a root for them today any more than before.
 *
 * <p>So the data directory is not an argument for a second constant; it is an argument against one,
 * and a different one. Excalibur let an agent grep its own archive. Here the tree is fenced
 * entirely — a mandatory exclusion, no scope, no grant expressible — because <b>what is in it is
 * other people's content</b>: exports hold conversations' ejected payloads, and an image reaches a
 * model by being attached by the server rather than read by an agent. <b>That sentence was written
 * in the future tense and is now the present one.</b> {@code ImageStore} is the second tenant of
 * the fenced tree, and the shape held: {@code POST /v1/images} is the producer, and a run is handed
 * a UID in its submission which {@code agents.Pictures} turns into bytes on the request thread.
 * <b>A tool can name an image now — {@code agent_run} takes ids, and since 2026-09-08 ids the
 * caller was told and not only ones it was shown, which makes it the second reader of that
 * store.</b> The fence was not opened a crack for it and did not have to be: what the tool gets
 * back is a part on the callee's model request, never a byte an agent reads, and the directory
 * stays as unreachable to {@code file_read} as it was. An agent names a thing and the server
 * decides what that means — the reasoning that keeps {@code project_lend} out of {@code
 * knownTools()}, applied to files.
 *
 * <p>There is no {@code data} or {@code config} scope either, for the reason Excalibur gives for
 * refusing its own: the server's configuration, its agents directory, its own operator token and
 * the conversation payloads it has ejected to disk belong to no agent. <b>The last of those is not
 * the server's secret but other people's content</b>, and it is named here because it is the first
 * entry of that kind — Excalibur let an agent grep its own archive, and this does not. Here none of
 * it is a missing enum constant but <b>two mechanisms below this enum, neither of them a scope</b>:
 *
 * <ul>
 *   <li>exclusions applied to every root a project names, whatever it names and whether it is the
 *       workspace or lent beside it — see {@code ProjectStore.mandatoryExclusions}, which owns that
 *       rule and its argument, and which states it as a location rather than as a list of files, so
 *       how many paths it works out to on a given deployment is not something to count on here;
 *   <li>and a rule that is not a list at all: {@code
 *       io.aeyer.plowshare.protocol.FileAccess#permits} refuses any candidate with a dot-prefixed
 *       component below its root. That one is not per-workspace and is not the server's own paths —
 *       it is every hidden directory a workspace might contain, on both machines, and it is why an
 *       earlier version of this paragraph naming only the exclusions was describing half of what
 *       stands between an agent and a credential.
 * </ul>
 */
public enum Scope {

  /**
   * The directories this job's project names: its workspace, and whatever is lent alongside it.
   *
   * <p><b>Plural since V30, and the name is still right.</b> One grant, one scope — but a project's
   * leash is {@code ProjectRecord.roots()}, which is the workspace prepended to the {@code lent}
   * column, so this constant names the <em>kind</em> of place and not a count of them. It said "the
   * directory" while a project had exactly one, and a reader who kept that reading would conclude
   * that a lent root needs a scope of its own, which is the enum value this class exists to argue
   * against adding.
   *
   * <p>Which directories those are comes from the {@code projects} row and never from the agent,
   * which is what makes the leash a leash — and is why lending is withheld from every agent tool:
   * the party that sets the leash must not be a party the leash binds.
   */
  WORKSPACE
}
