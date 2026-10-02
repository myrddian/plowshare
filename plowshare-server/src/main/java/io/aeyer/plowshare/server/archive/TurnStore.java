package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.agents.Outcome.Ending;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * What was said in each conversation, and what came back.
 *
 * <h2>Why a turn is a row when a job is not</h2>
 *
 * <p>A turn <em>is</em> a job, and jobs live in one process's memory: {@code
 * JobStore}'s own javadoc argues that a durable job row would come back saying
 * {@code RUNNING} about a thread that no longer exists. None of that is in
 * question here. What is durable is not the run but its <b>record</b> — the same
 * distinction {@code JobStore} draws when it says that what a run produced is in
 * Postgres and the handle is not.
 *
 * <p>And it has to be, because half of it already is. {@code conversations}
 * keeps a budget across restarts; a conversation that came back having spent
 * eleven of twenty model calls with no account anywhere of what they bought
 * would be a receipt for a purchase nobody wrote down. Either both survive or
 * neither should.
 *
 * <h2>The ordinal is this store's to assign</h2>
 *
 * <p>{@link #record} takes no turn number. It computes {@code MAX(ordinal) + 1}
 * over the conversation's own turns inside the INSERT, so a caller cannot write
 * turn three twice, cannot leave a gap, and cannot number one conversation's
 * turn from another conversation's count. The single statement is also what
 * keeps it correct under a concurrent writer, in the sense that matters: two
 * inserts that computed the same number collide on {@code
 * turns_one_per_place_in_a_conversation} rather than both landing.
 *
 * <h2>What this store does not translate, and why</h2>
 *
 * <p>Two constraints can refuse a {@link #record}, and neither is turned into a
 * sentence here. {@code turns_belong_to_a_conversation} fires for an id nothing
 * opened; {@code turns_one_per_place_in_a_conversation} fires for the concurrent
 * second writer above. Both arrive as {@code DataIntegrityViolationException} —
 * the second as its {@code DuplicateKeyException} subclass — and {@code
 * ProposalStore.propose} shows the shape a translation would take.
 *
 * <p>It is declined because the two situations are opposite and only one of them
 * can be reproduced. A single {@code catch} would give the race the missing
 * conversation's sentence, which is the guard-whose-message-describes-a-
 * different-situation this project keeps finding; a pair of clauses would put
 * the message for the race on a branch no fixture can reach, since this store
 * assigns ordinals in sequence and cannot be made to collide with itself. The
 * constraints are named, so Postgres says which rule broke, and that is a truer
 * answer than a sentence written for the wrong one.
 *
 * <p>Framework-free apart from {@link JdbcTemplate}, and wired by {@link
 * ArchiveConfig} rather than annotated, matching {@link ConversationStore},
 * {@link ProposalStore} and {@link ReasonLog}.
 *
 * <h2>Who calls this, and the gap that used to be here</h2>
 *
 * <p>{@code agents.Turn} writes one row per turn, from inside the ending
 * callback and before the job is published as ended; {@code agents.Compaction}
 * reads them back for the turn ordinals a fold reaches. <b>It no longer decides
 * anything from {@link TurnRecord#promptTokens()}</b>: that column is a turn's
 * <em>longest</em> prompt, which includes the turn's own tool traffic, and none
 * of that traffic is projected into a later turn's prompt — so it is not a
 * length any fold can shorten, and the fold trigger reads the ending turn's own
 * transcript instead. The column is still written, and Slice 4's {@code GET
 * /v1/conversations/&#123;id&#125;/turns} still reads these rows for a person
 * rather than for a prompt — a browser tab that is reloaded has no scrollback,
 * so this is the only thing standing between a refresh and an empty
 * conversation.
 *
 * <h2>The block a turn went out with, and why it is not on the row</h2>
 *
 * <p>{@link #record} takes a <em>reference</em> to the system block a turn was
 * sent, and {@link #remember} is what puts the text somewhere for it to point
 * at. The block is the agent's prompt as it stood when the turn ran, and it is
 * the one thing in a projection that cannot be recomputed from the log: the file
 * it came from is a file an operator edits.
 *
 * <p><b>Content-addressed, because a prompt is large and mostly static.</b> A
 * shipped prompt is ~5 kB and changes rarely; writing it onto every row would
 * put about a megabyte of byte-identical text into a 200-turn conversation, for
 * a value that changed zero times. {@code V32__turn_system_prompt.sql} carries
 * the measurement and the alternatives it rejects. What that costs here is one
 * extra statement per turn — {@link #remember} before {@link #record} — and the
 * caller catching the first separately, so that a block that could not be
 * stored does not take the turn's own row down with it.
 *
 * <p><b>{@link #forConversation} carries the digest and never the text.</b> The
 * text is fetched one turn at a time, by {@link #blockSentAt}, for the turn
 * somebody actually opened — otherwise the listing would put the ~5 kB back onto
 * every turn in the answer and undo the whole of the above.
 *
 * <p><b>This class shipped read-only and with no bean, and the reason is worth
 * keeping.</b> There was no path from a finished run to a prompt-token count —
 * {@code Outcome} carries no usage and {@code LedgerEntry} carries no job id —
 * so every row a caller could have written was a row with nothing to put in that
 * column, and a bean with no caller is the same speculation as an endpoint with
 * none. The compaction task built the path, through {@code agents.Transcript},
 * and gave the table both. The read was here first and driven regardless, for
 * the reason {@code ReasonLog.forMemory} gives about the same situation: a store
 * that can only be written is a table nothing can answer from.
 */
public final class TurnStore {

    private static final Logger log = LoggerFactory.getLogger(TurnStore.class);

    private static final String COLUMNS =
            "conversation_id, ordinal, utterance, answer, ending, prompt_tokens, agent,"
                    + " system_block";

    /*
     * INSERT ... SELECT and not a read followed by a write. The ordinal is
     * computed from the same statement that uses it, so there is no window
     * between deciding a turn's number and writing it — and the aggregate makes
     * this exactly one row even when the conversation has no turns yet, which is
     * what COALESCE turns into the 1 that turns_are_numbered_from_one requires.
     *
     * The WHERE is the whole of the per-conversation numbering. Without it every
     * conversation would be numbered from the table's global maximum, which is a
     * history that still reads in order and whose first turn is number 47.
     *
     * RETURNING, so the assigned ordinal comes back without a second read of a
     * row this statement already has in hand.
     */
    private static final String INSERT = """
            INSERT INTO turns
                   (conversation_id, ordinal, utterance, answer, ending, prompt_tokens, agent,
                    system_block)
            SELECT ?, COALESCE(MAX(ordinal), 0) + 1, ?, ?, ?, ?, ?, ?
              FROM turns
             WHERE conversation_id = ?
            RETURNING %s""".formatted(COLUMNS);

    /*
     * Insert if absent, and never an update. The key IS the body's digest, so a
     * row that is already there holds the identical text by construction and
     * there is nothing an UPDATE could change that would not be a different row.
     *
     * ON CONFLICT DO NOTHING and not SELECT-then-INSERT: two turns of two
     * conversations sending the same block on two threads is the ordinary case,
     * not the rare one, and a read followed by a write would have both decide
     * "absent" and the second collide on the primary key. This statement is what
     * makes `remember` idempotent under a concurrent writer rather than merely
     * usually correct.
     */
    private static final String REMEMBER =
            "INSERT INTO system_blocks (hash, body) VALUES (?, ?) ON CONFLICT (hash) DO NOTHING";

    /*
     * A join and not two reads, and it is deliberately NOT part of `COLUMNS`.
     * `forConversation` answers every turn of a conversation and is what the
     * console's history screen reads; carrying the block text on each of those
     * rows would put the ~5 kB back onto every turn in the answer, which is the
     * cost V32 spends its header refusing to pay in the table. The listing
     * carries the digest, and the text is fetched for the one turn somebody
     * actually opened.
     */
    private static final String BLOCK_SENT_AT = """
            SELECT b.body
              FROM turns t
              JOIN system_blocks b ON b.hash = t.system_block
             WHERE t.conversation_id = ? AND t.ordinal = ?""";

    private final JdbcTemplate jdbc;

    public TurnStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Write down one turn, at the next place in its conversation.
     *
     * <p><b>The first turn also names the conversation</b>, from its own
     * utterance — see {@link #nameTheConversation}, and {@code
     * ConversationStore#nameFromFirstTurn} for the one statement that does it.
     * It happens here and not at a call site because every path that records a
     * turn comes through this method: a conversation whose first turn was
     * written by some other caller would otherwise stay nameless for ever, which
     * would make the naming a property of one caller rather than of the archive.
     * A name that could not be written is logged and stepped over and never
     * reaches this method's caller.
     *
     * @param conversationId the conversation this was said in
     * @param utterance what the person said. Never blank — {@code
     *     turns_utterance_is_not_blank} refuses one, as {@code JobStore.submit}
     *     already refuses a blank task
     * @param answer what the turn came to, which is {@code Outcome.text} for
     *     every ending. Blank is permitted only for {@code ANSWERED}; see {@code
     *     turns_a_turn_that_stopped_says_how}, which is {@code Outcome}'s own
     *     rule held in the database
     * @param ending which of the seven this turn reached
     * @param promptTokens what this turn's prompt cost by the model's own
     *     tokenizer, or {@code null} for a turn nobody measured. <b>Not zero</b>
     *     — absent and free are different facts, and {@code agents.Compaction}
     *     decides from this number. What {@code Turn} supplies is the turn's
     *     <em>longest</em> prompt, since the context bound applies to every call
     *     a turn makes and not only to its first
     * @param agent which agent answered, or {@code null} for a caller that does
     *     not know. <b>Nullable and not required, which is a decision about
     *     history rather than about callers.</b> Every caller in {@code main}
     *     supplies it — a turn is started at {@code POST
     *     /v1/agents/&#123;name&#125;/runs}, so there is no run of one that does
     *     not know which agent is answering — and the null exists because the
     *     column does: {@code V17__conversation_origin.sql} could not backfill
     *     rows written before it, and a store that refused null here would be
     *     unable to write the shape its own table already holds
     * @param systemBlock the hash {@link #remember} returned for the system
     *     block this turn actually went out with, or {@code null} for a turn
     *     that did not record one. <b>Nullable, permanently, and that is what
     *     the column is for rather than a concession to one.</b> Every row
     *     written before {@code V32__turn_system_prompt.sql} has no block and
     *     nothing anywhere can recover one; so does a turn whose block could not
     *     be stored, which {@code Compaction} logs and steps over rather than
     *     failing the turn for. A null reads back as "not recorded", which is
     *     true of both, and {@code Compaction.projectionAsOf} answers such a turn
     *     with the definition as it stands now and says so on the wire
     * @return the turn as it was written, carrying the ordinal it was given
     * @throws ValidationException if the conversation id cannot be named
     * @throws org.springframework.dao.DataIntegrityViolationException if no
     *     conversation has that id, or if {@code systemBlock} names a block
     *     {@code system_blocks} does not hold — {@code
     *     turns_a_recorded_block_is_one_this_table_holds}. Untranslated, and the
     *     class javadoc says why
     */
    public TurnRecord record(
            String conversationId, String utterance, String answer, Ending ending,
            Integer promptTokens, String agent, String systemBlock) {
        String conversation = ConversationStore.named(conversationId);
        String endingName = ending.name();
        TurnRecord written = ArchiveUnavailableException.translating("write down a turn",
                () -> jdbc.queryForObject(INSERT, ROW_MAPPER,
                        conversation, utterance, answer, endingName, promptTokens, agent,
                        systemBlock, conversation));
        nameTheConversation(conversation, utterance);
        return written;
    }

    /**
     * Offer the conversation a name made from what was just said, and let it
     * keep the one it has.
     *
     * <h2>After the insert, never before it, and never inside it</h2>
     *
     * <p><b>After</b>, because a conversation that has no turn must not have a
     * name: the INSERT is what refuses a turn in a conversation nothing opened —
     * {@code turns_belong_to_a_conversation} — and naming first would mean this
     * statement deciding, separately and more weakly, whether that conversation
     * exists. Ordering it this way means a title only ever lands on a row that
     * has just been proved to hold a turn.
     *
     * <p><b>Not inside the INSERT</b>, for the reason {@link #remember} gives
     * about system blocks one method down: a turn must not fail because
     * something written beside it could not be. A single statement naming both
     * tables would have a refused title take the {@code turns} row with it, and
     * a conversation would lose the record of what was said over the record of
     * what to call it.
     *
     * <h2>Swallowed here rather than by the caller, which is the point</h2>
     *
     * <p>The catch cannot be left to {@code agents.Compaction}. Its {@code catch}
     * around {@link #record} already exists and already logs <em>"its run ended,
     * and could not be written into the transcript"</em> — a sentence about a
     * turn that was <b>not</b> written. A title that threw out of this method
     * would reach that catch after the turn row had landed, and an operator
     * would be told a turn was lost that is sitting in the table: a guard whose
     * message describes a different situation, which is the failure shape this
     * project keeps finding. So the failure is named here, where what failed is
     * known.
     *
     * <p><b>A turn recorded with no title is strictly better than a title with
     * no turn</b>, and this is the asymmetry every decision above serves. An
     * unnamed conversation reads back as a conversation with no name, which is
     * already the ordinary state of every row written before {@code
     * V38__conversation_title.sql} and of every conversation nobody has spoken
     * into — so a failure here degrades to a state the whole system already
     * handles, rather than to a missing one.
     *
     * <p><b>It is deliberately not driven by a fixture.</b> Nothing a caller can
     * pass makes this UPDATE fail: {@code ConversationStore.titleFrom} never
     * produces the one string {@code conversations_a_title_is_named} refuses,
     * the row was proved to exist one statement ago, and the column has no
     * width to overflow. What is left is a database that went away between two
     * statements, which is the situation this catch is for and not one a test
     * can stage — {@code TurnStore}'s own javadoc declines to translate two
     * constraints on the same grounds: a branch no fixture can reach is worse
     * written speculatively than written honestly.
     */
    private void nameTheConversation(String conversation, String utterance) {
        try {
            ConversationStore.nameFromFirstTurn(jdbc, conversation, utterance);
        } catch (RuntimeException notNamed) {
            // The first line only. Postgres puts "Detail: Failing row contains
            // (...)" on the second, and for `conversations` that row now holds
            // a title -- which is the first line of what somebody said.
            log.warn("conversation {}: its first turn was written down but could not name it,"
                            + " so it will read back as unnamed. Reason: {}",
                    conversation, firstLineOf(notNamed));
        }
    }

    /** The first line of a failure's message, or the exception's own name when
     *  it carries none — {@code agents.JobRuntime.describe} one package over,
     *  which this class cannot see. */
    private static String firstLineOf(RuntimeException failed) {
        String message = failed.getMessage();
        if (message == null || message.isBlank()) {
            return failed.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(failed.getClass().getSimpleName());
    }

    /**
     * Write down one system block, if this is the first turn to send it, and
     * answer with the name it is known by.
     *
     * <h2>Separate from {@link #record}, and the separation is the point</h2>
     *
     * <p><b>A turn must not fail because its audit trail could not be
     * written.</b> If the block were hashed and inserted inside {@link #record},
     * a refused {@code system_blocks} insert would take the {@code turns} row
     * down with it, and a conversation would lose the record of what it said
     * over a record of what it was shown. Two calls means the caller can catch
     * the first, carry on with {@code null}, and still write the turn — which is
     * what {@code Compaction.closed} does, beside the catch that already stands
     * around {@link #record} for the same reason.
     *
     * <p><b>Idempotent, and by the statement rather than by a check.</b> The key
     * is the block's own digest, so "already stored" and "stored by another
     * thread a microsecond ago" are the same situation, and {@code ON CONFLICT
     * DO NOTHING} is the whole of the answer. Nothing is ever updated: a row
     * under this key holds these bytes by construction.
     *
     * @param block the system block as it was sent, whole and untrimmed. Two
     *     texts differing only in trailing whitespace are two blocks, because
     *     they are two things a model was shown
     * @return the lowercase hex SHA-256 of {@code block}'s UTF-8 bytes, to be
     *     handed to {@link #record}
     * @throws ValidationException if the block is null or blank. <b>Refused
     *     rather than stored</b>: {@code JobRuntime.oneSystemMessageFirst} sends
     *     no system message at all when there is no system text, so a row
     *     holding one would be the record of a message that was never sent, and
     *     {@code system_blocks_a_block_is_not_blank} refuses it one layer down
     */
    public String remember(String block) {
        if (block == null || block.isBlank()) {
            throw new ValidationException(
                    "field 'block' is required and must not be empty: a turn that sent no system"
                            + " message has no block to remember, and that is turns.system_block"
                            + " being NULL rather than a row holding nothing");
        }
        String hash = hashOf(block);
        ArchiveUnavailableException.translating("write down a system block",
                () -> jdbc.update(REMEMBER, hash, block));
        return hash;
    }

    /**
     * The system block one turn was actually sent, when that turn recorded one.
     *
     * <p><b>Empty is "not recorded", and it is the ordinary answer for most of
     * the archive.</b> Every turn written before {@code
     * V32__turn_system_prompt.sql} is in that state and will be for ever, since
     * nothing can recover a prompt file as it stood; so is a turn whose block
     * could not be stored. It is deliberately not distinguished here from "no
     * such turn", because the caller has already refused a turn the conversation
     * never reached — {@code ConversationController.aTurnThatHappened} — and a
     * second refusal at this depth would be one rule held in two places that can
     * disagree.
     *
     * <p><b>One turn and not the conversation's.</b> This is the read {@code
     * Compaction.projectionAsOf} makes for the one turn somebody opened, which
     * is what makes the ~5 kB it carries affordable; {@link #forConversation}
     * answers with the digest and never with the text, for the reason {@code
     * BLOCK_SENT_AT} carries above it.
     *
     * @param conversationId the conversation being read
     * @param turn the turn's ordinal, from 1
     * @return the block that turn went out with, or empty when it recorded none
     * @throws ValidationException if the conversation id cannot be named
     */
    public Optional<String> blockSentAt(String conversationId, int turn) {
        String conversation = ConversationStore.named(conversationId);
        return ArchiveUnavailableException.translating("read the block a turn was sent",
                () -> jdbc.query(BLOCK_SENT_AT, (rs, rowNum) -> rs.getString("body"),
                                conversation, turn)
                        .stream().findFirst());
    }

    /**
     * What a block is called: the lowercase hex SHA-256 of its UTF-8 bytes.
     *
     * <p><b>The one place this is computed</b>, so a block written and a block
     * looked up cannot be named by two different rules — {@code
     * ConversationStore.named}'s reason for being static and shared.
     *
     * <p>{@code V32__turn_system_prompt.sql} carries the argument for SHA-256
     * and for the two digests it rejects. In short: this is an identity and not
     * a security check, and the width is chosen against accident, because a
     * collision would serve one agent's prompt as another's on the screen that
     * exists to stop plausible stories.
     *
     * <p>The {@code NoSuchAlgorithmException} is unreachable — every JDK is
     * required to ship SHA-256 — and it is rethrown rather than swallowed,
     * because a runtime that really lacked it could not name a block at all, and
     * a naming rule that quietly changed is the one failure this method must not
     * have.
     */
    private static String hashOf(String block) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(block.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException noSha256) {
            throw new IllegalStateException(
                    "this runtime has no SHA-256, so no system block can be named", noSha256);
        }
    }

    /**
     * Everything said in one conversation, in the order it was said.
     *
     * <p><b>A conversation nobody has spoken into answers with an empty list
     * rather than raising</b>, the choice {@code ReasonLog.forMemory} makes and
     * for the same reason: "this conversation has no turns" is a true and
     * ordinary state — every conversation is in it for a moment after {@code
     * ConversationStore.open} — so refusing would make an empty history
     * indistinguishable from a missing conversation.
     *
     * <p>Which is exactly why an unusable id is refused rather than answered.
     * A null or blank one would otherwise come back as that same empty list, and
     * a dropped identifier would read as a person who has said nothing.
     *
     * @throws ValidationException if the conversation id cannot be named
     */
    public List<TurnRecord> forConversation(String conversationId) {
        String conversation = ConversationStore.named(conversationId);
        return ArchiveUnavailableException.translating("read a conversation's turns",
                () -> jdbc.query(
                        "SELECT " + COLUMNS + " FROM turns WHERE conversation_id = ?"
                                + " ORDER BY ordinal",
                        ROW_MAPPER, conversation));
    }

    /**
     * The ordinal of the conversation's latest turn, or 0 before its first — where a record written
     * outside any turn is filed, at 1 or more (spec 2026-09-28-hooks-reach-the-log decision 7).
     */
    public int latestOrdinal(String conversationId) {
        String conversation = ConversationStore.named(conversationId);
        Integer latest = ArchiveUnavailableException.translating("read a conversation's latest turn",
                () -> jdbc.queryForObject(
                        "SELECT COALESCE(MAX(ordinal), 0) FROM turns WHERE conversation_id = ?",
                        Integer.class, conversation));
        return latest == null ? 0 : latest;
    }

    /*
     * getObject and not getInt for prompt_tokens: getInt reads a SQL NULL as 0,
     * which is precisely the collapse turns_prompt_tokens_are_a_measurement
     * exists to prevent one layer down — a turn nobody measured would come back
     * claiming its prompt was free, and the next task would decide compaction
     * from it.
     */
    private static final RowMapper<TurnRecord> ROW_MAPPER = (rs, rowNum) -> new TurnRecord(
            rs.getString("conversation_id"),
            rs.getInt("ordinal"),
            rs.getString("utterance"),
            rs.getString("answer"),
            Ending.valueOf(rs.getString("ending")),
            rs.getObject("prompt_tokens", Integer.class),
            rs.getString("agent"),
            rs.getString("system_block"));
}
