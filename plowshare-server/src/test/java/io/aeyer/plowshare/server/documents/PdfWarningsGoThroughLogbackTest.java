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

/** PDFBox must resolve through the server's single spring-jcl provider to slf4j/logback. */
class PdfWarningsGoThroughLogbackTest {

  /**
   * A class that exists only in the real {@code commons-logging} jar.
   *
   * <p>{@code spring-jcl} provides the same package and the same {@code LogFactory} entry point,
   * but none of {@code org.apache.commons.logging .impl}. So this name resolving is the exclusion
   * having failed, and it is a more direct question than asking what {@code LogFactory} happened to
   * return.
   */
  private static final String THE_REAL_JARS_OWN_FACTORY =
      "org.apache.commons.logging.impl.LogFactoryImpl";

  @Test
  void the_only_commons_logging_on_this_class_path_is_the_bridge_onto_slf4j() {
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName(THE_REAL_JARS_OWN_FACTORY),
        "the real commons-logging is on this class path beside spring-jcl, so which of"
            + " the two owns org.apache.commons.logging is decided by class path"
            + " order rather than by anything written down");

    String resolved = LogFactory.getLog("io.aeyer.probe").getClass().getName();
    assertTrue(
        resolved.toLowerCase(Locale.ROOT).contains("slf4j"),
        "commons-logging resolved to "
            + resolved
            + ", which does not route to slf4j —"
            + " PDFBox's warnings are then going somewhere this server's logging"
            + " configuration does not decide");
  }

  @Test
  void a_noisy_document_logs_through_logback_and_still_extracts() {
    LoggerContext context =
        assertInstanceOf(
            LoggerContext.class,
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
      extracted =
          TextExtraction.extract("noisy.pdf", Pdfs.chatty("a document the parser complains about"));
    } finally {
      library.detachAppender(heard);
      heard.stop();
    }

    assertEquals(
        "a document the parser complains about",
        extracted.text().strip(),
        "the read has to have SUCCEEDED, or this is a test about a refusal");
    assertFalse(
        heard.list.isEmpty(),
        "this fixture is supposed to make the parser complain; if it has stopped doing"
            + " so, the rest of this test proves nothing and the fixture needs"
            + " fixing rather than the assertion relaxing");
    assertEquals(Level.WARN, heard.list.get(0).getLevel());
  }
}
