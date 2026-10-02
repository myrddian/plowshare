package io.aeyer.plowshare.server.config;

import io.aeyer.plowshare.server.api.BadRequestException;
import io.aeyer.plowshare.server.config.RuntimeConfigSeed.Declaration;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two verbs an operator changes a running server through.
 *
 * <p>{@code GET /v1/config} says what is live, what each key holds, who set it
 * and whether the change would survive a restart. {@code PUT /v1/config/{key}}
 * sets one. Everything else about this server is still bound once at boot and
 * frozen into a constructor; these are the doors onto the few keys that are not.
 *
 * <h2>There is no agent tool for either, and that is the design</h2>
 *
 * <p>Every other capability in this server that an operator has, an agent has
 * too, through a tool. Not this one. An agent that can raise its own ingest
 * budget is an agent whose budget is not a bound — it is a suggestion, and the
 * whole point of {@code DocumentsProperties#ingestBudget} is that it is the
 * thing a runaway ingest stops at. The same argument covers the model an agent
 * answers under and the directory its definitions are read from.
 *
 * <p><b>What holds it is that nothing registers one</b>, which is an absence and
 * therefore not something a test in this package can assert. What can be said is
 * where it would have to be added: the tool surface is assembled in {@code
 * AgentsConfig}, and every tool an agent can reach appears in the fixtures under
 * {@code src/test/resources/surface}. A tool over this controller would change
 * those files, and this paragraph is what the reviewer of that change should be
 * pointed at.
 *
 * <p><b>OPEN LIMITATION: the absent tool is not the only door, and the HTTP one
 * is not closed against an agent.</b> The paragraph above is about tools; the
 * route below is about credentials, and they do not meet. {@code
 * AuthFilter.accepted} asks {@code TokenStore.validAccess} and nothing else, and
 * {@code TokenStore.acceptOperator} files the operator token as an ordinary
 * access grant — on a chain that never expires. So <em>any</em> holder of the
 * raw operator token can {@code PUT} here, and this server has no tier that
 * could distinguish one holder from another.
 *
 * <p><b>The file half of that is now closed, and this paragraph records what it
 * said while it was open.</b> That token is a file. {@code
 * AuthConfig.defaultTokenFile} writes it to {@code
 * ~/.config/plowshare/console-token}, and {@code
 * ProjectStore.mandatoryExclusions} — what keeps a workspace from reaching this
 * server's own secrets — named the working directory, the config file, and
 * every other operator-configured directory it fenced at the time, and
 * <b>not that file</b>. Two
 * shipped agents ({@code interlocutor}, {@code code_reviewer}) carry {@code
 * file_read} and {@code file_grep}, so on a deployment whose project workspace
 * happened to cover that path — or whose console client was rooted with {@code
 * client_root_project_here} somewhere above it, where {@code ClientEnforcer}
 * applies no exclusions at all — an agent could read the operator token and put
 * it in its answer.
 *
 * <p>Two things closed it, and both were built because either alone leaves a
 * deployment uncovered. {@code
 * io.aeyer.plowshare.protocol.FileAccess#permits} now refuses any candidate with
 * a dot-prefixed component below its root, which reaches <em>both</em>
 * enforcement points — including the client, which passes no exclusions and
 * therefore could never have been reached by a list. And the token file joins
 * {@code mandatoryExclusions}, because {@code plowshare.auth.token-file} is
 * configurable and an operator who points it somewhere unhidden loses the
 * predicate with no warning.
 *
 * <p><b>What is still open is the other half, and it is the bigger one.</b>
 * {@code validAccess} is still the whole of the check for a write here: this
 * server has no privilege tier, so an operator token and a session token minted
 * through {@code AuthController} are indistinguishable at this route, and the
 * operator token still never expires. <b>Nothing in {@code server.agents} could
 * make an outbound HTTP request — until this slice, and no longer.</b> {@code
 * FetchTool} and {@code SearchTool}, both declared {@code package
 * io.aeyer.plowshare.server.agents}, now cause this server to make one: {@code
 * FetchTool} reaches {@code BuiltinFetcher}, which calls {@code
 * http.newCall(request)} on a URL an agent supplies, and {@code SearchTool}
 * reaches the registered-provider ladder. The sentence that used to close this
 * paragraph — "an agent that somehow held the token could not spend it itself
 * — a gap of one tool, not of one design decision" — was a prediction, and
 * this slice is the tool it predicted. {@code SearchProviderController}'s
 * javadoc carried the identical sentence and was corrected first; this file is
 * the one it explicitly deferred to ("the same two protections {@code
 * RuntimeConfigController}'s javadoc records for {@code PUT /v1/config}"),
 * and pointing a reader at an uncorrected file from a corrected one is worse
 * than the original defect, which is why this paragraph is fixed the same
 * way rather than left as the thing the fix pointed past. What remains true
 * here is narrower than the sentence it replaces: {@code fetch} issues a
 * {@code GET} to a URL an agent names, setting no {@code Authorization}
 * header and carrying no body, so it cannot, by itself, replay a stolen
 * operator token as the {@code PUT} with a body this route requires. That is
 * a fact about the shape of one tool, not a design that closes the door, and
 * whether some future tool carries a body and a header is the open question
 * this javadoc leaves open rather than answers. <b>The honest statement of
 * this endpoint's guarantee is therefore that no agent can raise its own
 * allowance through a tool shaped like {@code fetch} or {@code search} today,
 * and that no ordinary workspace hands one the credential — not that this
 * route distinguishes who is holding it, and not that no tool ever
 * will.</b>
 *
 * <h2>In {@code server.config} and not {@code server.api}, unlike every other
 * controller</h2>
 *
 * <p>Deliberately, for one reason: {@code pinned}. Whether an operator pinned a
 * key outside the jar is decided by {@link RuntimeConfigSeed#operatorPinned},
 * which took two rounds of review to get right — the rule is a comparison
 * against a second resolution over the classpath-backed sources only, and the
 * three drafts before it each read some real deployment's pin as a shipped
 * default. Re-deciding it here would be a second implementation of that rule,
 * and the second implementation is the one that would be wrong. Sitting in the
 * same package lets this class ask the class that already decides.
 *
 * <p>{@link RuntimeConfigSeed#declared()} is the same seam for the other half —
 * <em>which</em> keys are live. Its javadoc already anticipated this caller:
 * "What is live is answered for the rest of the server by the config API, over
 * the same list." Two scans would be two answers to "what is live", and the one
 * that drifted would be the one an operator's write was refused by.
 *
 * <p><b>Asked per request, not snapshotted at boot.</b> Neither answer can
 * change while the server runs — property sources are fixed after the refresh
 * and so is the set of {@code @Live} accessors — so a snapshot would be correct.
 * It is not taken because a cached copy of an unchanging fact is a staleness
 * question a reader has to rule out, and the scan is a reflective walk of nine
 * properties beans on an endpoint an operator calls by hand. If a caller ever
 * polls this, that is the paragraph to revisit.
 *
 * <h2>Behind the same door as everything else</h2>
 *
 * <p>Under {@code /v1}, so {@code AuthFilter} refuses both to a request carrying
 * no access token, and {@code
 * AuthFilterTest.every_route_this_application_publishes_is_gated_or_deliberately_open}
 * asserts it over the whole tree by scanning for controllers — this one
 * included, without anybody adding it to a list.
 */
@RestController
public class RuntimeConfigController {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigController.class);

    /**
     * What {@code updated_by} records when the request carried no named subject,
     * which on this server is every request.
     *
     * <p><b>This server's authentication has no principal.</b> {@code TokenStore}
     * holds bootstrap, access, refresh and operator grants; not one of them
     * names a person, and {@code AuthFilter} answers a boolean and puts nothing
     * on the request. So {@code getUserPrincipal()} is null on every request that
     * reaches here, and will be until this server grows accounts.
     *
     * <p>The constant is not a placeholder for a name — it is the true answer to
     * the question the column asks. {@code RuntimeConfig#authorOf}'s reason for
     * the column is that "a value that surprises somebody has a name against
     * it", and the surprise an operator actually has is <em>did somebody type
     * this, or did the boot put it there</em>. {@code "operator"} against the
     * seed's {@code "boot"} answers exactly that, which is as much as this
     * server truthfully knows. Writing {@code "unknown"} would answer it worse
     * while looking more careful.
     */
    private static final String OPERATOR = "operator";

    private final RuntimeConfigSeed seed;
    private final RuntimeConfig store;
    private final Environment environment;

    /**
     * @param seed asked <em>only</em> the two questions it already answers —
     *     which keys are live, and whether each is pinned. It is not written
     *     through and not re-run; the boot precedence it applies happened before
     *     this bean ever served a request
     * @param environment read for the bound value of a key the map has no row
     *     for. Plain {@link Environment} and not {@code ConfigurableEnvironment}:
     *     walking the source list is the pinned rule's business and is asked of
     *     {@code seed}, so widening this would put the ingredients of a second
     *     implementation within reach for no gain
     */
    public RuntimeConfigController(
            RuntimeConfigSeed seed, RuntimeConfig store, Environment environment) {
        this.seed = seed;
        this.store = store;
        this.environment = environment;
    }

    /**
     * {@code GET /v1/config} — every live key, whatever it holds, and whether a
     * write to it would survive a restart.
     *
     * <h2>{@code pinned} is why this endpoint is worth having</h2>
     *
     * <p>Spec §1.2 makes a runtime write <b>permanent</b> unless an operator has
     * pinned that key outside the jar, and <b>temporary</b> if they have: the
     * next boot overwrites it from the pin. That is a defensible rule and an
     * invisible one — an operator raises a budget, it works, and three weeks
     * later a deploy silently puts it back. Being <em>told</em> before writing is
     * what turns §1.2 from true into usable, and it is the one field here that
     * no other surface in this server can answer.
     *
     * <h2>Listed over what is live, not over what has been written</h2>
     *
     * <p>{@link RuntimeConfig#all} lists rows, and its javadoc is explicit that
     * rows are not the live set: a key that was live and no longer is leaves a
     * row behind, and a live key nobody has set has no row. It hands the
     * reconciliation to this class because only the source declaring {@code
     * @Live} knows the other half. So the listing is driven by {@link
     * RuntimeConfigSeed#declared()} and the rows are looked up into it — which
     * means a stale row for a key that stopped being live is <em>not</em>
     * listed, and a caller cannot be told they may write something that would
     * change nothing.
     *
     * <p><b>A live key with no row is listed, and shows the value it is actually
     * running on.</b> After an ordinary boot there is no such key — the seed
     * writes a shipped default into every gap — so this is the shape of a
     * database somebody deleted a row from, or of a key that went live in a
     * build whose boot has not run against this map yet. Omitting it would be
     * the worse of the two errors available: the key is live and writable, and a
     * caller reading this listing to decide what they may write would be told it
     * does not exist. Showing it blank would be worse still, because blank is a
     * value {@link RuntimeConfig} treats as one somebody chose.
     *
     * <p>So {@link Setting#value} is the row's value when there is a row, and
     * otherwise what Spring bound — the number the accessor is falling back to
     * this instant. {@link Setting#updatedBy} is the discriminator and the only
     * one: {@code null} means no row, which means nobody has ever set this key
     * and what is shown came from the jar or from the operator's own pin.
     *
     * <p><b>What {@code value} is not:</b> a promise about what a live accessor
     * returns. {@code RuntimeConfig.intOr} falls back to the bound value when the
     * map holds text that is not a whole number, so a map holding {@code
     * "banana"} for a budget is listed as {@code "banana"} while every ingest
     * runs on the bound number. That is not a defect in this listing — it is
     * §1.1's no-write-time-validation showing through, and showing an operator
     * exactly the string they wrote is what lets them see the typo.
     */
    @GetMapping("/v1/config")
    public List<Setting> list() {
        return ConfigUnavailableException.translating("list the live configuration", () -> {
            Map<String, RuntimeConfig.Entry> written = new HashMap<>();
            for (RuntimeConfig.Entry row : store.all()) {
                written.put(row.key(), row);
            }
            List<Setting> live = new ArrayList<>();
            for (Declaration declared : seed.declared()) {
                live.add(setting(declared.key(), written.get(declared.key())));
            }
            return live;
        });
    }

    /**
     * {@code PUT /v1/config/{key}} — set one live key to the body of the
     * request.
     *
     * <h2>The only refusal is that the key is not live</h2>
     *
     * <p>A key with no {@code @Live} accessor is bound once and read from a
     * final field, so writing it into the map would be accepted, persisted,
     * listed, and would change nothing whatsoever — the silent-no-op failure
     * {@link Live} exists to refuse at boot, arriving at runtime instead. 400,
     * because it is a thing the caller corrects by naming a different key, and
     * the message names the ones they may.
     *
     * <p><b>And that refusal must not quietly grow into validating the value.</b>
     * Spec §1.1 chose, with the alternative written down and rejected on blast
     * radius, that a live key is checked at boot by the {@code @Configuration}
     * class that has always checked it and is <em>not</em> checked on write. So
     * {@code PUT plowshare.documents.ingest-budget} with a body of {@code 0} is
     * accepted here, and so is one that is not a number at all. The cost is
     * stated rather than implied: an operator can write a value that makes the
     * next ingest useless, and will find out from the ingest. Adding a check
     * here would put a rule in this class that the boot check already owns, so
     * the two would drift and neither would be the one an operator was refused
     * by — and it would do it for the one key that is live, before anyone has
     * changed a value at runtime once. {@code
     * a_live_key_takes_a_value_the_boot_check_would_have_refused} is what stops
     * it drifting in.
     *
     * <h2>What it answers with</h2>
     *
     * <p>The same {@link Setting} the listing carries, for the key just written
     * — above all {@code pinned}, so an operator who wrote without looking first
     * is still told, in the answer to the write itself, whether it survives a
     * restart.
     *
     * <p>Read back from the map rather than echoed from the request, so the
     * answer carries the timestamp the database assigned and is what the map
     * holds rather than what this request hoped. If another write lands between
     * the two statements the caller is shown the newer one, which is the truer
     * answer to "what is this key now".
     *
     * @param value the raw request body, stored exactly as it arrives. Not
     *     trimmed here: {@code RuntimeConfig.intOr} trims on the way out and
     *     says why — {@code curl --data-binary @file} carries the file's
     *     trailing newline — and doing it in both places would make this class a
     *     second opinion about what an operator's text means
     * @param caller resolved from {@code getUserPrincipal()}, which is null on
     *     every request this server serves today. See {@link #OPERATOR}. It is
     *     taken as an argument rather than not asked for, so that the day this
     *     server has accounts the name lands in {@code updated_by} with nothing
     *     here to change
     */
    @PutMapping("/v1/config/{key}")
    public Setting set(@PathVariable String key, @RequestBody String value, Principal caller) {
        // Asked once and carried, not asked twice. The class note above accounts
        // for the cost of asking per request rather than caching, and that
        // accounting is written for one scan; `isLive` then `liveKeys` walked
        // the same reflective list twice on the path that is refused, which is
        // the path a mistyped key takes.
        List<Declaration> live = seed.declared();
        if (!isLive(live, key)) {
            throw new BadRequestException(key + " is not a live configuration key, so writing it"
                    + " would change nothing: it is bound once at boot and read from a final"
                    + " field, and only a restart changes it. The keys that can be written are "
                    + liveKeys(live) + ", which GET /v1/config lists with what each one holds");
        }
        String author = nameOf(caller);
        log.info("{} is setting {}", author, key);
        return ConfigUnavailableException.translating("write " + key, () -> {
            store.put(key, value, author);
            return setting(key, store.entry(key).orElse(null));
        });
    }

    /**
     * One live key as this surface reports it.
     *
     * <p>{@code updatedAt} is here because {@link RuntimeConfig}'s upsert was
     * corrected in review specifically so that this listing could carry it — the
     * {@code ON CONFLICT} branch assigns {@code now()} rather than leaving the
     * column's {@code DEFAULT} to fire on inserts only, and the comment on that
     * statement names this endpoint as the reader that would otherwise have
     * shown a frozen timestamp for every key anybody had changed twice.
     *
     * @param value what the map holds, or the bound value when it holds nothing
     * @param updatedAt when it was set, or null when nobody has set it
     * @param updatedBy who set it — {@code "boot"} for a value the seed wrote in
     *     from the environment, {@code "operator"} for one written through this
     *     controller, or null when there is no row at all
     * @param pinned whether an operator has pinned this key outside the jar, in
     *     which case the next boot overwrites whatever is written here
     */
    public record Setting(
            String key, String value, Instant updatedAt, String updatedBy, boolean pinned) {}

    private Setting setting(String key, RuntimeConfig.Entry row) {
        boolean pinned = seed.operatorPinned(key);
        return row == null
                ? new Setting(key, environment.getProperty(key), null, null, pinned)
                : new Setting(key, row.value(), row.updatedAt(), row.updatedBy(), pinned);
    }

    private static boolean isLive(List<Declaration> live, String key) {
        for (Declaration declared : live) {
            if (declared.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** The keys a refusal offers instead, in {@link RuntimeConfigSeed#declared()}'s
     *  order, which is sorted for the reason that method gives: the same tree
     *  should hand a reader the same sentence on every JVM. Takes the list its
     *  caller already holds, so that a refusal costs one reflective walk. */
    private static String liveKeys(List<Declaration> live) {
        List<String> keys = new ArrayList<>();
        for (Declaration declared : live) {
            keys.add(declared.key());
        }
        return String.join(", ", keys);
    }

    /**
     * The name to record against the value, which is {@link #OPERATOR} until
     * this server has accounts.
     *
     * <p><b>The inner blank-name test cannot run today, and is kept as the guard
     * for the change that would make it run.</b> {@code caller} is null on every
     * request this server serves — {@code AuthFilter} sets no principal and does
     * not wrap the request, and this class holds the only reference to {@link
     * Principal} in {@code src/main} — so the first disjunct always short-circuits
     * and {@code getName()} is never called. What it is against, for the day a
     * principal does arrive: V28 refuses an empty {@code updated_by} at the
     * schema, so passing a blank name through would turn a nameless caller into a
     * 500 for a row Postgres was right to refuse, and {@code
     * ConfigUnavailableException} deliberately does not dress that up as an
     * outage. It becomes live behaviour the moment something upstream populates
     * {@code getUserPrincipal()} — a {@code HttpServletRequestWrapper} in {@code
     * AuthFilter}, or Spring Security — and it is cheaper to carry the branch
     * than to remember it then.
     */
    private static String nameOf(Principal caller) {
        return caller == null || caller.getName() == null || caller.getName().isBlank()
                ? OPERATOR
                : caller.getName();
    }
}
