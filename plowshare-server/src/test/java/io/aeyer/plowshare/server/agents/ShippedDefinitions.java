package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The two shipped definition directories, read as the one set the server reads.
 *
 * <h2>Why this exists, and the failure that produced it</h2>
 *
 * <p>{@code agents/} and {@code bots/} are <b>one set at the same tier</b> —
 * not layers, so a name in both is a mistake rather than an override — and the
 * server validates them together. Two tests were reading them <em>apart</em>:
 * {@code AristoxenusDefinitionTest} loaded only {@code bots/}, and {@code
 * ModelSurfaceTest} loaded each directory in its own call and merged the maps
 * afterwards.
 *
 * <p>That was invisible while the shipped bot called nothing. The moment {@code
 * aristoxenus.md} declared {@code calls: [code_reviewer, image_reader,
 * close_reader]}, ten tests failed with
 *
 * <pre>the agent 'aristoxenus' calls 'code_reviewer', which no file in this
 * directory defines … The agents defined are [aristoxenus]</pre>
 *
 * <p>and the agent it names is filed one directory over. <b>The definition was
 * right and the fixtures were narrower than the thing they were testing</b>:
 * {@link AgentRegistry#load} validates callees per directory with every name
 * required, so a cross-directory call cannot survive a single-directory read,
 * however the maps are merged afterwards.
 *
 * <h2>Copied into one directory rather than layered</h2>
 *
 * <p>{@code LayeredDefinitions} exists and is the wrong instrument: it resolves
 * a name from the most specific source that has it, which is override
 * semantics, and these two directories do not override each other. Copying both
 * into one place reproduces exactly what the server sees — and {@link
 * #asOneSet} fails on a name that appears in both, which is the property the
 * merge in {@code ModelSurfaceTest} was relying on the registry to catch.
 */
final class ShippedDefinitions {

    /** Both directories, as the build lays them out. */
    static final List<Path> DIRECTORIES = List.of(
            Path.of("src/main/resources/agents"),
            Path.of("src/main/resources/bots"));

    private ShippedDefinitions() {
    }

    /**
     * Every shipped definition, copied into a directory of this method's own.
     *
     * <p>For the callers that are static and have no {@code @TempDir} to hand.
     * The directory is deleted on exit rather than by a lifecycle hook, because
     * the whole point is that it can be built from a static initialiser.
     */
    static Path asOneSet() {
        try {
            Path into = Files.createTempDirectory("shipped-definitions");
            into.toFile().deleteOnExit();
            return asOneSet(into);
        } catch (IOException notWritten) {
            throw new UncheckedIOException(notWritten);
        }
    }

    /**
     * Every shipped definition, copied into {@code into}, which is returned.
     *
     * @param into an empty directory, normally a {@code @TempDir}
     * @throws IOException if a definition could not be read or written
     */
    static Path asOneSet(Path into) throws IOException {
        Files.createDirectories(into);
        for (Path directory : DIRECTORIES) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(each -> each.toString().endsWith(".md")).toList()) {
                    Path landing = into.resolve(file.getFileName());
                    // A NAME IN BOTH IS A MISTAKE, not an override, and this is
                    // where that is caught now. Silently overwriting would hide
                    // exactly the collision the two directories are supposed to
                    // be free of.
                    assertFalse(Files.exists(landing),
                            file.getFileName() + " is filed in both " + DIRECTORIES.get(0)
                                    + " and " + DIRECTORIES.get(1) + "; the two are one set at"
                                    + " the same tier, so a name in both is a mistake rather"
                                    + " than an override");
                    Files.copy(file, landing);
                }
            }
        }
        return into;
    }
}
