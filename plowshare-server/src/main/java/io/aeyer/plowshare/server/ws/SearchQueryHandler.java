package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.SearchController;
import io.aeyer.plowshare.server.search.SearchService;
import java.util.Map;
import java.util.Objects;

/**
 * {@code web.search} — ask the open web a question. The frame equivalent of {@code POST
 * /v1/search}.
 *
 * <h2>A domain miss is {@link Code#OK}, and that is a ruling</h2>
 *
 * <p><b>Do not "fix" this.</b> Two different kinds of "did not work" travel two different ways out
 * of {@link SearchService#search}, and this handler adds nothing to either — which is the
 * endpoint's own division and the reason it returns a bare {@code SearchPage} rather than a {@code
 * ResponseEntity}. An exhausted ladder, or a page whose stored set expired, is {@code
 * SearchPage.refusal} carried inside an otherwise ordinary page, because that text is prose a model
 * reads and acts on — search again, accept fewer hits — and not a caller mistake this route is
 * refusing. A frame that promoted it to a failure {@link Code} would be a second contract for one
 * read.
 *
 * <p>A request malformed on its face — a blank {@code query}, a non-positive {@code max}, {@code
 * page} or {@code pageSize} — is the other kind, and the service raises {@code CallerFault} for it
 * before doing anything at all. {@code Faults} turns that into the same 400 the endpoint answers,
 * in the same words, because it is the same throw.
 *
 * <h2>No provider name reaches the caller, here either</h2>
 *
 * <p>Search's whole design is that nobody learns which provider answered. This handler renders
 * exactly what the service returned, so that holds on the socket for the same reason it holds on
 * HTTP: there is nothing here that could add it.
 */
public final class SearchQueryHandler implements FrameHandler {

  private final SearchService service;

  /**
   * @param service the same service the controller is injected with, which carries the ladder, the
   *     stored sets and both kinds of refusal
   */
  public SearchQueryHandler(SearchService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    SearchController.SearchRequest asked =
        Payloads.as(payload, SearchController.SearchRequest.class, FrameTypes.WEB_SEARCH);
    return Outcome.ok(service.search(asked.query(), asked.pageSize(), asked.max(), asked.page()));
  }
}
