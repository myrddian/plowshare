package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.data.DataLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one writer of an orchestration definition file — spec 2026-09-29-orchestration-studio §3.4.
 * Called by the engine on a person's {@code install} answer, never handed to a model, exactly as
 * {@link DefinitionWriter} is never handed to one.
 *
 * <p><b>It checks the name and the size, not the definition.</b> Whether the text loads is the
 * trial's question, asked by the caller on these same bytes the moment before; asking it again here
 * would be a second loader with its own idea of a project's tiers.
 *
 * <p><b>What it replaces is kept once</b>, as {@code <name>.md.prev}: an install has one step of
 * undo, by renaming the file back. {@link FilesystemDefinitions} reads only {@code *.md}, so a
 * {@code .prev} is never loaded.
 *
 * <p><b>The old definition is preserved if the write fails.</b> If {@code <name>.md} already
 * exists, it is copied to {@code <name>.md.prev} first, then {@link
 * DefinitionWriter#writeAtomically} atomically replaces it. A failed write leaves the old
 * definition in place and its copy at {@code .prev}.
 */
public final class OrchestrationWriter {

  /** An orchestration's name: its tool is {@code orchestrate_<name>}, so the parser's own rule. */
  private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");

  private final DataLayout data;

  /** Where it landed, and where what it replaced was kept, or null when it replaced nothing. */
  public record Written(Path file, Path previous) {}

  public OrchestrationWriter(DataLayout data) {
    this.data = Objects.requireNonNull(data, "data");
  }

  /**
   * The installed file of {@code name}, when it already holds exactly {@code text} — an install
   * settled twice (a restart between writing and speaking) finds its own bytes there, and writing
   * again would copy them over the {@code .prev} the first write kept.
   */
  public Optional<Path> holding(long projectId, String name, String text) {
    Objects.requireNonNull(text, "text");
    if (name == null
        || !NAME.matcher(name).matches()
        || OrchestrationRegistry.REQUIRED.contains(name)) {
      return Optional.empty();
    }
    Path target = data.orchestrationsFor(projectId).resolve(name + suffix(text));
    try {
      return Files.isRegularFile(target) && Files.readString(target).equals(text)
          ? Optional.of(target)
          : Optional.empty();
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }

  public Written write(long projectId, String name, String text) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "'"
              + name
              + "' is not an orchestration's name: 1 to"
              + " 64 lower-case letters, digits and underscores. Nothing was installed.");
    }
    if (OrchestrationRegistry.REQUIRED.contains(name)) {
      throw new IllegalArgumentException(
          OrchestrationRegistry.protectedRefusal(name) + " Nothing was installed.");
    }
    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > DefinitionWriter.MAX_DEFINITION_BYTES) {
      throw new IllegalArgumentException(
          "the draft is "
              + bytes
              + " bytes, and no definition"
              + " may be more than "
              + DefinitionWriter.MAX_DEFINITION_BYTES
              + ". Nothing was installed.");
    }
    Path dir = data.orchestrationsFor(projectId);
    String extension = suffix(text);
    Path target = dir.resolve(name + extension);
    Path other = dir.resolve(name + (extension.equals(".js") ? ".md" : ".js"));
    if (Files.exists(target) && Files.exists(other))
      throw new IllegalStateException(
          "two orchestration formats exist for '"
              + name
              + "'; resolve the duplicate before installing");
    Path old = Files.exists(target) ? target : other;
    Path previous = old.resolveSibling(old.getFileName() + ".prev");
    try {
      Files.createDirectories(dir);
      boolean replacing = Files.exists(old);
      if (replacing) {
        Files.copy(old, previous, StandardCopyOption.REPLACE_EXISTING);
      }
      DefinitionWriter.writeAtomically(dir, target, text);
      if (replacing && !old.equals(target)) Files.delete(old);
      return new Written(target, replacing ? previous : null);
    } catch (IOException failed) {
      throw new UncheckedIOException(
          "the definition could not be written to "
              + target
              + ": "
              + failed.getMessage()
              + ". The old definition remains at "
              + target
              + " and its copy is at "
              + previous
              + " if one existed.",
          failed);
    }
  }

  private static String suffix(String text) {
    return io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram.isScript(text)
        ? ".js"
        : ".md";
  }
}
