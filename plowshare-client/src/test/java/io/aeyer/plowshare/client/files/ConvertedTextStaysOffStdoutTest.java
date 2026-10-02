package io.aeyer.plowshare.client.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.protocol.Window;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * The containment test for the dependency this slice added.
 *
 * <h2>What is at stake</h2>
 *
 * <p>stdout is the MCP protocol channel. {@code LoggingConfigTest} holds that
 * down for everything that logs through slf4j, and until now that was
 * everything. <b>PDFBox does not.</b> It logs through commons-logging, which has
 * no configuration of its own: it <em>discovers</em> a backend at runtime, and
 * whatever it finds decides where the line goes. A document library is also the
 * chattiest thing in this process — a font it cannot map, a stream a byte short,
 * both at WARN, on reads that otherwise succeed.
 *
 * <p>So the build excludes the real commons-logging and substitutes {@code
 * jcl-over-slf4j}, and this file is what makes that a fact rather than a comment
 * in a build file.
 *
 * <h2>Three assertions, and each covers the others' blind spot</h2>
 *
 * <ul>
 *   <li>the substitution happened — otherwise the rest passes for the wrong
 *       reason, since commons-logging's own second choice is {@code
 *       java.util.logging}, whose default handler already writes to stderr;
 *   <li>a warning from a real read of a real document arrives in logback —
 *       otherwise "nothing on stdout" is true of a run in which nothing logged
 *       at all, which is the vacuous version of this test;
 *   <li>and stdout stayed empty while it did.
 * </ul>
 */
class ConvertedTextStaysOffStdoutTest {

    @TempDir
    Path tmp;

    @Test
    void commons_logging_resolves_to_the_bridge_and_not_to_a_backend_of_its_own() {
        String resolved = LogFactory.getLog("io.aeyer.probe").getClass().getName();

        assertTrue(resolved.startsWith("org.apache.commons.logging.impl.SLF4J"),
                "commons-logging resolved to " + resolved + ", which is not the slf4j"
                        + " bridge — PDFBox's warnings are then going somewhere logback.xml"
                        + " does not decide, and stdout is one of the places that could be");
    }

    @Test
    void a_noisy_document_logs_through_logback_and_writes_nothing_to_stdout() throws Exception {
        Path pdf = tmp.resolve("noisy.pdf");
        Files.write(pdf, Pdfs.chatty("a document the parser complains about"));
        Workspace workspace = new Workspace();
        workspace.set(List.of(tmp));
        ClientEnforcer enforcer = new ClientEnforcer(workspace);

        // Attached to the library's own logger rather than to root, so that
        // LoggingConfigTest -- which fails on any non-console appender it finds
        // on root -- cannot be affected by this one even if it were to run while
        // this appender is up.
        LoggerContext context = assertInstanceOf(LoggerContext.class,
                LoggerFactory.getILoggerFactory(),
                "logback should be the slf4j provider on this classpath");
        ch.qos.logback.classic.Logger library = context.getLogger("org.apache.pdfbox");
        ListAppender<ILoggingEvent> heard = new ListAppender<>();
        heard.setContext(context);
        heard.start();
        library.addAppender(heard);

        PrintStream protocolChannel = System.out;
        ByteArrayOutputStream onStdout = new ByteArrayOutputStream();
        String lines;
        try {
            System.setOut(new PrintStream(onStdout, true, StandardCharsets.UTF_8));
            lines = String.join("\n", enforcer
                    .answer(FileRequest.read("r1", pdf.toString(), Window.of(0, 100)))
                    .span().lines());
        } finally {
            System.setOut(protocolChannel);
            library.detachAppender(heard);
            heard.stop();
        }

        assertEquals("a document the parser complains about", lines,
                "the read has to have SUCCEEDED, or this is a test about a refusal");
        assertFalse(heard.list.isEmpty(),
                "this fixture is supposed to make the parser complain; if it has stopped"
                        + " doing so, the rest of this test proves nothing and the fixture"
                        + " needs fixing rather than the assertion relaxing");
        assertEquals(Level.WARN, heard.list.get(0).getLevel());
        assertEquals("", onStdout.toString(StandardCharsets.UTF_8),
                "one non-JSON-RPC line on stdout desynchronises the harness for the whole"
                        + " session, and it presents as a hung tool call rather than as a"
                        + " logging problem");
    }
}
