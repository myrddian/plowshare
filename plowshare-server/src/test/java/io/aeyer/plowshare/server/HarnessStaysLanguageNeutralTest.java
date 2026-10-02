package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The harness assumes no language. A shipped agent or orchestration prompt may show a command as
 * an example, but a paragraph that names one ecosystem's tooling names at least one other beside
 * it: a single example is the one a model copies, in a JS or Rust project as readily as in a Python
 * one. Measured 2026-09-29: the acceptance paragraphs gave {@code python -m pytest} as their only
 * example, and the check's tool example was {@code ["python", "-m", "pytest", "-q"]}; the person's
 * words, "it could of been JS or typesafe and that would no longer be true".
 */
class HarnessStaysLanguageNeutralTest {

    private static final List<Path> PROMPTS = List.of(
            Path.of("src/main/resources/agents"), Path.of("src/main/resources/orchestrations"));

    /** Each ecosystem by the tooling a prompt might name for it. */
    private static final Map<String, Pattern> ECOSYSTEMS = new LinkedHashMap<>();

    static {
        ECOSYSTEMS.put("python", word("python|python3|pytest|pip|pygame|venv"));
        // Not a bare "node": the prompts speak of an inference node.
        ECOSYSTEMS.put("javascript", word("npm|npx|node -e|vitest|jest|pnpm|yarn"));
        ECOSYSTEMS.put("rust", word("cargo"));
        ECOSYSTEMS.put("go", Pattern.compile("\\bgo (?:test|run|build)\\b"));
        ECOSYSTEMS.put("jvm", word("gradle|gradlew|mvn|maven"));
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }

    /** A prompt file without its front matter, the part between its opening {@code ---} lines. */
    private static String body(String text) {
        if (!text.startsWith("---")) {
            return text;
        }
        int end = text.indexOf("\n---", 3);
        return end < 0 ? text : text.substring(end + 4);
    }

    @Test
    void a_paragraph_naming_one_ecosystem_names_another_beside_it() throws IOException {
        List<String> alone = new ArrayList<>();
        for (Path directory : PROMPTS) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(each -> each.toString().endsWith(".md")).sorted()
                        .toList()) {
                    // The front matter's own comments are notes for whoever edits the file and
                    // are never sent to a model; everything after it is.
                    String[] paragraphs = body(Files.readString(file)).split("\\n\\s*\\n");
                    for (String paragraph : paragraphs) {
                        String lower = paragraph.toLowerCase(Locale.ROOT);
                        List<String> named = ECOSYSTEMS.entrySet().stream()
                                .filter(each -> each.getValue().matcher(lower).find())
                                .map(Map.Entry::getKey).toList();
                        if (named.size() == 1) {
                            alone.add(file.getFileName() + " names only " + named.get(0) + ": "
                                    + paragraph.strip().lines().findFirst().orElse(""));
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), alone);
    }

    /**
     * What the harness itself says — a refusal, a notice, a tool's example — names no ecosystem at
     * all: a Java string literal outside a comment. The check's tool example was the one found.
     */
    @Test
    void no_harness_string_names_an_ecosystem() throws IOException {
        Pattern literal = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
        List<String> named = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(each -> each.toString().endsWith(".java")).sorted()
                    .toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int n = 0; n < lines.size(); n++) {
                    String line = lines.get(n).strip();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    int at = n + 1;
                    var found = literal.matcher(line);
                    while (found.find()) {
                        String text = found.group().toLowerCase(Locale.ROOT);
                        ECOSYSTEMS.forEach((ecosystem, pattern) -> {
                            if (pattern.matcher(text).find()) {
                                named.add(file.getFileName() + ":" + at + " names " + ecosystem
                                        + ": " + text);
                            }
                        });
                    }
                }
            }
        }
        assertEquals(List.of(), named);
    }
}
