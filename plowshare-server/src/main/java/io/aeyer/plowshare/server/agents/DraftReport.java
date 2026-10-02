package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.files.Grant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code orchestration_validate} answers about a draft — spec
 * 2026-09-29-orchestration-studio §3.3: the loader's refusals in the project the draft would live
 * in, a deterministic summary of what it is and what it grants, and five lints.
 *
 * <p><b>The lints are few because they are certain.</b> Each names a fact read off the definition
 * — a stage nothing checks, a command nothing runs, a callee nothing names — and never a guess at
 * whether the prompt is good. A lint is a warning: the person may accept it.
 */
public record DraftReport(List<String> refusals, String summary, List<String> lints,
        List<String> beyondCaller) {

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    public DraftReport {
        refusals = List.copyOf(refusals);
        lints = List.copyOf(lints);
        beyondCaller = List.copyOf(beyondCaller);
        Objects.requireNonNull(summary, "summary");
    }

    /** Whether nothing refuses it. Lints do not. */
    public boolean installable() {
        return refusals.isEmpty();
    }

    /**
     * @param caller what the agent that started this run holds, or null when it cannot be read —
     *     then every grant is beyond it
     * @param attended whether a person started the run's tree
     */
    public static DraftReport of(OrchestrationResolver.Trial trial, AgentDefinition caller,
            boolean attended) {
        List<String> refusals = new ArrayList<>();
        if (trial.refusal() != null) {
            refusals.add(trial.refusal());
        } else if (trial.draft() == null) {
            // NEVER INSTALLABLE UNREAD: a trial that names no refusal but has no draft is refused.
            refusals.add("the draft did not load");
        }
        trial.newlyRefused().forEach((name, why) ->
                refusals.add(name + " would no longer load: " + why));
        OrchestrationDefinition draft = trial.draft();
        if (draft == null) {
            return new DraftReport(refusals, "", List.of(), List.of());
        }
        AgentDefinition conductor = draft.conductor();
        List<String> beyond = beyond(conductor, caller);
        String callerName = caller == null ? "the caller" : caller.name();
        if (!attended) {
            beyond.forEach(grant -> refusals.add(grant + " is beyond what " + callerName
                    + " holds, and this run was not started by a person, so it cannot be"
                    + " granted."));
        }
        return new DraftReport(refusals, summary(trial, draft, beyond, callerName), lints(draft),
                beyond);
    }

    private static List<String> beyond(AgentDefinition conductor, AgentDefinition caller) {
        List<String> beyond = new ArrayList<>();
        for (String tool : conductor.tools()) {
            if (!tool.equals(AgentRegistry.AGENT_RUN)
                    && (caller == null || !caller.tools().contains(tool))) {
                beyond.add("tool " + tool);
            }
        }
        for (String callee : conductor.calls()) {
            if (caller == null || !caller.calls().contains(callee)) {
                beyond.add("callee " + callee);
            }
        }
        for (Grant scope : conductor.scopes()) {
            boolean covered = caller != null && AgentRegistry.escalatingGrant(caller,
                    conductor.withScopes(List.of(scope))).isEmpty();
            if (!covered) {
                beyond.add("scope " + scope.declaration());
            }
        }
        for (String nested : conductor.orchestrations()) {
            if (caller == null || !caller.orchestrations().contains(nested)) {
                beyond.add("orchestration " + nested);
            }
        }
        return beyond;
    }

    /**
     * What the draft is and grants. <b>What it grants beyond its caller comes first</b>: this is
     * the install question's preview too, and a preview cut to fit — or dropped by a short
     * terminal — loses its end, never the grants the person is being asked to give.
     */
    private static String summary(OrchestrationResolver.Trial trial, OrchestrationDefinition draft,
            List<String> beyond, String callerName) {
        StringBuilder text = new StringBuilder();
        if (!beyond.isEmpty()) {
            text.append("Grants beyond ").append(callerName).append(':');
            beyond.forEach(grant -> text.append("\n  ").append(grant));
            text.append('\n');
        }
        text.append("Stages:");
        List<OrchestrationDefinition.Stage> stages = draft.stages();
        for (int at = 0; at < stages.size(); at++) {
            OrchestrationDefinition.Stage stage = stages.get(at);
            text.append("\n  ").append(at + 1).append(". ").append(stage.id());
            if (stage.checked()) {
                text.append(" — check");
            }
            if (stage.acceptance() != null) {
                text.append(" — acceptance: ").append(stage.acceptance());
            }
            if (stage.holdsPhases()) {
                text.append(" — children: phases");
            }
            if (!stage.mayReturnTo().isEmpty()) {
                text.append(" — may return to: ").append(String.join(", ", stage.mayReturnTo()));
            }
        }
        text.append("\n  max-returns: ").append(draft.maxReturns());
        AgentDefinition conductor = draft.conductor();
        text.append("\nGrants:");
        for (String tool : conductor.tools()) {
            text.append(grant("tool " + tool, null, beyond));
        }
        for (String callee : conductor.calls()) {
            text.append(grant("callee " + callee, null, beyond));
        }
        for (Grant scope : conductor.scopes()) {
            text.append(grant("scope " + scope.declaration(), null, beyond));
        }
        for (String nested : conductor.orchestrations()) {
            OrchestrationDefinition found = trial.reachable().get(nested);
            text.append(grant("orchestration " + nested,
                    found == null ? null : found.description(), beyond));
        }
        if (trial.replaces() != null) {
            text.append("\nReplaces: the project's ").append(draft.name()).append('.');
        } else if (trial.replacesBroken()) {
            text.append("\nReplaces a project file of this name that does not load.");
        }
        if (trial.shadows() != null) {
            text.append("\nShadows the ").append(trial.shadows().tier().name().toLowerCase(Locale.ROOT))
                    .append(' ').append(draft.name()).append('.');
        }
        if (trial.hidesSession()) {
            text.append("\nHides your .plowshare ").append(draft.name()).append(" for you.");
        }
        for (OrchestrationDefinition.Trigger trigger : draft.triggers()) {
            trial.reachable().values().stream()
                    .filter(other -> !other.name().equals(draft.name()))
                    .filter(other -> other.triggers().stream()
                            .anyMatch(t -> t.text().equalsIgnoreCase(trigger.text())))
                    .forEach(other -> text.append("\nTriggers also used by: ").append(other.name())
                            .append(" (\"").append(trigger.text()).append("\")"));
        }
        return text.toString();
    }

    private static String grant(String what, String description, List<String> beyond) {
        return "\n  " + what + (description == null ? "" : " — " + description)
                + (beyond.contains(what) ? " (beyond caller)" : "");
    }

    private static List<String> lints(OrchestrationDefinition draft) {
        List<String> lints = new ArrayList<>();
        String body = draft.conductor().prompt();
        for (OrchestrationDefinition.Stage stage : draft.stages()) {
            if (!stage.checked() && stage.acceptance() == null) {
                lints.add("stage '" + stage.id() + "' has no check and no acceptance: it is done"
                        + " when the conductor says so.");
            }
        }
        for (OrchestrationDefinition.Stage stage : draft.stages()) {
            Matcher command = BACKTICKED.matcher(stage.doneWhen() == null ? "" : stage.doneWhen());
            if (!stage.checked() && command.find()) {
                lints.add("stage '" + stage.id() + "' names a command in its done-when ("
                        + command.group(1) + ") but has no check: nothing runs it.");
            }
        }
        for (String callee : draft.conductor().calls()) {
            if (!body.contains(callee)) {
                lints.add("callee '" + callee + "' is in calls: but the prompt never names it: the"
                        + " conductor will not know when to use it.");
            }
        }
        boolean required = draft.stages().stream().anyMatch(stage ->
                OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(stage.acceptance()));
        if (required && !body.toLowerCase(Locale.ROOT).contains("acceptance")) {
            lints.add("a stage requires acceptance but the prompt never mentions acceptance: the"
                    + " conductor will not know to write it.");
        }
        if (draft.artifacts() == null
                && draft.stages().stream().anyMatch(stage -> stage.acceptance() != null)) {
            lints.add("a stage has acceptance but the definition names no artifacts: spec.md is"
                    + " read from the artifacts directory, so that stage can never be marked"
                    + " done.");
        }
        return lints;
    }

    /** What orchestration_validate answers. */
    public String render() {
        StringBuilder text = new StringBuilder();
        if (!refusals.isEmpty()) {
            text.append("REFUSED — this draft would not install:");
            refusals.forEach(refusal -> text.append("\n- ").append(refusal));
            if (!summary.isEmpty()) {
                text.append("\n\n");
            }
        } else {
            text.append("The loader accepts this draft.\n\n");
        }
        text.append(summary);
        if (!lints.isEmpty()) {
            text.append("\n\nLints — fix each, or put it to the person:");
            lints.forEach(lint -> text.append("\n- ").append(lint));
        }
        return text.toString();
    }
}
