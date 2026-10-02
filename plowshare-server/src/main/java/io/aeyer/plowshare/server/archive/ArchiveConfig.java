package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.agents.JobsProperties;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires {@link Archive} as the bean {@link
 * io.aeyer.plowshare.server.api.MemoryController} depends on.
 *
 * <p>{@link Archive} takes no stereotype annotation of its own — it stays
 * framework-free, so whatever wires it supplies the mechanisms it will not
 * name. This is that wiring: {@link MemoryStore} and {@link EmbeddingClient}
 * are already beans ({@code @Repository} and {@code @Service} respectively),
 * the three plain numbers come from {@link ArchiveProperties}, and the
 * transaction arrives as a {@link UnitOfWork}.
 */
@Configuration
@EnableConfigurationProperties(ArchiveProperties.class)
public class ArchiveConfig {

    /** Where the account of each write goes; see {@link ReasonLog}. Wired here
     *  rather than annotated, matching {@link ProposalStore} below: it is the
     *  archive's, not a bean anything else asks for. */
    @Bean
    public ReasonLog reasonLog(JdbcTemplate jdbc) {
        return new ReasonLog(jdbc);
    }

    @Bean
    public Archive archive(
            MemoryStore store,
            ReasonLog reasons,
            EmbeddingClient embeddings,
            UnitOfWork unitOfWork,
            ArchiveProperties props) {
        return new Archive(
                store,
                reasons,
                embeddings,
                unitOfWork,
                props.getMaxBodyChars(),
                props.getIndexThreshold(),
                props.getHalfLifeDays());
    }

    /**
     * The proposal queue's rows.
     *
     * <p>Wired here rather than annotated {@code @Repository} like {@link
     * MemoryStore}, because it takes a clock and an id factory: a component scan
     * has nothing to supply for either, and the production defaults are what its
     * one-argument constructor already means.
     */
    @Bean
    public ProposalStore proposalStore(JdbcTemplate jdbc) {
        return new ProposalStore(jdbc);
    }

    /** The layer that holds the queue and the archive together; see its javadoc
     *  on why the claim is taken before the promotion, and why the two are not
     *  wrapped in one transaction. */
    /**
     * The conversations this server holds.
     *
     * <p>Wired here rather than annotated, matching {@link ProposalStore} and
     * {@link ReasonLog}: it takes a clock and an id factory that a component
     * scan has nothing to supply, and the production constructor is the one that
     * supplies both. {@code agents.Turn} is what reads it — a conversation's
     * budget is looked up when a person speaks and written back when the turn
     * ends — and until this bean existed the table had no production caller at
     * all.
     */
    @Bean
    public ConversationStore conversationStore(JdbcTemplate jdbc) {
        return new ConversationStore(jdbc);
    }

    /** Local hook sets by hash (spec 2026-09-30-local-hooks-are-served §3). */
    @Bean
    public LocalHookSetStore localHookSetStore(JdbcTemplate jdbc) {
        return new LocalHookSetStore(jdbc);
    }

    /**
     * What was said in each conversation.
     *
     * <p><b>The bean this store shipped without.</b> It was written read-only in
     * the commit that created the table, deliberately: there was no path from a
     * finished run to a prompt-token count, so nothing could have written a
     * complete row, and a bean with no caller is the same speculation as an
     * endpoint with none. {@code agents.Turn} writes one per turn now and {@code
     * agents.Compaction} reads them back as the history a turn opens with.
     */
    @Bean
    public TurnStore turnStore(JdbcTemplate jdbc) {
        return new TurnStore(jdbc);
    }

    /** Where a conversation's older turns were folded away, and how far back
     *  each fold reaches. Read on every turn of a conversation that has one,
     *  written by {@code agents.Compaction} when a measurement says the next
     *  turn would not fit. */
    @Bean
    public CompactionStore compactionStore(JdbcTemplate jdbc) {
        return new CompactionStore(jdbc);
    }

