package io.aeyer.plowshare.server.hooks.script;

import java.util.Optional;

/** A hook that could not load or could not decide, in a sentence a person reads in the log. */
public final class HookFailure extends Exception {
  private final transient ScriptLocation location;

  public HookFailure(String sentence) {
    this(sentence, null);
  }

  public HookFailure(String sentence, Throwable cause) {
    this(sentence, cause, null);
  }

  HookFailure(String sentence, Throwable cause, ScriptLocation location) {
    super(sentence, cause);
    this.location = location;
  }

  Optional<ScriptLocation> location() {
    return Optional.ofNullable(location);
  }
}
