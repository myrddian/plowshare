package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Stage;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Trigger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code orchestrations/<name>.md} file read into an {@link OrchestrationDefinition}, or refused
 * with a sentence naming the file. Spec §4.2's checks that need nothing but the file itself; the
 * ones that need the tier's agents are {@link OrchestrationRegistry}'s.
 *
 * <p><b>The file is an agent definition for its conductor plus four keys</b>, so the agent half is
 * {@link AgentRegistry#parseWith}'s and not restated here — every rule an agent's frontmatter keeps
 * (fences, a named body, the model, the limits) is kept the same way, by the same code.
 *
 * <p>A conductor may hold {@code orchestrations:} and the {@code orchestrate_*}/{@code
 * orchestration_*} tools its grants offer. The flat "orchestrations do not nest" refusal
 * this class used to enforce here is gone; {@code implementation rationale
 * orchestrations-design.md} §2.1's cycle and escalation checks replace it, and they live
 * in {@link OrchestrationRegistry#read}, which is where a grant graph — as opposed to one
 * file — is knowable.
 */
final class OrchestrationParser {

    static final int DEFAULT_MAX_RETURNS = 3;

    static final String STAGES = "stages";
    static final String MAX_RETURNS = "max-returns";
    static final String ARTIFACTS = "artifacts";
    static final String TRIGGERS = "triggers";
    /** The acceptance checker the harness runs against this orchestration (spec 2026-10-01). */
    static final String CHECKER = "checker";
    static final Set<String> KEYS = Set.of(STAGES, MAX_RETURNS, ARTIFACTS, TRIGGERS, CHECKER);

    /** Agent keys that mean nothing on a conductor, which only the harness speaks to. A conductor
     *  that hit a cap is asked of the caller rather than retried on its own — Decision 7 — so
     *  {@code fallback} is refused with the rest. */
    static final Set<String> NOT_FOR_A_CONDUCTOR =
            Set.of("exported", "delegable", "bot", "announces-inbox", "fallback", "board");

    /**
     * Tool names a conductor never declares, because the harness hands them to a run rather than a
     * definition holding them: {@code RunExtras} tools are deliberately outside {@code knownTools},
     * so without this a {@code tools:} entry naming one reads "this server does not bind it", which
     * is true of the registry and useless to the author. Every {@code orchestrate_*} name is one
     * too, and matched by prefix.
     *
     * <p>{@link TodoTools#READ_NAME} and {@link TodoTools#WRITE_NAME} are here for the same reason
     * as the rest: every conductor is handed its own checked {@code todo_write} — spec 2026-09-26
     * §3 — and a declared one of the same name would win over it ({@code JobRuntime.offeredTo}
     * offers the declared tools first).
     */
    private static final Set<String> HARNESS_TOOLS = Set.of(ConductorTools.ASK_NAME,
            ConductorTools.CHECK_NAME, ConductorTools.FINISH_NAME,
            CallerOrchestrationTools.ANSWER_NAME, CallerOrchestrationTools.STATUS_NAME,
            CallerOrchestrationTools.CANCEL_NAME, TodoTools.READ_NAME, TodoTools.WRITE_NAME,
            BoardTools.OPEN_NAME, BoardTools.READ_NAME, BoardTools.POST_NAME,
            BoardTools.DOCUMENT_NAME, BoardTools.PASS_NAME, BoardTools.CLOSE_NAME,
            BoardTools.REQUEST_NAME, BoardTools.DECIDE_NAME, ConductorTools.CHECKER_ANSWER_NAME);

    private static final String START_PREFIX = "orchestrate_";

    private static final Set<String> STAGE_KEYS =
            Set.of("id", "done-when", "may-return-to", "check", "acceptance", "children");
    /** The one value {@code children:} takes: the stage whose child todos are the phases. */
    private static final String CHILDREN_PHASES = "phases";
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern COMMAND = Pattern.compile("/[a-z0-9_-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Set<String> PLACEHOLDERS = Set.of("date", "name", "id");
    private static final String AGENT_PREFIX = "the agent definition '";

    private OrchestrationParser() {
    }

    private static OrchestrationDefinition parseScript(DefinitionSource.Definition entry,
            Set<String> knownTools, OrchestrationDefinition.Tier tier) {
        var manifest = io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram.manifest(entry.text());
        OrchestrationDefinition checked = parse(new DefinitionSource.Definition(entry.name(),
                entry.origin(), "---\n" + manifest + "\n---\nExecute this orchestration's pinned script."), knownTools, tier);
        return new OrchestrationDefinition(checked.conductor().withPrompt(entry.text()), checked.stages(),
                checked.maxReturns(), checked.artifacts(), checked.triggers(), hash(entry.text()),
                entry.text(), entry.origin(), tier, checked.checker());
    }

    static OrchestrationDefinition parse(DefinitionSource.Definition entry, Set<String> knownTools,
            OrchestrationDefinition.Tier tier) {
        if (io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram.isScript(entry.text())) {
            return parseScript(entry, knownTools, tier);
        }
        String refusal = "the orchestration definition '" + entry.name() + "' (" + entry.origin() + ")";
        if (!ID.matcher(entry.name()).matches()) {
            throw new IllegalStateException(refusal + " is named '" + entry.name() + "', and an"
                    + " orchestration's name becomes the tool orchestrate_<name>, so it is lower-case"
                    + " letters, digits and underscores");
        }
        Map<String, String> dropped = new LinkedHashMap<>();
        AgentRegistry.Parsed parsed;
        try {
            // THE STUDIO'S TOOLS ARE A CONDUCTOR'S TO DECLARE (spec 2026-09-29-orchestration-studio
            // §3): bound here and nowhere else, so an ordinary agent naming one is refused as
            // unbound, and a conductor naming one is handed it per run by its RunExtras.
            Set<String> conductorTools = new HashSet<>(knownTools);
            conductorTools.addAll(StudioTools.NAMES);
            parsed = AgentRegistry.parseWith(entry, Set.copyOf(conductorTools), dropped, KEYS,
                    NOT_FOR_A_CONDUCTOR);
        } catch (IllegalStateException shared) {
            throw new IllegalStateException(asOrchestration(shared.getMessage()), shared);
        }
        AgentDefinition conductor = parsed.definition();
        // SPEC §4.3: A CONDUCTOR IS GIVEN agent_run OVER ITS calls, WITHOUT DECLARING IT. An agent
        // naming calls without the tool is refused at load; a conductor is exempt from that check
        // precisely because this grants it — and until 2026-09-25 nothing did. Every conductor
        // was offered its callees in its prompt and no tool to reach them: all 29 agent_run calls
        // ever made from one were refused, so no phase reached coder, test_designer or
        // code_reviewer, and the conductors wrote the code themselves and passed their own review.
        // Only where this server binds the tool: a runtime without delegation drops a declared
        // agent_run too (AgentRegistry), and a granted one it cannot build would be the same lie.
        if (!conductor.calls().isEmpty() && !conductor.canDelegate()
                && knownTools.contains(AgentRegistry.AGENT_RUN)) {
            List<String> tools = new ArrayList<>(conductor.tools());
            tools.add(AgentRegistry.AGENT_RUN);
            conductor = conductor.withTools(List.copyOf(tools));
        }
        for (Map.Entry<String, String> droppedEntry : dropped.entrySet()) {
            String tool = droppedEntry.getKey().substring(droppedEntry.getKey().indexOf(": ") + 2);
            if (knownTools.contains(tool)) {
                throw new IllegalStateException(refusal + " may not hold the tool '" + tool + "': "
                        + asOrchestration(droppedEntry.getValue()) + ". A conductor without a tool its stages need"
                        + " would fail mid-run, so the orchestration is not offered at all");
            }
            if (HARNESS_TOOLS.contains(tool) || tool.startsWith(START_PREFIX)) {
                throw harnessToolRefusal(refusal, tool);
            }
            throw new IllegalStateException(refusal + " names the tool '" + tool + "', which this"
                    + " server does not bind. A conductor without a tool its stages need would fail"
                    + " mid-run, so the orchestration is not offered at all");
        }
        // The dropped-tool loop above only sees a tool that parseWith could not bind: one outside
        // knownTools. todo_read/todo_write are unlike orchestration_ask/check/finish (deliberately
        // never in knownTools, so always dropped) and unlike orchestrate_<name>/answer/status/
        // cancel (legitimately IN knownTools, and kept, exactly when this conductor's own
        // 'orchestrations:' grant supplies them): a board wires them into knownTools for every
        // ordinary agent (JobRuntime.knownTools), so a conductor declaring 'tools: [todo_write]'
        // has it KEPT rather than dropped, and the loop above never sees it. Checked here, against
        // what the conductor actually kept, so the declared todo_write cannot win over the checked
        // one JobRuntime.offeredTo's putIfAbsent would otherwise let it shadow.
        for (String tool : conductor.tools()) {
            if (TodoTools.NAMES.contains(tool)) {
                throw harnessToolRefusal(refusal, tool);
            }
        }
        Map<String, Object> extras = parsed.extras();
        List<Stage> stages = stages(refusal, extras.get(STAGES),
                !conductor.orchestrations().isEmpty());
        OrchestrationDefinition definition = new OrchestrationDefinition(conductor, stages,
                maxReturns(refusal, extras), artifacts(refusal, extras), triggers(refusal, extras),
                hash(entry.text()), entry.text(), entry.origin(), tier,
                checker(refusal, extras, stages));
        if (conductor.tools().contains(StudioTools.INSTALL_NAME) && definition.artifacts() == null) {
            throw new IllegalStateException(refusal + " declares " + StudioTools.INSTALL_NAME
                    + ", but orchestration_install installs a draft from the run's artifacts"
                    + " directory, and this definition names none. Add 'artifacts:'");
        }
        return definition;
    }

    /** The refusal for a conductor naming a tool the harness hands it rather than one it declares —
     *  shared by the dropped-tool loop and the kept-tools check, so the two routes to the same
     *  mistake (never bound, or bound for every ordinary agent) read identically. */
    private static IllegalStateException harnessToolRefusal(String refusal, String tool) {
        return new IllegalStateException(refusal + " names the tool '" + tool + "', which a"
                + " conductor does not declare: the harness hands " + ConductorTools.ASK_NAME
                + ", " + ConductorTools.CHECK_NAME + ", " + ConductorTools.FINISH_NAME + ", "
                + TodoTools.READ_NAME + " and " + TodoTools.WRITE_NAME + " to every"
                + " conductor, and "
                + START_PREFIX + "<name> with " + CallerOrchestrationTools.ANSWER_NAME + ", "
                + CallerOrchestrationTools.STATUS_NAME + " and "
                + CallerOrchestrationTools.CANCEL_NAME + " to one that holds an"
                + " 'orchestrations:' grant. Drop it from 'tools:' — the grant is what"
                + " offers it");
    }

    /** An agent refusal shared by {@link AgentRegistry#parseWith}, re-prefixed to name the kind of
     *  file it was actually read from. */
    private static String asOrchestration(String message) {
        return message != null && message.startsWith(AGENT_PREFIX)
                ? "the orchestration definition '" + message.substring(AGENT_PREFIX.length())
                : message;
    }

    /**
     * @param startsRuns the conductor holds an {@code orchestrations:} grant, without which no
     *     stage can hold phases: there would be nothing to start under them
     */
    private static List<Stage> stages(String refusal, Object raw, boolean startsRuns) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException(refusal
                    + " has no stages: an orchestration is a list of at least one stage");
        }
        List<Stage> stages = new ArrayList<>();
        Set<String> earlier = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map) || !(map.get("id") instanceof String id)) {
                throw new IllegalStateException(refusal
                        + " has a stage that is not a map with an 'id': " + element);
            }
            if (!ID.matcher(id).matches()) {
                throw new IllegalStateException(refusal + " has the stage id '" + id
                        + "', and a stage id is lower-case letters, digits and underscores");
            }
            if (earlier.contains(id)) {
                throw new IllegalStateException(refusal + " has two stages with the id '" + id + "'");
            }
            for (Object key : map.keySet()) {
                // instanceof first: SnakeYAML reads '~: x' as a null key, and Set.of's contains
                // throws on null rather than answering false.
                if (!(key instanceof String name) || !STAGE_KEYS.contains(name)) {
                    throw new IllegalStateException(refusal + " has the stage '" + id
                            + "' with the unrecognised key '" + key + "'. A stage takes "
                            + new TreeSet<>(STAGE_KEYS));
                }
            }
            Object doneWhen = map.get("done-when");
            if (map.containsKey("done-when") && !(doneWhen instanceof String)) {
                throw new IllegalStateException(refusal + " has the stage '" + id
                        + "' whose 'done-when' is not text");
            }
            List<String> returns = new ArrayList<>();
            if (map.get("may-return-to") instanceof List<?> targets) {
                for (Object target : targets) {
                    if (!(target instanceof String back) || !earlier.contains(back)) {
                        throw new IllegalStateException(refusal + " lets the stage '" + id
                                + "' return to '" + target + "', which is not an earlier stage."
                                + " A stage may only return to one before it");
                    }
                    returns.add(back);
                }
            } else if (map.containsKey("may-return-to")) {
                throw new IllegalStateException(refusal + " has the stage '" + id
                        + "' whose 'may-return-to' is not a list of stage ids");
            }
            Object check = map.get("check");
            if (map.containsKey("check") && !"required".equals(check)) {
                throw new IllegalStateException(refusal + " has the stage '" + id + "' whose 'check'"
                        + " is not 'required' — the one value it takes: the harness runs the run's"
                        + " check whenever this stage is marked done");
            }
            Object acceptance = map.get("acceptance");
            if (map.containsKey("acceptance")
                    && !OrchestrationDefinition.ACCEPTANCE_WRITTEN.equals(acceptance)
                    && !OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(acceptance)) {
                throw new IllegalStateException(refusal + " has the stage '" + id + "' whose"
                        + " 'acceptance' is not 'written' or 'required': 'written' marks the stage"
                        + " whose done move reads spec.md's ## Acceptance, 'required' the stage"
                        + " whose done move runs it");
            }
            // Measured 2026-09-29/30, orc_318DFD3782228160: a root filed its phases under `plan`
            // and was refused eleven times "`plan` has N not done" without being told where they
            // belonged. The stage that holds them is the definition's to say, not an id to guess.
            Object children = map.get("children");
            if (map.containsKey("children") && !CHILDREN_PHASES.equals(children)) {
                throw new IllegalStateException(refusal + " has the stage '" + id + "' whose"
                        + " 'children' is not 'phases' — the one value it takes: this stage's child"
                        + " todos are the phases the conductor starts runs under");
            }
            if (map.containsKey("children") && !startsRuns) {
                throw new IllegalStateException(refusal + " has 'children: phases' on the stage '"
                        + id + "', but its conductor holds no 'orchestrations:' grant to start them");
            }
            stages.add(new Stage(id,
                    doneWhen instanceof String text && !text.isBlank() ? text.strip() : null, returns,
                    "required".equals(check), (String) acceptance,
                    CHILDREN_PHASES.equals(children)));
            earlier.add(id);
        }
        if (!stages.isEmpty() && stages.get(0).checked()) {
            throw new IllegalStateException(refusal + " checks the stage '" + stages.get(0).id()
                    + "', but no stage comes before it in which the check can be set; a checked"
                    + " stage needs one before it (test_design, where there is one)");
        }
        long written = stages.stream()
                .filter(s -> OrchestrationDefinition.ACCEPTANCE_WRITTEN.equals(s.acceptance()))
                .count();
        long required = stages.stream()
                .filter(s -> OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(s.acceptance()))
                .count();
        if (stages.stream().filter(Stage::holdsPhases).count() > 1) {
            throw new IllegalStateException(refusal + " has more than one stage with 'children:"
                    + " phases'; the phases are held in one place");
        }
        if (written > 1 || required > 1) {
            throw new IllegalStateException(refusal + " has more than one stage with 'acceptance: "
                    + (written > 1 ? "written" : "required") + "'");
        }
        int writtenAt = indexOf(stages, OrchestrationDefinition.ACCEPTANCE_WRITTEN);
        int requiredAt = indexOf(stages, OrchestrationDefinition.ACCEPTANCE_REQUIRED);
        if (requiredAt >= 0 && (writtenAt < 0 || writtenAt > requiredAt)) {
            throw new IllegalStateException(refusal + " has 'acceptance: required' on the stage '"
                    + stages.get(requiredAt).id() + "', and 'acceptance: required' needs an"
                    + " earlier stage with 'acceptance: written', where the commands are written");
        }
        return stages;
    }

    /** The index of the first stage whose {@code acceptance} is {@code value}, or -1. */
    private static int indexOf(List<Stage> stages, String value) {
        for (int i = 0; i < stages.size(); i++) {
            if (value.equals(stages.get(i).acceptance())) {
                return i;
            }
        }
        return -1;
    }

    private static int maxReturns(String refusal, Map<String, Object> extras) {
        if (!extras.containsKey(MAX_RETURNS)) {
            return DEFAULT_MAX_RETURNS;
        }
        Object raw = extras.get(MAX_RETURNS);
        if (raw instanceof Integer n && n >= 1) {
            return n;
        }
        throw new IllegalStateException(refusal + " has 'max-returns: " + raw
                + "', and it is a whole number of at least 1");
    }

    private static String artifacts(String refusal, Map<String, Object> extras) {
        if (!extras.containsKey(ARTIFACTS)) {
            return null;
        }
        Object raw = extras.get(ARTIFACTS);
        String path = raw instanceof String text ? text.strip() : String.valueOf(raw);
        boolean relative = raw instanceof String && !path.isEmpty() && !path.startsWith("/")
                && !path.startsWith("~") && !path.contains("\\");
        if (relative) {
            for (String segment : path.split("/")) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                        || segment.startsWith(".")) {
                    relative = false;
                    break;
                }
            }
        }
        if (!relative) {
            throw new IllegalStateException(refusal + " has 'artifacts: " + path + "', and it must"
                    + " be a path relative to the project, with no '.' or '..' segment and no"
                    + " segment starting with '.'");
        }
        Matcher placeholders = PLACEHOLDER.matcher(path);
        while (placeholders.find()) {
            if (!PLACEHOLDERS.contains(placeholders.group(1))) {
                throw new IllegalStateException(refusal + " has 'artifacts: " + path
                        + "', which uses '" + placeholders.group() + "'. The placeholders are"
                        + " {date}, {name} and {id}");
            }
        }
        if (PLACEHOLDER.matcher(path).replaceAll("").matches(".*[{}].*")) {
            throw new IllegalStateException(refusal + " has 'artifacts: " + path
                    + "', which has a brace that is not one of {date}, {name} and {id}");
        }
        return path;
    }

    /**
     * {@code checker:} — the agent the harness runs as this orchestration's acceptance checker
     * (spec 2026-10-01 §2), or null for none. It checks acceptance, so a definition naming one
     * needs a stage that runs it ({@code acceptance: required}) and one that writes it, and an
     * artifacts directory for the spec and plan it reads. Whether the agent exists and is fit to be
     * one — read-only, and never delegable — is {@link OrchestrationRegistry}'s, which has the
     * agents.
     */
    private static String checker(String refusal, Map<String, Object> extras,
            List<Stage> stages) {
        if (!extras.containsKey(CHECKER)) {
            return null;
        }
        Object raw = extras.get(CHECKER);
        if (!(raw instanceof String name) || !ID.matcher(name.strip()).matches()) {
            throw new IllegalStateException(refusal + " has 'checker: " + raw + "', and it names"
                    + " one agent: lower-case letters, digits and underscores");
        }
        boolean required = stages.stream()
                .anyMatch(s -> OrchestrationDefinition.ACCEPTANCE_REQUIRED.equals(s.acceptance()));
        if (!required) {
            throw new IllegalStateException(refusal + " names the checker '" + name.strip()
                    + "', but has no stage with 'acceptance: required': a checker holds a run to"
                    + " its acceptance, and this one has none to hold it to");
        }
        if (!extras.containsKey(ARTIFACTS)) {
            throw new IllegalStateException(refusal + " names the checker '" + name.strip()
                    + "', but no 'artifacts:' directory, where the spec and plan it reads are");
        }
        return name.strip();
    }

    private static List<Trigger> triggers(String refusal, Map<String, Object> extras) {
        if (!extras.containsKey(TRIGGERS)) {
            return List.of();
        }
        if (!(extras.get(TRIGGERS) instanceof List<?> list)) {
            throw new IllegalStateException(refusal + " has 'triggers' that is not a list");
        }
        List<Trigger> triggers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof String raw)) {
                throw new IllegalStateException(refusal + " has the trigger '" + element
                        + "', which is not text");
            }
            if (raw.isBlank()) {
                throw new IllegalStateException(refusal + " has a blank trigger");
            }
            String text = raw.strip();
            boolean command = text.startsWith("/");
            if (command && !COMMAND.matcher(text).matches()) {
                throw new IllegalStateException(refusal + " has the trigger '" + text + "', and a"
                        + " command trigger is '/' followed by lower-case letters, digits, '_' or '-'");
            }
            if (!seen.add(text.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(refusal + " has the trigger '" + text + "' twice");
            }
            triggers.add(new Trigger(text, command));
        }
        return triggers;
    }

    private static String hash(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this JVM has no SHA-256, which every Java SE has", impossible);
        }
    }
}
