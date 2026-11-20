package io.aeyer.plowshare.server.approvals;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Account-owned consent policy. Rules narrow consent; they never grant filesystem or role access.
 */
public record ApprovalPolicy(long revision, Mode mode, String bot, List<Rule> rules) {
  public static final long MAX_REVISION = 9007199254740991L;

  public enum Mode {
    MANUAL,
    SMART,
    AUTO
  }

  public enum Effect {
    MAY_APPROVE,
    PERSONAL_READ_INTENT,
    ASK_HUMAN,
    REJECT
  }

  public enum Kind {
    COMMAND,
    FILE_READ,
    FILE_WRITE,
    FILE_DELETE,
    FILE_MOVE
  }

  /**
   * Conditions evaluated against provider-resolved operation identity. MAY_APPROVE requires an
   * exact target and digest. PERSONAL_READ_INTENT supplies intent for an otherwise permitted read
   * in the owner's verified Personal workspace, with optional exact selectors. Paths and argv match
   * whole values.
   */
  public record Rule(
      String id,
      Effect effect,
      Kind kind,
      String project,
      String provider,
      String cwd,
      String inputDigest,
      List<String> argv,
      List<String> paths,
      String guidance) {
    public Rule {
      text(id, "Rule ID", 128);
      Objects.requireNonNull(effect, "Rule effect");
      Objects.requireNonNull(kind, "Rule operation");
      optional(project, "Rule project", 512);
      optional(provider, "Rule provider", 1024);
      optional(cwd, "Rule working directory", 8192);
      optional(inputDigest, "Rule input digest", 128);
      digest(inputDigest);
      argv = strings(argv, "Rule command", 256);
      paths = strings(paths, "Rule paths", 2);
      for (String path : paths) text(path, "Rule path", 8192);
      if (!argv.isEmpty()) text(argv.getFirst(), "Rule program", 8192);
      optional(guidance, "Rule guidance", 2000);
      if (kind == Kind.COMMAND && !paths.isEmpty() || kind != Kind.COMMAND && !argv.isEmpty())
        throw new IllegalArgumentException("A rule must use its operation's target type");
      if (effect == Effect.PERSONAL_READ_INTENT && kind != Kind.FILE_READ)
        throw new IllegalArgumentException("Personal read intent requires FILE_READ");
      if (effect == Effect.MAY_APPROVE
          && (project == null
              || provider == null
              || cwd == null
              || (kind == Kind.COMMAND ? argv.isEmpty() : paths.isEmpty())))
        throw new IllegalArgumentException(
            "Automatic approval needs an exact project, provider, working directory and operation target");
      if (kind != Kind.COMMAND
          && !paths.isEmpty()
          && paths.size() != (kind == Kind.FILE_MOVE ? 2 : 1))
        throw new IllegalArgumentException(
            "A file rule needs its exact source and destination shape");
    }

    public boolean matches(ApprovalIntent intent) {
      return kind == intent.kind()
          && (effect != Effect.PERSONAL_READ_INTENT || intent.ownerPersonalRead())
          && (project == null || project.equals(intent.project()))
          && (provider == null || provider.equals(intent.provider()))
          && (cwd == null || cwd.equals(intent.cwd()))
          && (effect != Effect.MAY_APPROVE || Objects.equals(inputDigest, intent.inputDigest()))
          && (effect != Effect.PERSONAL_READ_INTENT
              || inputDigest == null
              || inputDigest.equals(intent.inputDigest()))
          && (argv.isEmpty() || argv.equals(intent.argv()))
          && (paths.isEmpty() || paths.equals(intent.paths()));
    }
  }

  public ApprovalPolicy {
    if (revision < 0 || revision > MAX_REVISION)
      throw new IllegalArgumentException("Policy revision must be a nonnegative safe integer");
    Objects.requireNonNull(mode, "Approval mode");
    optional(bot, "Approval bot", 128);
    rules = List.copyOf(Objects.requireNonNull(rules, "Approval rules"));
    if (rules.size() > 128) throw new IllegalArgumentException("At most 128 approval rules");
    // Bound the complete text as well as each collection. This keeps serialization/reviewer
    // evidence finite before allocating the JSON representation (including worst-case escaping).
    long textSize = bot == null ? 0 : bot.length();
    for (Rule rule : rules) {
      textSize += rule.id().length();
      for (String value :
          new String[] {
            rule.project(), rule.provider(), rule.cwd(), rule.inputDigest(), rule.guidance()
          }) if (value != null) textSize += value.length();
      for (String value : rule.argv()) textSize += value.length();
      for (String value : rule.paths()) textSize += value.length();
    }
    if (textSize > 100000)
      throw new IllegalArgumentException("Approval policy text exceeds 100000 characters");
    Set<String> ids = new HashSet<>();
    for (Rule rule : rules)
      if (!ids.add(rule.id())) throw new IllegalArgumentException("Duplicate approval rule ID");
    if (mode == Mode.AUTO && bot == null)
      throw new IllegalArgumentException("Auto mode requires a selected personal bot");
  }

  public static ApprovalPolicy manual() {
    return new ApprovalPolicy(0, Mode.MANUAL, null, List.of());
  }

  static List<String> strings(List<String> values, String label, int maximum) {
    List<String> copy = List.copyOf(Objects.requireNonNull(values, label));
    if (copy.size() > maximum) throw new IllegalArgumentException(label + " has too many entries");
    // Empty argv entries are legitimate arguments. The program and file targets are validated
    // separately; never rewrite an empty argument into a different command.
    for (String value : copy)
      if (value.length() > 8192 || value.indexOf('\0') >= 0)
        throw new IllegalArgumentException(label + " contains an invalid value");
    return copy;
  }

  static void optional(String value, String label, int maximum) {
    if (value != null) text(value, label, maximum);
  }

  static void digest(String value) {
    if (value != null && !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Approval input digest must be a lowercase SHA-256 value");
  }

  static void text(String value, String label, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException(
          label + " must be nonblank text of at most " + maximum + " characters");
  }
}
