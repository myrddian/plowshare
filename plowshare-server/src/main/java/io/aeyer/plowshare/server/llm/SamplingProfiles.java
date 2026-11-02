package io.aeyer.plowshare.server.llm;

import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * What an agent's declared intent means on the model actually in use.
 *
 * <h2>Two artifacts, both owned by a person</h2>
 *
 * <p>A <b>mapping</b> from wire model to profile, and a <b>profile</b> per model family. They are
 * separate on purpose: adding a model is then one line in the mapping, and two models that want the
 * same numbers point at one profile rather than duplicating it — which is exactly the {@code
 * gemma-4-26b-a4b} and {@code gemma-3-27b-it} case. This is {@code global/agents/} and {@code
 * global/bots/}'s own precedent applied to a second kind of file: a directory a person owns, which
 * this server reads and validates at boot.
 *
 * <p><b>The shipped profiles live on the classpath and are always loaded</b> — exactly as the
 * shipped agent set does now that {@code AgentsConfig.agentRegistry} treats the classpath seed as a
 * floor rather than treating an absent tree as an absent set. What still differs is what an
 * <em>absent operator directory</em> means for each: a project or a deployment with nothing in its
 * own tier still runs every shipped agent, which is visible on {@code GET /v1/agents} the moment
 * somebody looks. A model with no profile runs perfectly well at whatever it defaults to, forever,
 * which is not — so the three families this project has actually measured against ship live, and
 * the directory is where a person extends and overrides them.
 *
 * <p>Version 2 files may also declare native input formats and output combinations. Those values
 * are configuration only: routing and transport support are not enabled by loading them.
 * Unversioned sampling profiles continue to resolve with no declared media capability.
 *
 * <h2>Resolution</h2>
 *
 * <ol>
 *   <li>the wire model is folded to lower case and any {@code vendor/} prefix stripped;
 *   <li>an exact mapping key wins; otherwise the longest key the name starts with, which is what
 *       makes a key a <em>family</em> and gives a new version of it a free ride;
 *   <li>the mapping's {@code mode:} selects an entry in the profile, or {@code default} where it
 *       states none — never inferred, because nothing in a request says whether a model is thinking
 *       and the recommendations differ by a factor of two;
 *   <li>the intent selects the row.
 * </ol>
 *
 * <p><b>No match is {@link Sampling#NONE} and not a refusal.</b> Refusing every model nobody has
 * written a profile for would make this server hostile to the next one, and there is a safe thing
 * to do instead: send nothing and let the model's own defaults apply. What that costs is that the
 * agent's intent is not honoured, and {@code AgentsConfig} says so at boot, by agent and by model.
 *
 * <h2>Everything else is a boot failure naming the file</h2>
 *
 * <p>A profile that omits an intent, a mode with no rows, a mapping pointing at a profile nobody
 * wrote, an unreadable file, a {@code reasoning_effort} that is not one of the three the vendor
 * names: all refused at boot, naming the path an operator has to edit. The one thing that is
 * <em>not</em> a failure is a model nobody mapped, because that is a state the world produces on
 * its own.
 */
public final class SamplingProfiles implements ModelMediaProfiles {

  private static final Logger log = LoggerFactory.getLogger(SamplingProfiles.class);

  /** Where the shipped copies live inside the jar. */
  private static final String BUILT_IN = "sampling/";

  /**
   * The shipped profile files, by name. Written out because a classpath directory cannot be listed
   * as a {@code Path} from inside a jar — the same constraint {@code AgentsProperties} records
   * about the reference agents.
   */
  private static final List<String> SHIPPED = List.of("gemma", "qwen3", "gpt-oss", "glm");

  private static final String MAPPING = "models";

  private static final int MAX_DOCUMENT_BYTES = 262144;
  private static final int MAX_MODES = 32;

  /**
   * What an unstated {@code mode:} selects, and the fallback for a mode a profile does not carry.
   * §8.3: <i>"the default profile could provide a fall back"</i>.
   */
  public static final String DEFAULT_MODE = "default";

  /** Mapping keys, longest first, so the first match is the most specific. */
  private final List<Map.Entry<String, Mapped>> mapping;

  private final Map<String, Profile> profiles;

  /** YAML is converted completely at the loader boundary, before resolution can inspect it. */
  private record Profile(
      int version,
      Map<String, Map<Sampling.Intent, Sampling>> modes,
      Optional<ModelMediaCapabilities> media) {}

  /** One mapping row: which profile, and which of its modes. */
  private record Mapped(String profile, String mode, String source) {}

  /**
   * What a wire model and an intent came to.
   *
   * @param profile the profile that answered, or {@code null} when nothing matched — {@code null}
   *     rather than a made-up name, because DEFAULT is the absence of a profile and calling it one
   *     would put a fiction in a log line
   * @param mode which of the profile's entries applied, or {@code null} with no profile
   * @param sampling what to send; {@link Sampling#NONE} means send nothing
   * @param media native format declaration, empty for legacy/unmapped profiles; not transport
   *     support
   */
  public record Resolved(
      String profile, String mode, Sampling sampling, Optional<ModelMediaCapabilities> media) {

    public Resolved {
      java.util.Objects.requireNonNull(sampling);
      java.util.Objects.requireNonNull(media);
    }

    /** Compatibility construction for callers carrying only sampling values. */
    public Resolved(String profile, String mode, Sampling sampling) {
      this(profile, mode, sampling, Optional.empty());
    }

    /** Whether a profile answered at all. */
    public boolean matched() {
      return profile != null;
    }

    static final Resolved DEFAULT = new Resolved(null, null, Sampling.NONE);
  }

  private SamplingProfiles(List<Map.Entry<String, Mapped>> mapping, Map<String, Profile> profiles) {
    this.mapping = List.copyOf(mapping);
    this.profiles = Map.copyOf(profiles);
  }

  /**
   * The shipped profiles, with {@code directory} layered over them.
   *
   * @param directory a person's own profile directory, or {@code null} / a path that is not a
   *     directory, which is ordinary and means the shipped set stands alone. A directory that
   *     exists and contains something unreadable is a boot failure, which is the same asymmetry
   *     {@code AgentsConfig} applies to {@code global/agents} and {@code global/bots}: absent is
   *     fine, present and wrong is not.
   */
  public static SamplingProfiles read(Path directory) {
    Map<String, Profile> profiles = new LinkedHashMap<>();
    for (String name : SHIPPED) {
      profiles.put(name, profile(BUILT_IN + name + ".yaml", builtIn(name + ".yaml")));
    }
    Map<String, Mapped> mapped = mapping(BUILT_IN + MAPPING + ".yaml", builtIn(MAPPING + ".yaml"));

    if (directory != null && Files.isDirectory(directory)) {
      overlay(directory, profiles, mapped);
    }

    for (Map.Entry<String, Mapped> entry : mapped.entrySet()) {
      Profile profile = profiles.get(entry.getValue().profile());
      if (profile == null) {
        throw new IllegalStateException(
            entry.getValue().source()
                + ": the sampling mapping sends '"
                + entry.getKey()
                + "' to the profile '"
                + entry.getValue().profile()
                + "', and no such profile exists."
                + " Write "
                + entry.getValue().profile()
                + ".yaml in the"
                + " sampling directory, or point that line at one of "
                + profiles.keySet());
      }
      String mode = entry.getValue().mode();
      if (profile.version() == 2 && mode != null && !profile.modes().containsKey(mode)) {
        throw new IllegalStateException(
            entry.getValue().source()
                + ": the sampling mapping for '"
                + entry.getKey()
                + "' names missing mode '"
                + mode
                + "' in version 2 profile '"
                + entry.getValue().profile()
                + "'");
      }
    }

    List<Map.Entry<String, Mapped>> ordered = new ArrayList<>(mapped.entrySet());
    // Longest key first: an exact model name must beat the family prefix it
    // happens to start with, and a longer family must beat a shorter one.
    ordered.sort((left, right) -> right.getKey().length() - left.getKey().length());
    return new SamplingProfiles(ordered, profiles);
  }

  /**
   * Read a person's directory over the shipped set.
   *
   * <p><b>A profile file replaces; the mapping merges.</b> The two are treated differently because
   * they are different kinds of document. A profile is a table, and half a table is not a profile —
   * somebody who writes {@code gemma.yaml} has decided what Gemma means here and must not silently
   * inherit rows they did not write. A mapping is a list of independent statements, so adding one
   * model has no business re-stating the others.
   */
  private static void overlay(
      Path directory, Map<String, Profile> profiles, Map<String, Mapped> mapped) {
    Set<String> stems = new LinkedHashSet<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.sorted().toList()) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
          continue;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("yaml") && !extension.equals("yml") && !extension.equals("json")) {
          continue;
        }
        String stem = name.substring(0, dot);
        if (!stems.add(stem)) {
          throw new IllegalStateException(
              directory + " contains duplicate profile/mapping " + stem);
        }
        // Bound before decoding, including a file changed concurrently with the read. Do not load
        // an unbounded document merely to let the YAML parser reject it afterwards.
        String text;
        try (InputStream input = Files.newInputStream(file)) {
          byte[] bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
          if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalStateException(file + " exceeds the 256 KiB profile file limit");
          }
          text =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .decode(java.nio.ByteBuffer.wrap(bytes))
                  .toString();
        }
        if (stem.equals(MAPPING)) {
          mapped.putAll(mapping(file.toString(), text));
        } else {
          profiles.put(stem, profile(file.toString(), text));
        }
      }
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "the sampling directory " + directory + " could not be read", unreadable);
    }
  }

  private static String builtIn(String name) {
    try (InputStream in =
        SamplingProfiles.class.getClassLoader().getResourceAsStream(BUILT_IN + name)) {
      if (in == null) {
        throw new IllegalStateException(
            "the shipped sampling file "
                + BUILT_IN
                + name
                + " is missing from this"
                + " build. It is a resource of the server jar and not something"
                + " an operator can delete, so this is a packaging fault");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "the shipped sampling file " + BUILT_IN + name + " could not be read", unreadable);
    }
  }

  // --- parsing ----------------------------------------------------------------

  private static Map<String, Object> document(String where, String text) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(MAX_DOCUMENT_BYTES);
    // SafeConstructor for AgentRegistry's reason exactly: a profile is a
    // file on disk and a YAML loader that can instantiate arbitrary classes
    // turns "an operator edited a config file" into "an operator ran code".
    Object loaded;
    try {
      loaded = new Yaml(new SafeConstructor(options)).load(text);
    } catch (YAMLException malformed) {
      throw new IllegalStateException(
          where + " is not readable as YAML or JSON: " + malformed.getMessage(), malformed);
    }
    if (loaded == null) {
      return Map.of();
    }
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new IllegalStateException(
          where
              + " is a "
              + loaded.getClass().getSimpleName()
              + " and not a mapping"
              + " of keys to values");
    }
    Map<String, Object> keyed = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalStateException(where + " contains a non-text field name");
      }
      keyed.put(key, entry.getValue());
    }
    return keyed;
  }

  private static Map<String, Mapped> mapping(String where, String text) {
    Map<String, Object> document = document(where, text);
    knownFields(where, document, Set.of(MAPPING));
    Object models = document.get(MAPPING);
    if (models == null) {
      return new LinkedHashMap<>();
    }
    if (!(models instanceof Map<?, ?> rows)) {
      throw new IllegalStateException(
          where
              + " has a '"
              + MAPPING
              + ":' that is not a mapping of model names to"
              + " profiles");
    }
    Map<String, Mapped> mapped = new LinkedHashMap<>();
    for (Map.Entry<?, ?> row : rows.entrySet()) {
      if (!(row.getKey() instanceof String modelName)
          || modelName.isBlank()
          || modelName.length() > 512
          || modelName
              .chars()
              .anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
        throw new IllegalStateException(where + " contains an invalid model mapping key");
      }
      String model = modelName.toLowerCase(Locale.ROOT);
      if (!(row.getValue() instanceof Map<?, ?> fields)) {
        throw new IllegalStateException(
            where
                + " maps '"
                + model
                + "' to "
                + row.getValue()
                + ", which is not a"
                + " block. Write it as 'profile: <name>', optionally with"
                + " 'mode: <name>' underneath");
      }
      knownFields(where + " models/" + model, fields, Set.of("profile", "mode"));
      Object profile = fields.get("profile");
      if (!(profile instanceof String named) || named.isBlank()) {
        throw new IllegalStateException(
            where
                + " maps '"
                + model
                + "' to a block with no 'profile:' in it, so"
                + " nothing says which file holds its numbers");
      }
      Object mode = fields.get("mode");
      if (mode != null && !(mode instanceof String)) {
        throw new IllegalStateException(
            where + " gives '" + model + "' the mode " + mode + ", which is not a" + " name");
      }
      if (mapped.putIfAbsent(
              model,
              new Mapped(named.strip(), mode == null ? null : ((String) mode).strip(), where))
          != null) {
        throw new IllegalStateException(where + " contains a duplicate normalized model mapping");
      }
    }
    return mapped;
  }

  private static Profile profile(String where, String text) {
    Map<String, Object> document = document(where, text);
    boolean versioned = document.containsKey("version");
    if (versioned && !Integer.valueOf(2).equals(document.get("version"))) {
      throw new IllegalStateException(where + " must declare integer version: 2 or omit version");
    }
    if (versioned) {
      knownFields(where, document, Set.of("version", "modes", "media"));
    } else if (document.containsKey("media")) {
      throw new IllegalStateException(where + " requires version: 2 for media declarations");
    }
    Object modes = document.get("modes");
    if (!(modes instanceof Map<?, ?> rows) || rows.isEmpty()) {
      throw new IllegalStateException(
          where
              + " has no 'modes:' block. A profile is a table of modes, each"
              + " carrying one row per intent; even a family with a single mode"
              + " writes it as 'modes: { default: … }' so that the family that"
              + " grows a second one is not a different shape of file");
    }
    if (versioned && (rows.size() > MAX_MODES || !rows.containsKey(DEFAULT_MODE))) {
      throw new IllegalStateException(where + " requires a default mode and at most 32 modes");
    }
    Map<String, Map<Sampling.Intent, Sampling>> profile = new LinkedHashMap<>();
    for (Map.Entry<?, ?> row : rows.entrySet()) {
      String mode = String.valueOf(row.getKey());
      if (versioned
          && (!(row.getKey() instanceof String) || !mode.matches("[a-z][a-z0-9_-]{0,63}"))) {
        throw new IllegalStateException(where + " contains an invalid mode name");
      }
      if (!(row.getValue() instanceof Map<?, ?> intents)) {
        throw new IllegalStateException(where + "'s mode '" + mode + "' is not a block of intents");
      }
      if (versioned) {
        knownFields(
            where + " modes/" + mode, intents, Set.of("precise", "balanced", "exploratory"));
      }
      Map<Sampling.Intent, Sampling> table = new EnumMap<>(Sampling.Intent.class);
      for (Sampling.Intent intent : Sampling.Intent.values()) {
        Object declared = intents.get(intent.declared());
        if (declared == null) {
          // REFUSED AND NOT DEFAULTED. The vocabulary is closed at
          // three, so a table missing one is a table an agent can
          // legitimately ask a question of and get nothing back. That
          // is the invisible fact this design exists to end, and the
          // fix is one line in a file this message names.
          throw new IllegalStateException(
              where
                  + "'s mode '"
                  + mode
                  + "' has no '"
                  + intent.declared()
                  + ":' row. An agent may declare any of the three intents,"
                  + " so a profile has to answer all three — write it out"
                  + " even where it is the same as its neighbour, which is"
                  + " itself a statement worth being able to read");
        }
        if (!(declared instanceof Map<?, ?> fields)) {
          throw new IllegalStateException(
              where
                  + "'s '"
                  + mode
                  + "/"
                  + intent.declared()
                  + "' is "
                  + declared
                  + " and not a block of parameters");
        }
        table.put(intent, sampling(where, mode + "/" + intent.declared(), fields));
      }
      profile.put(mode, Map.copyOf(table));
    }
    Optional<ModelMediaCapabilities> media =
        document.containsKey("media")
            ? Optional.of(media(where, document.get("media")))
            : Optional.empty();
    return new Profile(versioned ? 2 : 1, Map.copyOf(profile), media);
  }

  private static ModelMediaCapabilities media(String where, Object value) {
    try {
      Map<?, ?> media = block(where + " media", value);
      knownFields(where + " media", media, Set.of("inputs", "outputs", "image-context"));
      Set<String> inputs = formats(where + " media/inputs", media.get("inputs"));
      Map<?, ?> outputs = block(where + " media/outputs", media.get("outputs"));
      if (outputs.isEmpty() || outputs.size() > ModelMediaCapabilities.MAX_OUTPUT_MODES) {
        throw new IllegalStateException(where + " media/outputs must contain 1..32 modes");
      }
      Map<String, ModelMediaCapabilities.OutputMode> modes = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : outputs.entrySet()) {
        if (!(entry.getKey() instanceof String name)) {
          throw new IllegalStateException(where + " media/outputs has a non-text mode name");
        }
        Map<?, ?> mode = block(where + " media/outputs/" + name, entry.getValue());
        knownFields(where + " media/outputs/" + name, mode, Set.of("formats"));
        modes.put(
            name,
            new ModelMediaCapabilities.OutputMode(
                formats(where + " media/outputs/" + name + "/formats", mode.get("formats"))));
      }
      ImageContextBudget budget = ImageContextBudget.DEFAULT;
      if (media.containsKey("image-context")) {
        Map<?, ?> context = block(where + " media/image-context", media.get("image-context"));
        knownFields(
            where + " media/image-context",
            context,
            Set.of("tokens-per-image", "framing-tokens", "max-images", "max-prompt-bytes"));
        budget =
            new ImageContextBudget(
                budgetValue(context, "tokens-per-image", budget.tokensPerImage()),
                budgetValue(context, "framing-tokens", budget.framingTokens()),
                budgetValue(context, "max-images", budget.maxImages()),
                budgetValue(context, "max-prompt-bytes", budget.maxPromptBytes()));
      }
      return new ModelMediaCapabilities(inputs, modes, budget);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException(
          where + " has an invalid media declaration: " + invalid.getMessage(), invalid);
    }
  }

  private static int budgetValue(Map<?, ?> context, String key, int fallback) {
    if (!context.containsKey(key)) return fallback;
    if (!(context.get(key) instanceof Integer value))
      throw new IllegalArgumentException("Image context budget values must be integers");
    return value;
  }

  private static Map<?, ?> block(String where, Object value) {
    if (value instanceof Map<?, ?> fields) return fields;
    throw new IllegalStateException(where + " must be a mapping");
  }

  private static Set<String> formats(String where, Object value) {
    if (!(value instanceof List<?> values)
        || values.isEmpty()
        || values.size() > ModelMediaCapabilities.MAX_FORMATS) {
      throw new IllegalStateException(where + " must contain 1..32 exact MIME types");
    }
    Set<String> formats = new LinkedHashSet<>();
    for (Object format : values) {
      if (!(format instanceof String text)) {
        throw new IllegalStateException(where + " must contain only MIME strings");
      }
      if (!formats.add(ModelMediaCapabilities.mediaType(text))) {
        throw new IllegalStateException(where + " contains a duplicate normalized MIME type");
      }
    }
    return formats;
  }

  private static void knownFields(String where, Map<?, ?> fields, Set<String> allowed) {
    for (Object key : fields.keySet()) {
      if (!(key instanceof String) || !allowed.contains(key)) {
        throw new IllegalStateException(where + " contains an unknown field; expected " + allowed);
      }
    }
  }

  private static Sampling sampling(String where, String at, Map<?, ?> fields) {
    Sampling sampling = Sampling.NONE;
    for (Map.Entry<?, ?> field : fields.entrySet()) {
      String key = String.valueOf(field.getKey());
      Object value = field.getValue();
      switch (key) {
        case "temperature" ->
            sampling = sampling.withTemperature(number(where, at, key, value).doubleValue());
        case "top_p" -> sampling = sampling.withTopP(number(where, at, key, value).doubleValue());
        case "top_k" -> sampling = sampling.withTopK(whole(where, at, key, value));
        case "max_tokens" -> sampling = sampling.withMaxTokens(whole(where, at, key, value));
        case "reasoning_effort" ->
            sampling = sampling.withReasoningEffort(effort(where, at, value));
        // A CLOSED SET, for AgentRegistry.KNOWN_KEYS' reason: an unknown
        // key in an open set is a misspelling that reads as a decision
        // nobody can see was never applied.
        default ->
            throw new IllegalStateException(
                where
                    + "'s '"
                    + at
                    + "' names '"
                    + key
                    + "', which is not a sampling"
                    + " parameter this server can send. It knows temperature,"
                    + " top_p, top_k, max_tokens and reasoning_effort");
      }
    }
    return sampling;
  }

  private static Number number(String where, String at, String key, Object value) {
    if (value instanceof Number parsed) {
      return parsed;
    }
    throw new IllegalStateException(
        where
            + "'s '"
            + at
            + "' has '"
            + key
            + ": "
            + value
            + "', which is not a"
            + " number. Write it unquoted: a quoted value reads as text and this"
            + " parameter would otherwise go unsent while the file says otherwise");
  }

  private static int whole(String where, String at, String key, Object value) {
    if (value instanceof Integer parsed) {
      return parsed;
    }
    throw new IllegalStateException(
        where
            + "'s '"
            + at
            + "' has '"
            + key
            + ": "
            + value
            + "', which is not a whole"
            + " number");
  }

  private static Sampling.Effort effort(String where, String at, Object value) {
    if (value instanceof String named) {
      for (Sampling.Effort effort : Sampling.Effort.values()) {
        if (effort.wire().equals(named.strip().toLowerCase(Locale.ROOT))) {
          return effort;
        }
      }
    }
    throw new IllegalStateException(
        where
            + "'s '"
            + at
            + "' has 'reasoning_effort: "
            + value
            + "', which is not one"
            + " of low, medium, high. The vendor names three and a fourth is a"
            + " value the endpoint would refuse or ignore");
  }

  // --- resolution -------------------------------------------------------------

  /**
   * What {@code wireModel} should be sent at {@code intent}.
   *
   * <p>Never null. A model nothing matches resolves to {@link Resolved#DEFAULT}, which carries
   * {@link Sampling#NONE} and no media declaration. Resolution only reads the immutable startup
   * snapshot; profile edits take effect when a new snapshot is loaded.
   *
   * @param wireModel the model name as the endpoint knows it, not a class
   * @param intent declared sampling intent, never null
   */
  public Resolved resolve(String wireModel, Sampling.Intent intent) {
    java.util.Objects.requireNonNull(intent, "intent");
    if (wireModel == null) {
      return Resolved.DEFAULT;
    }
    String name = wireModel.toLowerCase(Locale.ROOT);
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    for (Map.Entry<String, Mapped> candidate : mapping) {
      if (!name.startsWith(candidate.getKey())) {
        continue;
      }
      Mapped hit = candidate.getValue();
      Profile loaded = profiles.get(hit.profile());
      Map<String, Map<Sampling.Intent, Sampling>> profile = loaded.modes();
      String mode = hit.mode();
      if (mode == null || !profile.containsKey(mode)) {
        if (mode != null) {
          // §8.3's own fallback, said out loud. A mode a person named
          // and a profile does not carry is a typo or a file half
          // edited, and running silently at another mode's numbers is
          // how a factor of two on temperature goes unnoticed.
          log.warn(
              "the sampling mapping runs '{}' in mode '{}', which the profile"
                  + " '{}' does not carry; its '{}' entry applies instead",
              wireModel,
              mode,
              hit.profile(),
              DEFAULT_MODE);
        }
        mode = DEFAULT_MODE;
      }
      Map<Sampling.Intent, Sampling> table = profile.get(mode);
      if (table == null) {
        log.warn(
            "the profile '{}' has no '{}' mode, so nothing can be resolved for"
                + " '{}' and no sampling parameters will be sent",
            hit.profile(),
            DEFAULT_MODE,
            wireModel);
        return Resolved.DEFAULT;
      }
      return new Resolved(hit.profile(), mode, table.get(intent), loaded.media());
    }
    return Resolved.DEFAULT;
  }

  /** Native declaration for the resolved model and mode, independent of sampling intent. */
  @Override
  public Optional<ModelMediaCapabilities> mediaFor(String wireModel) {
    return resolve(wireModel, Sampling.Intent.BALANCED).media();
  }

  /** The profile names loaded, for a boot line. */
  public Set<String> names() {
    return profiles.keySet();
  }
}
