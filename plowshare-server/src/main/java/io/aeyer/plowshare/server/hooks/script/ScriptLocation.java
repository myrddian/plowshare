package io.aeyer.plowshare.server.hooks.script;

import java.util.Optional;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;

/** A one-based position in a script. Host adapters and dynamically evaluated text are excluded. */
public record ScriptLocation(int line, int column) {

  /** The throw site, or the first frame in the admitted script when a host helper throws. */
  public static Optional<ScriptLocation> in(PolyglotException failure, Source source) {
    SourceSection location = failure.getSourceLocation();
    if (belongsTo(location, source)) return Optional.of(position(location));
    for (var frame : failure.getPolyglotStackTrace()) {
      if (frame.isGuestFrame() && belongsTo(frame.getSourceLocation(), source))
        return Optional.of(position(frame.getSourceLocation()));
    }
    return Optional.empty();
  }

  private static boolean belongsTo(SourceSection location, Source source) {
    return location != null
        && location.hasLines()
        && location.hasColumns()
        && source.equals(location.getSource());
  }

  private static ScriptLocation position(SourceSection location) {
    return new ScriptLocation(location.getStartLine(), location.getStartColumn());
  }

  public String at(String file) {
    return file + ":" + line + ":" + column;
  }
}
