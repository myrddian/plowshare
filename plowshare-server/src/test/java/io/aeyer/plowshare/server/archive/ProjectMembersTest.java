package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ProjectMembersTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    private static JdbcTemplate jdbc;
    private ProjectMembers members;
    @TempDir Path tmp;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE projects, admins CASCADE");
        jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('alice', 'hash'), ('bob', 'hash')");
        ProjectIds.toWrite(jdbc, Home.of("ledger"));
        members = new ProjectMembers(jdbc);
    }

    @Test void first_user_claims_and_other_accounts_are_refused() {
        assertFalse(members.mayUse("ledger", null));
        assertTrue(members.members("ledger").isEmpty());
        assertTrue(members.mayUse("ledger", "alice"));
        assertTrue(members.mayUse("ledger", "alice"));
        assertFalse(members.mayUse("ledger", "bob"));
        assertFalse(members.isMember("ledger", null));
        assertEquals(List.of("alice"), members.members("ledger"));
        members.add("ledger", "bob");
        members.add("ledger", "bob");
        assertEquals(List.of("alice", "bob"), members.members("ledger"));
        members.remove("ledger", "alice");
        assertFalse(members.mayUse("ledger", "alice"));
        members.remove("ledger", "bob");
        assertTrue(members.mayUse("ledger", "alice"));
        assertThrows(ArchiveRefusedException.class, () -> members.add("ledger", "unknown"));
    }

    @Test void concurrent_first_claims_leave_exactly_one_member() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var alice = pool.submit(() -> { start.await(); return members.mayUse("ledger", "alice"); });
            var bob = pool.submit(() -> { start.await(); return members.mayUse("ledger", "bob"); });
            start.countDown();
            assertNotEquals(alice.get(10, TimeUnit.SECONDS), bob.get(10, TimeUnit.SECONDS));
            assertEquals(1, members.members("ledger").size());
        }
    }

    @Test void creators_are_members_and_failed_writes_roll_back_membership_and_project() {
        ProjectStore projects = new ProjectStore(jdbc, tmp.resolve("config"), tmp.resolve("sampling"), null, null, null);
        projects.define("created", tmp, List.of(), "alice");
        projects.rootOn("remote", "laptop", "/repo", "bob");
        assertEquals(List.of("alice"), members.members("created"));
        assertEquals(List.of("bob"), members.members("remote"));
        assertThrows(RuntimeException.class, () -> projects.rootOn("bad", "laptop", "/repo", "unknown"));
        assertNull(projects.id("bad"));
        assertThrows(ValidationException.class, () -> projects.define("invalid", tmp.resolve("missing"), List.of(), "alice"));
        assertNull(projects.id("invalid"));
    }

    @Test void a_file_channel_claims_an_unowned_project_for_its_authenticated_account() throws Exception {
        var sessions = new io.aeyer.plowshare.server.session.SessionRegistry();
        var presences = new io.aeyer.plowshare.server.session.PresenceRegistry();
        ProjectStore projects = new ProjectStore(jdbc, tmp.resolve("config"), tmp.resolve("sampling"), null, null, null);
        var channel = new io.aeyer.plowshare.server.ws.FileChannelHandler(sessions, presences,
                projects::rootOn, members);
        var socket = org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(socket.getUri()).thenReturn(java.net.URI.create(
                "ws://localhost/v1/files?session=desk&project=ledger&machine=laptop&root=/repo"));
        var attributes = new java.util.HashMap<String, Object>();
        attributes.put(io.aeyer.plowshare.server.ws.EventChannelHandler.HANDLE, "alice");
        org.mockito.Mockito.when(socket.getAttributes()).thenReturn(attributes);
        channel.afterConnectionEstablished(socket);
        assertEquals(List.of("alice"), members.members("ledger"));
        assertEquals("desk", presences.serving("ledger").orElseThrow().session());
        assertEquals("laptop", projects.rootedElsewhere("ledger").orElseThrow());
        assertFalse(members.mayUse("ledger", "bob"));
    }

    @Test void migration_backfills_every_existing_project() {
        jdbc.execute("CREATE SCHEMA backfill");
        var before = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("backfill").defaultSchema("backfill").target("76").load();
        before.migrate();
        jdbc.update("INSERT INTO backfill.admins (handle, password_hash) VALUES ('alice', 'hash')");
        jdbc.update("INSERT INTO backfill.projects (name) VALUES ('one'), ('two')");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("backfill").defaultSchema("backfill").load().migrate();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM backfill.project_members WHERE handle = 'alice'", Integer.class));
    }
}
