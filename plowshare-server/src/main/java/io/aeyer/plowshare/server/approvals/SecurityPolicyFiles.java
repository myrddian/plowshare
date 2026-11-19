package io.aeyer.plowshare.server.approvals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Loads one explicitly rooted security document and its declared fragments. */
public final class SecurityPolicyFiles {
  public static final String MAIN = "security.md";
  public static final String DIRECTORY = "security";
  public static final int MAX_BYTES = 64 * 1024;
  private static final int MAX_INCLUDES = 16;
  private static final ObjectMapper JSON = FileApprovalPolicyRepository.strictMapper();

  /** Fixed-root reads distinguish ordinary absence from malformed or unavailable policy. */
  @FunctionalInterface
  public interface Source {
    Optional<String> read(String relative);
  }

  private record Header(int version, List<ApprovalPolicy.Rule> rules, List<String> includes) {}

  private record WorkspaceHeader(int version, List<String> includes) {}

  private record Document(String frontmatter, String body) {}

  private SecurityPolicyFiles() {}

  /** Uses a registered directory, refusing links below it, including the fragment directory. */
  public static Source directory(Path root) {
    Objects.requireNonNull(root);
    return relative -> {
      Path name = Path.of(relative);
      if (name.isAbsolute() || !name.equals(name.normalize()) || name.startsWith(".."))
        throw new IllegalArgumentException(
            "Security files must be relative to the configuration root");
      Path path = root;
      for (Path part : name) {
        path = path.resolve(part);
        if (Files.isSymbolicLink(path))
          throw new IllegalStateException("Security policy files cannot use symbolic links");
      }
      try {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Security policy must be a regular file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
          return Optional.of(readText(input));
        }
      } catch (IOException failure) {
        throw new IllegalStateException("Security policy file could not be read", failure);
      }
    };
  }

  /** An absent deployment file uses the shipped defaults. A malformed override fails startup. */
  static DeploymentSecurityPolicy deployment(Source override) {
    return load(override).orElseGet(() -> load(SecurityPolicyFiles::packaged).orElseThrow());
  }

  private static Optional<DeploymentSecurityPolicy> load(Source source) {
    return source.read(MAIN).map(text -> rules(text, source));
  }

  /**
   * Workspace Markdown is reviewer evidence. Only the deployment format can define rule effects.
   */
  public static String workspace(Source source) {
    return source.read(MAIN).map(text -> workspace(text, source)).orElse("");
  }

  private static String workspace(String text, Source source) {
    Document document = document(text);
    var guidance = new StringBuilder(document.body());
    if (document.frontmatter() != null) {
      var header = parse(document, WorkspaceHeader.class);
      validateHeader(header.version(), header.includes());
      for (String name : filenames(header.includes(), "md")) {
        guidance.append("\n\n").append(fragment(source, name));
        if (guidance.length() > 16000)
          throw new IllegalArgumentException(
              "Workspace security guidance exceeds 16000 characters");
      }
    }
    if (guidance.length() > 16000)
      throw new IllegalArgumentException("Workspace security guidance exceeds 16000 characters");
    return guidance.toString().strip();
  }

  private static DeploymentSecurityPolicy rules(String text, Source source) {
    Document document = document(text);
    if (document.frontmatter() == null)
      throw new IllegalArgumentException("Deployment security policy needs YAML frontmatter");
    Header header = parse(document, Header.class);
    validateHeader(header.version(), header.includes());
    var rules = new ArrayList<>(Objects.requireNonNull(header.rules()));
    var guidance = new StringBuilder(document.body());
    for (String name : filenames(header.includes(), "(?:json|md)")) {
      String content = fragment(source, name);
      if (name.endsWith(".md")) {
        guidance.append("\n\n").append(content);
        if (guidance.length() > 16000)
          throw new IllegalArgumentException(
              "Deployment security guidance exceeds 16000 characters");
        continue;
      }
      try {
        var fragment = JSON.readValue(content, new TypeReference<List<ApprovalPolicy.Rule>>() {});
        if (fragment == null) throw new IOException("Security fragment must be a rule array");
        rules.addAll(fragment);
      } catch (IOException failure) {
        throw new IllegalArgumentException("Invalid security rules fragment", failure);
      }
    }
    // The same rule bounds and duplicate checks apply to all rule sources.
    var policy = new ApprovalPolicy(0, ApprovalPolicy.Mode.MANUAL, null, rules);
    return new DeploymentSecurityPolicy(policy.rules(), guidance.toString().strip());
  }

  private static Document document(String text) {
    checkSize(text);
    var lines = text.lines().toList();
    if (lines.isEmpty() || !lines.getFirst().equals("---")) return new Document(null, text.strip());
    int end = lines.subList(1, lines.size()).indexOf("---") + 1;
    if (end == 0) throw new IllegalArgumentException("Security policy frontmatter is not closed");
    return new Document(
        String.join("\n", lines.subList(1, end)),
        String.join("\n", lines.subList(end + 1, lines.size())).strip());
  }

  private static <T> T parse(Document document, Class<T> type) {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(12);
    var yaml = new Yaml(new SafeConstructor(options));
    var header = JSON.convertValue(yaml.load(document.frontmatter()), type);
    if (header == null) throw new IllegalArgumentException("Security policy frontmatter is empty");
    return header;
  }

  private static void validateHeader(int version, List<String> includes) {
    if (version != 1 || includes == null || includes.size() > MAX_INCLUDES)
      throw new IllegalArgumentException("Invalid security policy version or fragment list");
  }

  private static List<String> filenames(List<String> includes, String extension) {
    var seen = new HashSet<String>();
    for (String name : includes) {
      if (name == null
          || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\\." + extension)
          || !seen.add(name))
        throw new IllegalArgumentException("Security fragments must have unique local filenames");
    }
    return List.copyOf(includes);
  }

  private static String fragment(Source source, String name) {
    String content =
        source
            .read(DIRECTORY + "/" + name)
            .orElseThrow(
                () -> new IllegalArgumentException("A declared security fragment is missing"));
    checkSize(content);
    return content;
  }

  private static Optional<String> packaged(String path) {
    try (var input =
        SecurityPolicyFiles.class.getResourceAsStream("/deployment-security/" + path)) {
      return input == null ? Optional.empty() : Optional.of(readText(input));
    } catch (IOException failure) {
      throw new IllegalStateException("Shipped security policy could not be read", failure);
    }
  }

  private static String readText(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw new IOException("Security policy file exceeds 64 KiB");
    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
  }

  private static void checkSize(String text) {
    if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException("Security policy file exceeds 64 KiB");
  }
}
