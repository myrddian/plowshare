package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.LlmProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

class AccountingConfigTest {
    @TempDir Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AccountingConfig.class, Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmProperties.class)
    static class Binding { }

    private ApplicationContextRunner storage(DataLayout layout) {
        DataSource offline = mock(DataSource.class);
        return runner.withBean(DataLayout.class, () -> layout)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(offline))
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(offline));
    }

    @Test
    void storage_is_disabled_by_default_and_needs_no_database_or_data_directory() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.getBean(LlmProperties.class).getAccounting().isEnabled());
            assertEquals(0, context.getBeansOfType(AccountingJournal.class).size());
            assertEquals(0, context.getBeansOfType(AccountingProjector.class).size());
        });
    }

    @Test
    void enabled_storage_binds_limits_and_closes_the_writer_with_the_context() {
        var layout = new DataLayout(directory).initialise();
        storage(layout).withPropertyValues("plowshare.llm.accounting.enabled=true",
                "plowshare.llm.accounting.max-journal-bytes=32KB",
                "plowshare.llm.accounting.segment-bytes=2KB",
                "plowshare.llm.accounting.projection-interval=20ms",
                "plowshare.llm.accounting.retry-buffer-events=3").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(32 * 1024, context.getBean(AccountingJournal.class).health().maxBytes());
                    assertEquals(3, context.getBean(LlmProperties.class).getAccounting().getRetryBufferEvents());
                    assertNotNull(context.getBean(AccountingRecorder.class));
                    assertNotNull(context.getBean(AccountingProjector.class));
                    assertTrue(Files.exists(layout.accounting().resolve("checkpoint")));
                });
        try (var reopened = new AccountingJournal(layout.accounting(), 32768, 2048,
                java.time.Duration.ofSeconds(1), AccountingFixtures.MAPPER)) {
            assertEquals(AccountingJournal.Problem.NONE, reopened.health().problem());
        }
    }

    @Test
    void enabled_storage_refuses_an_ephemeral_layout() {
        storage(DataLayout.NONE).withPropertyValues("plowshare.llm.accounting.enabled=true").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(trail(context.getStartupFailure()).contains("requires plowshare.data.dir"));
        });
    }

    @Test
    void a_submillisecond_projection_interval_fails_before_creating_journal_files() {
        storage(new DataLayout(directory).initialise()).withPropertyValues("plowshare.llm.accounting.enabled=true",
                "plowshare.llm.accounting.projection-interval=PT0.0005S").run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(trail(context.getStartupFailure()).contains("accounting"));
                    assertFalse(Files.exists(directory.resolve("accounting")));
                });
    }

    private static String trail(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) { result.append(cause.getMessage()); }
        return result.toString();
    }
}
