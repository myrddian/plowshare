package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.FileAccess;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.hooks.ToolPost;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Skill checks on the harness's existing tool stages. Whole content is checked before execution;
 * acknowledged edits are checked afterward against a fenced snapshot, with correction guidance. No
 * stage rewrites files, widens grants, marks a file read or replays a mutation.
 */
public final class SkillFileHooks implements Hooks {
  private final ProviderRouter files;

  /**
   * Captures the run's existing router; its providers recheck scopes and ownership on each read.
   */
  public SkillFileHooks(ProviderRouter files) {
    this.files = Objects.requireNonNull(files, "files");
  }

  private record Edit(Path path, String content) {}

  @Override
  public ToolPre toolPre(HookContext context, String tool, String arguments) {
    Edit edit = edit(tool, arguments);
    if (edit == null || edit.content() == null) return ToolPre.allowed(arguments);
    try {
      FileProvider provider = files.providerFor(home(context), edit.path());
      SkillFileValidation.Target target = SkillFileValidation.target(edit.path(), provider.roots());
      if (target == null) return ToolPre.allowed(arguments);
      String invalid = target.check(edit.content());
      if (invalid.isEmpty()) return ToolPre.allowed(arguments);
      String reason = "Invalid skill source; nothing was written.\n" + invalid;
      return new ToolPre(
          arguments, reason, List.of(record(Stage.TOOL_PRE, HookRecord.DENY, reason)));
    } catch (WorkspaceRefusedException | WorkspaceUnavailableException unavailable) {
      String reason =
          "Skill validation could not establish filesystem access; nothing was written. "
              + ToolArguments.firstLine(unavailable);
      return new ToolPre(
          arguments, reason, List.of(record(Stage.TOOL_PRE, HookRecord.FAILED, reason)));
    }
  }

  @Override
  public ToolPost toolPost(HookContext context, String tool, String arguments, String result) {
    if (!tool.equals(FileTools.EDIT_NAME)
        || !ToolLines.OK.equals(ToolLines.outcome(tool, result))) {
      return ToolPost.untouched(result);
    }
    Edit edit = edit(tool, arguments);
    if (edit == null) return ToolPost.untouched(result);
    String example = "";
    try {
      FileProvider provider = files.providerFor(home(context), edit.path());
      SkillFileValidation.Target target = SkillFileValidation.target(edit.path(), provider.roots());
      if (target == null) return ToolPost.untouched(result);
      example = target.example();
      var metadata = provider.fingerprint(edit.path());
      if (metadata.size() > ChannelDefinitions.MAX_DEFINITION_BYTES) {
        return note(
            result,
            "The edit was saved, but the skill file exceeds the source limit."
                + " Reduce it before using it."
                + target.example(),
            HookRecord.NOTE);
      }
      // Raw snapshots preserve line endings and detect concurrent changes. They do not affect
      // the file tools' read-before-replace ledger. Older clients may refuse this capability.
      byte[] bytes =
          provider.snapshot(edit.path(), metadata, (int) ChannelDefinitions.MAX_DEFINITION_BYTES);
      String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      String invalid = target.check(source);
      return note(
          result,
          invalid.isEmpty()
              ? "The saved skill source is valid."
              : "The edit was saved, but the skill source is invalid. Repair the saved file with"
                  + " a new edit before using it.\n"
                  + invalid,
          HookRecord.NOTE);
    } catch (CharacterCodingException invalid) {
      return note(
          result, "The edit was saved, but skill source must be UTF-8." + example, HookRecord.NOTE);
    } catch (WorkspaceRefusedException | WorkspaceUnavailableException unavailable) {
      // Retain the acknowledged mutation when the validation read cannot finish. A post check
      // failure must not hide the write receipt or invite replay of an already completed edit.
      return note(
          result,
          "The edit was saved, but validation could not read a complete snapshot: "
              + ToolArguments.firstLine(unavailable)
              + ". Inspect the saved file before correcting it; do not repeat the acknowledged edit.",
          // FAILED means the post hook withheld the result in the existing harness contract.
          // This is an explicit unverified check beside an acknowledged write, not a withheld
          // result.
          HookRecord.NOTE);
    }
  }

  private static Home home(HookContext context) {
    return context.project() == null ? Home.global() : Home.of(context.project());
  }

  private static ToolPost note(String result, String message, String decision) {
    String added = "[Skill validation] " + message;
    return new ToolPost(result + "\n" + added, List.of(record(Stage.TOOL_POST, decision, added)));
  }

  private static HookRecord record(Stage stage, String decision, String message) {
    return new HookRecord(
        "harness:skill-validation",
        null,
        Tier.HARNESS,
        stage,
        FileTools.EDIT_NAME,
        decision,
        decision.equals(HookRecord.NOTE) ? null : message,
        decision.equals(HookRecord.NOTE) ? message : null,
        null,
        0);
  }

  /** Decode candidate edit arguments at the tool boundary; the file tool owns malformed inputs. */
  private static Edit edit(String tool, String json) {
    if (!tool.equals(FileTools.EDIT_NAME)) return null;
    try {
      var arguments = ToolArguments.parse(json, tool, "a file edit");
      var field = arguments.get("path");
      if (field == null || !field.isTextual()) return null;
      Path path = Path.of(field.asText().strip());
      if (!path.isAbsolute()
          || path.getFileName() == null
          || !Set.of("SKILL.md", "skills.yml").contains(path.getFileName().toString())) {
        return null;
      }
      var content = arguments.get("content");
      boolean whole = content != null && !content.isNull();
      boolean old = arguments.hasNonNull("old");
      boolean replacement = arguments.hasNonNull("new");
      if (whole) {
        if (!content.isTextual() || old || replacement) return null;
      } else if (!old
          || !replacement
          || !arguments.get("old").isTextual()
          || !arguments.get("new").isTextual()) {
        return null;
      }
      return new Edit(FileAccess.canonical(path), whole ? content.asText() : null);
    } catch (ToolArguments.BadArguments | InvalidPathException invalid) {
      return null;
    }
  }
}
