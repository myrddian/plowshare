package io.aeyer.plowshare.server.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The values this server can be told to change while it is running.
 *
 * <p>Everything else here is bound once. Spring reads {@code application.yml} at boot, a
 * {@code @Configuration} class reads the bound object, and the numbers go into constructors that
 * hold them as final fields — so a running server has no path by which a value can change, and
 * every change is a file edit and a restart. This is that path, for the few keys that have one.
 *
 * <h2>The store deals in text, and what a key means is written down elsewhere</h2>
 *
 * <p>The properties classes already turn strings into ints, durations and URLs, and they already
 * carry the javadoc explaining what each one means and what changing it costs — {@code
 * DocumentsProperties#ingestBudget} spends four paragraphs on one integer. A map that decided what
 * a key <em>is</em> would be a second place that knows, and two places that know one thing is what
 * drifts. So a row is a string, and nothing here asks what the string meant.
 *
 * <p><b>That is a rule about where meaning lives, not about which class a parse runs in.</b> A
 * typed convenience over this store — an {@code intOr(key, fallback)} for the first live accessor
 * that wants one — is still a caller naming its own type and its own default, and it leaves both
 * facts on the accessor that already documents them. What the rule forbids is this class holding an
 * opinion about a key: a column typed per setting, a registry of which key is an {@code int}, a
 * default of its own to fall back to.
 *
 * <h2>Absent is not blank, and the boot rule rests on the difference</h2>
 *
 * <p>{@link #get} answers {@link Optional#empty()} for a key with no row, never {@code ""}. A key
 * with no row is a key no operator has ever set, which is what lets a shipped default seed it on a
 * later boot without overruling somebody; a key holding an empty string is a value a person chose.
 * Collapsing the two would make a runtime write indistinguishable from a key nobody has touched,
 * and the seed would then overwrite it on the next restart.
 *
 * <h2>Nothing here validates, and nothing here translates</h2>
 *
 * <p><b>No write-time validation.</b> A live key is checked at boot, by the same
 * {@code @Configuration} class that has always checked it, and is <em>not</em> checked on write.
 * Writing an ingest budget of 0 at runtime is accepted here and the next ingest summarises nothing
 * on a healthy document. That is the stated cost of keeping the validating classes untouched for a
 * first slice of <b>one</b> key, rather than an oversight: the design records it, and the shape of
 * the fix — a predicate carried by the annotation — is written down beside the argument it would
 * have to re-open.
 *
 * <p><b>One key, and the count is smaller than the design expected rather than smaller than it
 * was.</b> Spec §6 named two and §6.1 a third; the third does not exist as a property Spring binds,
 * and the second was found in Task 6 to have no reader that can honour a runtime write, so
 * annotating it would have produced a key the API could set and nothing behavioural would read.
 * §6.1 and §6.2 carry both findings and {@code AgentsProperties#getDirectory} carries the second.
 * What is live today is {@code plowshare.documents.ingest-budget}, on its own.
 *
 * <p><b>No exception translation, and the reason is not {@code MemoryStore}'s.</b> That store wraps
 * its statements in {@code ArchiveUnavailableException.translating} so that a dead Postgres
 * <em>ends the run</em>: {@code JobRuntime.dependencyFailure} names that type, and an untranslated
 * {@link org.springframework.dao.DataAccessException} is not in that list, so it falls to the
 * recoverable side and returns to the model as an ordinary tool result. <b>Translating there makes
 * runs fail more loudly, not less</b> — and it is the only honest move that store has, because
 * there is no bound copy of a memory to fall back to.
 *
 * <p>This map is in the opposite position: every key it holds also exists as a value Spring bound
 * and a {@code @Configuration} class validated at boot. So translation is not reused here — but
 * neither is a raw exception the right answer for every reader, and it is worth being exact about
 * which reader gets what.
 *
 * <p><b>The boot check</b> wants the failure as it is: a server that cannot reach the map must not
 * come up believing it has read one.
 *
 * <p><b>An operator's {@code PUT}</b> wants a refusal it can act on, and it now has one. This
 * paragraph read "and today does not get one": there was no handler for a {@code
 * DataAccessException} anywhere in {@code ApiExceptionHandler}, so a database outage reached the
 * one person able to fix it as a 500 saying <i>"This is a fault in the server, not in the
 * request"</i> — false, and pointed at the wrong thing. {@link ConfigUnavailableException} is that
 * gap closed: {@code RuntimeConfigController} wraps its calls to this class in it, and the 503
 * beside it says plainly that nothing was read, nothing was written, and nothing about the request
 * was wrong. Reusing {@code ArchiveUnavailableException} would have traded the wrong status for a
 * 503 whose body talks about what is remembered, which this is not, and that class's own comment
 * records the argument rather than repeating it here.
 *
 * <p><b>The translation is not done here, and that is the one thing this paragraph asks of a future
 * reader.</b> {@code MemoryStore} wraps every statement it makes; this class wraps none, and raises
 * Spring's exception as it comes. The reason is the split above — the boot check wants the failure
 * raw, and a live accessor wants a fallback rather than a throw — so a translation in the store
 * would have to be undone by two of its three readers. Only the one reader that wants a status has
 * it, and it does it at its own boundary.
 *
 * <p><b>A live accessor</b> — the reader that runs inside a run, since {@code OpenAiTransport} asks
 * a pool for its {@code base-url} while building a model call — <b>must fall back rather than
 * propagate</b>. Not because failing would be too loud, but because this is the one reader that
 * <em>has</em> something true to fall back to. Propagating would newly couple every model call to
 * Postgres being up: a run that uses only file tools and a model, and survives an outage today,
 * would begin dying of a feature whose whole purpose was to save restarts. The accessor names the
 * fallback, because the accessor is what holds the bound value; {@link #intOr} is where that
 * falling back is <em>done</em>, and its javadoc argues why the memory it needs cannot live on the
 * accessor.
 *
 * <p>Those refusals are not incidental. {@link #put} does <b>not</b> check its author argument in
 * Java. {@code updated_by} is {@code NOT NULL} and {@code CHECK (updated_by <> '')} in V28, so a
 * nameless write is refused by Postgres — which means the promise that a value carries the name of
 * whoever set it holds against a {@code psql} session as well as against this method, rather than
 * resting on every future writer remembering to come through here.
 */
@Repository
public class RuntimeConfig {

  /*
   * `updated_at = now()` is written out on the conflict branch and is not
   * redundant with the column's DEFAULT: a DEFAULT applies to an INSERT, so
   * an upsert that omitted it would leave a key's timestamp frozen at the
   * moment it was first written, however many times it has since changed --
   * and the one column whose job is to say when would then be quietly wrong
   * for every key that has ever been changed twice.
   *
   * `now()` and not a bound Instant, so the timestamp and the DEFAULT come
   * from one clock. It is the transaction's start time, which is the same
   * answer the DEFAULT would have given.
   */
  private static final String UPSERT =
      """
            INSERT INTO runtime_config (key, value, updated_by)
            VALUES (?, ?, ?)
            ON CONFLICT (key) DO UPDATE SET
                value      = EXCLUDED.value,
                updated_at = now(),
                updated_by = EXCLUDED.updated_by
            """;

  private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

  private final JdbcTemplate jdbc;

  /**
   * The last answer this store gave for a key, kept so that a store which cannot be reached can
   * still answer what a reachable one last answered.
   *
   * <p><b>Here, and not on the properties object that holds the bound value.</b> Three reasons, in
   * the order they decided it.
   *
   * <p>It is a fact about <em>this store</em>, not about a document ingest or an agent directory.
   * What a key last read is the same fact whoever asks, and the class that owns the key has no more
   * claim on remembering it than on remembering the connection pool's state. A copy per properties
   * class would be several memories of one thing, which is the shape the class comment above
   * already refuses for what a key <em>means</em>.
   *
   * <p>It is correct across several properties classes for a reason the boot enforces rather than
   * one this class hopes for: {@code RuntimeConfigSeed.oneAccessorPerKey} refuses a boot in which
   * two accessors claim one key, so a key names at most one live accessor and keying this by key
   * cannot mix two of them up.
   *
   * <p>And it keeps the fallback in one implementation. On the accessor, every class that goes live
   * after {@code DocumentsProperties} would carry its own copy of "cache the read, fall back to the
   * cache, warn once it is stale" — six lines that are wrong in a way nothing shows, since a copy
   * that forgot the memory and fell back to its bound value still passes every test about a store
   * that is <em>up</em>.
   *
   * <p><b>What it holds is the raw text, absence included</b>, which is what makes the promise
   * expressible as one sentence: an unreachable store answers what a reachable one last answered. A
   * read that succeeded and found nothing is remembered as {@link Optional#empty()} and not as a
   * gap, so a row deleted while the store was up puts the accessor back on its bound value instead
   * of leaving it on a value whose row no longer exists. A gap — no entry at all — means only that
   * no read has ever succeeded.
   *
   * <p>Concurrent because a properties bean is a singleton read from every request thread and every
   * job thread. Unbounded, and deliberately: it holds one entry per live key — a set the boot has
   * already enumerated and refused a typo in, and which is one key. Spec §6 expected a second; §6.2
   * is why it is not coming, so this map is bounded by the declarations in this jar and not by a
   * number anybody expects to grow soon.
   */
  private final Map<String, Optional<String>> lastRead = new ConcurrentHashMap<>();

  public RuntimeConfig(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * What this key currently holds, or empty if nobody has ever set it.
   *
   * <p>Empty is a real answer and every caller has one for it: an accessor falls back to its bound
   * value, and the boot seed takes it as permission to write a shipped default in. See the class
   * comment on why that is not the same answer as an empty string.
   */
  public Optional<String> get(String key) {
    return entry(key).map(Entry::value);
  }

  /**
   * What a live accessor reads: this key as a whole number, or the value the accessor was bound
   * with.
   *
   * <p>The caller names its own type and its own default, so this stays a convenience over the
   * store rather than the store holding an opinion about a key — the class comment above draws that
   * line and this is the side of it a typed read sits on. A second type wanting one gets {@code
   * longOr} beside this, not a registry of what each key is.
   *
   * <p>The fallback is returned in three cases: the key has no row, its value is not a whole
   * number, and <b>the store cannot be reached at all</b>. The first is silent; the other two are
   * not.
   *
   * <h2>Falling back means the last known good value, not the bound one</h2>
   *
   * <p>{@code fallback} is the value Spring bound at boot, and it is the answer only until a read
   * has succeeded once. After that, an unreachable store answers with what the map last said.
   * <b>Reverting to the bound value instead has a failure with a name.</b> An operator raises
   * {@code ingest-budget} from 300 to 1000, a Postgres blip lands mid-ingest, and the ingest
   * silently runs at 300 — the exact number and the exact failure {@code implementation rationale}
   * §11 records the budget work as existing to stop, arriving through the feature meant to end it.
   * For a model key it is worse: a run answers under a model an operator swapped away from, and the
   * archive records the swap as having happened.
   *
   * <p>So {@code fallback} is the floor and not the fallback. The distinction is the whole of
   * {@link #lastRead}, and {@code
   * DocumentsPropertiesTest.an_unreachable_store_answers_with_the_last_value_it_read} is what holds
   * it.
   *
   * <h2>It is loud, and what silence would collapse</h2>
   *
   * <p>A silent fallback makes "no override is set" and "an override exists and could not be read"
   * the same observable event — the collapse this class's own <i>absent is not blank</i> paragraph
   * argues is unacceptable one level down, arriving one level up. So the two unusable states warn,
   * naming the key, and an absent key does not: warning on a key nobody has set would fire on the
   * ordinary first boot of every deployment and make the other two unreadable.
   *
   * <p>At {@code warn} and not into the value, on {@code
   * ApiExceptionHandler.embeddingUnavailable}'s reasoning: a dependency that is not answering is a
   * fact about the deployment rather than about the one caller that happened to ask. <b>Per read,
   * and not once per outage</b> — every fallback is a decision this server made on somebody's
   * behalf, and a report that stops after the first one leaves an operator unable to see how much
   * ran on the old number. The rate is affordable because of what the next paragraph measures; a
   * live key ever landing on a per-model-call path — spec §6.1's deferred pool keys are the
   * candidates — is what would make it worth revisiting, and is a decision for whoever puts one
   * there.
   *
   * <h2>Every call is a read, and what that costs</h2>
   *
   * <p>No time-to-live and no invalidation: the store is asked on every call. What that buys is
   * that a {@code PUT} is visible to the next read rather than to the one after a window nobody
   * documented, which is the only form in which spec §1.3's promise is checkable by the operator
   * who made the write.
   *
   * <p><b>Measured</b> on this machine, 1000 sequential {@code intOr} calls against the {@code
   * pgvector/pgvector:pg16} container the tests use: <b>0.16 ms</b> each over a pooled connection,
   * which is what this server runs on — {@code spring-boot-starter-jdbc} brings HikariCP and {@code
   * application.yml} configures no other {@code DataSource}. The same loop over a {@code
   * DriverManagerDataSource} costs <b>4.4 ms</b>, and the difference is entirely the TCP and
   * authentication handshake that unpooled source performs per call; it is recorded because the
   * test fixtures use exactly that shape and a number read off one of them would be twenty-seven
   * times the truth.
   *
   * <p>The number that actually bounds the cost is not the latency but how often a live value is
   * consumed, <b>and on this tree that is once per ingest</b>: {@code IngestService.ingest} asks
   * for the budget as it starts and holds it for that run, so the ingest that spends 496 model
   * calls on §11's 30-page paper reads this once and then works for half an hour. A TTL cache would
   * be a second staleness window, sized by guesswork, layered on the fallback above, to save a
   * sixth of a millisecond per ingest.
   *
   * <p><b>This paragraph said the opposite until the review of that change, and the way it was
   * wrong is worth keeping.</b> It read "twice per boot", and closed by asserting that spec §1.3
   * "needs a consumer that reads the accessor per unit of work rather than per boot, <em>which no
   * task in this plan changes</em>". Both halves were true when written and both were falsified by
   * the next commit — {@code DocumentsConfig} now reads the bound accessor for its refusal and
   * hands the object over, and the live read happens inside {@code ingest()}. A sentence that says
   * what the rest of the plan will not do is a sentence with a short life, and it survived a commit
   * because this file was not in that task's file list.
   *
   * @param fallback what the accessor holds — the floor described above, and the answer whenever
   *     the map has nothing usable to offer
   */
  public int intOr(String key, int fallback) {
    Optional<String> known = currentOrLastKnownGood(key);
    if (known.isEmpty()) {
      return fallback;
    }
    String value = known.get();
    try {
      // Trimmed, because surrounding whitespace is a fact about the text
      // and not about the key: `curl --data-binary @file` carries the
      // file's trailing newline, and refusing that would be this class
      // deciding an operator meant something they did not.
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException notAWholeNumber) {
      log.warn(
          "the runtime config map holds '{}' for {}, which is not a whole number, so"
              + " this server is answering with {} — the value it started with. Nothing"
              + " checks a value on the way in, so this is what a mistyped write looks"
              + " like from the reading end",
          value,
          key,
          fallback);
      return fallback;
    }
  }

  /**
   * This key as the store has it, or as the store last had it.
   *
   * <p><b>{@link org.springframework.dao.DataAccessException} and nothing narrower</b>, because
   * narrower is a list of the outages somebody thought of. A dead socket is {@code
   * CannotGetJdbcConnectionException}, an exhausted pool and a statement that ran out of time are
   * {@code QueryTimeoutException}, a Postgres in recovery is a {@code
   * TransientDataAccessResourceException} — three different branches of that hierarchy for one
   * question, and the next deployment shape adds a fourth. What the caller has to decide is
   * answerable without the taxonomy: this read did not produce a value, and there is a true one to
   * hand back.
   *
   * <p><b>And nothing wider.</b> {@code RuntimeException} would cover this class's own bugs — a
   * mapper reading a column that is not there — with a plausible number and a warning that says an
   * outage, so a defect in the store would look exactly like somebody else's Postgres being down.
   * The whole family below {@code DataAccessException} is what Spring produces from talking to a
   * database, and it is the boundary this is about.
   *
   * <p><b>An absent key is not an unreachable store, and the two cannot be confused here</b> —
   * which is what the design exists to keep apart, and it holds by construction rather than by
   * care: absence arrives as a returned {@link Optional#empty()} from a read that worked,
   * unreachability as a throw from a read that did not. A successful empty read updates the memory
   * and warns about nothing; a throw warns and never touches it.
   */
  private Optional<String> currentOrLastKnownGood(String key) {
    try {
      Optional<String> read = get(key);
      lastRead.put(key, read);
      return read;
    } catch (DataAccessException unreachable) {
      Optional<String> remembered = lastRead.get(key);
      String answering =
          remembered == null || remembered.isEmpty()
              ? "the value it started with"
              : "'" + remembered.get() + "', the last value it read from the map";
      log.warn(
          "the runtime config map could not be read for {}, so this server is"
              + " answering with {}",
          key,
          answering,
          unreachable);
      return remembered == null ? Optional.empty() : remembered;
    }
  }

  /**
   * Sets a key, replacing whatever was there.
   *
   * <p>An upsert on the primary key, so a key holds one value and a second write is one row that
   * changed rather than a second row. This map answers "what is this value now"; the history of a
   * setting is a different question, with a retention argument of its own that V24's {@code jobs}
   * table is the precedent for.
   *
   * <p>The author replaces the previous one along with the value, because the name it carries is
   * the name of whoever chose the value it is holding. An upsert that kept the first writer's name
   * would leave {@code updated_by} answering a question nobody asked.
   *
   * @param updatedBy who is setting it — an operator, or {@code "boot"} when the seed writes an
   *     operator's pinned value in. Deliberately unchecked here: the schema refuses a nameless
   *     write, so the guarantee holds for anything that writes the row and not only for callers of
   *     this method.
   * @throws org.springframework.dao.DataAccessException if the row is refused, which is what an
   *     empty author arrives as
   */
  public void put(String key, String value, String updatedBy) {
    jdbc.update(UPSERT, key, value, updatedBy);
  }

  /**
   * Who last set this key, or empty if nobody has.
   *
   * <p>The reason {@code updated_by} is in the table at all: a value that surprises somebody has a
   * name against it. Separate from {@link #get} because the ordinary read path wants a value and
   * nothing else — asking for the author is asking a question about the change rather than about
   * the configuration.
   */
  public Optional<String> authorOf(String key) {
    return entry(key).map(Entry::updatedBy);
  }

  /**
   * Everything the map holds, by key.
   *
   * <p>What {@code GET /v1/config} is built from, and it carries each value's author beside it so
   * that listing what is set does not cost a second query per key. Sorted, so the listing is the
   * same listing twice running — physical row order would make any assertion about it depend on the
   * plan.
   *
   * <p>It lists what has been <em>written</em>, which is not the same set as what is live: a key
   * that was live and no longer is leaves a row behind, and a live key nobody has set has no row.
   * Reconciling the two is the caller's job, because only the source declaring {@code @Live} knows
   * the other half.
   */
  public List<Entry> all() {
    return jdbc.query("SELECT " + COLUMNS + " FROM runtime_config ORDER BY key", ROW_MAPPER);
  }

  /**
   * One key's whole row, or empty if nobody has ever set it.
   *
   * <p>Public because {@link #get} and {@link #authorOf} are two statements, and a caller that
   * wants both — an operator asking what a value is and who set it, the listing of a single key —
   * can have a write land between them and see writer A's value beside writer B's name. That is a
   * row state that never existed, and it contradicts the one promise {@link #put} makes about the
   * pair: that the name a value carries is the name of whoever chose it. One statement cannot tear.
   *
   * <p>{@link #get} and {@link #authorOf} stay. Most callers want one field, and making every one
   * of them take a record apart to reach it would spend the readability of the common path on a
   * hazard only the uncommon path has.
   */
  public Optional<Entry> entry(String key) {
    List<Entry> found =
        jdbc.query("SELECT " + COLUMNS + " FROM runtime_config WHERE key = ?", ROW_MAPPER, key);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  /**
   * One key's row: what it holds, and the record of who put it there.
   *
   * <p>{@link #get} and {@link #authorOf} are both projections of {@link #entry}, so the column
   * names live in one mapper and a schema change has one place to be answered.
   */
  public record Entry(String key, String value, Instant updatedAt, String updatedBy) {}

  private static final String COLUMNS = "key, value, updated_at, updated_by";

  private static final RowMapper<Entry> ROW_MAPPER =
      (rs, rowNum) ->
          new Entry(
              rs.getString("key"),
              rs.getString("value"),
              instant(rs, "updated_at"),
              rs.getString("updated_by"));

  /*
   * OffsetDateTime and not java.sql.Timestamp, for MemoryStore's reason: a
   * Timestamp carries no zone and the driver reads it back through the JVM's
   * default calendar, so a server outside UTC round-trips a shifted instant.
   * Here that would misreport when a value was changed, which is half of what
   * the row is kept for.
   *
   * No null branch, where MemoryStore's copy of this has one. That store reads
   * columns that are genuinely nullable -- `invalidated_at` is null for every
   * live memory -- and `updated_at`, the only column this reads, is NOT NULL
   * with a DEFAULT. Carrying the branch anyway would make Entry.updatedAt a
   * nullable field in a record where nothing else is, so every caller would
   * owe a case the schema has already made unreachable.
   */
  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, OffsetDateTime.class).toInstant();
  }
}
