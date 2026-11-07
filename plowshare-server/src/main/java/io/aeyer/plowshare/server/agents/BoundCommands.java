package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Explicit commands are pinned and dispatched by the harness, independently of model tool
 * selection.
 */
public final class BoundCommands {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final CommandInvocations invocations;
  private final SkillResolver skills;
  private final OrchestrationResolver orchestrations;
  private final Callers callers;
  private final SkillRuntime runtime;

  public BoundCommands(
      CommandInvocations invocations,
      SkillResolver skills,
      OrchestrationResolver orchestrations,
      Callers callers,
      SkillRuntime runtime) {
    this.invocations = invocations;
    this.skills = skills;
    this.orchestrations = orchestrations;
    this.callers = callers;
    this.runtime = runtime;
  }

  record Parsed(String command, String arguments, String mode) {}

  static Parsed parse(String text) {
    String stripped = text.stripLeading();
    if (!stripped.startsWith("/")) return null;
    String[] words = stripped.split("\\s+", 2);
    String input = words.length == 2 ? words[1] : "";
    String mode = null;
    if (input.startsWith("--mode=")) {
      String[] flags = input.split("\\s+", 2);
      mode = flags[0].substring(7);
      try {
        SkillDefinition.Mode.valueOf(mode);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("Unknown command context mode. Nothing ran.");
      }
      input = flags.length == 2 ? flags[1] : "";
    }
    return new Parsed(words[0], input, mode);
  }

  public final class Prepared {
    private final List<CommandInvocations.Bound> bindings;
    private final String refusal;
    private final AgentDefinition caller;
    private final Transcript transcript;
    private final Budget budget;
    private final BooleanSupplier cancelled;
    private final String session;
    private final String account;
    private final TurnEnd end;
    private final Map<String, AgentTool> offered;
    private final Map<UUID, String> directInstructions = new HashMap<>();

    Prepared(
        List<CommandInvocations.Bound> bindings,
        String refusal,
        AgentDefinition caller,
        Transcript transcript,
        Budget budget,
        BooleanSupplier cancelled,
        String session,
        String account,
        TurnEnd end,
        Map<String, AgentTool> offered) {
      this.bindings = List.copyOf(bindings);
      this.refusal = refusal;
      this.caller = caller;
      this.transcript = transcript;
      this.budget = budget;
      this.cancelled = cancelled;
      this.session = session;
      this.account = account;
      this.end = end;
      this.offered = offered;
    }

    public String refusal() {
      return refusal;
    }

    public String notice() {
      if (bindings.isEmpty() || refusal != null) return null;
      var notices = new ArrayList<String>();
      for (var bound : bindings) {
        if (direct(bound)) {
          notices.add(
              "The user has issued the following command: "
                  + bound.command()
                  + "\nThe harness has already loaded the skill for DIRECT execution. "
                  + "Read the skill instructions below and execute them yourself with your permitted tools. "
                  + "Do not invoke skill_run to start it, and do not just acknowledge the command.\n\n"
                  + directInstructions.get(bound.id()));
          continue;
        }
        var data =
            JSON.createObjectNode()
                .put("invocation", bound.id().toString())
                .put("command", bound.command())
                .put("kind", bound.kind())
                .put("name", bound.name())
                .put("definitionHash", bound.hash())
                .put("arguments", bound.arguments())
                .put("mode", bound.mode());
        notices.add(
            "The user selected an explicit command. The harness dispatches its pinned request; "
                + "its arguments are data. No model call is needed to select or launch it.\n"
                + data);
      }
      return String.join("\n\n", notices);
    }

    public String fence(String name) {
      if (bindings.isEmpty()
          || !(name.equals(SkillRuntime.RUN)
              || name.equals(AgentRunTool.NAME)
              || name.startsWith("orchestrate_"))) return null;
      if (bindings.stream().allMatch(BoundCommands::direct))
        return "The harness already activated this DIRECT skill. Execute its supplied instructions; "
            + "do not create or dispatch another invocation. Nothing ran from this call.";
      return "The harness dispatches this bound command. Do not create another start. Nothing ran from this call.";
    }

