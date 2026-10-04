package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.FileAccess;

/**
 * One path, two machines that both claim it, and no tiebreak.
 *
 * <p>Within one filesystem "most specific root wins" is a statement about one tree, and {@link
 * FileAccess#permits} makes it. <b>Across providers it is not available</b>: {@code
 * /Users/example/proj/plowshare} on a laptop and the same string on the server are two different
 * files that share a name, so a tiebreak would silently pick one and the failure mode is reading
 * the wrong file and never knowing. The spec calls this out as the one place a rule is deliberately
 * not reused.
 *
 * <h2>Why it is a {@link WorkspaceRefusedException} and not an ending</h2>
 *
 * <p>A subclass, which is a relationship {@link WorkspaceUnavailableException} is deliberately
 * denied — and the asymmetry is the point rather than an oversight. Both this and its parent are
 * the <em>correctable</em> side of the split: everything answered, every other path on this run
 * still works, and a tool renders the sentence so the model can name a different file. Ending a run
 * here would take out a job that had a whole workspace left to do its work in. Collapsing the two
 * <em>sides</em> is what the file-level rule forbids; a second sentence on one side is not that.
 *
 * <p>It is a type of its own rather than a message because the fix is not the model's. The model
 * can move on, but somebody has pointed two providers at overlapping trees, and a caller that wants
 * to say so — task 7's channel, when a client re-advertises roots that collide with the server's —
 * needs to be able to name this case without matching on a string.
 *
 * <p>Its message names <b>every</b> provider that claimed the path. A refusal that says "ambiguous"
 * without saying between what cannot be acted on by anybody: not by the model, which does not know
 * what to avoid, and not by the operator, who has to find the two roots and change one.
 */
public class AmbiguousPathException extends WorkspaceRefusedException {

  public AmbiguousPathException(String message) {
    super(message);
  }
}
