package io.aeyer.plowshare.server.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The stages, as something the runtime and a log's doors call.
 *
 * <h2>Layers, and why a chain rather than a list the runtime walks</h2>
 *
 * <p>The harness runs first and a project's hooks after it; the runtime does not know how many
 * layers there are or what is in them. {@link #chain} is the one place the ordering rules live:
 * additions concatenate, a rewrite or a redaction is what the next layer sees, and a denial ends
 * the chain — which is what "the strictest decision wins" comes to when decisions are applied in
 * order.
 *
 * <p><b>An implementation must not throw.</b> A hook that fails is recorded as a {@code failed}
 * decision with the stage's failure policy applied: tool stages deny, prompt stages add nothing.
 * {@code JobRuntime} guards the call anyway, because a guard nobody needed is cheaper than a turn
 * that dies.
 */
public interface Hooks {

  Hooks NONE = new Hooks() {};

  default PromptPre promptPre(HookContext context, String utterance) {
    return PromptPre.NOTHING;
  }

  default PromptPost promptPost(HookContext context, String reply, List<String> toolsAsked) {
    return PromptPost.untouched(reply);
  }

  default ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
    return ToolPre.allowed(argumentsJson);
  }

  default ToolPost toolPost(HookContext context, String tool, String argumentsJson, String result) {
    return ToolPost.untouched(result);
  }

  default StepPost stepPost(HookContext context, Step step) {
    return StepPost.NOTHING;
  }

  /** {@code log.open}: after the log's row is committed and before its first turn. */
  default LogOpen logOpen(HookContext context, LogOpening opening) {
    return LogOpen.NOTHING;
  }

  /** {@code log.close}: the log will take no more turns. */
  default Notified logClose(HookContext context, LogClosing closing) {
    return Notified.NOTHING;
  }

  /** {@code delivery.pre}: a result is about to be delivered. */
  default DeliveryPre deliveryPre(HookContext context, Handover handover) {
    return DeliveryPre.NOTHING;
  }

  /** {@code delivery.post}: a result was delivered. */
  default Notified deliveryPost(HookContext context, Handover handover) {
    return Notified.NOTHING;
  }

  /** {@code stage.pre}: a stage is about to start or be returned to; fails closed. */
  default Gate stagePre(HookContext context, StageStart start) {
    return Gate.NOTHING;
  }

  /** {@code stage.post}: a stage is about to be marked done, its system checks passed. */
  default Gate stagePost(HookContext context, StageDone done) {
    return Gate.NOTHING;
  }

  /** {@code approval.pre}: a person is about to be asked; may deny or note, never allow. */
  default Gate approvalPre(HookContext context, Approving approving) {
    return Gate.NOTHING;
  }

  /** {@code approval.post}: an approval was answered or revoked. */
  default Notified approvalPost(HookContext context, ApprovalAnswer answer) {
    return Notified.NOTHING;
  }

  /**
   * {@code fold.post}: the folder has summarised a span and the fold is not yet saved. Every layer
   * shares {@code deadline}: the whole chain's running gets one hook time limit (spec
   * 2026-09-28-hooks-reach-the-log decision 4, amended 2026-09-29). Loading a changed hook file or
   * building a fresh context is not counted against it; see {@link Deadline}.
   *
   * <p><b>A Java layer is not pre-empted.</b> Project hooks run in GraalJS contexts the pool closes
   * at the deadline; a harness hook is plain Java nothing can stop, so it must watch {@link
   * Deadline#remaining()} itself and answer before it reaches zero.
   */
  default FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
    return FoldPost.NOTHING;
  }

  /** The layers in the order given: harness first, then the project. */
  static Hooks chain(Hooks... layers) {
    List<Hooks> ordered = List.of(layers);
    return new Hooks() {
      @Override
      public PromptPre promptPre(HookContext context, String utterance) {
        List<Addition> additions = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          PromptPre one = layer.promptPre(context, utterance);
          additions.addAll(one.additions());
          records.addAll(one.records());
        }
        return new PromptPre(additions, records);
      }

      @Override
      public PromptPost promptPost(HookContext context, String reply, List<String> toolsAsked) {
        String current = reply;
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          PromptPost one = layer.promptPost(context, current, toolsAsked);
          current = one.reply();
          records.addAll(one.records());
        }
        return new PromptPost(current, records);
      }

      @Override
      public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
        String current = argumentsJson;
        List<HookRecord> records = new ArrayList<>();
        // An allow is bound to the arguments it judged: a later layer that changes them
        // voids it, unless that layer allows what it made (spec
        // 2026-09-30-local-hooks-are-served, Task 7 fix round 2). ScriptHooks keeps the
        // same rule between the hooks of one layer.
        String allowedFor = null;
        String asked = null;
        for (Hooks layer : ordered) {
          ToolPre one = layer.toolPre(context, tool, current);
          records.addAll(one.records());
          if (one.isDenied()) {
            return new ToolPre(current, one.denied(), records);
          }
          if (!one.arguments().equals(current)) {
            allowedFor = null;
          }
          if (one.explicitlyAllowed()) {
            allowedFor = one.arguments();
          }
          if (asked == null) {
            asked = one.asked();
          }
          current = one.arguments();
        }
        return new ToolPre(current, null, records, asked, allowedFor);
      }

      @Override
      public ToolPost toolPost(
          HookContext context, String tool, String argumentsJson, String result) {
        String current = result;
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          ToolPost one = layer.toolPost(context, tool, argumentsJson, current);
          current = one.result();
          records.addAll(one.records());
        }
        return new ToolPost(current, records);
      }

      @Override
      public StepPost stepPost(HookContext context, Step step) {
        List<String> notes = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          StepPost one = layer.stepPost(context, step);
          notes.addAll(one.notes());
          records.addAll(one.records());
        }
        return new StepPost(notes, records);
      }

      // The log stages (spec 2026-09-28-hooks-reach-the-log §4): additions, notes and keeps
      // concatenate, notices are collected, and a gate's first denial ends the chain.
      @Override
      public LogOpen logOpen(HookContext context, LogOpening opening) {
        List<String> additions = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          LogOpen one = layer.logOpen(context, opening);
          additions.addAll(one.additions());
          records.addAll(one.records());
        }
        return new LogOpen(additions, records);
      }

      @Override
      public Notified logClose(HookContext context, LogClosing closing) {
        return notified(layer -> layer.logClose(context, closing));
      }

      @Override
      public DeliveryPre deliveryPre(HookContext context, Handover handover) {
        List<String> notes = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          DeliveryPre one = layer.deliveryPre(context, handover);
          notes.addAll(one.notes());
          records.addAll(one.records());
        }
        return new DeliveryPre(notes, records);
      }

      @Override
      public Notified deliveryPost(HookContext context, Handover handover) {
        return notified(layer -> layer.deliveryPost(context, handover));
      }

      @Override
      public Gate stagePre(HookContext context, StageStart start) {
        return gated(layer -> layer.stagePre(context, start));
      }

      @Override
      public Gate stagePost(HookContext context, StageDone done) {
        return gated(layer -> layer.stagePost(context, done));
      }

      @Override
      public Gate approvalPre(HookContext context, Approving approving) {
        return gated(layer -> layer.approvalPre(context, approving));
      }

      @Override
      public Notified approvalPost(HookContext context, ApprovalAnswer answer) {
        return notified(layer -> layer.approvalPost(context, answer));
      }

      @Override
      public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
        List<FoldPost.Kept> kept = new ArrayList<>();
        List<Notified.Notice> notices = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          FoldPost one = layer.foldPost(context, summarised, deadline);
          kept.addAll(one.kept());
          notices.addAll(one.notices());
          records.addAll(one.records());
        }
        return new FoldPost(kept, notices, records);
      }

      /**
       * The gates' merge: notes and records concatenate in layer order, and the first denial ends
       * the chain with what came before it — "the strictest decision wins" applied in order, as
       * {@code toolPre} does (spec §4).
       */
      private Gate gated(Function<Hooks, Gate> call) {
        List<String> notes = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          Gate one = call.apply(layer);
          notes.addAll(one.notes());
          records.addAll(one.records());
          if (one.isDenied()) {
            return new Gate(one.denied(), notes, records);
          }
        }
        return new Gate(null, notes, records);
      }

      /**
       * The notified merge every notifying stage shares: every layer's notices and records
       * concatenate, in layer order (pre-flight ruling F9).
       */
      private Notified notified(Function<Hooks, Notified> call) {
        List<Notified.Notice> notices = new ArrayList<>();
        List<HookRecord> records = new ArrayList<>();
        for (Hooks layer : ordered) {
          Notified one = call.apply(layer);
          notices.addAll(one.notices());
          records.addAll(one.records());
        }
        return new Notified(notices, records);
      }
    };
  }
}
