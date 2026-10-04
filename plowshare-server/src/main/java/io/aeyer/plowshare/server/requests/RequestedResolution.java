package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The two things a caller has to say when it settles a promotion proposal: which way, and who
 * decided.
 *
 * <p>Moved out of {@code ProposalController.resolve}, where both were presence checks on the
 * request's own fields — no queue, no archive, nothing beyond a boolean and a string — which is
 * {@link RequestedInvalidation}'s shape exactly and this package's reason for existing. The {@code
 * proposal.resolve} frame binds the same record and has to refuse the same bodies in the same
 * words.
 *
 * <h2>Why not into {@code PromotionQueue}, where the settlement itself lives</h2>
 *
 * <p>Because neither refusal needs the queue to decide it, and because putting them there would
 * make them unreachable from the one place both surfaces are compared: {@code
 * ProposalControllerTest} and {@code ProposalFramesTest} both drive a <em>mocked</em> queue — what
 * approving really does is {@code PromotionQueueTest}'s subject against a real Postgres — so a rule
 * inside a mocked method is a rule neither surface would actually run. Here it runs on both, and
 * {@code FrameParity.assertSameRefusal} compares the sentence.
 *
 * <h2>Two methods rather than one taking both fields</h2>
 *
 * <p>{@link RequestedInvalidation}'s argument, unchanged: the two refusals are two sentences and a
 * caller that got one of them wrong should be told which. The order is the endpoint's own — which
 * way first, then who.
 */
public final class RequestedResolution {

  private RequestedResolution() {}

  /**
   * Which way this settlement went, or a {@link CallerFault} for one that said neither. The
   * sentence is the endpoint's own, word for word.
   *
   * <p><b>There is no default, and that is the content of the refusal rather than its shape.</b> A
   * settled proposal is never re-opened by settling it again, so a guess here would be permanent —
   * which is why an absent {@code accept} cannot be read as either a rejection (silently dropping a
   * memory from global) or an approval (silently promoting one).
   *
   * @param accept the request's own {@code accept} field
   * @throws CallerFault if {@code accept} is null
   */
  public static boolean accepted(Boolean accept) {
    if (accept == null) {
      throw new CallerFault(
          "resolving a proposal needs 'accept': true to promote the memory, false to"
              + " refuse it. There is no default, because a settled proposal is"
              + " never re-opened and a guess here would be permanent");
    }
    return accept;
  }

  /**
   * Who decided, or a {@link CallerFault} for a settlement nobody signed. The sentence is the
   * endpoint's own, word for word.
   *
   * <p>Blank as well as absent, on {@link RequestedInvalidation}'s reasoning: an empty string
   * passes a null check while being exactly the absence the rule is about, and it is what an unset
   * form field arrives as.
   *
   * @param by the request's own {@code by} field
   * @throws CallerFault if {@code by} is null or blank
   */
  public static String by(String by) {
    if (by == null || by.isBlank()) {
      throw new CallerFault(
          "resolving a proposal needs 'by': who decided. It is what tells a person's"
              + " decision from the curator's own on a row read months later");
    }
    return by;
  }
}
