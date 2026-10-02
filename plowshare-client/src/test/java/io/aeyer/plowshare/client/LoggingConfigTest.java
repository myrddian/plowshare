package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A containment test for the one mistake that breaks an MCP server outright.
 *
 * <p>stdout is the protocol channel. A single log line on it is a non-JSON-RPC
 * line in the middle of the stream, and every client that reads line by line is
 * desynchronised for the rest of the session — presenting as a harness that
 * hangs, or that blames the client, never as a logging problem. Logback's
 * default {@code ConsoleAppender} target is {@code System.out}, so the safe
 * configuration is the one that has to be written down and then held in place.
 *
 * <p>Asserts on the resolved appenders rather than on the XML text: a
 * {@code <target>} logback rejected, or an appender added later without one,
 * would still read correctly in the file.
 */
class LoggingConfigTest {

    @Test
    void every_configured_appender_writes_to_stderr() {
        List<ConsoleAppender<?>> consoles = rootConsoleAppenders();

        assertFalse(consoles.isEmpty(), "logback.xml should configure at least one appender");
        for (ConsoleAppender<?> console : consoles) {
            assertEquals("System.err", console.getTarget(),
                    "appender " + console.getName() + " writes to the protocol channel");
        }
    }

    private static List<ConsoleAppender<?>> rootConsoleAppenders() {
        // A failure here means the module picked up some other slf4j provider
        // and logback.xml is no longer what decides where logs go — which is
        // itself the failure this test exists to catch.
        LoggerContext context = assertInstanceOf(LoggerContext.class, LoggerFactory.getILoggerFactory(),
                "logback should be the slf4j provider on this classpath");
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        var consoles = new ArrayList<ConsoleAppender<?>>();
        for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> appender = it.next();
            if (appender instanceof ConsoleAppender<?> console) {
                consoles.add(console);
            } else {
                // Not a console appender at all — a file or socket appender is
                // harmless, but this test cannot tell, so it says so out loud
                // rather than passing by omission.
                fail("appender " + appender.getName() + " is a " + appender.getClass().getName()
                        + "; confirm it cannot reach stdout, then teach this test about it");
            }
        }
        return consoles;
    }
}
