package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TickerStaysOffInTestsTest {

    @Test
    void the_test_classpath_turns_the_ticker_off() throws Exception {
        Properties test = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
            test.load(in);
        }
        assertEquals("false", test.getProperty("plowshare.events.ticker-enabled"));
    }

    @Test
    void the_test_classpath_turns_boot_recovery_off() throws Exception {
        Properties test = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
            test.load(in);
        }
        assertEquals("false", test.getProperty("plowshare.events.recover-at-boot"));
    }
}
