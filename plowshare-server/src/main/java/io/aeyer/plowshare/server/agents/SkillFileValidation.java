package io.aeyer.plowshare.server.agents;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Pure write-time checks using the same parsers as skill discovery; never changes authored text.
 */
final class SkillFileValidation {
  private SkillFileValidation() {}

  /** A recognized skill package or legacy discovery policy within an advertised filesystem root. */
  record Target(String name) {
    boolean policy() {
      return name == null;
    }

    /** Empty means valid. A diagnostic describes how to correct the complete file. */
    String check(String content) {
      try {
        if (content.getBytes(StandardCharsets.UTF_8).length
            > ChannelDefinitions.MAX_DEFINITION_BYTES) {
          throw new IllegalArgumentException("skill file exceeds source limit");
        }
        List<String> problems =
            policy()
                ? SkillVisibility.problems(content)
                : SkillDefinition.problems(
                    new DefinitionSource.Definition(name, "SKILL.md", content));
        if (problems.isEmpty()) return "";
        StringBuilder report = new StringBuilder(header());
        for (int at = 0; at < problems.size(); at++) {
          String problem = bounded(problems.get(at));
          String detail = "\nReason: " + problem + "\nCorrection: " + correction(problem);
          if (report.length() + detail.length() > 8000) {
            report
                .append("\n")
                .append(problems.size() - at)
                .append(" additional validation failures omitted to keep the response bounded.");
            break;
          }
          report.append(detail);
        }
        return report + example();
      } catch (IllegalArgumentException | YAMLException invalid) {
        String reason = invalid.getMessage();
        if (reason == null) reason = "invalid skill source";
        // Parser errors can quote authored YAML. Keep diagnostics bounded like other tool results.
        return header()
            + "\nReason: "
            + bounded(reason)
            + "\nCorrection: "
            + correction(invalid)
            + "\nOther validation failures may remain; correct this failure before checking the remaining fields."
            + example();
      }
    }

    private String header() {
      return "Validation failed for "
          + (policy() ? "skills.yml" : "SKILL.md in skill '" + name + "'")
          + ".";
    }

    private static String bounded(String reason) {
      return reason.length() > 4000
          ? reason.substring(0, 4000) + " (diagnostic shortened)"
          : reason;
    }

    /** A stable valid scaffold, never a replacement for the authored instructions or policy. */
    String example() {
      String prefix =
          "\nValid format example (reference only; preserve your intended content and settings):\n";
      if (policy()) {
        return prefix + "```yaml\nskills:\n  example-skill:\n    agentVisible: false\n```";
      }
      String exampleName =
          name != null
                  && name.codePointCount(0, name.length()) <= 64
                  && name.matches("[\\p{L}\\p{Nd}]+(?:-[\\p{L}\\p{Nd}]+)*")
                  && name.equals(name.toLowerCase(java.util.Locale.ROOT))
              ? name
              : "example-skill";
      return prefix
          + "```markdown\n---\nname: '"
          + exampleName
          + "'\ndescription: 'Describe when to use this skill.'\n---\n"
          + "Describe the steps this skill should perform.\n```";
    }

    /**
     * Keep the parser's precise reason, including YAML locations, and explain only the failed
     * invariant. A duplicate policy entry must never elicit an unrelated frontmatter template.
     */
    private String correction(RuntimeException invalid) {
      if (invalid instanceof DuplicateKeyException) {
        return "Merge duplicate entries for the reported key into one entry, retaining the intended"
            + " value. Leave unrelated fields and instructions unchanged.";
      }
      if (invalid instanceof MarkedYAMLException) {
        return "Fix the YAML syntax at the reported line and column. Keep the existing fields and"
            + " instruction body unless the reported error requires changing them.";
      }
      String reason = invalid.getMessage();
      if (reason == null) return "Correct the rejected source before retrying.";
      return correction(reason);
    }

