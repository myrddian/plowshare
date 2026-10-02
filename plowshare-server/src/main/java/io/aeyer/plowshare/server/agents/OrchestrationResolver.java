package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.agents.DefinitionResolver.Caller;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.SessionCloseListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which orchestrations a caller can reach: {@link DefinitionResolver}'s tiers for a third kind of
 * file. Most specific first — the project's {@code orchestrations/}, then a rooting session's
 * {@code .plowshare/orchestrations}, then the boot set (the global directory over the classpath).
 *
 * <p><b>Validity depends on the tier's agents</b> — a conductor's callees must be agents the tier
 * serves — so the cache is keyed on the directory's fingerprint <em>and</em> on the identity of the
 * agent registry {@code agentsFor} answered with. {@code DefinitionResolver} hands back the same
 * instance until its own tier changes, so an agent edit rebuilds this tier and nothing else does.
 *
 * <p><b>A client's files are not in the fingerprint</b>, exactly as they are not in {@code
 * DefinitionResolver}'s: an edit on the laptop is seen when the session reconnects (TODO §19).
 */
public final class OrchestrationResolver implements SessionCloseListener {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationResolver.class);

    private record Key(Long projectId, String sessionId) {}

    private record Cached(String stamp, AgentRegistry agents, OrchestrationRegistry.Loaded tier,
            Map<String, OrchestrationDefinition> merged) {}

    /** The refusal key a tier that could not be read at all is reported under. */
    static final String BROKEN_TIER = "(the project tier)";

    private final OrchestrationRegistry.Loaded bootSet;
    private final DataLayout data;
    private final LongPredicate projectExists;
    private final Set<String> knownTools;
    private final SessionChannel channel;
    private final Predicate<String> sessionLive;
    private final BiPredicate<Long, String> sessionRoots;
    private final Function<Caller, AgentRegistry> agentsFor;
    private final DefinitionChecks checks;
    private final Map<Key, Cached> byProject = new ConcurrentHashMap<>();

    public OrchestrationResolver(OrchestrationRegistry.Loaded bootSet, DataLayout data,
            LongPredicate projectExists, Set<String> knownTools, SessionChannel channel,
            Predicate<String> sessionLive, BiPredicate<Long, String> sessionRoots,
            Function<Caller, AgentRegistry> agentsFor, DefinitionChecks checks) {
        this.bootSet = Objects.requireNonNull(bootSet, "bootSet");
        this.data = Objects.requireNonNull(data, "data");
        this.projectExists = Objects.requireNonNull(projectExists, "projectExists");
        this.knownTools = Set.copyOf(knownTools);
        this.channel = Objects.requireNonNull(channel, "channel");
        this.sessionLive = Objects.requireNonNull(sessionLive, "sessionLive");
        this.sessionRoots = Objects.requireNonNull(sessionRoots, "sessionRoots");
        this.agentsFor = Objects.requireNonNull(agentsFor, "agentsFor");
        this.checks = Objects.requireNonNull(checks, "checks");
    }

    public Map<String, OrchestrationDefinition> bootSet() {
        return bootSet.enabled();
    }

    public Map<String, OrchestrationDefinition> forCaller(Caller caller) {
        return resolve(caller).map(Cached::merged).orElse(bootSet.enabled());
    }

    public Optional<OrchestrationDefinition> find(Caller caller, String name) {
        return Optional.ofNullable(forCaller(caller).get(name));
    }

    /** Every file that is not offered to this caller, and why; a project's refusal wins by name. */
    public Map<String, String> refusalsFor(Caller caller) {
        Map<String, String> refused = new LinkedHashMap<>(bootSet.disabled());
        resolve(caller).ifPresent(cached -> refused.putAll(cached.tier().disabled()));
        return Map.copyOf(refused);
    }

    public void invalidate(Long projectId) {
        byProject.keySet().removeIf(key -> Objects.equals(projectId, key.projectId()));
    }

    @Override
    public void sessionClosed(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        byProject.keySet().removeIf(key -> sessionId.equals(key.sessionId()));
    }

    /** The project tier for this caller, or empty when the caller only reaches the boot set. */
    private Optional<Cached> resolve(Caller caller) {
        Objects.requireNonNull(caller, "caller");
        Long projectId = caller.projectId();
        if (projectId == null || !projectExists.test(projectId) || !data.keepsAnything()) {
            return Optional.empty();
        }
        Key key = keyFor(caller);
        AgentRegistry agents = agentsFor.apply(caller);
        String stamp = DefinitionResolver.fingerprint(data.orchestrationsFor(projectId));
        Cached cached = byProject.get(key);
        if (cached != null && cached.stamp().equals(stamp) && cached.agents() == agents) {
            return Optional.of(cached);
        }
        List<OrchestrationRegistry.Layer> layers = new ArrayList<>();
        layers.add(new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT,
                new FilesystemDefinitions(data.orchestrationsFor(projectId), true)));
        if (key.sessionId() != null) {
            layers.add(new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SESSION,
                    ChannelDefinitions.orchestrations(channel, key.sessionId())));
        }
        String tierDescribe = layers.stream().map(l -> l.source().describe())
                .collect(Collectors.joining(", then "));
        Cached built;
        try {
            built = build(stamp, agents,
                    OrchestrationRegistry.read(List.copyOf(layers), knownTools, agents, checks));
        } catch (RuntimeException brokenTier) {
            // Cached like DefinitionResolver's own fallback: warned once per stamp and agents,
            // and rebuilt when either changes.
            String warning = "The orchestrations of " + tierDescribe + " could not be read, so"
                    + " only the boot set is offered there: " + brokenTier.getMessage();
            log.warn("{}", warning);
            built = new Cached(stamp, agents, new OrchestrationRegistry.Loaded(Map.of(),
                    Map.of(BROKEN_TIER, warning)), bootSet.enabled());
        }
        byProject.put(key, built);
        if (key.sessionId() != null && !sessionLive.test(key.sessionId())) {
            byProject.remove(key);
        }
        return Optional.of(built);
    }

    /**
     * The tier over the boot set. A boot definition was checked against the boot agents and the
     * boot orchestrations, and this caller's may shadow either a callee or a grant, so each is
     * re-asked {@link OrchestrationRegistry#calleeRefusal} against {@code agents} and {@link
     * OrchestrationRegistry#grantEscalationRefusal} against {@code reachable}; one that fails
     * either is dropped with its reason, unless a project file of the same name is enabled and
     * shadows it anyway. {@link DefinitionChecks} are not re-run: the model and sampling do not
     * depend on the tier.
     *
     * <p><b>The grant check runs in both directions, and neither tier's own read can stand in for
     * it.</b> {@code OrchestrationRegistry.read} asks it over the map of the read it is in — the
     * boot read over the boot set, the project read over the project and session layers — and a
     * grant to a name absent from that map is skipped rather than refused. So a project or session
     * conductor granting a <em>boot</em> orchestration is checked nowhere but here: its own read
     * never saw the grantee, and {@code startNested} makes no grant check at run time. Every entry
     * of the project tier is therefore re-asked against {@code reachable} too, and a granter that
     * escalates is disabled with the same sentence a load-time refusal carries — otherwise a
     * lower-trust tier could widen its reach by granting a wider boot run, which is the one thing
     * spec §2.1's rule exists to stop.
     *
     * <p><b>Dropping a shadowing project file un-shadows the boot file of its name, and that is
     * what the next grant must be judged against.</b> {@code reachable} is keyed by name, so a
     * project entry has overwritten boot's; removing the key outright would both hide a boot
     * definition that is about to be offered and, worse, leave every granter of that name judged
     * against the <em>narrower</em> file — the wide boot one is then offered under it, grant and
     * all. So a dropped entry puts boot's definition back, and the check runs to a fixed point:
     * a granter already judged is judged again, because what its grant resolves to has changed.
     * Each pass that changes anything has removed an entry from a finite map, so it terminates, and
     * a name once refused is never re-enabled — {@code OrchestrationRegistry.read}'s own rule.
     *
     * <p>The reverse — {@code reachable} holding a boot definition the loop below then refuses for
     * its own callees — can only make this check stricter than what is offered, never laxer, so it
     * is left alone: a granter of a file nobody is offered cannot start it either way.
     */
    private Cached build(String stamp, AgentRegistry agents, OrchestrationRegistry.Loaded loaded) {
        Map<String, OrchestrationDefinition> merged = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>(loaded.disabled());
        // What this caller can reach, project shadowing boot by name — the same shape `merged`
        // ends up with — so a boot conductor's own `orchestrations:` grant is resolved against
        // what is actually offered here, not just the boot set it was originally checked in.
        Map<String, OrchestrationDefinition> reachable = new LinkedHashMap<>(bootSet.enabled());
        reachable.putAll(loaded.enabled());
        Map<String, OrchestrationDefinition> tier = new LinkedHashMap<>(loaded.enabled());
        boolean judgeAgain = true;
        while (judgeAgain) {
            judgeAgain = false;
            for (Map.Entry<String, OrchestrationDefinition> own
                    : new LinkedHashMap<>(tier).entrySet()) {
                Optional<String> refusal = OrchestrationRegistry.grantEscalationRefusal(
                        own.getValue(), reachable);
                if (refusal.isEmpty()) {
                    continue;
                }
                tier.remove(own.getKey());
                OrchestrationDefinition unshadowed = bootSet.enabled().get(own.getKey());
                if (unshadowed == null) {
                    reachable.remove(own.getKey());
                } else {
                    reachable.put(own.getKey(), unshadowed);
                }
                refused.put(own.getKey(), refusal.get());
                judgeAgain = true;
            }
        }
        for (Map.Entry<String, OrchestrationDefinition> boot : bootSet.enabled().entrySet()) {
            if (tier.containsKey(boot.getKey())) {
                continue;
            }
            Optional<String> refusal = OrchestrationRegistry.calleeRefusal(boot.getValue(), agents)
                    .or(() -> OrchestrationRegistry.grantEscalationRefusal(boot.getValue(), reachable));
            if (refusal.isPresent()) {
                // A name neither tier offers has two reasons, and half of one explains nothing: the
                // project file of this name kept its own, and the boot file that would have been
                // offered in its place adds why it is not there either.
                refused.merge(boot.getKey(), refusal.get(), (project, boots) ->
                        (project.endsWith(".") ? project : project + ".")
                                + " The boot definition of that name is not offered here either: "
                                + boots);
            } else {
                merged.put(boot.getKey(), boot.getValue());
            }
        }
        merged.putAll(tier);
        return new Cached(stamp, agents, new OrchestrationRegistry.Loaded(tier, refused),
                Map.copyOf(merged));
    }

    private Key keyFor(Caller caller) {
        String sessionId = caller.sessionId();
        if (sessionId == null || !sessionLive.test(sessionId)
                || !sessionRoots.test(caller.projectId(), sessionId)) {
            return new Key(caller.projectId(), null);
        }
        return new Key(caller.projectId(), sessionId);
    }

    /**
     * A draft, as this caller's project tier would load it with the draft in place — spec
     * 2026-09-29-orchestration-studio §3.3.
     *
     * @param draft the draft as it would load, or null when refused
     * @param refusal why the draft would not load, or null
     * @param newlyRefused every other name the draft would disable, and why — a cycle through it,
     *     a grant of it that now escalates
     * @param replaces the project-tier file of this name, or null
     * @param shadows the global or shipped definition of this name the draft would hide, or null
     * @param hidesSession whether the caller's own {@code .plowshare} tier has this name, which a
     *     project file hides (the project layer is read first)
     * @param reachable what the caller would reach with the draft in place
     * @param replacesBroken whether a project file of this name exists that does not load — an
     *     install still replaces it
     */
    public record Trial(OrchestrationDefinition draft, String refusal,
            Map<String, String> newlyRefused, OrchestrationDefinition replaces,
            OrchestrationDefinition shadows, boolean hidesSession,
            Map<String, OrchestrationDefinition> reachable, boolean replacesBroken) {}

    /**
     * Trial {@code text} as {@code projects/<id>/orchestrations/<name>.md}: the same layers {@link
     * #resolve} reads and the same {@link #build}, once without the draft and once with it, quietly
     * and uncached. Nothing is written and nothing this resolver serves changes.
     *
     * @throws IllegalArgumentException when the caller has no project this server keeps
     */
    public Trial trial(Caller caller, String name, String text) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(text, "text");
        Long projectId = caller.projectId();
        if (projectId == null || !projectExists.test(projectId) || !data.keepsAnything()) {
            throw new IllegalArgumentException("This run is in no project, so there is no project"
                    + " tier to install into.");
        }
        Key key = keyFor(caller);
        AgentRegistry agents = agentsFor.apply(caller);
        DefinitionSource project = new FilesystemDefinitions(data.orchestrationsFor(projectId), true);
        DefinitionSource session = key.sessionId() == null ? null
                : ChannelDefinitions.orchestrations(channel, key.sessionId());
        Cached before = build("trial", agents, OrchestrationRegistry.read(
                layers(project, session), knownTools, agents, checks, true));
        DefinitionSource withDraft = withDraft(project, name, text);
        Cached after = build("trial", agents, OrchestrationRegistry.read(
                layers(withDraft, session), knownTools, agents, checks, true));
        Map<String, String> newly = new LinkedHashMap<>();
        after.tier().disabled().forEach((other, why) -> {
            if (!other.equals(name) && !why.equals(before.tier().disabled().get(other))) {
                newly.put(other, why);
            }
        });
        OrchestrationDefinition drafted = after.tier().enabled().get(name);
        // ONLY A PROJECT FILE IS REPLACED: the tier read also holds the session layer, whose file
        // of this name an install hides rather than replaces.
        OrchestrationDefinition loaded = before.tier().enabled().get(name);
        OrchestrationDefinition replaced = loaded != null
                && loaded.tier() == OrchestrationDefinition.Tier.PROJECT ? loaded : null;
        boolean onDisk = project.list().stream().anyMatch(each -> each.name().equals(name));
        OrchestrationDefinition shadowed = bootSet.enabled().get(name);
        boolean hidesSession = session != null
                && session.list().stream().anyMatch(each -> each.name().equals(name));
        return new Trial(drafted, drafted == null ? after.tier().disabled().get(name) : null,
                Collections.unmodifiableMap(newly), replaced, shadowed, hidesSession,
                after.merged(), replaced == null && onDisk);
    }

    private static List<OrchestrationRegistry.Layer> layers(DefinitionSource project,
            DefinitionSource session) {
        List<OrchestrationRegistry.Layer> layers = new ArrayList<>();
        layers.add(new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.PROJECT, project));
        if (session != null) {
            layers.add(new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SESSION, session));
        }
        return List.copyOf(layers);
    }

    /** The project tier with the draft in the name's place, read first so it wins the name. */
    private static DefinitionSource withDraft(DefinitionSource project, String name, String text) {
        return new DefinitionSource() {
            @Override
            public String describe() {
                return "the draft " + name + " over " + project.describe();
            }

            @Override
            public List<Definition> list() {
                List<Definition> listed = new ArrayList<>();
                listed.add(new Definition(name, "draft " + name, text));
                project.list().stream().filter(each -> !each.name().equals(name))
                        .forEach(listed::add);
                return List.copyOf(listed);
            }
        };
    }
}
