package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.ConductorTools;
import io.aeyer.plowshare.server.agents.DefinitionResolver;
import io.aeyer.plowshare.server.agents.DefinitionResolver.Caller;
import io.aeyer.plowshare.server.agents.DraftReport;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.OrchestrationResolver;
import io.aeyer.plowshare.server.agents.OrchestrationWriter;
import io.aeyer.plowshare.server.agents.StructuredAnswers;
import io.aeyer.plowshare.server.agents.StructuredQuestions;
import io.aeyer.plowshare.server.agents.StructuredQuestions.Option;
import io.aeyer.plowshare.server.agents.StudioTools;
import io.aeyer.plowshare.server.agents.StudioTools.Installing;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.files.Grant;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The Orchestration Studio behind its four tools, and the installer behind a person's {@code
 * install} answer — spec 2026-09-29-orchestration-studio §3.1–§3.4.
 *
 * <h2>Every answer is about one run</h2>
 *
 * <p>The tools hand in a run id; everything else — the caller, what it holds, whether a person
 * attends, where its drafts may lie — is read from that run's row, never from the model. A run
 * that is missing or has ended is refused, since nothing it asks could be delivered.
 *
 * <h2>The model trials; the person installs</h2>
 *
 * <p>{@link #install} writes nothing: it trials the draft, and puts it to the person as a
 * question of the person-only {@code install} kind whose structure holds the draft's whole text.
 * {@link #settle} is called by the engine on the person's answer, and writes <em>those</em> bytes
 * — the ones the person was shown the summary of — after trialling them once more, whatever the
 * file in the artifacts directory has become since.
 *
 * <p><b>It holds an {@link Asker}, not the engine</b>, so wiring it to the engine that holds it
 * as its {@link Orchestrations.Installer} makes no cycle.
 *
 * <p><b>It never throws to a tool.</b> A refusal, and a failure of anything it leans on — the
 * store, the resolvers, the asker — is a sentence the model reads.
 */
public final class Studio implements StudioTools.Port, Orchestrations.Installer {

    /** Records an install question on a run; empty when asked, else why not. */
    @FunctionalInterface
    public interface Asker {
        Optional<String> ask(String run, String question, String structure);
    }

    static final String INSTALL = "Install";
    static final String DONT_INSTALL = "Don't install";
    private static final String HEADER = "Install";
    private static final Set<String> WORDS_THAT_INSTALL = Set.of("install", "yes", "y");
    private static final String STARTED_KEEP =
            " Runs already started keep the definition they started with.";
    private static final String NOT_GRANTED =
            " Nothing starts it until an agent's `orchestrations:` grant names it.";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Tools a run hands its conductor and a definition never declares: {@code OrchestrationParser}
     * refuses each, so the catalog does not offer them. Every {@code orchestrate_*} name is one
     * too, matched by prefix.
     */
    private static final Set<String> HANDED_BY_A_RUN = Set.of(ConductorTools.ASK_NAME,
            ConductorTools.CHECK_NAME, ConductorTools.FINISH_NAME,
            CallerOrchestrationTools.ANSWER_NAME, CallerOrchestrationTools.STATUS_NAME,
            CallerOrchestrationTools.CANCEL_NAME, TodoTools.READ_NAME, TodoTools.WRITE_NAME);

    /** A sentence a helper answers with; each public method catches it and says it. */
    private static final class Refusal extends RuntimeException {
        Refusal(String why) {
            super(why, null, false, false);
        }
    }

    /** A draft trialled, what that trial reports, and whom its grants were weighed against. */
    private record Trialled(OrchestrationResolver.Trial trial, DraftReport report,
            String callerName) {}

    private final OrchestrationStore store;
    private final ConversationStore conversations;
    private final Callers callers;
    private final DefinitionResolver agents;
    private final OrchestrationResolver resolver;
    private final OrchestrationWriter writer;
    private final Set<String> knownTools;
    private final Asker asker;

    public Studio(OrchestrationStore store, ConversationStore conversations, Callers callers,
            DefinitionResolver agents, OrchestrationResolver resolver, OrchestrationWriter writer,
            Set<String> knownTools, Asker asker) {
        this.store = Objects.requireNonNull(store, "store");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.knownTools = Set.copyOf(knownTools);
        this.asker = Objects.requireNonNull(asker, "asker");
    }

    // --- the port --------------------------------------------------------------------------------

    @Override
    public String catalog(String runId) {
        try {
            OrchestrationRecord run = run(runId);
            Caller caller = callerOf(run);
            AgentDefinition held = callerDefinition(run, caller);
            return catalog(caller, held, attended(run));
        } catch (Refusal refused) {
            return refused.getMessage();
        } catch (RuntimeException failed) {
            return "The catalog could not be read: " + stop(why(failed));
        }
    }

    @Override
    public String read(String runId, String name) {
        try {
            Caller caller = callerOf(run(runId));
            return resolver.find(caller, name).map(found -> name + " (" + tier(found) + ", "
                    + found.origin() + "):\n\n" + found.source())
                    .orElse("No orchestration named " + name + " is reachable here.");
        } catch (Refusal refused) {
            return refused.getMessage();
        } catch (RuntimeException failed) {
            return name + " could not be read: " + stop(why(failed));
        }
    }

    @Override
    public String validate(String runId, String path, String text) {
        try {
            OrchestrationRecord run = run(runId);
            return report(run, callerOf(run), draftPath(run, path), text).report().render();
        } catch (Refusal refused) {
            return refused.getMessage();
        } catch (RuntimeException failed) {
            return path + " could not be validated: " + stop(why(failed));
        }
    }

    @Override
    public Optional<String> notADraft(String runId, String path) {
        try {
            draftPath(run(runId), path);
            return Optional.empty();
        } catch (Refusal refused) {
            return Optional.of(refused.getMessage());
        } catch (RuntimeException failed) {
            return Optional.of(path + " could not be checked: " + stop(why(failed)));
        }
    }

    @Override
    public Installing install(String runId, String path, String text) {
        try {
            OrchestrationRecord run = run(runId);
            String name = draftPath(run, path);
            Trialled trialled = report(run, callerOf(run), name, text);
            if (!trialled.report().installable()) {
                return new Installing.Refused("Not installed; nothing was asked.\n\n"
                        + trialled.report().render());
            }
            String question = "Install " + name + " into this project?\n\n"
                    + trialled.report().summary();
            String structure = installQuestion(artifactsDir(run), name, path, text, trialled);
            Optional<String> unasked;
            try {
                unasked = asker.ask(run.id(), question, structure);
            } catch (RuntimeException failed) {
                return new Installing.Refused("The install question could not be asked: "
                        + stop(why(failed)));
            }
            return unasked.<Installing>map(Installing.Refused::new)
                    .orElseGet(() -> new Installing.Asked(question));
        } catch (Refusal refused) {
            return new Installing.Refused(refused.getMessage());
        } catch (RuntimeException failed) {
            return new Installing.Refused(path + " could not be put to the person: "
                    + stop(why(failed)));
        }
    }

    // --- the installer ---------------------------------------------------------------------------

    /**
     * What the harness did with the person's answer, then — fenced as data — any words they
     * gave with it: a note, words beside or in place of a choice. The conductor that asked hears
     * both; it acts on neither as an instruction to install.
     */
    @Override
    public String settle(OrchestrationRecord run, OrchestrationMessage question,
            OrchestrationMessage answer) {
        String outcome = outcome(run, question, answer);
        List<String> words = wordsOf(answer);
        return words.isEmpty() ? outcome : outcome + "\n\nThe person also said:\n"
                + Utterances.fence("text", String.join("\n", words));
    }

    private String outcome(OrchestrationRecord run, OrchestrationMessage question,
            OrchestrationMessage answer) {
        JsonNode held = tree(question.structure());
        String name = held.path("name").asText(null);
        String path = held.path("path").asText(null);
        String text = held.path("text").asText(null);
        if (name == null || path == null || text == null) {
            return "Nothing was installed: the install question holds no draft.";
        }
        String stays = " The draft stays at " + path + ".";
        if (!chosenInstall(answer)) {
            return "The person declined to install " + name + "; the draft stays at " + path + ".";
        }
        // THE BYTES THE QUESTION WAS ASKED WITH: a stored text its stored digest does not match
        // is not what the person was shown the summary of.
        if (!digest(text).equals(held.path("sha256").asText(null))) {
            return "The install question's draft does not match its digest; nothing was"
                    + " installed." + stays;
        }
        Caller caller;
        DraftReport report;
        try {
            caller = callerOf(run);
            report = report(run, caller, name, text).report();
        } catch (Refusal refused) {
            return "Nothing was installed: " + stop(refused.getMessage()) + stays;
        }
        if (!report.installable()) {
            return "Nothing was installed: " + stop(String.join("; ", report.refusals())) + stays;
        }
        long projectId = caller.projectId();
        // ALREADY THERE: a settle repeated after a restart finds its own bytes, and writing them
        // again would copy them over the .prev the first write kept.
        Optional<Path> already = writer.holding(projectId, name, text);
        if (already.isPresent()) {
            resolver.invalidate(projectId);
            return "Installed " + name + " at " + already.get() + "." + NOT_GRANTED + STARTED_KEEP;
        }
        OrchestrationWriter.Written written;
        try {
            written = writer.write(projectId, name, text);
        } catch (RuntimeException failed) {
            String why = String.valueOf(failed.getMessage()).replace(" Nothing was installed.", "");
            return "Nothing was installed: " + stop(why) + stays;
        }
        resolver.invalidate(projectId);
        return "Installed " + name + " at " + written.file() + "."
                + (written.previous() == null ? "" : " The one it replaced is kept as "
                        + written.previous() + "; rename it back to undo.")
                + NOT_GRANTED + STARTED_KEEP;
    }

    // --- the run ---------------------------------------------------------------------------------

    private OrchestrationRecord run(String id) {
        OrchestrationRecord run = store.find(id).orElseThrow(() ->
                new Refusal("No orchestration " + id + " exists; the Studio acts only for a live"
                        + " run."));
        if (run.endedAt() != null) {
            throw new Refusal("Orchestration " + id + " has ended; the Studio acts only for a live"
                    + " run.");
        }
        return run;
    }

    private Caller callerOf(OrchestrationRecord run) {
        return callers.callerForConversation(run.conductorConversation(), run.callerSession());
    }

    /**
     * What the agent that started this run holds: for a nested run, its parent's conductor as the
     * parent pinned it; else the caller agent as this caller's tier serves it. Null when neither
     * can be read — then every grant is beyond it.
     */
    private AgentDefinition callerDefinition(OrchestrationRecord run, Caller caller) {
        if (run.parent() != null) {
            return store.find(run.parent()).map(parent -> {
                try {
                    return OrchestrationRegistry.parsePinned(parent.definitionName(),
                            parent.definitionOrigin(), parent.definitionSource(), knownTools,
                            parent.tier()).conductor();
                } catch (RuntimeException unparsed) {
                    return null;
                }
            }).orElse(null);
        }
        if (run.callerAgent() == null) {
            return null;
        }
        return agents.forCaller(caller).find(run.callerAgent()).orElse(null);
    }

    /** Whether the run tree's root was started from a person's own chat: its origin is TURN. */
    private boolean attended(OrchestrationRecord run) {
        OrchestrationRecord root = run;
        Set<String> seen = new HashSet<>();
        while (root.parent() != null) {
            if (!seen.add(root.id())) {
                return false;
            }
            Optional<OrchestrationRecord> up = store.find(root.parent());
            if (up.isEmpty()) {
                return false;
            }
            root = up.get();
        }
        return root.callerConversation() != null && conversations.find(root.callerConversation())
                .map(conversation -> conversation.origin() == Origin.TURN).orElse(false);
    }

    private String artifactsDir(OrchestrationRecord run) {
        String dir = store.artifactsDir(run.id()).orElseThrow(() -> new Refusal("This run has no"
                + " artifacts directory, so it has no draft to read."));
        return dir.endsWith("/") ? dir : dir + "/";
    }

    /** The draft's name, its file stem, when {@code path} is a draft in the artifacts directory. */
    private String draftPath(OrchestrationRecord run, String path) {
        String dir = artifactsDir(run);
        Refusal outside = new Refusal(path + " is not a draft in this run's artifacts directory ("
                + dir + "): a draft is " + dir + "<name>.md.");
        Path draft;
        Path root;
        try {
            draft = Path.of(path).normalize();
            root = Path.of(dir).normalize();
        } catch (InvalidPathException unusable) {
            throw outside;
        }
        String file = draft.getFileName() == null ? "" : draft.getFileName().toString();
        if (!draft.startsWith(root) || draft.equals(root) || !file.endsWith(".md")
                || file.length() == ".md".length()) {
            throw outside;
        }
        return file.substring(0, file.length() - ".md".length());
    }

    /** {@code text} trialled as {@code name} in this caller's project, and its report. */
    private Trialled report(OrchestrationRecord run, Caller caller, String name, String text) {
        OrchestrationResolver.Trial trial;
        try {
            trial = resolver.trial(caller, name, text);
        } catch (IllegalArgumentException noProject) {
            throw new Refusal(noProject.getMessage());
        } catch (RuntimeException unreadable) {
            throw new Refusal(name + " could not be trialled: " + unreadable.getMessage());
        }
        AgentDefinition held = callerDefinition(run, caller);
        return new Trialled(trial, DraftReport.of(trial, held, attended(run)),
                held == null ? "the caller" : held.name());
    }

    // --- the catalog -----------------------------------------------------------------------------

    private String catalog(Caller caller, AgentDefinition held, boolean attended) {
        StringBuilder text = new StringBuilder();
        if (caller.projectId() == null) {
            text.append("This run is in no project, so there is no project tier to install into.")
                    .append("\n\n");
        }
        String who = held == null ? "the caller" : held.name();
        text.append("What an orchestration here may be granted. * marks what ").append(who)
                .append(", the agent that started this run, holds; any other grant is beyond it.");
        if (held == null) {
            text.append(" Its definition could not be read, so nothing is marked.");
        }
        text.append("\n\nTools:");
        new TreeSet<>(knownTools).stream()
                .filter(tool -> !HANDED_BY_A_RUN.contains(tool) && !tool.startsWith("orchestrate_"))
                .forEach(tool -> text.append(line(held != null && held.tools().contains(tool),
                        tool)));
        text.append("\n\nAgents a conductor may call:");
        AgentRegistry registry = agents.forCaller(caller);
        int callees = 0;
        for (String name : registry.names()) {
            AgentDefinition agent = registry.find(name).orElse(null);
            if (agent == null || !agent.delegable()) {
                continue;
            }
            callees++;
            text.append(line(held != null && held.calls().contains(name), name + " — "
                    + agent.description() + scopes(agent.scopes())));
        }
        if (callees == 0) {
            text.append("\n  (none)");
        }
        text.append("\n\nOrchestrations reachable here:");
        Map<String, OrchestrationDefinition> reachable = new TreeMap<>(resolver.forCaller(caller));
        reachable.forEach((name, found) -> text.append(line(
                held != null && held.orchestrations().contains(name), name + " (" + tier(found)
                        + ") — stages: " + found.stages().stream()
                                .map(OrchestrationDefinition.Stage::id)
                                .collect(Collectors.joining(", "))
                        + triggers(found.triggers()))));
        if (reachable.isEmpty()) {
            text.append("\n  (none)");
        }
        text.append("\n\nScopes ").append(who).append(" holds:");
        if (held == null || held.scopes().isEmpty()) {
            text.append("\n  (none)");
        } else {
            held.scopes().forEach(scope -> text.append(line(true, scope.declaration())));
        }
        text.append("\n\nAttended: ").append(attended
                ? "yes — a person started this run's tree, so a grant beyond the caller is marked"
                        + " and put to them when installing."
                : "no — nobody started this run's tree from their own chat, so a grant beyond the"
                        + " caller is refused.");
        return text.toString();
    }

    private static String line(boolean held, String what) {
        return (held ? "\n* " : "\n  ") + what;
    }

    private static String scopes(List<Grant> scopes) {
        return scopes.isEmpty() ? "" : " (scopes: " + scopes.stream().map(Grant::declaration)
                .collect(Collectors.joining(", ")) + ")";
    }

    private static String triggers(List<OrchestrationDefinition.Trigger> triggers) {
        return triggers.isEmpty() ? "" : " — triggers: " + triggers.stream()
                .map(trigger -> "\"" + trigger.text() + "\"").collect(Collectors.joining(", "));
    }

    private static String tier(OrchestrationDefinition definition) {
        return definition.tier().name().toLowerCase(Locale.ROOT);
    }

    // --- the install question --------------------------------------------------------------------

    /**
     * The structure an install question is stored with: one question the person answers, and
     * beside it — harness-written, never the model's — the draft's name, path, whole text and
     * that text's hash, which {@link #settle} installs from.
     *
     * <p><b>The Install option's description leads with what the draft grants beyond its
     * caller.</b> The modal always draws a description, if cut to one row on a short terminal,
     * and drops the preview first; a grant the person is giving must be where it is never lost.
     */
    private static String installQuestion(String dir, String name, String path, String text,
            Trialled trialled) {
        OrchestrationResolver.Trial trial = trialled.trial();
        String effect = trial.replaces() != null
                ? "it replaces the project's " + name + ", kept as " + name + ".md.prev"
                : trial.replacesBroken()
                        ? "it replaces a project file of this name that does not load, kept as "
                                + name + ".md.prev"
                        : trial.shadows() != null
                                ? "it shadows the " + tier(trial.shadows()) + " " + name
                                : "a new orchestration";
        String leave = "Leave it as a draft in " + dir;
        if (leave.length() > StructuredQuestions.MOST_DESCRIPTION) {
            leave = "Leave it as a draft in this run's artifacts directory";
        }
        String write = "Write it into the project; " + effect;
        String grants = grantsBeyond(trialled.callerName(), trialled.report().beyondCaller(),
                Math.max(0, StructuredQuestions.MOST_DESCRIPTION - write.length() - 1));
        String install = grants + " " + write;
        if (install.length() > StructuredQuestions.MOST_DESCRIPTION) {
            install = install.substring(0, StructuredQuestions.MOST_DESCRIPTION);
        }
        StructuredQuestions.Asked asked = new StructuredQuestions.Asked(
                "The draft " + name + " at " + path + " passed the loader's trial.",
                List.of(new StructuredQuestions.Question(HEADER,
                        "Install " + name + " into this project?", false, List.of(
                                new Option(INSTALL, install,
                                        preview(trialled.report().summary(), trial, text, path)),
                                new Option(DONT_INSTALL, leave, null)))));
        ObjectNode root = (ObjectNode) tree(StructuredQuestions.structure(asked));
        root.put("name", name);
        root.put("path", path);
        root.put("text", text);
        root.put("sha256", trial.draft().hash());
        return root.toString();
    }

    /**
     * One sentence naming what a draft grants beyond {@code caller}, at most {@code room}
     * characters: as many grants as fit, in order, then how many more there are.
     */
    static String grantsBeyond(String caller, List<String> beyond, int room) {
        if (beyond.isEmpty()) {
            return "Grants nothing beyond " + caller + ".";
        }
        String head = "Grants beyond " + caller + ": ";
        for (int shown = beyond.size(); shown >= 1; shown--) {
            String said = head + String.join(", ", beyond.subList(0, shown))
                    + (shown == beyond.size() ? "" : " … and " + (beyond.size() - shown)
                            + " more") + ".";
            if (said.length() <= room) {
                return said;
            }
        }
        String fewest = head + beyond.size() + (beyond.size() == 1 ? " grant." : " grants.");
        return fewest.length() <= room ? fewest : fewest.substring(0, Math.max(0, room));
    }

    /** The summary, and for a replace or shadow the changes, cut to what a preview may hold. */
    private static String preview(String summary, OrchestrationResolver.Trial trial, String text,
            String path) {
        StringBuilder preview = new StringBuilder(summary);
        OrchestrationDefinition before = trial.replaces() != null ? trial.replaces()
                : trial.shadows();
        if (before != null) {
            preview.append("\n\nChanges:\n").append(diff(before.source(), text));
        }
        if (preview.length() <= StructuredQuestions.MOST_PREVIEW) {
            return preview.toString();
        }
        String tail = "\n… the whole draft is " + path;
        int keep = Math.max(0, StructuredQuestions.MOST_PREVIEW - tail.length());
        String cut = preview.substring(0, keep) + tail;
        return cut.length() <= StructuredQuestions.MOST_PREVIEW ? cut
                : cut.substring(0, StructuredQuestions.MOST_PREVIEW);
    }

    /** Old lines missing from the new as {@code - }, then new lines missing from the old as
     *  {@code + }, each in order. */
    private static String diff(String before, String after) {
        List<String> old = before.lines().toList();
        List<String> now = after.lines().toList();
        Set<String> oldSet = Set.copyOf(old);
        Set<String> nowSet = Set.copyOf(now);
        StringBuilder diff = new StringBuilder();
        old.stream().filter(line -> !nowSet.contains(line))
                .forEach(line -> diff.append("- ").append(line).append('\n'));
        now.stream().filter(line -> !oldSet.contains(line))
                .forEach(line -> diff.append("+ ").append(line).append('\n'));
        return diff.isEmpty() ? "(no line changes)" : diff.substring(0, diff.length() - 1);
    }

    // --- the answer ------------------------------------------------------------------------------

    /**
     * Whether the person chose to install: the structured answer chose exactly {@code Install}, or
     * the words — the answer's text, or the words given in place of a choice — are {@code install},
     * {@code yes} or {@code y}.
     */
    private static boolean chosenInstall(OrchestrationMessage answer) {
        JsonNode choice = tree(answer.structure()).path("choices").path(0);
        JsonNode chosen = choice.path("chosen");
        if (chosen.isArray() && chosen.size() == 1 && INSTALL.equals(chosen.get(0).asText())) {
            return true;
        }
        if (chosen.isArray() && !chosen.isEmpty()) {
            return false;
        }
        return installs(answer.text()) || installs(choice.path("other").asText(null));
    }

    /**
     * The person's own words on an answer: each choice's words beside or in place of an option
     * and its note, then the note beside them all — or a plain answer's text. Words that only
     * choose ({@code yes}, an option's label) say nothing more.
     */
    private static List<String> wordsOf(OrchestrationMessage answer) {
        List<String> words = new ArrayList<>();
        String text = answer.text() == null ? "" : answer.text();
        JsonNode choices = tree(answer.structure()).path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            if (!onlyChooses(text)) {
                words.add(text.strip());
            }
            return words;
        }
        List<StructuredAnswers.Choice> read = new ArrayList<>();
        for (JsonNode node : choices) {
            List<String> chosen = new ArrayList<>();
            node.path("chosen").forEach(label -> chosen.add(label.asText()));
            String other = node.path("other").isTextual() ? node.path("other").asText() : null;
            String note = node.path("note").isTextual() ? node.path("note").asText() : null;
            read.add(new StructuredAnswers.Choice(node.path("header").asText(""), chosen, other,
                    note));
            if (other != null && !(chosen.isEmpty() && onlyChooses(other))) {
                words.add(other);
            }
            if (note != null) {
                words.add(note);
            }
        }
        // THE NOTE BESIDE THEM ALL is kept only in the answer's text, after what render wrote.
        String also = StructuredAnswers.render(read, null) + "\nAlso: ";
        if (text.startsWith(also) && text.length() > also.length()) {
            words.add(text.substring(also.length()));
        }
        return words;
    }

    private static boolean onlyChooses(String words) {
        String said = words == null ? "" : words.strip();
        return said.isEmpty() || installs(said) || said.equalsIgnoreCase(INSTALL)
                || said.equalsIgnoreCase(DONT_INSTALL);
    }

    private static boolean installs(String words) {
        return words != null && WORDS_THAT_INSTALL.contains(words.strip().toLowerCase(Locale.ROOT));
    }

    /** A stored structure as JSON; a missing or unreadable one is an empty object. */
    private static JsonNode tree(String structure) {
        if (structure == null) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode read = JSON.readTree(structure);
            return read == null ? JSON.createObjectNode() : read;
        } catch (JsonProcessingException unreadable) {
            return JSON.createObjectNode();
        }
    }

    /** {@code text}'s digest as a loaded definition's hash reads: {@code sha256:} and hex. */
    static String digest(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this JVM has no SHA-256, which every Java SE has",
                    impossible);
        }
    }

    /** What a failure says: its message, or what it is when it has none. */
    private static String why(RuntimeException failed) {
        return failed.getMessage() == null ? failed.toString() : failed.getMessage();
    }

    /** {@code sentence} ending in exactly one full stop. */
    private static String stop(String sentence) {
        String stripped = sentence.strip();
        return stripped.endsWith(".") ? stripped : stripped + ".";
    }
}
