package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.config.Live;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The two live keys, read at the moment they are asked rather than the
 * moment this server booted, and the two bound-once keys, which must stay
 * exactly that even when the map holds something else for their name.
 *
 * <p>Follows {@code SearchPropertiesTest}: Testcontainers and a real
 * Postgres, per class rather than a shared base — {@link RuntimeConfig} is
 * Postgres-backed, so a stand-in map would only be proving that this class
 * reads a map the test itself decided how to implement.
 *
 * <p>Every liveness assertion below writes a value that differs from the
 * bound default before reading the live accessor — a test that wrote back
 * the same value the field already holds would pass whether the accessor
 * consulted {@link RuntimeConfig} at all or simply returned its field, which
 * is exactly the defect {@code Live}'s own javadoc warns a reader against.
 */
@Testcontainers
class FetchPropertiesTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private RuntimeConfig runtimeConfig;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void freshMap() {
        jdbc.execute("TRUNCATE TABLE runtime_config");
        runtimeConfig = new RuntimeConfig(jdbc);
    }

    @Test
    void the_ttl_is_read_from_the_map_at_the_moment_it_is_asked() {
        FetchProperties props = new FetchProperties(runtimeConfig);
        props.setTtl(Duration.ofHours(24));
        assertEquals(Duration.ofHours(24), props.ttlNow());
        runtimeConfig.put("plowshare.fetch.ttl", "PT1H", "test");
        assertEquals(Duration.ofHours(1), props.ttlNow());
    }

    @Test
    void the_live_window_is_live_too() {
        FetchProperties props = new FetchProperties(runtimeConfig);
        props.setLiveWindow(Duration.ofMinutes(10));
        runtimeConfig.put("plowshare.fetch.live-window", "PT2M", "test");
        assertEquals(Duration.ofMinutes(2), props.liveWindowNow());
    }

    @Test
    void a_ttl_that_will_not_parse_falls_back_to_the_bound_value() {
        FetchProperties props = new FetchProperties(runtimeConfig);
        props.setTtl(Duration.ofHours(24));
        runtimeConfig.put("plowshare.fetch.ttl", "not a duration", "test");
        assertEquals(Duration.ofHours(24), props.ttlNow());
    }

    @Test
    void the_window_and_the_timeout_are_bound_once_and_are_not_live_keys() {
        FetchProperties props = new FetchProperties(runtimeConfig);
        props.setWindow(8000);
        props.setTimeout(Duration.ofSeconds(30));
        runtimeConfig.put("plowshare.fetch.window", "1", "test");
        runtimeConfig.put("plowshare.fetch.timeout", "PT1S", "test");
        assertEquals(8000, props.getWindow(),
                "the window is the shape of what a model is handed, not an ops dial");
        assertEquals(Duration.ofSeconds(30), props.getTimeout());
    }

    @Test
    void allow_private_is_bound_once_and_is_not_a_live_key() {
        assertEquals(List.of(), new FetchProperties().getAllowPrivate(),
                "no entries is the default: fetch reaches nothing private");

        FetchProperties props = new FetchProperties(runtimeConfig);
        props.setAllowPrivate(List.of("docs.internal:8080"));
        runtimeConfig.put("plowshare.fetch.allow-private", "evil.example:80", "test");
        assertEquals(List.of("docs.internal:8080"), props.getAllowPrivate(),
                "a row in the runtime map must not widen what fetch may reach");

        for (Method method : FetchProperties.class.getMethods()) {
            Live live = method.getAnnotation(Live.class);
            assertTrue(live == null || !live.value().equals("plowshare.fetch.allow-private"),
                    method + " makes the allowlist live; spec §2.5 binds it at boot only");
        }
    }
}
