package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.DefineAgentRequest;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.DefinitionWriter;
import io.aeyer.plowshare.server.agents.Definitions;
import io.aeyer.plowshare.server.api.AgentView;
import io.aeyer.plowshare.server.api.DefinedAgent;
import java.util.Map;
import java.util.Objects;

/**
 * {@code agent.define} — write one definition to disk, and answer with what it resolves to. The
 * frame equivalent of {@code POST /v1/agents}.
 *
 * <h2>The one type on this surface whose success code is not fixed</h2>
 *
 * <p>{@link Code#CREATED} for a name that did not exist and {@link Code#OK} for one that did and
 * was replaced at the caller's own request — the standard reading of a {@code POST}, and exactly
 * the bit {@link DefinitionWriter.Written#disposition()} already carries. Collapsing the two into
 * one code would erase the only thing telling a caller whether it had just overwritten somebody's
 * work.
 *
 * <h2>It catches nothing, and the 409 is the reason that is worth saying</h2>
 *
 * <p>A name that exists and was <b>not</b> asked to be replaced raises {@code
 * DefinitionAlreadyExistsException}, which {@code Faults} maps to {@code CONFLICT}; everything else
 * {@link DefinitionWriter#write} refuses raises {@code CallerFault} and is a {@code BAD_REQUEST}.
 * Both reach a client through {@link FrameRouter} without this class naming either — a caller that
 * merely collided succeeds the instant {@code overwrite} is added, and one whose file will never
 * load fails identically on every resend, and merging the two would erase exactly the bit a
 * retrying caller needs.
 *
 * <h2>The branch a global write takes, and why it is not a resolution</h2>
 *
 * <p>{@link Definitions#define} synthesises a view for a write to the global tier rather than
 * resolving one: the boot set is read once at start-up and is never rebuilt, so this process cannot
 * see what was just written and will not until it restarts. {@link Definitions.Defined} carries
 * either a resolved definition or the reason there is none, never both and never neither, so this
 * handler cannot render a stale view by forgetting to branch — there is nothing there to render.
 */
public final class AgentDefineHandler implements FrameHandler {

  private final Definitions definitions;

  /**
   * @param definitions the same service the controller is injected with, which owns the ordering,
   *     the four refusals, the ceiling, the cache invalidation and the global tier's whole branch
   */
  public AgentDefineHandler(Definitions definitions) {
    this.definitions = Objects.requireNonNull(definitions, "definitions");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    DefineAgentRequest asked =
        Payloads.as(payload, DefineAgentRequest.class, FrameTypes.AGENT_DEFINE);
    Definitions.Defined defined =
        definitions.define(
            new Definitions.Ask(asked.project(), asked.name(), asked.text(), asked.overwrite()));

    Code code =
        defined.written().disposition() == DefinitionWriter.Disposition.CREATED
            ? Code.CREATED
            : Code.OK;
    AgentView view =
        defined.restartRequired()
            ? AgentView.disabled(defined.name(), defined.restartReason())
            : AgentView.of(defined.definition(), defined.withheld());
    return new Outcome(code, null, new DefinedAgent(view, defined.restartRequired()));
  }
}