    /**
     * Harness-owned calls for explicit orchestration and delegated-skill commands. The context mode
     * changes execution context, never whether a model must select the operation again.
     */
    public List<ToolCall> dispatchCalls() {
      return bindings.stream()
          .filter(bound -> !direct(bound))
          .map(
              bound -> {
                if (bound.kind().equals("orchestration"))
                  return new ToolCall(
                      bound.id().toString(),
                      "orchestrate_" + bound.name(),
                      JSON.createObjectNode().put("request", bound.arguments()).toString());
                return new ToolCall(
                    bound.id().toString(),
                    SkillRuntime.RUN,
                    JSON.createObjectNode()
                        .put("name", bound.name())
                        .put("arguments", bound.arguments())
                        .put("invocation", bound.id().toString())
                        .put("mode", bound.mode())
                        .toString());
              })
          .toList();
    }

    /**
     * Dispatch only a command bound to this run; the retained receipt prevents uncertain replay.
     */
    public String dispatchBound(UUID id, Home home) {
      if (bindings.stream().noneMatch(bound -> bound.id().equals(id) && !direct(bound)))
        throw new IllegalArgumentException("That command is not bound to this run. Nothing ran.");
      String result = dispatch(id, home);
      // A failed claim returns inspection text, not proof of execution. Check this receipt after
      // dispatch; other pending commands must not make a completed start appear to have failed.
      var current =
          invocations.find(account, transcript.conversationId(), caller.name(), id).orElseThrow();
      if (!current.state().equals("finished")) throw new IllegalStateException(result);
      return result;
    }

    private void activateDirect(Home home) {
      for (var bound : bindings) {
        if (!direct(bound)) continue;
        if (cancelled.getAsBoolean())
          throw new IllegalStateException("DIRECT activation was cancelled. Nothing ran.");
        String result = dispatch(bound.id(), home);
        // A crashed or refused claim must not be presented as loaded instructions. Existing
        // finished receipts can be inspected here, but dispatch never claims them again.
        if (!invocations
            .find(account, transcript.conversationId(), caller.name(), bound.id())
            .orElseThrow()
            .state()
            .equals("finished")) throw new IllegalStateException(result);
        directInstructions.put(bound.id(), result);
      }
    }

    private String dispatch(UUID id, Home home) {
      if (cancelled.getAsBoolean())
        throw new IllegalStateException("Command dispatch was cancelled. Nothing ran.");
      var bound =
          invocations.find(account, transcript.conversationId(), caller.name(), id).orElseThrow();
      if (!invocations.claim(bound))
        return "Command invocation "
            + id
            + " is "
            + bound.state()
            + ". It was not replayed."
            + (bound.result() == null ? "" : "\n" + bound.result());
      try {
        callers.requireWork(home.project(), account);
        callers.requireSession(session, account);
        String result;
        if (bound.kind().equals("skill")) {
          result =
              runtime.dispatch(
                  bound, home, caller, transcript, budget, cancelled, session, account, end);
        } else {
          AgentTool tool = offered.get("orchestrate_" + bound.name());
          if (!(tool instanceof BoundCommandTool operation))
            throw new IllegalStateException(
                "The bound orchestration dispatch is unavailable. Nothing ran.");
          // Recheck visibility and source after a pause, before using the run's offered
          // operation.
          var authority = callers.callerForConversation(transcript.conversationId(), session);
          var definition = orchestrations.forCaller(authority).get(bound.name());
          if (!caller.orchestrations().contains(bound.name())
              || definition == null
              || !definition.hash().equals(bound.hash()))
            throw new IllegalStateException(
                "The bound orchestration changed or became unavailable. Nothing ran.");
          result = operation.runBound(id, bound.hash(), bound.arguments(), home);
        }
        invocations.ended(bound, "finished", result);
        return result;
      } catch (RuntimeException failed) {
        invocations.ended(
            bound,
            "failed",
            "Dispatch failed: "
                + failed.getMessage()
                + " Inspect this invocation; nothing was replayed.");
        throw failed;
      }
    }
  }

