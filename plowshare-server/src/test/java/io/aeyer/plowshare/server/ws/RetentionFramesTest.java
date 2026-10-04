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
import io.aeyer.plowshare.server.api.RetentionController;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.Retention;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one endpoint {@code RetentionController} answers, driven twice — once over HTTP and once as a
 * frame — off one request.
 *
 * <h2>{@link Code#OK} and a report, not {@link Code#ACCEPTED} and a job</h2>
 *
 * <p>The endpoint's own decision, and the one a handler author is most likely to get wrong here:
 * every other verb on this server that starts work answers 202, and this one does not, because a
 * sweep calls no model and a caller who has just asked for data to be removed wants to be told what
 * was removed. The comparison below is what would catch a frame that reached for {@code ACCEPTED}
 * on the strength of the noun.
 *
 * <h2>A payload with nothing in it</h2>
 *
 * <p>The endpoint takes no body, no path value and no query parameter, so there is nothing for this
 * type's payload to carry and no record to bind — {@code job.list}'s shape.
 */
class RetentionFramesTest {

  private Retention retention;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    retention = mock(Retention.class);
    mvc = FrameParity.endpointsOf(new RetentionController(retention));
    router = new FrameRoutingConfig().frameRouter(List.of(new RetentionFrames(retention)));
  }

  /** Both surfaces run one sweep and answer with the same report. */
  @Test
  void both_surfaces_sweep_and_answer_with_the_same_report() throws Exception {
    when(retention.sweep()).thenReturn(new Retention.SweepReport(3, 2, 11, 90210, 4));

    MockHttpServletResponse http = swept();
    Outcome outcome = route("{}");

    assertEquals(Code.OK, outcome.code(), "a sweep answers with what it did, not a handle");
    FrameParity.assertSameAnswer(http, outcome);
    verify(retention, times(2)).sweep();
  }

  /**
   * An archive that could not be reached is the same 503 in the same words on both surfaces.
   *
   * <p>Worth comparing for this verb above most: the sentence {@code Faults} builds for an
   * unreachable archive ends by saying that nothing was read and nothing was written, and an
   * operator who has just asked for data to be deleted is the reader that sentence exists for.
   */
  @Test
  void an_archive_that_could_not_be_reached_is_the_same_refusal_on_both_surfaces()
      throws Exception {
    when(retention.sweep()).thenThrow(new ArchiveUnavailableException("connection refused", null));

    MockHttpServletResponse http = swept();
    Outcome outcome = route("{}");

    assertEquals(Code.ARCHIVE_UNAVAILABLE, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  @Test
  void this_type_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    when(retention.sweep()).thenReturn(new Retention.SweepReport(0, 0, 0, 0, 0));

    FrameParity.assertUnknownFieldsAreIgnored(router, FrameTypes.RETENTION_SWEEP, "{}");
  }

  @Test
  void the_production_routing_table_claims_this_type() {
    assertTrue(
        FrameAreas.router().types().contains(FrameTypes.RETENTION_SWEEP),
        FrameTypes.RETENTION_SWEEP + " is not in the production table");
  }

  private Outcome route(String payload) {
    return router.route(FrameParity.frame(FrameTypes.RETENTION_SWEEP, payload), FrameParity.ASKING);
  }

  private MockHttpServletResponse swept() throws Exception {
    return mvc.perform(post("/v1/retention/sweep")).andReturn().getResponse();
  }
}
