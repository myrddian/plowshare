package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Locale;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The containment test for the dependency this slice added.
 *
 * <h2>What is at stake, and it is not the same stake as on the client</h2>
 *
 * <p>PDFBox logs through {@code commons-logging}, and it is the chattiest thing
 * in this process: a font it cannot map or a stream that ends a byte early is a
 * WARN per occurrence, on reads that otherwise succeed. {@code
 * commons-logging} has no configuration of its own — it <em>discovers</em> a
 * backend at runtime and whatever it finds decides where the line goes.
 *
 * <p>{@code plowshare-client}'s {@code ConvertedTextStaysOffStdoutTest} guards
 * the same library against a harder consequence: there, stdout is the MCP
 * protocol channel and a stray line desynchronises the session. <b>Here the
 * consequence is quieter and the mechanism is different.</b> This server already
 * has a jar providing {@code org.apache.commons.logging} — {@code spring-jcl},
 * which {@code spring-core} depends on, and which is a bridge onto slf4j and
 * therefore onto logback. Adding PDFBox's own {@code commons-logging} would put
 * <b>two jars on one package</b>, resolved by class path order, which no file in
 * this repository states. On the day the real jar won, PDFBox's warnings would
 * stop obeying any level or appender this server sets and would land in {@code
 * java.util.logging} instead — losing them from every log the operator reads,
 * without one line of this repository changing.
 *
 * <p>So the build excludes it and leaves {@code spring-jcl} as the only
 * provider, and this file is what makes that a fact rather than a comment in a
 * build file.
 *
 * <h2>Three assertions, and each covers the others' blind spot</h2>
 *
 * <ul>
 *   <li>the real jar is gone — otherwise the rest can pass for the wrong reason,
 *       since a class path where both are present resolves to one of them and
 *       the test would simply be recording today's ordering;
 *   <li>whatever <em>does</em> provide the package routes to slf4j;
 *   <li>a warning from a real read of a real document arrives in logback —
 *       otherwise the two above are facts about class names and not about where
 *       a log line goes;
 *   <li>and the read that produced it succeeded, because a warning on a
 *       <em>failed</em> read is one somebody would notice anyway.
 * </ul>
 *
 * <h2>What this test class path has that the server's does not</h2>
 *
 * <p><b>Measured, because the obvious assertion was wrong.</b> {@code
 * LogFactory} here resolves to {@code
 * org.apache.commons.logging.impl.SLF4JLocationAwareLog}, which is <em>
 * jcl-over-slf4j</em> and not spring-jcl — it arrives from {@code
 * testImplementation(project(":plowshare-client"))}, because the client declares
 * that bridge {@code runtimeOnly} for its own stdout rule. So the test class
 * path has two providers of this package where the server's has one, which is
 * why the assertion below is about <em>routing to slf4j</em> rather than about
 * which of the two answered. The production shape is measured in the build file
 * from the resolved {@code runtimeClasspath}: spring-jcl, and nothing else.
 *
 * <p>That difference cannot hide a failure, and it is worth saying why: the
 * thing being guarded against is the <b>real</b> commons-logging appearing and
 * discovering a backend of its own, and the first assertion sees that jar
 * whichever class path it is on.
 */
class PdfWarningsGoThroughLogbackTest {

    /**
     * A class that exists only in the real {@code commons-logging} jar.
     *
     * <p>{@code spring-jcl} provides the same package and the same {@code
     * LogFactory} entry point, but none of {@code org.apache.commons.logging
     * .impl}. So this name resolving is the exclusion having failed, and it is a
     * more direct question than asking what {@code LogFactory} happened to
     * return.
     */
    private static final String THE_REAL_JARS_OWN_FACTORY =
            "org.apache.commons.logging.impl.LogFactoryImpl";

    @Test
    void the_only_commons_logging_on_this_class_path_is_the_bridge_onto_slf4j() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(THE_REAL_JARS_OWN_FACTORY),
                "the real commons-logging is on this class path beside spring-jcl, so which of"
                        + " the two owns org.apache.commons.logging is decided by class path"
                        + " order rather than by anything written down");

        String resolved = LogFactory.getLog("io.aeyer.probe").getClass().getName();
        assertTrue(resolved.toLowerCase(Locale.ROOT).contains("slf4j"),
                "commons-logging resolved to " + resolved + ", which does not route to slf4j —"
                        + " PDFBox's warnings are then going somewhere this server's logging"
                        + " configuration does not decide");
    }

    @Test
    void a_noisy_document_logs_through_logback_and_still_extracts() {
        LoggerContext context = assertInstanceOf(LoggerContext.class,
                LoggerFactory.getILoggerFactory(),
                "logback should be the slf4j provider on this class path");
        // Attached to the library's own logger rather than to root, so nothing
        // that asserts over root's appenders can be affected by this one.
        ch.qos.logback.classic.Logger library = context.getLogger("org.apache.pdfbox");
        ListAppender<ILoggingEvent> heard = new ListAppender<>();
        heard.setContext(context);
        heard.start();
        library.addAppender(heard);

        Extracted extracted;
        try {
            extracted = TextExtraction.extract("noisy.pdf",
                    Pdfs.chatty("a document the parser complains about"));
        } finally {
            library.detachAppender(heard);
            heard.stop();
        }

        assertEquals("a document the parser complains about", extracted.text().strip(),
                "the read has to have SUCCEEDED, or this is a test about a refusal");
        assertFalse(heard.list.isEmpty(),
                "this fixture is supposed to make the parser complain; if it has stopped doing"
                        + " so, the rest of this test proves nothing and the fixture needs"
                        + " fixing rather than the assertion relaxing");
        assertEquals(Level.WARN, heard.list.get(0).getLevel());
    }
}
