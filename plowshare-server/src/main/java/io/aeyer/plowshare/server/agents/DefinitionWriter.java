package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one way a definition is created rather than found on disk by a human.
 *
 * <h2>An operator surface, not an agent tool</h2>
 *
 * <p>Nothing in this package hands an agent a reference to this class, and nothing must: an agent
 * that could write its own definition could grant itself any tool at the next boot, which is
 * exactly {@link AgentRegistry#WITHHELD}'s "the party that sets the leash must not be a party the
 * leash binds" argument, one level up. The four layered sources this package already reads from —
 * the shipped seed, {@code global/}, a project's own tier, a connected client's {@code .plowshare/}
 * — stay read paths; this is the write path behind them, reached only by whatever this repository's
 * later tasks put in front of it (an HTTP endpoint, a CLI verb, an MCP tool bound to the
 * harness-facing surface, a Console screen), none of which is this task.
 *
 * <h2>Refused as a request, or written — never both, never neither</h2>
 *
 * <p>A human editing a file by hand only ever finds out it is wrong at the next resolution, in a
 * log line naming the file after the fact. This class exists to move that discovery earlier: {@link
 * #write} either lands the exact bytes it was handed, atomically, or throws a {@link CallerFault}
 * naming the fault — {@link DefinitionAlreadyExistsException} is the one exception, kept a distinct
 * type rather than folded in because {@code faults.Faults} answers it {@code 409} and every {@code
 * CallerFault} {@code 400} — and nothing is written on either refused branch. What it validates
 * <b>against</b> is not a second opinion — it is {@link AgentRegistry#read}, the same call the
 * loader itself makes, so that "this would load" and "this did load" can never disagree. A writer
 * that reimplemented the rules would drift from them the moment either changed alone, and would
 * then accept a definition the loader immediately disables — discovered, again, later, by somebody
 * else.
 *
 * <p><b>{@link DefinitionChecks} for the same reason, extended.</b> {@code AgentRegistry.read} only
 * knows what the file said; whether the model it names is actually served, and by a pool that sees
 * if it asked to, is a question {@code AgentsConfig} answers for the boot set and {@link
 * DefinitionResolver} for a project tier, through the identical lambda — {@code modelChecked}
 * composed with {@code sampled}. This class takes the same seam rather than an {@code
 * LlmDispatcher} of its own: a writer that asked the fleet a different question than the loader
 * does would be the drift this class exists to close, wearing a different name. Wiring a
 * dispatcher-backed {@code DefinitionChecks} here is later tasks' work, not this one's; a context
 * with nothing to ask passes {@link DefinitionChecks#NONE}.
 *
 * <p><b>{@code DefinitionChecks}' own contract is "may disable, may never throw" — {@link
 * AgentRegistry.Loaded#without} is its one move.</b> This class trusts that and re-checks it:
 * {@link #write} inspects {@link AgentRegistry.Loaded#withheldTools} and {@link
 * AgentRegistry.Loaded#withheldEdges} again after {@link #checks} runs, not only {@link
 * AgentRegistry.Loaded#disabled}. An implementation that widened either map — which nothing in this
 * codebase does, and which the interface's own javadoc says not to — must still be caught here
 * rather than accepted in silence, on exactly the reasoning {@link AgentRegistry#read} itself gives
 * for treating a withheld item as seriously as a disablement.
 *
 * <h2>{@code name} is a filename stem, and it is checked like one</h2>
 *
 * <p>{@link AgentRegistry#read} requires {@code name} to equal the definition's own frontmatter
 * {@code name:}, and nothing about that check constrains which characters either side may use — a
 * caller free to choose both halves can make them agree on anything, including {@code
 * ../../../global/bots/scribe}. {@link #requireBareName} runs before any {@link Path#resolve},
 * refuses everything outside a closed allow-list, and is the actual boundary; the check after
 * resolving that the result still sits inside the tier directory is redundant with it by
 * construction and kept anyway, because a boundary enforced in exactly one place is a boundary one
 * future edit away from a hole — see its own javadoc.
 *
 * <h2>Tier targeting</h2>
 *
 * <p>{@code projectId} says which of two directories {@link DataLayout#botsFor} answers with: a
 * project's own {@code projects/&lt;id&gt;/bots/} when it names one, {@code global/bots/} when it
 * names none. <b>Never the classpath seed</b> — {@code ClasspathDefinitions} reads from inside this
 * server's own jar, which is not a {@link Path} this process could write to even if it wanted to,
 * and that unwritability is the property that makes the seed a floor: every deployment can trust it
 * is exactly what shipped, because nothing — this class included — has a way to change it.
 *
 * <h2>A required name is a project-tier fault, not a global one</h2>
 *
 * <p>{@link DefinitionResolver#readProject} refuses a project file named after a {@link
 * AgentsConfig#REQUIRED} agent outright, because a project layers over the boot set and the
 * inherited definition must keep answering to that name. {@code global/bots/} is different: it is
 * one of the layers {@code AgentsConfig.agentRegistry} builds the boot set <em>out of</em>, so a
 * global file named {@code scribe.md} does not override a running agent, it is what running as
 * {@code scribe} means on this deployment. Refusing it here would refuse the one legitimate way to
 * reconfigure a required agent that this repository already ships. So the check below applies only
 * when {@code projectId} is non-null, mirroring exactly where {@link DefinitionResolver} applies it
 * and nowhere else.
 *
 * <h2>Overwrite is refused unless asked for</h2>
 *
 * <p>A definition is an executable prompt plus a set of tool and delegation grants, with no
 * revision history behind the file on disk. A caller that mistypes a name and silently replaces
 * another agent's file has destroyed it, with no trace of what it said a moment before — and on a
 * filesystem that normalises differently than this JVM does, an NFD {@code name} could silently
 * replace an NFC file nobody meant to touch, invisibly to a person reading either string. So {@link
 * #write} refuses a target that already exists unless {@code overwrite} says otherwise, and {@link
 * Written#disposition} says which of the two happened — {@link Disposition#CREATED} or {@link
 * Disposition#REPLACED} — for a caller that has to answer 201 against 200. The refused case is
 * {@link DefinitionAlreadyExistsException}, a distinct type so that 409 against 400 is a row in
 * {@code faults.Faults} rather than something a caller has to work out. The mechanism is unchanged
 * either way: an atomic rename, {@link StandardCopyOption#REPLACE_EXISTING} included, so a
 * concurrent write that slips between this method's existence check and the rename below still
 * lands as one complete file or the other and never a merge of both.
 *
 * <h2>Atomicity</h2>
 *
 * <p>The bytes are written to a sibling temporary file in the same directory and moved into place
 * with {@link StandardCopyOption#ATOMIC_MOVE}, same filesystem guaranteed by construction because
 * the temp file is created alongside the target rather than in a system temp directory. {@link
 * DefinitionResolver} re-reads a project tier on a directory stamp change — see its own javadoc —
 * so a reader could otherwise observe a file mid-write: a truncated frontmatter fence, a
 * half-written grant list. That reader would either refuse a file that is actually fine a moment
 * later, or — worse — load a definition nobody asked for. An atomic rename means every stamp change
 * this class ever produces is a change from "file absent" straight to "file complete", with no
 * state in between for anything else to observe.
 *
 * <p><b>The file that lands is made world-readable, {@code rw-r--r--}, and not left at {@link
 * Files#createTempFile}'s own default of owner-only.</b> {@code AuthConfig.writeOperatorToken}
 * stages a temp file at that default deliberately, because that file's whole protection is its
 * mode; a definition is the opposite case — it is meant to be read and hand-edited by whoever
 * operates this server, exactly like every file the other three sources already read, and a
 * definition an operator cannot {@code cat} without becoming root is a regression from writing it
 * by hand. Set with {@link Files#setPosixFilePermissions} <b>after</b> the move, deliberately not
 * at creation via {@link PosixFilePermissions#asFileAttribute} — that reaches {@code open(2)}'s
 * mode argument, which the process {@code umask} masks, so the mode this class asks for and the
 * mode a file lands at could silently disagree under a restrictive one. {@code chmod(2)} is not
 * masked; see {@link #makeReadable} for the rest of this argument, including why it is best-effort.
 * Skipped entirely on a filesystem with no POSIX view, where the concept does not apply.
 */
public final class DefinitionWriter {

  /**
   * What {@code name} may be: 1 to 64 of the characters a bare filename stem needs and nothing
   * else. No {@code /}, no {@code \}, no {@code .} on its own, no control character — including a
   * null byte, which would otherwise surface as an opaque {@link
   * java.nio.file.InvalidPathException} from inside {@link Path#resolve} rather than a sentence
   * naming the rule.
   */
  private static final Pattern BARE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /**
   * The most {@code text} this class will write as one definition.
   *
   * <h2>Why the writer holds it, and not the surface that asked</h2>
   *
   * <p>It lived in {@code api.AgentController} until the task that brought that handler's decisions
   * down, on the reading that a byte count is a request-shape check. It is not one. The number is
   * reasoned about entirely in this class's own terms — against {@code
   * LocalProvider.MAX_FILE_BYTES}, this codebase's existing answer to "how big may one file this
   * server holds be" at 8 MiB, and against the largest definition this repository ships, {@code
   * interlocutor.md}, which is under 25 KiB. 256 KiB is ten times that with room to grow, and
   * nowhere near what a real upload gets. Nothing about an HTTP body decides any of it.
   *
   * <p>And the ceiling has to hold for <b>every</b> caller of {@link #write}, not only for one that
   * remembered to check first. This class's own javadoc names the callers it expects — an HTTP
   * endpoint, a CLI verb, an MCP tool, a Console screen — and nothing bounds a JSON body the way
   * {@code application.yml}'s multipart limits bound an upload: those apply to {@code
   * multipart/form-data}, and Tomcat's {@code maxPostSize} only to form encoding, neither of which
   * the endpoint uses. Left on one surface, this is a resource guarantee the next surface silently
   * does not have.
   *
   * <p>Measured in UTF-8 bytes, matching this class's own {@code Files.writeString(...,
   * StandardCharsets.UTF_8)} — a character count would under-measure a file whose grants or prose
   * use anything outside ASCII.
   *
   * <p><b>The refusal names this constant, and the change of name was its own commit.</b> It said
   * {@code AgentController.MAX_DEFINITION_BYTES} for as long as the move lasted, deliberately: the
   * plan that brought the check down forbade changing a refusal message by a character, and its
   * whole proof was that nothing changed — so the sentence went on naming where the constant used
   * to be, and the wrongness was recorded here rather than quietly fixed inside the refactor. It is
   * corrected now, by a task allowed to edit the message and saying why, which is the case the rule
   * was protecting. {@code AgentDefinitionApiTest} pins the sentence a caller reads.
   */
  public static final int MAX_DEFINITION_BYTES = 256 * 1024;

  /**
   * {@code rw-r--r--} — see this class's own javadoc, "atomicity", for why a definition is not
   * staged at {@link Files#createTempFile}'s default.
   */
  private static final Set<PosixFilePermission> READABLE =
      PosixFilePermissions.fromString("rw-r--r--");

  private final AgentRegistry bootSet;
  private final DataLayout data;
  private ScopedTools scopedTools = ScopedTools.NONE;

  public void useScopedTools(ScopedTools tools) {
    scopedTools = Objects.requireNonNull(tools);
  }

  private Set<String> knownTools(Long project) {
    var names = new java.util.TreeSet<>(knownTools);
    names.addAll(scopedTools.names(project));
    return Set.copyOf(names);
  }

  private final Set<String> knownTools;
  private final Set<String> required;
  private final DefinitionChecks checks;

  /**
   * Whether a write created a file that was not there, or replaced one that was — see this class's
   * own javadoc, "overwrite is refused unless asked for".
   */
  public enum Disposition {
    CREATED,
    REPLACED
  }

  /**
   * One definition, written.
   *
   * @param file where it landed, absolute
   * @param origin what {@link #file} was named as, to a reader of a log line or a refusal about it
   *     — the same string {@link DefinitionSource}'s own javadoc describes, kept here so a caller
   *     does not have to derive it a second time from {@code file}
   * @param disposition whether this write created {@code file} or replaced it
   */
  public record Written(Path file, String origin, Disposition disposition) {
    public Written {
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(disposition, "disposition");
    }
  }

  /**
   * @param bootSet the registry this deployment currently serves — the shipped seed plus (if this
   *     deployment keeps one) {@code global/}. Its {@link AgentRegistry#names()} are handed to
   *     {@link AgentRegistry#read} as the names a candidate may {@code calls:} without defining
   *     itself, exactly as {@link DefinitionResolver} hands the same set to a project tier's own
   *     read — see {@code AgentRegistry}'s four-{@code Set} overload. Without it, a perfectly valid
   *     project definition delegating to an agent the boot set already serves would read to this
   *     class as a typo and be refused, though it would have loaded fine
   * @param data where this deployment's tree is, or {@link DataLayout#NONE} for one that keeps
   *     nothing — every write on such a deployment is refused, by {@link #write} itself and not by
   *     an exception surfacing from three calls deep inside {@link DataLayout#botsFor}
   * @param knownTools the exact set of tool names the tool layer registers at this boot, on {@link
   *     AgentRegistry#read}'s own warning about a superset: too broad and a grant this deployment
   *     cannot actually issue is written and accepted in silence
   * @param required the agents this server's own code looks up by name — {@link
   *     AgentsConfig#REQUIRED}, in production. A write naming one of these against a project is
   *     refused; see this class's own javadoc for why that refusal does not extend to {@code
   *     projectId == null}
   * @param checks the questions {@link AgentRegistry#read} cannot answer for itself, applied to the
   *     candidate exactly as they would be applied on load. See this class's own javadoc, "the same
   *     seam, extended"
   */
  public DefinitionWriter(
      AgentRegistry bootSet,
      DataLayout data,
      Set<String> knownTools,
      Set<String> required,
      DefinitionChecks checks) {
    this.bootSet = Objects.requireNonNull(bootSet, "bootSet");
    this.data = Objects.requireNonNull(data, "data");
    this.knownTools = Set.copyOf(Objects.requireNonNull(knownTools, "knownTools"));
    this.required = Set.copyOf(Objects.requireNonNull(required, "required"));
    this.checks = Objects.requireNonNull(checks, "checks");
  }

  /**
   * {@link #write(Long, String, String, boolean)} with {@code overwrite} false — refuses rather
   * than replaces a file already at that name.
   */
  public Written write(Long projectId, String name, String text) {
    return write(projectId, name, text, false);
  }

  /**
   * Validate {@code text} as though it were about to be loaded, and only then write it as {@code
   * name}{@code .md} in the tier {@code projectId} names.
   *
   * <h2>The order, and why it is this order</h2>
   *
   * <ol>
   *   <li>{@code text} is within {@link #MAX_DEFINITION_BYTES} — first of all, ahead even of
   *       whether this deployment keeps a data directory, because that is the position the check
   *       held on the surface it came down from and a caller sent an oversized body either way;
   *   <li>{@code name} is a bare filename stem and nothing else — see this class's own javadoc,
   *       "{@code name} is a filename stem";
   *   <li>the file parses, its grants name real tools, and its call graph is sound — everything
   *       {@link AgentRegistry#read} checks about one definition, asked with {@code required} empty
   *       so that any fault lands as a disablement or a withheld item this method can read back,
   *       rather than a thrown {@link IllegalStateException} whose message this method would have
   *       to unwrap;
   *   <li>the name is not one a project may not define — see this class's own javadoc, "a required
   *       name";
   *   <li>the fleet's own opinion, through {@link #checks};
   *   <li>nothing already sits at that name, unless {@code overwrite} says so.
   * </ol>
   *
   * <p>Parse faults first and the naming policies after, because a definition that is wrong on its
   * own terms should be reported as that and not as a policy it never got far enough to run into —
   * the same ordering {@link AgentRegistry#read}'s own javadoc states for the reasons a whole
   * directory can fail. The existence check is last of all: it is a fact about the filesystem and
   * not about {@code text}, and a candidate that is wrong on its own terms should be told so even
   * when a file is already there.
   *
   * @param projectId which tier to write into — see this class's own javadoc, "tier targeting"
   * @param name the file's stem; refused, not corrected, if it disagrees with the definition's own
   *     {@code name:}, or if it is not {@link #BARE_NAME}
   * @param text the whole file, frontmatter and body, written exactly as given — this method
   *     validates it, it does not reformat it
   * @param overwrite whether to replace a file already at this name, rather than refuse. See this
   *     class's own javadoc, "overwrite is refused unless asked for"
   * @return where it landed, and whether it was created or replaced
   * @throws CallerFault naming the fault, if {@code text} is longer than {@link
   *     #MAX_DEFINITION_BYTES}, if {@code name} is not {@link #BARE_NAME}, if {@code text} would
   *     not load, if it grants a tool this runtime does not bind, if {@code name} is {@link
   *     #required} and {@code projectId} is not null, if {@link #checks} would disable or withhold
   *     anything from it, or if this deployment keeps no data directory. Nothing is written on any
   *     of these
   * @throws DefinitionAlreadyExistsException if a file is already at this name and {@code
   *     overwrite} is false — a distinct type rather than a {@link CallerFault}, so that {@code
   *     faults.Faults} can answer it {@code 409} where it answers this method's other refusals
   *     {@code 400}; see its own class javadoc
   * @throws UncheckedIOException if the write itself fails
   */
  public synchronized Written write(Long projectId, String name, String text, boolean overwrite) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
    // Before everything, including the deployment's own refusal: this is
    // the position it held when it was the caller's check, so an oversized
    // body on a server that keeps nothing is still told about its size.
    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_DEFINITION_BYTES) {
      throw new CallerFault(
          "'text' is "
              + bytes
              + " bytes, and this endpoint will not write more than "
              + MAX_DEFINITION_BYTES
              + " as one definition. See"
              + " DefinitionWriter.MAX_DEFINITION_BYTES for why. Nothing was"
              + " written");
    }
    if (!data.keepsAnything()) {
      throw new CallerFault(
          "this server keeps no data directory, so there is nowhere to write '"
              + name
              + "'. Set PLOWSHARE_DATA_DIR");
    }
    String bareName = requireBareName(name);

    Path dir = data.botsFor(projectId);
    Path target = dir.resolve(bareName + ".md");
    // Belt and braces over BARE_NAME, which is the actual boundary: a
    // string admitted by that pattern cannot contain '/' or '.' at all,
    // so resolving it can never leave `dir`. This is redundant with it by
    // construction and kept anyway, on the reasoning this class's own
    // javadoc gives -- a boundary enforced in exactly one place is a
    // boundary one future edit to BARE_NAME away from a hole.
    if (!dir.normalize().equals(target.normalize().getParent())) {
      throw new CallerFault(
          "'"
              + name
              + "' resolved to "
              + target
              + ", which is not directly inside "
              + dir
              + ". Refusing rather than trusting a resolve that should have"
              + " been unreachable once the name passed "
              + BARE_NAME.pattern());
    }
    String origin = target.toString();

    DefinitionSource candidate = singleEntry(bareName, origin, text);
    // required is Set.of(): a candidate is never depended on by this
    // server's own Java, so every fault it can have is one AgentRegistry
    // itself already knows how to absorb rather than throw -- which is
    // what lets this method read the fault back from Loaded instead of
    // catching an IllegalStateException and re-wrapping its message.
    // bootSet.names() is alsoDefined, exactly as DefinitionResolver hands
    // it to the same overload for a project tier's own read -- without it
    // a `calls:` naming an agent the boot set already serves reads as a
    // typo here and is withheld, though the very same file would have
    // loaded fine.
    AgentRegistry.Loaded read =
        AgentRegistry.read(candidate, knownTools(projectId), Set.of(), bootSet.names());

    requireNothingDisabled(read);
    requireNothingWithheld(read);

    if (projectId != null && required.contains(bareName)) {
      throw new CallerFault(
          "'"
              + bareName
              + "' is required by this server and cannot be defined by a"
              + " project -- write it under the global tier instead, or choose a"
              + " different name. See DefinitionResolver's own rule for the same"
              + " name, which this refusal keeps a project from writing a file that"
              + " rule would only ever refuse to serve");
    }

    AgentRegistry.Loaded checked = checks.applyTo(read, origin);
    requireNothingDisabled(checked);
    // DefinitionChecks' own contract is "may disable, may never withhold
    // or throw" -- see this class's own javadoc. Re-run rather than
    // trust it: `read`'s two maps were already proven empty above, so
    // this only ever fires if `checks` did something its own interface
    // says it must not.
    requireNothingWithheld(checked);

    // Serialize service-owned writes across filenames: two different names can compete for the
    // same alias/guidance slot. External edits are still revalidated by the normal tier loader.
    requireValidAliases(projectId, candidate, checked.enabled().get(bareName));

    boolean existedBefore = Files.exists(target);
    if (existedBefore && !overwrite) {
      throw new DefinitionAlreadyExistsException(
          "'"
              + origin
              + "' already exists. Pass overwrite to replace it -- a"
              + " definition carries an executable prompt and tool grants with no"
              + " history behind the file, so a name that collides by accident must"
              + " not silently destroy what was there");
    }

    writeAtomically(dir, target, text);
    return new Written(target, origin, existedBefore ? Disposition.REPLACED : Disposition.CREATED);
  }

  /** Checks the family the candidate would join before accepting an edit on either API surface. */
  private void requireValidAliases(
      Long projectId, DefinitionSource candidate, AgentDefinition definition) {
    DefinitionSource tier =
        new LayeredDefinitions(
            List.of(
                candidate,
                new FilesystemDefinitions(data.agentsFor(projectId)),
                new FilesystemDefinitions(data.botsFor(projectId))));
    Map<String, AgentDefinition> merged = new LinkedHashMap<>(bootSet.byName());
    // Read individual entries before validating their union. Reading the union first would
    // discard a conflicting family, concealing the collision from the write's own refusal.
    for (DefinitionSource.Definition entry : tier.list()) {
      AgentRegistry.Loaded item =
          AgentRegistry.read(
              singleEntry(entry.name(), entry.origin(), entry.text()),
              knownTools(projectId),
              Set.of(),
              bootSet.names());
      merged.putAll(item.enabled());
    }
    Map<String, String> faults = AgentAliases.faults(merged);
    String alias = definition.alias() == null ? definition.name() : definition.alias();
    for (AgentDefinition member : AgentAliases.families(merged).getOrDefault(alias, List.of())) {
      String reason = faults.get(member.name());
      if (reason != null) throw new CallerFault(reason);
    }
  }

  /**
   * {@code name}, exactly as given, checked against {@link #BARE_NAME}.
   *
   * <p>NFC-normalised first and matched as given, not the normalised form: the pattern is
   * ASCII-only, so a string that needs normalising to match it does not match it either way, and
   * normalising is what stops a decomposed form of an <em>otherwise-ASCII</em> string reading as
   * different bytes than its composed form on a filesystem that normalises on write. This is the
   * actual boundary this class enforces — see its own javadoc, "{@code name} is a filename stem" —
   * and it runs before this method's caller ever builds a {@link Path} out of {@code name}.
   */
  private static String requireBareName(String name) {
    String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
    if (!BARE_NAME.matcher(normalized).matches()) {
      throw new CallerFault(
          "'"
              + name
              + "' is not a name this method will write. A definition's name is"
              + " 1 to 64 of "
              + BARE_NAME.pattern()
              + " -- no '/', no '.', no"
              + " whitespace, nothing else -- because it becomes a bare filename"
              + " stem with no further check on what it may contain. Rename the"
              + " definition and write it again");
    }
    return normalized;
  }

  /**
   * Refuses if {@code loaded} disabled the candidate — by non-emptiness of {@link
   * AgentRegistry.Loaded#disabled}, never by looking a reason up under {@code bareName}.
   *
   * <h2>Finding 5: the key a disablement lands under is not always the stem</h2>
   *
   * <p>{@code AgentRegistry.parse} accepts a frontmatter {@code name:} that is merely
   * <b>NFC-equal</b> to the stem, and then stores the RAW frontmatter string as the definition's
   * own {@code name()} — the two are allowed to be different {@link String}s that only compare
   * equal after normalising. A per-file fault such as an unparseable definition is caught in {@code
   * read()}'s own per-entry loop and keyed by the stem, so looking that one up under {@code
   * bareName} was always safe. A <b>set-level</b> fault — {@code disagreeingHalves}, a cycle — is
   * different: it runs after every entry is already in the {@code enabled} map, keyed by {@code
   * definition.name()}, and {@code refuse()} disables it under that same raw key. U+212A KELVIN
   * SIGN normalises to {@code 'K'}, so a candidate named {@code "K"} whose frontmatter reads {@code
   * name: â„ª} parses, and a self-contradicting {@code tools: [agent_run]}/no-{@code calls:} then
   * disables it under the literal Kelvin sign — a key {@code disabled().get("K")} never finds. This
   * class accepted such a candidate and wrote it, though the loader would immediately disable it:
   * the same writer/loader drift path traversal and the inherited-callee finding were, in the two
   * other directions.
   *
   * <p>The fix is the same move {@link #requireNothingWithheld} already makes for the other two
   * maps: a single-entry source can produce at most one disabled definition, so {@code
   * disabled().isEmpty()} alone answers "was this refused", with no key involved to disagree with
   * any other key.
   */
  private static void requireNothingDisabled(AgentRegistry.Loaded loaded) {
    if (!loaded.disabled().isEmpty()) {
      throw new CallerFault(loaded.disabled().values().iterator().next());
    }
  }

  /**
   * Both ways {@link AgentRegistry#read} can drop part of a definition without disabling it
   * outright. A single-entry source has exactly one definition, so any entry in either map belongs
   * to it -- there is no second agent's fault to filter out by key.
   */
  private static void requireNothingWithheld(AgentRegistry.Loaded loaded) {
    if (!loaded.withheldTools().isEmpty()) {
      throw new CallerFault(loaded.withheldTools().values().iterator().next());
    }
    if (!loaded.withheldEdges().isEmpty()) {
      throw new CallerFault(loaded.withheldEdges().values().iterator().next());
    }
  }

  /**
   * A {@link DefinitionSource} of exactly one definition, so {@link AgentRegistry#read}'s whole
   * validation ladder runs over a candidate that is not on disk yet, with no second implementation
   * of any of it.
   */
  private static DefinitionSource singleEntry(String name, String origin, String text) {
    DefinitionSource.Definition definition = new DefinitionSource.Definition(name, origin, text);
    return new DefinitionSource() {
      @Override
      public String describe() {
        return origin;
      }

      @Override
      public List<Definition> list() {
        return List.of(definition);
      }
    };
  }

  /**
   * Write {@code text} to {@code target}, or not at all.
   *
   * <p>To a sibling temp file <em>in {@code dir}</em> and not a system temp directory, so the
   * {@link Files#move} below crosses no filesystem boundary — {@link
   * StandardCopyOption#ATOMIC_MOVE} is only atomic within one, and a temp file elsewhere would
   * silently fall back to a copy plus delete on some platforms, which is the exact half-written
   * state this method exists to rule out.
   *
   * <p>{@code dir} is created here, on first write, and not by the constructor or by {@link #write}
   * before validation runs — {@code ImageStore.store}'s own precedent: a server shown nothing
   * leaves no empty directory on disk for an operator to go and explain, and a candidate that fails
   * validation must not have caused any filesystem change at all, directory included.
   *
   * <p>Package-private so {@link OrchestrationWriter} installs a definition with the same
   * discipline rather than a second copy of it.
   */
  static void writeAtomically(Path dir, Path target, String text) {
    Path tmp;
    try {
      Files.createDirectories(dir);
      tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
    } catch (IOException unwritable) {
      throw new UncheckedIOException(
          "the directory " + dir + " could not be prepared to write " + target.getFileName(),
          unwritable);
    }
    try {
      Files.writeString(tmp, text, StandardCharsets.UTF_8);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException unwritable) {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // Best-effort. A leftover *.tmp file names nothing a reader of
        // this directory treats as a definition -- FilesystemDefinitions
        // only ever reads *.md -- so it is litter and not a correctness
        // problem, and the exception below already reports the write
        // that actually failed.
      }
      throw new UncheckedIOException("'" + target + "' could not be written", unwritable);
    }
    makeReadable(target);
  }

  /**
   * {@code rw-r--r--} on {@code target}, which by now holds the exact bytes this method was asked
   * to write — best-effort, and never what turns a successful write into a reported failure.
   *
   * <h2>Finding 6: {@code chmod(2)}, not {@code open(2)}'s mode argument</h2>
   *
   * <p>An earlier version of this method asked {@link Files#createTempFile} to create the staged
   * file at {@code rw-r--r--} via {@link PosixFilePermissions#asFileAttribute}. That reaches {@code
   * open(2)}'s mode argument, which the process {@code umask} MASKS — under {@code umask 077} the
   * file lands {@code rw-------} regardless of what this class asked for, silently, and a
   * definition an operator could not {@code cat} without becoming root is exactly the regression
   * the earlier javadoc claimed to prevent. {@link Files#setPosixFilePermissions} instead calls
   * {@code chmod(2)} directly on the file that already exists, which sets the bits it is given and
   * is <b>not</b> subject to the umask — so this, run after the move rather than at creation, is
   * the one place this class can actually make the guarantee its javadoc states.
   *
   * <p>Best-effort: an {@link IOException} here is swallowed rather than wrapped and thrown. By the
   * time this runs, {@code target} is already the complete, correct file the caller asked for —
   * failing the whole call over a permission problem would report a definition as unwritten when it
   * is sitting on disk exactly as requested, which is a worse outcome for a caller to be told than
   * a file that is merely less readable than intended.
   *
   * <p>Skipped entirely on a filesystem with no {@code "posix"} attribute view — the mode bits this
   * sets do not exist there either way.
   */
  private static void makeReadable(Path target) {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return;
    }
    try {
      Files.setPosixFilePermissions(target, READABLE);
    } catch (IOException ignored) {
      // Best-effort, per this method's own javadoc.
    }
  }
}