    private String correction(String reason) {
      return switch (reason) {
        case "SKILL.md needs YAML frontmatter" ->
            "Add YAML frontmatter before the instructions: an opening --- line, name matching"
                + " the package directory and a non-empty description, then a closing --- line.";
        case "SKILL.md has no closing frontmatter fence" ->
            "Add the missing closing --- line between the existing YAML fields and instructions.";
        case "skill frontmatter must be a mapping" ->
            "Use key: value fields between the frontmatter fences, rather than a list or scalar.";
        case "skill name must match its directory and use lowercase letters, digits and single hyphens" ->
            "Make the name field and package directory '"
                + name
                + "' identical, using lowercase letters, digits and single hyphens.";
        case "skill instructions must not be empty" ->
            "Add the skill's instructions below the existing closing frontmatter fence.";
        case "DIRECT cannot select another agent" ->
            "Remove agent when using DIRECT, or choose a delegated mode if another agent should"
                + " execute the skill.";
        case "metadata must be a string mapping", "metadata keys and values must be strings" ->
            "Use a metadata mapping with string keys and string values; quote values that YAML"
                + " would otherwise interpret as booleans or numbers.";
        case "'agentVisible' must be a boolean" ->
            "Set agentVisible to the boolean true or false, without quotes.";
        case "skills.yml must contain only a skills mapping" ->
            "Use one top-level skills mapping and place visibility entries inside it. Remove"
                + " other top-level fields.";
        case "too many skill visibility overrides" ->
            "Reduce the visibility overrides to at most "
                + ChannelDefinitions.MAX_DEFINITIONS
                + " entries.";
        case "invalid skill name in skills.yml" ->
            "Correct the visibility entry's skill name to lowercase letters, digits and single"
                + " hyphens.";
        case "skill file exceeds source limit" ->
            "Reduce the file to at most "
                + ChannelDefinitions.MAX_DEFINITION_BYTES
                + " UTF-8 bytes; move supporting material into package resources.";
        default -> {
          if (reason.startsWith("unsupported skill field '")) {
            yield "Remove the reported unsupported field from the existing frontmatter.";
          }
          if (reason.startsWith("skill needs '")) {
            yield "Add the required field named above to the existing frontmatter.";
          }
          if (reason.startsWith("'mode' must be one of")) {
            yield "Set mode to one of the listed uppercase values, or omit it for an explicit"
                + " invocation to choose.";
          }
          if (reason.endsWith("needs only an agentVisible boolean")) {
            yield "For the reported skill entry, keep only agentVisible with the boolean true or"
                + " false, without quotes.";
          }
          if (reason.startsWith("'") && reason.contains("must be non-empty text of at most")) {
            yield "Correct the named field to non-empty text within the stated character limit."
                + " Quote it if YAML interprets the value as a boolean or number.";
          }
          yield "Correct the specific violation reported above before retrying.";
        }
      };
    }
  }

  /**
   * Recognizes packages in .plowshare, Personal Resources, and root-level server tiers. Ordinary
   * Markdown and supporting package resources are untouched. Routing still owns authorization.
   */
  static Target target(Path path, List<Path> roots) {
    path = path.normalize();
    if (path.getFileName() == null || path.getParent() == null) return null;
    Path directory;
    String name;
    if (path.getFileName().toString().equals("SKILL.md")) {
      if (path.getParent().getFileName() == null) return null;
      name = path.getParent().getFileName().toString();
      directory = path.getParent().getParent();
    } else if (path.getFileName().toString().equals("skills.yml")) {
      name = null;
      directory = path.getParent().resolve("skills");
    } else {
      return null;
    }
    if (directory == null) return null;
    for (Path root : roots) {
      if (!path.startsWith(root)) continue;
      if (directory.equals(root.resolve("skills"))
          || directory.endsWith(Path.of(".plowshare", "skills"))
          || directory.endsWith(Path.of("Resources", "skills"))) {
        return new Target(name);
      }
    }
    return null;
  }
}
