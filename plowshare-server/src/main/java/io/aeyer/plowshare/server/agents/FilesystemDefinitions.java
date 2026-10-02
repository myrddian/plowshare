package io.aeyer.plowshare.server.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Definitions in a directory on this server's own disk.
 *
 * <p><b>An absent directory lists nothing rather than throwing</b>, which is the
 * asymmetry {@code AgentsProperties} already states and this class inherits: a
 * directory that is not there is a tier nobody configured, and a tier nobody
 * configured is not an error. A path that exists and is not a directory IS one,
 * because it is a configuration mistake rather than an absence.
 */
public final class FilesystemDefinitions implements DefinitionSource {

    private final Path dir;
    private final boolean scripts;

    public FilesystemDefinitions(Path dir) {
        this(dir, false);
    }

    public FilesystemDefinitions(Path dir, boolean scripts) {
        this.dir = Objects.requireNonNull(dir, "dir").toAbsolutePath();
        this.scripts = scripts;
    }

    @Override
    public String describe() {
        return dir.toString();
    }

    @Override
    public List<Definition> list() {
        if (!Files.isDirectory(dir)) {
            if (Files.exists(dir)) {
                throw new IllegalStateException(
                        dir + " exists and is not a directory. An agent is a file in a"
                                + " directory of them, so a path naming one file cannot be read"
                                + " as the set");
            }
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> entries = Files.list(dir)) {
            files = entries.filter(Files::isRegularFile).toList();
        } catch (IOException unreadable) {
            // The directory itself could not be listed -- distinct from a
            // single file inside it being unreadable, below, which names the
            // file rather than the directory.
            throw new UncheckedIOException(
                    dir + " could not be listed, so the definitions in it cannot be read.",
                    unreadable);
        }

        List<Definition> definitions = new ArrayList<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            // Case-insensitively: on a case-sensitive filesystem AGENT.MD
            // would otherwise be skipped without a word, which is the silent
            // disappearance AgentRegistry's fence check refuses to allow.
            // The suffix is fixed-length, so trimming it off the
            // original-case filename below still lands on the right name
            // whichever case it was written in.
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".md")
                    && !(scripts && filename.toLowerCase(Locale.ROOT).endsWith(".js"))) {
                continue;
            }
            // NFC, because AgentRegistry.parse compares this name to the
            // frontmatter's own `name` in NFC, and AgentRegistry.read's
            // parse-failure path uses this name, unparsed and unmodified, as
            // the identity it tests against `required` -- on a filesystem
            // that answers readdir in NFD, an un-normalised name here would
            // let a REQUIRED agent whose file failed to parse be silently
            // disabled instead of taking the boot down, which is the one
            // direction read()'s own javadoc says must not happen.
            String name = Normalizer.normalize(
                    filename.substring(0, filename.length() - ".md".length()),
                    Normalizer.Form.NFC);
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(
                        "the agent file " + file + " could not be read", unreadable);
            }
            definitions.add(new Definition(name, file.toAbsolutePath().toString(), text));
        }
        definitions.sort(Comparator.comparing(Definition::name));
        return List.copyOf(definitions);
    }
}
