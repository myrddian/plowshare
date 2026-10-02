package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountingJournalTest {
    @TempDir Path directory;

    private AccountingJournal journal() {
        return new AccountingJournal(directory, 1024 * 1024, 8192, Duration.ofMillis(100), AccountingFixtures.MAPPER);
    }

    @Test
    void another_process_cannot_acquire_the_writer_lock_until_this_writer_closes() throws Exception {
        Path probe = directory.resolveSibling(directory.getFileName() + "-LockProbe.java");
        try {
            Files.writeString(probe, """
                    import java.nio.channels.FileChannel;
                    import java.nio.file.*;
                    class LockProbe {
                        public static void main(String[] args) throws Exception {
                            try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                                 var lock = channel.tryLock()) {
                                System.out.println(lock == null ? "locked" : "available");
                            }
                        }
                    }
                    """);
            try (var journal = journal()) {
                assertEquals("locked", probeLock(probe));
                // A rejected external writer must not disturb the original writer.
                assertEquals(1, journal.append(AccountingFixtures.created()));
            }
            assertEquals("available", probeLock(probe));
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    private String probeLock(Path source) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                source.toString(), directory.resolve("journal.lock").toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "lock probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void an_uncertain_empty_rotation_header_is_reused_even_for_an_event_larger_than_the_target() throws Exception {
        var fail = new AtomicBoolean();
        try (var journal = new AccountingJournal(directory, 65536, 1024, Duration.ofSeconds(1),
                AccountingFixtures.MAPPER, channel -> {
                    if (fail.getAndSet(false)) { throw new IOException("injected rotation failure"); }
                    channel.force(true);
                })) {
            var original = AccountingFixtures.metadata();
            var metadata = new AccountingEvent.CallCreated(original.attribution(), "s".repeat(256), "p".repeat(256),
                    original.wireModel(), original.modelFamily(), original.billingRoute(), original.lane(), original.price());
            var first = AccountingFixtures.created();
            journal.append(new AccountingEvent(first.eventId(), first.instanceId(), first.callId(), 1, first.at(), metadata));
            assertTrue(Files.size(segments().getFirst()) > 1024, "fixture event must exceed the rotation target");
            fail.set(true); // The next rotate writes its header, then fails its force.
            var identity = AccountingFixtures.created();
            var next = new AccountingEvent(identity.eventId(), identity.instanceId(), identity.callId(), 1, identity.at(), metadata);
            assertThrows(AccountingUnavailableException.class, () -> journal.append(next));
            journal.recover();
            assertEquals(2, journal.append(next));
            assertEquals(next, journal.readBatch(10).getLast().event());
        }
    }

    @Test
    void records_and_identity_survive_restart_and_acknowledged_segments_are_reclaimed() {
        var event = AccountingFixtures.created();
        java.util.UUID identity;
        try (var journal = journal()) {
            identity = journal.journalId();
            assertEquals(1, journal.append(event));
            assertEquals(List.of(new AccountingJournal.Entry(1, event)), journal.readBatch(100));
        }
        try (var journal = journal()) {
            assertEquals(identity, journal.journalId());
            assertEquals(event, journal.readBatch(1).getFirst().event());
            assertEquals(1, journal.health().pendingEvents());
            assertEquals(AccountingFixtures.NOW, journal.health().oldestPendingAt());
            journal.acknowledge(1);
            assertEquals(0, journal.health().bytes());
            assertTrue(journal.readBatch(10).isEmpty());
        }
        try (var journal = journal()) {
            assertEquals(identity, journal.journalId());
            assertEquals(1, journal.health().acknowledgedSequence());
            assertEquals(2, journal.append(AccountingFixtures.created()));
        }
    }

    @Test
    void an_incomplete_final_payload_is_removed_without_losing_the_preceding_record() throws Exception {
        var first = AccountingFixtures.created();
        try (var journal = journal()) {
            journal.append(first);
            journal.append(AccountingFixtures.created());
        }
        Path file = segments().getLast();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.truncate(channel.size() - 5); }
        try (var journal = journal()) {
            assertEquals(List.of(new AccountingJournal.Entry(1, first)), journal.readBatch(10));
            assertEquals(2, journal.append(AccountingFixtures.created()));
        }
    }

    @Test
    void an_incomplete_final_frame_header_is_removed() throws Exception {
        var event = AccountingFixtures.created();
        try (var journal = journal()) { journal.append(event); }
        Files.write(segments().getLast(), new byte[] {1, 2, 3}, StandardOpenOption.APPEND);
        try (var journal = journal()) {
            assertEquals(List.of(new AccountingJournal.Entry(1, event)), journal.readBatch(10));
        }
    }

    @Test
    void complete_checksum_corruption_is_not_mistaken_for_a_torn_tail() throws Exception {
        try (var journal = journal()) { journal.append(AccountingFixtures.created()); }
        Path file = segments().getLast();
        long size = Files.size(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertEquals(AccountingJournal.Problem.CORRUPT,
                assertThrows(AccountingUnavailableException.class, this::journal).problem());
        assertEquals(size, Files.size(file));
    }

    @Test
    void a_torn_middle_segment_stops_replay_instead_of_skipping_metadata() throws Exception {
        try (var journal = new AccountingJournal(directory, 1024 * 1024, 1024,
                Duration.ofSeconds(1), AccountingFixtures.MAPPER)) {
            journal.append(AccountingFixtures.created());
            journal.append(AccountingFixtures.created());
        }
        assertEquals(2, segments().size());
        try (FileChannel channel = FileChannel.open(segments().getFirst(), StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 5);
        }
        assertEquals(AccountingJournal.Problem.CORRUPT,
                assertThrows(AccountingUnavailableException.class, this::journal).problem());
    }

    @Test
    void deleting_an_unacknowledged_segment_is_a_sequence_gap() throws Exception {
        try (var journal = new AccountingJournal(directory, 1024 * 1024, 1024,
                Duration.ofSeconds(1), AccountingFixtures.MAPPER)) {
            journal.append(AccountingFixtures.created());
            journal.append(AccountingFixtures.created());
        }
        Files.delete(segments().getFirst());
        assertEquals(AccountingJournal.Problem.CORRUPT,
                assertThrows(AccountingUnavailableException.class, this::journal).problem());
    }

    @Test
    void only_one_writer_can_own_a_journal_and_a_refused_writer_does_not_break_the_first() {
        try (var journal = journal()) {
            assertEquals(AccountingJournal.Problem.WRITER_LOCKED,
                    assertThrows(AccountingUnavailableException.class, this::journal).problem());
            assertEquals(1, journal.append(AccountingFixtures.created()));
        }
        try (var journal = journal()) { assertEquals(1, journal.health().pendingEvents()); }
    }

    @Test
    void fullness_refuses_before_writing_and_projection_can_release_capacity() {
        try (var journal = new AccountingJournal(directory, 8192, 2048, Duration.ofSeconds(1), AccountingFixtures.MAPPER)) {
            int appended = 0;
            for (; appended < 100; appended++) {
                try { journal.append(AccountingFixtures.created()); }
                catch (AccountingUnavailableException full) { assertEquals(AccountingJournal.Problem.FULL, full.problem()); break; }
            }
            assertTrue(appended > 0 && appended < 100);
            assertTrue(journal.health().bytes() <= 8192);
            long before = journal.health().bytes();
            assertThrows(AccountingUnavailableException.class, () -> journal.append(AccountingFixtures.created()));
            assertEquals(before, journal.health().bytes());
            journal.acknowledge(journal.health().writtenSequence());
            assertEquals(AccountingJournal.Problem.NONE, journal.health().problem());
            assertEquals(0, journal.health().bytes());
            assertEquals(appended + 1L, journal.append(AccountingFixtures.created()));
        }
    }

    @Test
    void an_uncertain_fsync_is_reconciled_before_accepting_more_metadata() {
        var fail = new AtomicBoolean();
        try (var journal = new AccountingJournal(directory, 1024 * 1024, 8192, Duration.ofSeconds(1),
                AccountingFixtures.MAPPER, channel -> {
                    if (fail.getAndSet(false)) { throw new IOException("injected fsync failure"); }
                    channel.force(true);
                })) {
            journal.append(AccountingFixtures.created());
            var uncertain = AccountingFixtures.created();
            fail.set(true);
            assertThrows(AccountingUnavailableException.class, () -> journal.append(uncertain));
            assertEquals(AccountingJournal.Problem.IO_ERROR, journal.health().problem());
            assertThrows(AccountingUnavailableException.class, () -> journal.append(AccountingFixtures.created()));
            journal.recover();
            assertEquals(uncertain, journal.readBatch(10).getLast().event());
            assertEquals(3, journal.append(AccountingFixtures.created()));
        }
    }

    @Test
    void failed_checkpoint_sync_does_not_delete_unacknowledged_frames() {
        var fail = new AtomicBoolean();
        try (var journal = new AccountingJournal(directory, 1024 * 1024, 8192, Duration.ofSeconds(1),
                AccountingFixtures.MAPPER, channel -> {
                    if (fail.getAndSet(false)) { throw new IOException("injected checkpoint failure"); }
                    channel.force(true);
                })) {
            journal.append(AccountingFixtures.created());
            fail.set(true);
            assertThrows(AccountingUnavailableException.class, () -> journal.acknowledge(1));
            assertEquals(0, journal.health().acknowledgedSequence());
        }
        try (var journal = journal()) {
            assertEquals(1, journal.health().pendingEvents());
            assertEquals(0, journal.health().acknowledgedSequence());
        }
    }

    @Test
    void lock_admission_has_a_timeout_and_health_reads_do_not_wait_for_fsync() throws Exception {
        var block = new AtomicBoolean();
        var syncing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var journal = new AccountingJournal(directory, 1024 * 1024, 8192, Duration.ofMillis(50),
                AccountingFixtures.MAPPER, channel -> {
                    if (block.get()) {
                        syncing.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("test sync bound"); }
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                    }
                    channel.force(true);
                })) {
            journal.append(AccountingFixtures.created());
            block.set(true);
            Thread writer = Thread.ofVirtual().start(() -> journal.append(AccountingFixtures.created()));
            try {
                assertTrue(syncing.await(2, TimeUnit.SECONDS));
                assertEquals(1, journal.health().pendingEvents());
                assertEquals(AccountingJournal.Problem.BUSY,
                        assertThrows(AccountingUnavailableException.class, () -> journal.append(AccountingFixtures.created())).problem());
            } finally { release.countDown(); writer.join(2000); }
            assertFalse(writer.isAlive());
            assertEquals(2, journal.health().pendingEvents());
        }
    }

    private List<Path> segments() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".seg")).sorted().toList();
        }
    }
}
