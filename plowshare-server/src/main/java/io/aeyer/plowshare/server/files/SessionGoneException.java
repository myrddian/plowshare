package io.aeyer.plowshare.server.files;

/**
 * The client session whose files a run was working in went away.
 *
 * <p>A narrowing of {@link WorkspaceUnavailableException} and not a sibling of
 * it, for one reason stated as plainly as it can be: <b>a build that forgot to
 * look for this type still ends the run.</b> {@code
 * JobRuntime.dependencyFailure} names the supertype, so an implementation that
 * never learns about this one reports {@code UNAVAILABLE} — the previous
 * behaviour, which was already safe. A sibling type would have made that same
 * omission hand a model "the tool failed; you may try something else" about a
 * client that is not there any more, which is the retry loop this whole family
 * exists to stop.
 *
 * <p><b>And the other direction, which the first version of this paragraph left
 * out.</b> A subclass is caught by <em>strictly more</em> handlers than a
 * sibling, not merely by the ones that were going to end the run anyway — and
 * there is one live place that matters. {@link ProviderRouter#providerFor}
 * catches the supertype and <b>continues</b>, so a session that went away is
 * <em>swallowed</em> whenever another provider covers the path, where a sibling
 * would have propagated and ended the run. That is the right answer and it is
 * that class's own rule rather than a special case for this type — the file was
 * reachable, and a provider that could not be asked serves nothing either way —
 * but it is the one place this ending can be lost by design, so
 * {@code a_session_that_went_away_is_swallowed_when_another_provider_covers_the_path}
 * pins it as intended rather than leaving a later reader to read it as an
 * oversight. The trace on that return is what an operator gets instead of an
 * ending. Iteration order can cost it too, one step further on:
 * {@code which_of_two_dead_providers_is_reported_decides_the_ending_as_well}.
 *
 * <p><b>Why this is not the collapse {@link WorkspaceRefusedException} refuses,
 * in one line and a pointer.</b> That class owns the criterion — <em>does
 * collapsing the two change the run's fate?</em> — and both answers, because it
 * is the class somebody deciding a third case reaches first. Here the answer is
 * no: both endings stop the run. What the subclass therefore costs is bounded by
 * what the ordering of two {@code instanceof} checks can cost, and {@code
 * a_disk_that_went_away_is_not_a_session_that_went_away} holds that ordering.
 *
 * <p>(Those two paragraphs argued the same thing seven lines apart for one
 * commit: the generalisation was added above the section it generalises without
 * folding the section in, leaving the heading that justified the anecdote
 * standing after the anecdote had become a special case of it.)
 *
 * <h2>What counts as gone, and what deliberately does not</h2>
 *
 * <p>{@code FileChannelHandler} is the only producer, and it raises this at the
 * sites where the socket is <b>known</b> to be gone: no session of that name is
 * registered, the session closed before the request could be put to it, the
 * session closed while the request was outstanding, a second connection took the
 * name over, and a write to the socket failed.
 *
 * <p><b>The deadline is not one of them</b>, and that is the choice this type
 * makes rather than an omission. A client that has not answered in thirty
 * seconds is <em>connected</em>: measured, a wedged client fires no event at all
 * and the session still reports itself open, so nothing can tell a wedged one
 * from a slow one — and an ending that said "the session went away" about a
 * laptop that is merely busy would be an ending naming a situation that does not
 * hold. The run stops either way; only the sentence differs, and the sentence is
 * the whole reason there are two endings.
 *
 * <p>The send lock's own timeout stays out on <b>conservative</b> grounds rather
 * than on a claim about the peer, and the difference is worth being exact about:
 * a first version of this paragraph said it fires because another request to the
 * same <em>live</em> client is not completing, which asserts more than the layer
 * can see — a peer can be dead while a blocked write has not yet errored. What
 * is actually known is narrower and is enough: <b>nothing at that point has
 * observed this socket fail</b>, and an ending that named a disappearance on no
 * evidence of one is the guess this type exists to avoid.
 *
 * <p>Two more are deliberately outside it. A request this server could not
 * serialise is this server's bug and must not be reported as a client's
 * disappearance; a wait that was interrupted was ended by this process, not by
 * the peer.
 *
 * <p><b>Where the ending's verb is looser than its site's sentence, said here
 * rather than left for a reader to notice.</b> "No session of that name is
 * registered" also covers a session that <em>never</em> connected — which is
 * exactly what a mis-wiring would produce, a run pointed at an id no client ever
 * opened — and "went away" implies a transition nothing verified. The
 * classification is still right, because the observable fact the ending rests on
 * is "there is no client of that name" and that plainly holds; it is the verb
 * that generalises across five sites. <b>The site's own sentence stays exact</b>
 * — it says the session is not connected and never that it left — and {@code
 * JobRuntime.describe} puts that sentence in the run's {@code detail}, so an
 * operator reading a run gets the family from the ending and the instance from
 * the detail.
 */
public class SessionGoneException extends WorkspaceUnavailableException {

    public SessionGoneException(String message) {
        super(message);
    }

    public SessionGoneException(String message, Throwable cause) {
        super(message, cause);
    }
}
