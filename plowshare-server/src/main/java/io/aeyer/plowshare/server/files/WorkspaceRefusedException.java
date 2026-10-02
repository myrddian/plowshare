package io.aeyer.plowshare.server.files;

/**
 * A file request the caller had no business making.
 *
 * <p>The path is outside every root, the pattern is absolute, the file is a
 * directory or is not text. <b>The model that asked can correct it on its next
 * turn</b>, so this becomes a tool result naming the refusal rather than an
 * ending — 3a's split, unchanged, and the same one {@code memory_recall}'s
 * {@code limit} already uses.
 *
 * <h2>Deliberately not a relative of {@link WorkspaceUnavailableException}</h2>
 *
 * <p>Not a subclass of it, not a sibling under a shared {@code
 * WorkspaceException}, and not merged with it under a friendlier name. The two
 * mean opposite things to a run: this one continues it and that one ends it. A
 * common supertype is a {@code catch} clause waiting to collapse them, and the
 * collapse has a direction that is not symmetrical — an outage handed back as
 * "you may try something else" invites the retry loop {@link
 * io.aeyer.plowshare.server.archive.ArchiveUnavailableException} was created to
 * stop, one layer down.
 *
 * <p>The pressure to merge them is real and was recorded in the plan before
 * either existed: renaming this type to {@code …Unavailable} is exactly the
 * collapse {@link SessionGoneException} depends on not happening, because that
 * one is defined as "the party that owns the files went away" and cannot be told
 * apart from a mistyped path once both arrive as one type.
 *
 * <h2>The question that decides it, for the next split as well as this one</h2>
 *
 * <p><b>Does collapsing the two change the run's <em>fate</em>?</b> That is the
 * whole criterion, and it is written here rather than beside whichever split
 * last needed it, because this is the class that argues the family's supertype
 * policy and therefore the class somebody deciding a <em>third</em> case reaches
 * first.
 *
 * <p><b>And fate is decided in exactly two places, which this used to leave the
 * reader to find.</b> {@code JobRuntime.dependencyFailure} — four {@code
 * instanceof} tests naming the types that end a run — and the {@code instanceof}
 * ladder in {@code JobRuntime.run}'s tool-dispatch {@code catch}, which takes
 * {@link SessionGoneException} above that call. Everything they do not name is a
 * tool result and the run continues. <b>Naming them is not a convenience: it is
 * what makes the question answerable by reading rather than by reasoning</b>,
 * and the worked example below is retracted precisely because it was reasoned.
 *
 * <ul>
 *   <li><b>Yes → no shared supertype.</b> This split: a refusal continues a run
 *       and an outage ends it, so a {@code catch} taking them together ends a
 *       run over a mistyped path, or invites retries against a dead disk. Which
 *       of the two harms you get depends on which way the collapse went, and
 *       neither is recoverable by anything downstream.
 *   <li><b>No → a subclass is available, and is then a trade rather than a
 *       gift.</b> {@link SessionGoneException} narrows {@link
 *       WorkspaceUnavailableException}: both endings stop the run, so a collapse
 *       costs an operator a distinction and cannot cost the job an outcome. What
 *       the subclass buys — a handler that never learned about it still behaves
 *       correctly — has to be weighed against every handler that will now catch
 *       it <em>too</em>, which is not a neutral fact and is argued in full over
 *       there.
 * </ul>
 *
 * <h2>RETRACTED: the worked example this class used to end on</h2>
 *
 * <p>It read: <em>"Named for the case that is coming: {@code ArchiveException}'s
 * split into absent and refused is the same question one package over, and its
 * answer is the first bullet — an absent id is a caller's mistake the model
 * corrects, and a refusal is not. That is a fate difference, so it wants two
 * types rather than a subclass."</em> <b>The criterion stands; that answer is
 * wrong, and the tree already disagrees with it</b> — {@code
 * ArchiveRefusedException extends ArchiveException} ships in {@code archive/}.
 *
 * <p>Wrong because the premise was never checked against the two sites above.
 * {@code JobRuntime.dependencyFailure} names four types and <em>excludes {@code
 * ArchiveException} by name</em>, arguing in its own javadoc that it "is a tool
 * result, because that is a mistake a model can correct"; the ladder above it
 * takes only {@link SessionGoneException}, which no archive type can be. So an
 * absent id and a refusal end a run <b>identically</b>, which is to say neither
 * ends one — the second bullet, not the first. The premise is also false on its
 * own terms: "proposal X was already settled by enzo" is exactly a thing a model
 * stops doing on its next turn.
 *
 * <p><b>Broadening "fate" to include the HTTP status does not rescue it</b>, and
 * that is worth saying because it is the obvious repair. 404 against 409 is real
 * and is what the split buys — but it is delivered <em>by</em> the subclass, so
 * it cannot be the reason to reject one.
 *
 * <p>The retraction is here rather than only in {@code ArchiveRefusedException}
 * because <b>this is the class a reader deciding a third case opens first</b>,
 * by this file's own claim four paragraphs up: a rebuttal that lived only in the
 * class being argued about would never be reached by the reader the argument was
 * written for. That class carries the full account, every archive site's
 * classification, and the compile-time hold; nothing of it is repeated here.
 *
 * <h2>Why it carries a whole sentence and not a code</h2>
 *
 * <p>Its message is read by a language model, so it says which of the several
 * states holds rather than only that something is wrong. "Outside every root",
 * "no workspace is defined for this project", "the global tier has no
 * workspace" and "this agent was granted no workspace" are four different
 * fixes — one for the model, one for the operator, one for whoever chose the
 * tier, one for the agent's definition. Excalibur split its own {@code
 * NO_ROOTS} and {@code NO_WORKSPACE} apart after collapsing them sent readers
 * to fix the wrong thing.
 */
public class WorkspaceRefusedException extends RuntimeException {

    public WorkspaceRefusedException(String message) {
        super(message);
    }
}
