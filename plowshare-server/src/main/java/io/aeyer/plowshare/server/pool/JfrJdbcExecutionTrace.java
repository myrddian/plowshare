package io.aeyer.plowshare.server.pool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Opt-in JFR events with exact JDBC invocation counts/durations and repository stack attribution.
 */
public final class JfrJdbcExecutionTrace implements JdbcExecutionTrace {
  @Name("plowshare.JdbcExecute")
  @Label("Plowshare JDBC execute")
  @Category("Plowshare")
  @StackTrace(true)
  @Enabled(false)
  static final class Execute extends Event implements Span {
    @Label("SQL SHA-256 (no text or binds)")
    public String fingerprint;

    public String method;
    public boolean failed;

    public void failed() {
      failed = true;
    }

    public void close() {
      end();
      if (shouldCommit()) commit();
    }
  }

  private static final EventType TYPE = EventType.getEventType(Execute.class);
  private static final Span DISABLED =
      new Span() {
        public void failed() {}

        public void close() {}
      };

  public Span begin(String sql, String method) {
    if (!TYPE.isEnabled()) return DISABLED;
    var event = new Execute();
    try {
      // Only a one-way fingerprint reaches the recording. No exception messages/SQLState causes,
      // bind values, account/resource identifiers or connection origins become event fields.
      event.fingerprint =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(sql.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("Required SHA-256 implementation unavailable", missing);
    }
    event.method = method;
    event.begin();
    return event;
  }
}