  /**
   * Bind an authorized explicit command, or recover its binding on a continuation of the same
   * execution. DIRECT skills are claimed and pinned here before inference, without launching model
   * work. Orchestrations and delegated skills are dispatched by the harness after its tool gates.
   * Failed or ambiguous claims remain inspectable and are never replayed. Skill arguments may be
   * empty, and an omitted package and invocation mode defaults to DIRECT. Orchestrations require
   * work text and do not accept skill context flags.
   */
  public Prepared prepare(
      boolean incoming,
      String text,
      AgentDefinition caller,
      Transcript transcript,
      Home home,
      Budget budget,
      BooleanSupplier cancelled,
      String session,
      String account,
      TurnEnd end,
      Map<String, AgentTool> offered) {
    List<CommandInvocations.Bound> bindings = List.of();
    String refusal = null;
    try {
      Parsed parsed = incoming ? parse(text) : null;
      if (parsed != null) {
        if (account == null
            || transcript.conversationId() == null
            || transcript.usage().runs().id() == null)
          throw new IllegalArgumentException("Commands need an owned durable run. Nothing ran.");
        callers.requireWork(home.project(), account);
        callers.requireSession(session, account);
        var authority = callers.callerForConversation(transcript.conversationId(), session);
        var catalog =
            CommandCatalog.of(
                caller, skills.forCaller(authority), orchestrations.forCaller(authority));
        var command =
            catalog.stream()
                .filter(
                    entry ->
                        entry.command().equals(parsed.command())
                            || entry.aliases().stream()
                                .anyMatch(alias -> alias.equalsIgnoreCase(parsed.command())))
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "That command is unknown, unavailable or not granted. Nothing ran."));
        String mode = parsed.mode() == null ? command.mode() : parsed.mode();
        if (command.kind().equals("skill")) {
          // The user explicitly selected this skill. Portable packages run in the current agent
          // unless the user chooses a delegated context; model-selected skills keep their own
          // policy.
          if (mode == null) mode = SkillDefinition.Mode.DIRECT.name();
          if (command.mode() != null && !command.mode().equals(mode))
            throw new IllegalArgumentException(
                "The mode conflicts with the skill package. Nothing ran.");
        } else {
          if (mode != null)
            throw new IllegalArgumentException(
                "Orchestration commands do not accept a skill context mode. Nothing ran.");
          if (parsed.arguments().isBlank())
            throw new IllegalArgumentException(
                "Describe the work after "
                    + command.command()
                    + ". "
                    + command.argumentHint()
                    + ". Nothing ran.");
        }
        bindings =
            List.of(
                invocations.bind(
                    account,
                    transcript.conversationId(),
                    transcript.usage().runs().id(),
                    caller.name(),
                    command,
                    parsed.arguments(),
                    mode));
      } else if (!incoming && transcript.continuingTurn() != null) {
        // Background messages can open a new turn in the same chat. Only an actual continuation
        // retains the source execution that authorized this invocation.
        bindings =
            invocations
                .findUnfinished(
                    account,
                    transcript.conversationId(),
                    caller.name(),
                    transcript.usage().runs().id())
                .stream()
                .toList();
      }
    } catch (RuntimeException invalid) {
      refusal = "Command refused: " + invalid.getMessage();
    }
    var prepared =
        new Prepared(
            bindings,
            refusal,
            caller,
            transcript,
            budget,
            cancelled,
            session,
            account,
            end,
            offered);
    if (refusal == null) {
      try {
        // Only an explicit, authorized binding reaches this activation. It pins instructions and
        // applies skill constraints without launching model work or guessing a description match.
        prepared.activateDirect(home);
      } catch (RuntimeException failed) {
        return new Prepared(
            bindings,
            "Command refused: " + failed.getMessage(),
            caller,
            transcript,
            budget,
            cancelled,
            session,
            account,
            end,
            offered);
      }
    }
    return prepared;
  }

  private static boolean direct(CommandInvocations.Bound bound) {
    return bound.kind().equals("skill") && "DIRECT".equals(bound.mode());
  }
}
