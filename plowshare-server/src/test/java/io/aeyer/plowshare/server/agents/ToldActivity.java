package io.aeyer.plowshare.server.agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** A {@link RunActivity} that writes down what it is told, one line each, for a test to read. */
final class ToldActivity implements RunActivity {

    private final List<String> told = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Call called(String conversation, String agent, String tool,
            Supplier<String> salient) {
        told.add("called " + conversation + " " + agent + " " + tool + " [" + salient.get()
                + "]");
        return new Call() {
            @Override
            public void returned(String outcome) {
                told.add("returned " + tool + " " + outcome);
            }

            @Override
            public void returned(String outcome, String output) {
                told.add("returned " + tool + " " + outcome
                        + (output == null ? "" : " [" + output + "]"));
            }
        };
    }

    @Override
    public void callFailure(String conversation, String agent, String tool, int warningsLeft) {
        told.add("call failure " + agent + " " + tool + " " + warningsLeft);
    }

    @Override
    public void callFailureEnded(String conversation, String agent, String tool, Unwarned why) {
        told.add("call failure ended " + agent + " " + tool + " " + why);
    }

    @Override
    public void delegated(String conversation, String agent, String callee, String task,
            String calleeConversation) {
        told.add("delegated " + agent + " " + callee + " " + task);
    }

    @Override
    public void delegateReturned(String conversation, String agent, String callee,
            Outcome outcome) {
        told.add("delegate returned " + agent + " " + callee + " " + outcome.ending() + " "
                + outcome.text());
    }

    List<String> told() {
        synchronized (told) {
            return List.copyOf(told);
        }
    }
}
