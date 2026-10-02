package io.aeyer.plowshare.server.agents;

import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A {@link RunActivity} whose every telling is caught: a listener that throws costs the turn
 *  loop nothing but a log line. What {@link JobRuntime#useActivity} holds. */
final class GuardedActivity implements RunActivity {

    private static final Logger log = LoggerFactory.getLogger(GuardedActivity.class);

    private final RunActivity told;

    GuardedActivity(RunActivity told) {
        this.told = Objects.requireNonNull(told, "told");
    }

    @Override
    public Call called(String conversation, String agent, String tool,
            Supplier<String> salient) {
        try {
            Call call = told.called(conversation, agent, tool, salient);
            if (call == null) {
                return Call.NONE;
            }
            return new Call() {
                @Override
                public void returned(String outcome) {
                    returned(outcome, null);
                }

                @Override
                public void returned(String outcome, String output) {
                    try {
                        call.returned(outcome, output);
                    } catch (RuntimeException failed) {
                        quiet("the outcome of a call to " + tool, failed);
                    }
                }
            };
        } catch (RuntimeException failed) {
            quiet("a call to " + tool, failed);
            return Call.NONE;
        }
    }

    @Override
    public void callFailure(String conversation, String agent, String tool, int warningsLeft) {
        try {
            told.callFailure(conversation, agent, tool, warningsLeft);
        } catch (RuntimeException failed) {
            quiet("a call failure", failed);
        }
    }

    @Override
    public void callFailureEnded(String conversation, String agent, String tool, Unwarned why) {
        try {
            told.callFailureEnded(conversation, agent, tool, why);
        } catch (RuntimeException failed) {
            quiet("a call failure that ended a turn", failed);
        }
    }

    @Override
    public void delegated(String conversation, String agent, String callee, String task,
            String calleeConversation) {
        try {
            told.delegated(conversation, agent, callee, task, calleeConversation);
        } catch (RuntimeException failed) {
            quiet("a delegation", failed);
        }
    }

    @Override
    public void delegateReturned(String conversation, String agent, String callee,
            Outcome outcome) {
        try {
            told.delegateReturned(conversation, agent, callee, outcome);
        } catch (RuntimeException failed) {
            quiet("a delegation's return", failed);
        }
    }

    @Override
    public String delegationFacts(String conversation, String callee) {
        try {
            return told.delegationFacts(conversation, callee);
        } catch (RuntimeException failed) {
            quiet("a delegation's facts", failed);
            return null;
        }
    }

    private static void quiet(String what, RuntimeException failed) {
        log.warn("telling {} failed; the run goes on", what, failed);
    }
}
