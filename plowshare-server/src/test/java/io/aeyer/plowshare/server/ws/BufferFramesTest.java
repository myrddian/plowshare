package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.BufferPurgeController;
import io.aeyer.plowshare.server.buffers.Buffers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one endpoint {@code BufferPurgeController} answers, driven twice — once over HTTP and once as
 * a frame — off one request.
 *
 * <h2>{@link Code#OK} and a report, and not {@link Code#NO_CONTENT}</h2>
 *
 * <p>The mistake this comparison is here to catch. A purge is the one verb on this surface whose
 * name makes an empty answer sound right, and its answer is two numbers an operator asked for: how
 * many rows each buffer lost. {@code NO_CONTENT} would drop them and still look like a working
 * frame.
 */
class BufferFramesTest {

  private Buffers buffers;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    buffers = mock(Buffers.class);
    mvc = FrameParity.endpointsOf(new BufferPurgeController(buffers));
    router = new FrameRoutingConfig().frameRouter(List.of(new BufferFrames(buffers)));
  }

  /** Both surfaces purge once and answer with the same two counts. */
  @Test
  void both_surfaces_purge_and_answer_with_the_same_counts() throws Exception {
    when(buffers.purge()).thenReturn(new Buffers.BufferPurgeReport(12, 5));

    MockHttpServletResponse http = purged();
    Outcome outcome = route("{}");

    assertEquals(Code.OK, outcome.code(), "the counts are the point of the answer");
    FrameParity.assertSameAnswer(http, outcome);
    verify(buffers, times(2)).purge();
  }

  /**
   * A purge that found nothing expired answers two zeroes rather than nothing at all, on both
   * surfaces — safe to call twice is the whole reason an operator can run this from cron.
   */
  @Test
  void a_purge_with_nothing_to_reclaim_is_the_same_pair_of_zeroes_on_both_surfaces()
      throws Exception {
    when(buffers.purge()).thenReturn(new Buffers.BufferPurgeReport(0, 0));

    MockHttpServletResponse http = purged();
    Outcome outcome = route("{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  @Test
  void this_type_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    when(buffers.purge()).thenReturn(new Buffers.BufferPurgeReport(0, 0));

    FrameParity.assertUnknownFieldsAreIgnored(router, FrameTypes.BUFFER_PURGE, "{}");
  }

  @Test
  void the_production_routing_table_claims_this_type() {
    assertTrue(
        FrameAreas.router().types().contains(FrameTypes.BUFFER_PURGE),
        FrameTypes.BUFFER_PURGE + " is not in the production table");
  }

  private Outcome route(String payload) {
    return router.route(FrameParity.frame(FrameTypes.BUFFER_PURGE, payload), FrameParity.ASKING);
  }

  private MockHttpServletResponse purged() throws Exception {
    return mvc.perform(post("/v1/buffers/purge")).andReturn().getResponse();
  }
}
