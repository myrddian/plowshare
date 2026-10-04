package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.archive.PromotionQueue;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code ProposalController}'s area of the frame surface — every {@code proposal.*} type this
 * server answers, which is all three of that controller's endpoints.
 *
 * <h2>One service, and it is the controller's own</h2>
 *
 * <p>{@link PromotionQueue} is the same bean {@code ProposalController} is injected with, which is
 * what makes a frame's answer the endpoint's answer rather than a second one shaped like it. It
 * matters more here than in most areas: that class exists to hold an ordering — <b>the claim on the
 * proposal is taken before the promotion</b>, because {@code Archive.promote} explicitly does not
 * guard against two concurrent promotions and says the queue's unique index is what does, and the
 * two cannot be one transaction because {@code promote} calls the embedding model after closing its
 * own unit of work. {@code resolve}, {@code note} and {@code release} are package-private for
 * exactly that reason, so this surface could not bypass the ordering if it tried.
 *
 * <p><b>Nothing here is runtime state, and that was checked rather than assumed</b> — the check
 * {@code ProjectController}'s {@code PresenceRegistry} made compulsory. This controller's
 * constructor takes one queue; that queue holds a {@code ProposalStore} and an {@code Archive},
 * both of which are a database away, and neither holds a session.
 *
 * <p>One of the {@link FrameArea} classes that interface's javadoc describes.
 */
@Component
public class ProposalFrames implements FrameArea {

  private final PromotionQueue queue;

  /**
   * @param queue the one queue every handler below settles and reads through — the same bean {@code
   *     ProposalController} is handed
   */
  public ProposalFrames(PromotionQueue queue) {
    this.queue = Objects.requireNonNull(queue, "queue");
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.PROPOSAL_LIST, new ProposalListHandler(queue),
        FrameTypes.PROPOSAL_RECONSIDER, new ProposalReconsiderHandler(queue),
        FrameTypes.PROPOSAL_RESOLVE, new ProposalResolveHandler(queue));
  }
}
