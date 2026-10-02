package io.aeyer.plowshare.server.hooks.script;

/** A hook that could not load or could not decide, in a sentence a person reads in the log. */
public final class HookFailure extends Exception {

    public HookFailure(String sentence) {
        super(sentence);
    }

    public HookFailure(String sentence, Throwable cause) {
        super(sentence, cause);
    }
}
