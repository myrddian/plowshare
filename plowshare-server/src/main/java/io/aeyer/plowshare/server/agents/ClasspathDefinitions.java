package io.aeyer.plowshare.server.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The shipped definitions, from inside the jar.
 *
 * <p>This is the floor of the chain and the reason there is no longer a server
 * with no agents at all: {@code AgentsConfig.agentRegistry} used to return
 * {@code null} when a directory was absent, which left every write filed flat.
 * A jar always has these.
 */
public final class ClasspathDefinitions implements DefinitionSource {

    /** Where the reference agents live. */
    public static final String SHIPPED = "agents";

    /**
     * Where a shipped bot lives, and it is read for the reason {@code
     * AgentsConfig} layers both {@code global/} directories rather than one.
     *
     * <p>A deployment always has this jar and may have nothing else, so spec
     * §2.2's "a default bot ships in the resource bundle, so a fresh deployment
     * has somebody to talk to before anyone writes a definition" is a claim
     * about exactly this scan.
     *
     * <p><b>Scanning two locations is not the loader reading a directory.</b>
     * What a definition <em>is</em> stays {@code bot:} in its frontmatter — a
     * bot filed under {@code agents/} loads as one and an agent filed here loads
     * as an agent — and this decides only where in the jar to look, which is the
     * filing convenience {@code DataLayout.botsFor} describes.
     */
    public static final String SHIPPED_BOTS = "bots";

    /** Where shipped orchestrations would sit. Read by its own source, never by the agents' default. */
    public static final String SHIPPED_ORCHESTRATIONS = "orchestrations";

    private final List<String> locations;

    public ClasspathDefinitions() {
        this(List.of(SHIPPED, SHIPPED_BOTS));
    }

    public ClasspathDefinitions(String location) {
        this(List.of(Objects.requireNonNull(location, "location")));
    }

    public ClasspathDefinitions(List<String> locations) {
        this.locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
    }

    @Override
    public String describe() {
        return "the shipped definitions";
    }

    @Override
    public List<Definition> list() {
        List<Definition> definitions = new ArrayList<>();
        for (String location : locations) {
            scan(location, definitions);
        }
        definitions.sort(Comparator.comparing(Definition::name));
        return List.copyOf(definitions);
    }

    /**
     * One location's definitions, appended to {@code definitions}.
     *
     * <p><b>Nothing here refuses a name found in two locations</b>, and that is
     * the opposite judgement from {@code AgentsConfig.requireNoClash} for a
     * reason rather than by omission: those two directories are filled in by
     * hand by an operator, and these two ship inside a jar nobody can add to
     * without a build. A name defined twice in this repository is a fault of
     * this repository, and {@code AgentRegistry}'s own reading is where it
     * surfaces.
     */
    private void scan(String location, List<Definition> definitions) {
        // `classpath*:` and not `classpath:` -- the starred form scans every
        // classpath root rather than stopping at the first that matches, which
        // is what makes this work from a jar as well as from an exploded
        // build/resources/main.
        //
        // The glob is `/*` and not `/*.md`: Ant-style resource patterns match
        // case-sensitively, with no bracket-class escape for it, so a pattern
        // ending `.md` would silently skip `AGENT.MD`. That is exactly the
        // "skipped without a word" failure a `.md` file must never suffer --
        // FilesystemDefinitions refuses to let a directory on disk do it, and
        // a definition shipped in the jar is not a different kind of thing.
        // So every file is listed and the extension is filtered below,
        // case-insensitively, the same way FilesystemDefinitions does it.
        String pattern = "classpath*:" + location + "/*";
        Resource[] found;
        try {
            found = new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException scanningFailed) {
            // A genuine I/O error during classpath scanning (e.g., corrupted jar
            // directory listing) must surface as an error, not silently become
            // "nothing found". Wrap it in UncheckedIOException like we do for
            // per-resource read failures below, so callers in places that cannot
            // carry checked exceptions still see the failure.
            throw new UncheckedIOException(
                    "Scanning the classpath for definitions at " + location + " failed.",
                    scanningFailed);
        }

        for (Resource resource : found) {
            String filename = resource.getFilename();
            if (filename == null || (!filename.toLowerCase(Locale.ROOT).endsWith(".md")
                    && !(location.equals(SHIPPED_ORCHESTRATIONS) && filename.toLowerCase(Locale.ROOT).endsWith(".js")))) {
                continue;
            }
            // NFC, for the same reason FilesystemDefinitions normalises its
            // name: AgentRegistry.read's parse-failure path uses this name,
            // unmodified, as the identity tested against `required`, and a
            // source that handed back an un-normalised name would carry the
            // same latent hazard onto the jar that a raw filename carries on
            // disk.
            String name = Normalizer.normalize(
                    filename.substring(0, filename.length() - ".md".length()),
                    Normalizer.Form.NFC);
            String text;
            try (var in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                // A resource the resolver listed and cannot open is a broken
                // jar, not a bad definition, and it must not be reported as
                // though an operator wrote it wrong.
                throw new UncheckedIOException(
                        "The shipped definition `" + name + "` was listed and could not be read."
                                + " This jar is damaged.", unreadable);
            }
            definitions.add(new Definition(name, "the shipped definition `" + name + "`", text));
        }
    }
}
