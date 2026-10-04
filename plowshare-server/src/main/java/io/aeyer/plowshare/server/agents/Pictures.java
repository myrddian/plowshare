package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The UIDs a run named, resolved to the bytes it is shown — {@link Runs.Shown} itself, below the
 * surface that used to hold it.
 *
 * <h2>What it was waiting for</h2>
 *
 * <p>{@code Runs}' own javadoc records why this one part of {@code run} stayed on the HTTP surface
 * when the rest of the decision came down: it answers <b>404</b> for a UID that parses but names no
 * image this tier holds, and no fault below {@code api} mapped to 404. {@link NotFoundFault} is
 * that type now, so the decision could follow the others down — a frame handler asks for pictures
 * the same way and gets the same three refusals, rather than the 500 an unmapped {@code api}
 * exception would have become.
 *
 * <h2>On the asking thread, and not inside the run</h2>
 *
 * <p>{@code DocumentController}'s split exactly: this happens before a job id is handed back,
 * because a UID naming nothing is knowable in microseconds, and a caller handed an id and told to
 * poll it to discover that their picture was never going to be looked at is worse off than one
 * refused. It also keeps the resolution where the run's home already is, which is what makes "an
 * agent never holds image bytes" a property of the code rather than a rule somebody has to keep.
 *
 * <h2>Here rather than in {@code images}</h2>
 *
 * <p>Two of the three refusals are about the <em>agent</em>: whether it declared that it can see,
 * and which tier its run resolves ids against. {@link ImageStore} answers neither and should not
 * learn to — it holds pictures, not the rules about who may be shown one. So the collaborator is
 * the store and the subject is a run, which puts the file here beside {@link Runs}.
 *
 * <h2>The ordering, which is the half no status shows</h2>
 *
 * <p>{@link ImageStore#find} is asked before {@link ImageStore#dataUri} is called, deliberately, so
 * a UID this tier does not hold is a 404 naming it rather than what asking for absent bytes raises.
 * The two are different facts about the caller — one named something that is not an id, the other
 * named an id nothing here has — and only the order decides which one it is told. {@code
 * PicturesTest.a_uid_this_tier_does_not_hold_is_a_miss_and_not_a_ malformed_id} pins it.
 *
 * <h2>The property that keeps an agent from holding image bytes</h2>
 *
 * <p>This is not the only site that turns an id into bytes — {@code AgentRunTool} is the second and
 * last, since the owner decided on 2026-09-08 that a delegating agent may name an id it was told
 * and not only one it was shown. What is true of both, and is the load-bearing half: <b>a UID
 * resolves against the tier of the thing naming it, and nothing that names one chooses the
 * tier.</b> Here {@code home} is a parameter and comes from the run's own home; there it is a
 * parameter of {@code AgentTool.run}. Neither is anything a model wrote, and nothing agent-facing
 * enumerates images — {@code NothingEnumeratesImagesTest} — so an id can be used and not
 * discovered.
 */
@Service
public final class Pictures implements Runs.Shown {

  private final ImageStore images;

  public Pictures(ImageStore images) {
    this.images = images;
  }

  /**
   * The pictures, or a refusal naming which of the three things is wrong.
   *
   * <h2>The agent must have said it can see</h2>
   *
   * <p>This is the second half of a capability check. {@code AgentsConfig} refuses at boot an agent
   * that <em>declares</em> vision and names a model no pool declares as seeing; this refuses at
   * submission an agent that never declared it and is being handed a picture anyway. Without it,
   * the boot check is a check on a claim nobody has to make: an operator could show any agent an
   * image, the model would answer that it saw nothing, and the configuration would look correct
   * from every side.
   *
   * @param definition the agent the pictures are for
   * @param home the tier the ids resolve against — the run's own, never the caller's to choose
   * @param uids what the caller named, in the order it named them, or {@code null} for a caller
   *     that named nothing — see below on why null is a run that named none and not a caller
   *     mistake
   * @return the pictures in that same order, and an empty list for a run that named none, which is
   *     what {@code JobStore.submit} means by showing nothing
   * @throws CallerFault if the agent never declared {@code vision: true}, or if a string is not an
   *     image id at all
   * @throws NotFoundFault if an id is well formed and this tier holds no image under it
   */
  @Override
  public List<Content.Image> pictures(AgentDefinition definition, Home home, List<String> uids) {
    // Null is an absent list and not a caller mistake, which is exactly
    // what Runs.Ask's compact constructor already decides for the same
    // field -- `images = images == null ? List.of() : ...`. Only that
    // record's normalisation stood between this line and a
    // NullPointerException, and this is a public @Service a frame handler
    // calls directly, without a request body parsed into an Ask first. The
    // 500 was reachable by the new surface and by nothing else.
    if (uids == null || uids.isEmpty()) {
      return List.of();
    }
    if (!definition.vision()) {
      throw new CallerFault(
          "the agent '"
              + definition.name()
              + "' has not declared 'vision: true', so"
              + " this server will not show it a picture. A model that cannot see"
              + " answers that it saw nothing, which reads as the model's"
              + " limitation rather than as this run being misaddressed");
    }
    List<Content.Image> shown = new ArrayList<>(uids.size());
    for (String uid : uids) {
      if (!ImageStore.isUid(uid)) {
        throw new CallerFault(
            "'"
                + uid
                + "' is not an image id. An id is 'img_' and 32 hexadecimal"
                + " characters, which is what POST /v1/images answers with");
      }
      // find() first, so a UID this tier does not hold is a 404 naming it
      // rather than the 400 dataUri would raise. The two are different
      // facts: one is a caller naming something that is not an id, the
      // other is a caller naming an id nothing here has.
      if (images.find(home, uid).isEmpty()) {
        throw new NotFoundFault(
            "this server has no image "
                + uid
                + " for "
                + (home.isGlobal() ? "the global tier" : "the project " + home.project())
                + ". An image belongs to the tier it was uploaded to, so a run"
                + " in one project cannot be shown another's");
      }
      shown.add(new Content.Image(uid, images.dataUri(home, uid)));
    }
    return shown;
  }
}
