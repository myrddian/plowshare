package io.aeyer.plowshare.server.config;

import io.aeyer.plowshare.server.api.BadRequestException;
import io.aeyer.plowshare.server.auth.AuthFilter;
import io.aeyer.plowshare.server.auth.ServerAdministratorAccess;
import io.aeyer.plowshare.server.auth.ServiceCredentials;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The two verbs an operator changes a running server through.
 *
 * <p>{@code GET /v1/config} says what is live, what each key holds, who set it and whether the
 * change would survive a restart. {@code PUT /v1/config/{key}} sets one. Everything else about this
 * server is still bound once at boot and frozen into a constructor; these are the doors onto the
 * few keys that are not.
 *
 * <h2>There is no agent tool for either, and that is the design</h2>
 *
 * <p>Every other capability in this server that an operator has, an agent has too, through a tool.
 * Not this one. An agent that can raise its own ingest budget is an agent whose budget is not a
 * bound — it is a suggestion, and the whole point of {@code DocumentsProperties#ingestBudget} is
 * that it is the thing a runaway ingest stops at. The same argument covers the model an agent
 * answers under and the directory its definitions are read from.
 *
 * <p><b>What holds it is that nothing registers one</b>, which is an absence and therefore not
 * something a test in this package can assert. What can be said is where it would have to be added:
 * the tool surface is assembled in {@code AgentsConfig}, and every tool an agent can reach appears
 * in the fixtures under {@code src/test/resources/surface}. A tool over this controller would
 * change those files, and this paragraph is what the reviewer of that change should be pointed at.
 *
 * <h2>Server administrator authority</h2>
 *
 * <p>{@link AuthFilter} authenticates HTTP requests and supplies the trusted account attribute.
 * Both endpoints then ask {@link ServerAdministratorAccess} for that account's current role before
 * reading configuration, disclosing live keys or writing a value. An enabled, non-bootstrap human
 * administrator is required; project roles and service-token scopes cannot grant this capability.
 * Missing account context is refused rather than inferred from a query parameter or principal.
 *
 * <p>This check belongs here because the controller lives outside the API package covered by
 * project HTTP authorization. Keeping it at both mapped methods also covers an explicitly disabled
 * authentication filter: a configured identity still needs the persisted administrator role.
 *
 * <h2>In {@code server.config} and not {@code server.api}, unlike every other controller</h2>
 *
 * <p>Deliberately, for one reason: {@code pinned}. Whether an operator pinned a key outside the jar
 * is decided by {@link RuntimeConfigSeed#operatorPinned}, which took two rounds of review to get
 * right — the rule is a comparison against a second resolution over the classpath-backed sources
 * only, and the three drafts before it each read some real deployment's pin as a shipped default.
 * Re-deciding it here would be a second implementation of that rule, and the second implementation
 * is the one that would be wrong. Sitting in the same package lets this class ask the class that
 * already decides.
 *
 * <p>{@link RuntimeConfigSeed#declared()} is the same seam for the other half — <em>which</em> keys
 * are live. Its javadoc already anticipated this caller: "What is live is answered for the rest of
 * the server by the config API, over the same list." Two scans would be two answers to "what is
 * live", and the one that drifted would be the one an operator's write was refused by.
 *
 * <p><b>Asked per request, not snapshotted at boot.</b> Neither answer can change while the server
 * runs — property sources are fixed after the refresh and so is the set of {@code @Live} accessors
 * — so a snapshot would be correct. It is not taken because a cached copy of an unchanging fact is
 * a staleness question a reader has to rule out, and the scan is a reflective walk of nine
 * properties beans on an endpoint an operator calls by hand. If a caller ever polls this, that is
 * the paragraph to revisit.
 *
 * <h2>Behind the same door as everything else</h2>
 *
 * <p>Under {@code /v1}, so {@code AuthFilter} refuses both to a request carrying no access token,
 * and {@code AuthFilterTest.every_route_this_application_publishes_is_gated_or_deliberately_open}
 * asserts it over the whole tree by scanning for controllers — this one included, without anybody
 * adding it to a list.
 */
@RestController
public class RuntimeConfigController {

  private static final Logger log = LoggerFactory.getLogger(RuntimeConfigController.class);

  /**
   * Legacy attribution fallback when the servlet container supplies no named principal.
   * Authorization always uses the filter's account attribute independently of this display value.
   */
  private static final String OPERATOR = "operator";

  private final RuntimeConfigSeed seed;
  private final RuntimeConfig store;
  private final Environment environment;
  private final ServerAdministratorAccess administrators;

  /**
   * @param seed asked <em>only</em> the two questions it already answers — which keys are live, and
   *     whether each is pinned. It is not written through and not re-run; the boot precedence it
   *     applies happened before this bean ever served a request
   * @param store persisted runtime values; accessed only after the current account is authorized
   * @param environment read for the bound value of a key the map has no row for. Plain {@link
   *     Environment} and not {@code ConfigurableEnvironment}: walking the source list is the pinned
   *     rule's business and is asked of {@code seed}, so widening this would put the ingredients of
   *     a second implementation within reach for no gain
   * @param administrators current account role checks, required even when the HTTP filter is
   *     disabled
   */
  public RuntimeConfigController(
      RuntimeConfigSeed seed,
      RuntimeConfig store,
      Environment environment,
      ServerAdministratorAccess administrators) {
    this.seed = seed;
    this.store = store;
    this.environment = environment;
    this.administrators = administrators;
  }

  /**
   * {@code GET /v1/config} — every live key, whatever it holds, and whether a write to it would
   * survive a restart.
   *
   * <h2>{@code pinned} is why this endpoint is worth having</h2>
   *
   * <p>Spec §1.2 makes a runtime write <b>permanent</b> unless an operator has pinned that key
   * outside the jar, and <b>temporary</b> if they have: the next boot overwrites it from the pin.
   * That is a defensible rule and an invisible one — an operator raises a budget, it works, and
   * three weeks later a deploy silently puts it back. Being <em>told</em> before writing is what
   * turns §1.2 from true into usable, and it is the one field here that no other surface in this
   * server can answer.
   *
   * <h2>Listed over what is live, not over what has been written</h2>
   *
   * <p>{@link RuntimeConfig#all} lists rows, and its javadoc is explicit that rows are not the live
   * set: a key that was live and no longer is leaves a row behind, and a live key nobody has set
   * has no row. It hands the reconciliation to this class because only the source declaring
   * {@code @Live} knows the other half. So the listing is driven by {@link
   * RuntimeConfigSeed#declared()} and the rows are looked up into it — which means a stale row for
   * a key that stopped being live is <em>not</em> listed, and a caller cannot be told they may
   * write something that would change nothing.
   *
   * <p><b>A live key with no row is listed, and shows the value it is actually running on.</b>
   * After an ordinary boot there is no such key — the seed writes a shipped default into every gap
   * — so this is the shape of a database somebody deleted a row from, or of a key that went live in
   * a build whose boot has not run against this map yet. Omitting it would be the worse of the two
   * errors available: the key is live and writable, and a caller reading this listing to decide
   * what they may write would be told it does not exist. Showing it blank would be worse still,
   * because blank is a value {@link RuntimeConfig} treats as one somebody chose.
   *
   * <p>So {@link Setting#value} is the row's value when there is a row, and otherwise what Spring
   * bound — the number the accessor is falling back to this instant. {@link Setting#updatedBy} is
   * the discriminator and the only one: {@code null} means no row, which means nobody has ever set
   * this key and what is shown came from the jar or from the operator's own pin.
   *
   * <p><b>What {@code value} is not:</b> a promise about what a live accessor returns. {@code
   * RuntimeConfig.intOr} falls back to the bound value when the map holds text that is not a whole
   * number, so a map holding {@code "banana"} for a budget is listed as {@code "banana"} while
   * every ingest runs on the bound number. That is not a defect in this listing — it is §1.1's
   * no-write-time-validation showing through, and showing an operator exactly the string they wrote
   * is what lets them see the typo.
   */
  @GetMapping("/v1/config")
  public List<Setting> list(
      @RequestAttribute(value = AuthFilter.HANDLE_ATTRIBUTE, required = false) String account) {
    requireAdministrator(account);
    return ConfigUnavailableException.translating(
        "list the live configuration",
        () -> {
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
   * {@code PUT /v1/config/{key}} — set one live key to the body of the request.
   *
   * <h2>Authorized writes require a live key</h2>
   *
   * <p>A key with no {@code @Live} accessor is bound once and read from a final field, so writing
   * it into the map would be accepted, persisted, listed, and would change nothing whatsoever — the
   * silent-no-op failure {@link Live} exists to refuse at boot, arriving at runtime instead. 400,
   * because it is a thing the caller corrects by naming a different key, and the message names the
   * ones they may.
   *
   * <p><b>And that refusal must not quietly grow into validating the value.</b> Spec §1.1 chose,
   * with the alternative written down and rejected on blast radius, that a live key is checked at
   * boot by the {@code @Configuration} class that has always checked it and is <em>not</em> checked
   * on write. So {@code PUT plowshare.documents.ingest-budget} with a body of {@code 0} is accepted
   * here, and so is one that is not a number at all. The cost is stated rather than implied: an
   * operator can write a value that makes the next ingest useless, and will find out from the
   * ingest. Adding a check here would put a rule in this class that the boot check already owns, so
   * the two would drift and neither would be the one an operator was refused by — and it would do
   * it for the one key that is live, before anyone has changed a value at runtime once. {@code
   * a_live_key_takes_a_value_the_boot_check_would_have_refused} is what stops it drifting in.
   *
   * <h2>What it answers with</h2>
   *
   * <p>The same {@link Setting} the listing carries, for the key just written — above all {@code
   * pinned}, so an operator who wrote without looking first is still told, in the answer to the
   * write itself, whether it survives a restart.
   *
   * <p>Read back from the map rather than echoed from the request, so the answer carries the
   * timestamp the database assigned and is what the map holds rather than what this request hoped.
   * If another write lands between the two statements the caller is shown the newer one, which is
   * the truer answer to "what is this key now".
   *
   * @param value the raw request body, stored exactly as it arrives. Not trimmed here: {@code
   *     RuntimeConfig.intOr} trims on the way out and says why — {@code curl --data-binary @file}
   *     carries the file's trailing newline — and doing it in both places would make this class a
   *     second opinion about what an operator's text means
   * @param caller optional servlet principal used only for legacy write attribution
   * @param account trusted authenticated account attribute, checked before configuration access
   */
  @PutMapping("/v1/config/{key}")
  public Setting set(
      @PathVariable String key,
      @RequestBody String value,
      Principal caller,
      @RequestAttribute(value = AuthFilter.HANDLE_ATTRIBUTE, required = false) String account) {
    requireAdministrator(account);
    // Asked once and carried, not asked twice. The class note above accounts
    // for the cost of asking per request rather than caching, and that
    // accounting is written for one scan; `isLive` then `liveKeys` walked
    // the same reflective list twice on the path that is refused, which is
    // the path a mistyped key takes.
    List<Declaration> live = seed.declared();
    if (!isLive(live, key)) {
      throw new BadRequestException(
          key
              + " is not a live configuration key, so writing it"
              + " would change nothing: it is bound once at boot and read from a final"
              + " field, and only a restart changes it. The keys that can be written are "
              + liveKeys(live)
              + ", which GET /v1/config lists with what each one holds");
    }
    String author = nameOf(caller);
    log.info("{} is setting {}", author, key);
    return ConfigUnavailableException.translating(
        "write " + key,
        () -> {
          store.put(key, value, author);
          return setting(key, store.entry(key).orElse(null));
        });
  }

  /**
   * One live key as this surface reports it.
   *
   * <p>{@code updatedAt} is here because {@link RuntimeConfig}'s upsert was corrected in review
   * specifically so that this listing could carry it — the {@code ON CONFLICT} branch assigns
   * {@code now()} rather than leaving the column's {@code DEFAULT} to fire on inserts only, and the
   * comment on that statement names this endpoint as the reader that would otherwise have shown a
   * frozen timestamp for every key anybody had changed twice.
   *
   * @param value what the map holds, or the bound value when it holds nothing
   * @param updatedAt when it was set, or null when nobody has set it
   * @param updatedBy who set it — {@code "boot"} for a value the seed wrote in from the
   *     environment, {@code "operator"} for one written through this controller, or null when there
   *     is no row at all
   * @param pinned whether an operator has pinned this key outside the jar, in which case the next
   *     boot overwrites whatever is written here
   */
  public record Setting(
      String key, String value, Instant updatedAt, String updatedBy, boolean pinned) {}

  /**
   * Authorization precedes configuration reads, key discovery and persistence on both endpoints.
   */
  private void requireAdministrator(String account) {
    if (account == null
        || ServiceCredentials.principal(account)
        || !administrators.isServerAdmin(account)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only a server administrator may manage live configuration");
    }
  }

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

  /**
   * The keys a refusal offers instead, in {@link RuntimeConfigSeed#declared()}'s order, which is
   * sorted for the reason that method gives: the same tree should hand a reader the same sentence
   * on every JVM. Takes the list its caller already holds, so that a refusal costs one reflective
   * walk.
   */
  private static String liveKeys(List<Declaration> live) {
    List<String> keys = new ArrayList<>();
    for (Declaration declared : live) {
      keys.add(declared.key());
    }
    return String.join(", ", keys);
  }

  /**
   * Preserve the existing principal-based attribution and operator fallback; neither grants access.
   */
  private static String nameOf(Principal caller) {
    return caller == null || caller.getName() == null || caller.getName().isBlank()
        ? OPERATOR
        : caller.getName();
  }
}