    /**
     * Every message each conversation has held.
     *
     * <p><b>Write-only for now, and deliberately.</b> {@code agents.JobRuntime}
     * records each message as it appends it and {@code agents.Compaction}
     * appends a summary when it folds, so the log accumulates alongside the path
     * that is already running — but nothing builds a request from it yet.
     * {@code agents.Projection} is the reader, and it is held to answering with
     * what {@code Compaction} builds for the same conversation before anything
     * is switched over to it.
     *
     * <p>That is the opposite of the shape {@link TurnStore} shipped in, which
     * was read-only with no writer, and it is the same argument from the other
     * side: a table with one direction wired is a table whose other direction
     * has not been shown to work. What makes it acceptable here is that the
     * proof is the reader's test rather than a caller.
     */
    @Bean
    public EntryStore entryStore(JdbcTemplate jdbc, UnitOfWork transactions,
            org.springframework.beans.factory.ObjectProvider<ConversationSearch> retrieval) {
        return new EntryStore(jdbc, transactions).retrieving(retrieval::getObject);
    }

    /**
     * Where an ejected payload is written, or {@link PayloadExport#NONE}.
     *
     * <p><b>Three answers, and the order they are asked in is the convention
     * being a convention.</b> An operator who named a directory gets that
     * directory; an operator who named none gets the data directory's own place
     * for them; a deployment with neither keeps nothing. The key is the
     * override, which is the whole of what "convention over configuration" means
     * here — before this it was the only way to say it, and its default was a
     * bare relative {@code exports} that landed wherever the server happened to
     * start.
     *
     * <p><b>A blank key is still a deployment that keeps no export</b> and is
     * still not an error — the operator saying they want the liability gone
     * rather than moved, which {@code result_read} reports to a model rather
     * than naming an empty place. It reads as blank <em>before</em> the data
     * directory is consulted, so saying "nowhere" is not overridden by a
     * convention the operator never asked for.
     *
     * <p><b>Both configurations produce the same shape of tree</b>, {@code
     * &lt;root&gt;/&lt;project-id&gt;/…}, which is {@code
     * DataLayout.exportsUnder}'s to explain: the alternative was "which project
     * is this export of" being answerable on one deployment and not on another.
     *
     * <p>Neither directory is created here. A server that never ejects anything
     * should not leave an empty {@code exports/} behind for somebody to wonder
     * about; {@code PayloadExport.write} creates what it needs when it needs it.
     * The <em>data</em> directory is a different case and <b>is</b> created at
     * boot, by {@code DataConfig}, because a marker written on first start is
     * the only thing that tells a later version what layout it is looking at.
     *
     * <p><b>The {@code JdbcTemplate} is here and not in {@code
     * PayloadExport}.</b> An export is keyed by project id and a conversation
     * carries a project <em>name</em>, so somebody has to translate; {@link
     * ExportDirectories} argues why the translator is a seam bound here rather
     * than a connection handed to the class that writes files.
     */
    @Bean
    public PayloadExport payloadExport(
            ConversationsProperties conversations, DataLayout data, JdbcTemplate jdbc) {
        String directory = conversations.getRetention().getExportDirectory();
        if (directory != null && !directory.isBlank()) {
            return new PayloadExport(ExportDirectories.into(Path.of(directory), jdbc));
        }
        if (directory != null) {
            // BLANK, WHICH IS NOT THE SAME AS ABSENT and is read before the data
            // directory is consulted: the operator said "nowhere", and a
            // convention they never asked for must not override it.
            return PayloadExport.NONE;
        }
        return data.keepsAnything()
                ? new PayloadExport(ExportDirectories.under(data, jdbc))
                : PayloadExport.NONE;
    }

    /**
     * The durable record of every run this server starts: {@code jobs}.
     *
     * <p>A bean because two very different things need it — {@code JobStore}
     * writes it at both ends of a run, and {@link Retention} prunes it — and a
     * second instance would be a second place for the id scheme to be answered.
     */
    @Bean
    public JobLog jobLog(JdbcTemplate jdbc) {
        return new JobLog(jdbc);
    }

    /**
     * The retention policy as the archive reads it: three ages, all absent
     * unless an operator set them.
     *
     * <p>The conversion from days lives here, in the one place that touches both
     * the property file's units and the domain's — {@link RetentionPolicy}
     * carries {@link java.time.Duration} because that is what an age is, and the
     * keys are in days because that is what an operator thinks in.
     *
     * <p><b>Two property classes and not one</b>, because the third age is not a
     * conversation's: {@link JobsProperties} argues why {@code plowshare.jobs} is
     * its own prefix rather than a third key under {@code
     * plowshare.conversations.retention}, and this bean is the one place the two
     * meet — which is exactly where a units conversion already lived.
     */
    @Bean
    public RetentionPolicy retentionPolicy(
            ConversationsProperties conversations, JobsProperties jobs) {
        ConversationsProperties.Retention keys = conversations.getRetention();
        return new RetentionPolicy(days(keys.getCuratorAfterDays()),
                days(keys.getSubmissionAfterDays()), days(jobs.getRetentionAfterDays()));
    }

