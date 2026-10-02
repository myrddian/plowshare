package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.search.SearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/search} — the one route a caller asks a question through.
 * {@link SearchService} carries the whole of the behaviour; this class is the
 * HTTP shape around it, on {@code SearchProviderController}'s division of
 * labour between a route and the class that decides what it does.
 *
 * <h2>A plain {@code @RequestBody} record, not {@code ResponseEntity}</h2>
 *
 * <p>Two different kinds of "did not work" travel two different ways out of
 * {@link SearchService#search}, and this controller adds nothing to either
 * path. A domain-level miss — an exhausted ladder, a page whose stored set
 * expired — is {@link SearchPage#refusal} carried in a 200, because that
 * text is prose a model reads and acts on (search again, accept fewer hits),
 * not a caller mistake this route is refusing; that is the same shape {@code
 * SearchPage}'s own javadoc describes. A request that is malformed on its
 * face — a blank {@code query}, a non-positive {@code max}, {@code page} or
 * {@code pageSize} — is a caller mistake, and {@link SearchService#search}
 * throws {@link CallerFault} for it before doing anything else;
 * {@code ApiExceptionHandler} maps that to 400, on the same terms every
 * sibling controller in this package already uses. See {@code
 * SearchService}'s own class comment for why that check lives there rather
 * than here or left to a downstream constructor. This method therefore
 * returns {@link SearchPage} directly rather than wrapping it in a status
 * this route never itself varies.
 */
@RestController
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    /** What {@code POST /v1/search} takes. */
    public record SearchRequest(String query, int pageSize, int max, int page) {}

    @PostMapping("/v1/search")
    public SearchPage search(@RequestBody SearchRequest request) {
        return service.search(request.query(), request.pageSize(), request.max(), request.page());
    }
}
