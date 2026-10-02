package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.search.ProviderStore;
import io.aeyer.plowshare.server.search.Registration;
import io.aeyer.plowshare.server.search.SearchRegistrar;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three verbs an operator manages the provider registry through: register
 * one, list what is registered, deregister one. {@link SearchRegistrar}
 * carries the decisions; this class is the HTTP shape around it.
 *
 * <h2>This is the route that would matter most if this server had a privilege
 * tier — and it does not</h2>
 *
 * <p>A registered provider sees every query made through the search tool, for
 * as long as it stays on the ladder. (That sentence used to say "every query
 * every agent subsequently makes", which was a tense this server had not
 * earned when it was written: at the time, no shipped agent could search —
 * {@code search} was on the MCP tool surface and not in {@code AgentsConfig}.
 * That is no longer true. {@code interlocutor} now declares {@code search} in
 * its {@code tools:} list — {@code implementation rationale} §17.1 and §17.5 carry the
 * grant and the audit that found it — so the original tense is earned again,
 * for that one agent. The threat argument is correspondingly <em>wider</em>,
 * not merely restored: a registered provider now also reads every query
 * {@code interlocutor} makes on a person's behalf, across however many
 * conversations it holds open at once, which is a volume and a shape of
 * traffic a person typing at this terminal does not produce by hand. No other
 * shipped agent has search yet — {@code implementation rationale} §17.5's audit table
 * names the nine capabilities any agent reaches today (fetch joined search as
 * an eighth-then-ninth in the same slice that built it), and search is one of
 * them for {@code interlocutor} alone.) That makes {@code POST
 * /v1/search/providers} the highest-value single call an operator route
 * offers in this server: whoever can add a provider can read every question
 * this server is ever asked, now including the ones an agent asks on a
 * person's behalf. So it deserves the same honest accounting {@code
 * RuntimeConfigController} gives {@code PUT /v1/config}, and the temptation
 * to overstate it is the same one that javadoc names and refuses.
 *
 * <h2>This route also makes the server dial a URL of the caller's choosing,
 * and reports what happened</h2>
 *
 * <p>Stated plainly because the rest of this javadoc is: {@link
 * SearchRegistrar#register} takes an arbitrary {@code baseUrl} from the
 * request body, opens an HTTP connection to it from <em>this</em> process,
 * and answers differently depending on what came back. A refused connection
 * and an HTTP 404 both refuse, but with different text — OkHttp's own
 * connect-failure message in the first case, {@code "HTTP 404"} in the second
 * — and a well-formed capabilities body is a 201. That is a probe for
 * anything this server can route to, including loopback and anything else on
 * the private network the server sits on, available to any holder of any
 * valid access token. The ten-second probe timeout bounds how long each
 * attempt takes and nothing else.
 *
 * <p>It is not a defect of this route so much as an unavoidable consequence
 * of what registering a provider <em>is</em>: fetching the facts rather than
 * transcribing them (see {@link SearchRegistrar}'s own javadoc for why that
 * trade is worth it) means the server has to dial the URL before it will
 * trust it, and a registration that could not report why it failed would be
 * useless to the operator it exists for. What closes it is a privilege tier
 * on this route, which is spec cost 6 and its own slice — not a narrower
 * error message here, which would blind the operator without stopping the
 * probe.
 *
 * <p><b>What actually holds, stated plainly and no further than the facts
 * carry.</b> {@code AuthFilter.accepted} asks {@code TokenStore.validAccess}
 * and nothing else, and {@code TokenStore.acceptOperator} files the operator
 * token as an ordinary access grant on a chain that never expires. This
 * server has no privilege tier: an operator token and a console session token
 * minted through {@code AuthController} are indistinguishable at this route,
 * exactly as they are at every other one. So it is <b>false</b> to write that
 * this route "requires operator authority" or that "no agent can call it" —
 * this server cannot tell an agent's holder from an operator's, because
 * nothing about a token says who is holding it.
 *
 * <p>What was true at this route's original writing was three separate
 * facts, none of which was a privilege check. One of the three no longer
 * holds, named where it stood so a reader sees exactly what changed rather
 * than a silently shortened list:
 *
 * <ol>
 *   <li><b>No agent tool is registered for this route.</b> An absence, so no
 *       test in this package asserts it directly — the tool surface is
 *       assembled in {@code AgentsConfig}, and every tool an agent can reach
 *       appears in the fixtures under {@code src/test/resources/surface}. A
 *       tool over this controller would change those files, and that is what
 *       a reviewer of such a change should be pointed at. Still true: this
 *       route itself has no agent tool.
 *   <li><b>Nothing in {@code server.agents} could make an outbound HTTP
 *       request — until this slice, and no longer.</b> {@code FetchTool} and
 *       {@code SearchTool}, both declared {@code package
 *       io.aeyer.plowshare.server.agents}, now cause this server to make one:
 *       {@code FetchTool} reaches {@code BuiltinFetcher}, which calls {@code
 *       http.newCall(request)} on a URL an agent supplies, and {@code
 *       SearchTool} reaches the registered-provider ladder. The sentence
 *       that used to close this item — "an agent that somehow held the
 *       operator token could not spend it itself — a gap of one tool, not a
 *       design that closes the door" — was a prediction, and this slice is
 *       the tool it predicted. What remains of this item is narrower than
 *       what it originally claimed: {@code fetch} issues a {@code GET} to a
 *       URL an agent names and controls neither an {@code Authorization}
 *       header nor a request body, so it does not, by itself, give an agent
 *       a way to replay a stolen operator token as a {@code POST} against
 *       this route. That is a fact about the shape of one tool, not a
 *       design that closes anything, and whether some other tool someday
 *       does is the open question this javadoc leaves open rather than
 *       answers.
 *   <li><b>No ordinary workspace hands an agent the credential.</b> {@code
 *       io.aeyer.plowshare.protocol.FileAccess#permits} refuses any candidate
 *       with a dot-prefixed component below its root, and the token file
 *       joins {@code ProjectStore.mandatoryExclusions} besides — the same two
 *       protections {@code RuntimeConfigController}'s javadoc records for
 *       {@code PUT /v1/config}, because they are the same token guarding both
 *       routes. Still true, and untouched by this slice.
 * </ol>
 *
 * <p>Those three facts used to compose into "no agent can reach this route
 * today". They no longer do, because the second stopped holding without a
 * tier or any other guard replacing what it argued. What composes today is
 * narrower: items 1 and 3 mean no agent tool is registered for this exact
 * route and no ordinary workspace hands an agent the operator credential —
 * which is not the same claim as "no agent can reach this route", because an
 * agent that obtained the credential by some other means now has, in {@code
 * fetch} and {@code search}, tools that make outbound requests, even though
 * neither is shaped to spend this credential against this route today. A
 * future change to any of the three items, or to the shape of {@code fetch}
 * or {@code search} themselves, reopens this with no test in this file
 * noticing, exactly as {@code RuntimeConfigController}'s own javadoc warns
 * for its route. Building a tier that could make a stronger claim than this
 * is not in this slice.
 *
 * <p><b>No test in this package asserts that an agent-held token is
 * refused.</b> It would fail, because {@code AuthFilter} cannot distinguish
 * one holder of a valid token from another — that is not a gap this task
 * closes, and asserting otherwise would pin a claim this server cannot back.
 *
 * <h2>Behind the same door as everything else</h2>
 *
 * <p>Under {@code /v1}, so {@code AuthFilter} refuses all three routes to a
 * request carrying no access token, exactly as it refuses every other path —
 * see {@code AuthFilterTest}'s whole-tree scan.
 */
@RestController
public class SearchProviderController {

    private final SearchRegistrar registrar;
    private final ProviderStore store;

    /**
     * @param store read from directly for the listing — {@link
     *     SearchRegistrar} carries only what it was asked to carry, {@code
     *     register} and {@code deregister}, and giving it a third method that
     *     exists only to satisfy this controller's {@code GET} would make it
     *     a proxy for a store it already holds a reference to
     */
    public SearchProviderController(SearchRegistrar registrar, ProviderStore store) {
        this.registrar = registrar;
        this.store = store;
    }

    /** What {@code POST /v1/search/providers} takes: a place to dial, and
     *  nothing else — see {@link SearchRegistrar}'s class javadoc for why the
     *  facts are fetched rather than carried in this body. */
    public record RegisterRequest(String baseUrl) {}

    /**
     * {@code POST /v1/search/providers} — probe {@code baseUrl}, and file
     * what it says about itself.
     *
     * @return 201 and the row {@link SearchRegistrar#register} wrote
     */
    @PostMapping("/v1/search/providers")
    public ResponseEntity<Registration> register(@RequestBody RegisterRequest request) {
        Registration registered = registrar.register(request.baseUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    /** {@code GET /v1/search/providers} — every registered provider, in
     *  {@link ProviderStore#all}'s order. */
    @GetMapping("/v1/search/providers")
    public List<Registration> list() {
        return store.all();
    }

    /**
     * {@code DELETE /v1/search/providers/{key}} — remove one row.
     *
     * <p>204 when a row was removed, 404 when {@code key} named nothing. <b>The
     * second is no longer decided here</b>: {@link SearchRegistrar#deregister}
     * raises {@code faults.NotFoundFault} for a key nothing is registered
     * under, and {@code Faults} maps it to the same status this method used to
     * name itself. It moved so that {@code provider.deregister} could refuse in
     * the same words without restating them — see that method's javadoc for the
     * argument.
     */
    @DeleteMapping("/v1/search/providers/{key}")
    public ResponseEntity<Void> deregister(@PathVariable String key) {
        registrar.deregister(key);
        return ResponseEntity.noContent().build();
    }
}