    private static Duration days(Integer count) {
        return count == null ? null : Duration.ofDays(count);
    }

    /**
     * One retention sweep, triggered by {@code POST /v1/retention/sweep} and by
     * nothing else.
     *
     * <p><b>No {@code @Scheduled} anywhere near this bean</b>, and {@link
     * Retention} carries the four reasons at length. The short one is that this
     * tree stands up Spring contexts against real databases in dozens of tests,
     * so a timer started from a bean would be running retention against fixture
     * rows on a clock no test controls.
     */
    @Bean
    public Retention retention(
            ConversationStore conversations, EntryStore entries, JobLog jobs,
            PayloadExport exports, RetentionPolicy policy, org.springframework.beans.factory.ObjectProvider<DigestStore> digests) {
        return new Retention(conversations, entries, jobs, exports, policy, Instant::now).protectingDigests(id -> digests.getIfAvailable()==null || digests.getObject().covered(id));
    }

    @Bean
    public PromotionQueue promotionQueue(Archive archive, ProposalStore proposals) {
        return new PromotionQueue(archive, proposals);
    }

    /**
     * The real transaction, and the whole of the archive's atomicity.
     *
     * <p>Programmatic rather than {@code @Transactional}, because the boundary
     * has to be <em>narrower than a method</em>: it must close before {@link
     * Archive} calls the embedding model, and an annotation can only wrap a
     * whole call. {@link UnitOfWork} carries the account of what the annotation
     * version cost.
     *
     * <p>An anonymous class and not a lambda: {@code inTransaction} is a
     * generic method, and no lambda can implement one.
     *
     * <p>This bean is the single point of failure for atomicity, so it is also
     * the single point of mutation for testing it. Returning {@link
     * UnitOfWork#NONE} here is the exact equivalent of deleting the five
     * {@code @Transactional} annotations it replaced, and it is what {@code
     * TransactionBoundaryTest.a_supersession_that_fails_halfway_leaves_no_new_record}
     * catches. Nothing caught the annotations: removing all five left the suite
     * 170/170 green, because the only controller test builds no Spring context
     * and so applies no transaction proxy.
     *
     * <p><b>It is also the second place a dead database is translated, and the
     * first one a read actually reaches.</b> Measured on 2026-08-29 against a
     * datasource pointed at a closed port: with Postgres unreachable, {@code
     * Archive.recall} never gets as far as {@code MemoryStore} — {@code
     * TransactionTemplate.execute} asks for the connection first and raises
     * {@code CannotCreateTransactionException}, which is a {@code
     * TransactionException} and not a {@code DataAccessException}. Translating
     * in the two stores alone would therefore have left the most ordinary
     * unreachable path untranslated, and every archive read through a tool would
     * still have reached the model as a tool it might work around. Pinned by
     * {@code a_transaction_that_cannot_be_opened_is_an_unavailability}.
     */
    @Bean
    public UnitOfWork unitOfWork(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        return new UnitOfWork() {
            @Override
            public void afterCommit(Runnable action) {
                if (org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()) {
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(new org.springframework.transaction.support
                                    .TransactionSynchronization() {
                                @Override public void afterCommit() {
                                    // Cleanup can finish without holding a JDBC connection across
                                    // hooks or dispatch. The callback uses its own connections.
                                    Thread.startVirtualThread(action);
                                }
                            });
                } else {
                    action.run();
                }
            }

            @Override
            public <T> T inTransaction(Supplier<T> work) {
                // TransactionTemplate commits and then runs the cleanup that
                // unbinds and returns the JDBC connection, both before execute()
                // returns — so by the time Archive calls the model, the pool has
                // its connection back. An afterCommit synchronisation would have
                // run inside that window instead, with the connection still
                // held, and would have fixed the lost write while leaving the
                // pool exhaustion exactly as it was.
                return ArchiveUnavailableException.translating(
                        "open a transaction on the archive",
                        () -> template.execute(status -> work.get()));
            }
        };
    }
}
