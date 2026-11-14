package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Native execution is a distinct admission contract, never an exhausted inference agent. */
class NativeExecutionTest {
  private static final String SOURCE =
      ScriptProgram.MARKER
          + "\n"
          + """
      export const manifest = {
        name: 'native_tick', description: 'Deterministic tick.',
        'native-only': true, 'max-model-calls': 0, 'max-turns': 10,
        tools: [], stages: [{id: 'tick'}]
      };
      export function step() { return {tool: 'orchestration_finish', arguments: {done: true}}; }
      """;

  private OrchestrationDefinition parse(String source) {
    return OrchestrationParser.parse(
        new DefinitionSource.Definition("native_tick", "test", source),
        Set.of(),
        OrchestrationDefinition.Tier.PROJECT);
  }

  @Test
  void project_caps_keep_a_native_conductor_at_zero_model_calls() {
    // A project budget cap once turned a native conductor into a model-call definition with no
    // model: the constructor threw "model", Orchestrations logged the caps as unreadable and every
    // orchestration in that project ran without its project's caps.
    var nativeDefinition = parse(SOURCE).conductor();
    var capped = nativeDefinition.withCaps(25, 200);
    assertTrue(capped.nativeOnly());
    assertEquals(0, capped.maxModelCalls());
    assertEquals(25, capped.maxTurns());
    assertSame(nativeDefinition, nativeDefinition.withCaps(null, 200));
  }

  @Test
  void native_script_needs_no_model_and_retains_zero_calls() {
    var nativeDefinition = parse(SOURCE).conductor();
    assertTrue(nativeDefinition.nativeOnly());
    assertNull(nativeDefinition.model());
    assertEquals(0, nativeDefinition.maxModelCalls());
    assertEquals(SOURCE, nativeDefinition.prompt());
  }

  @Test
  void native_script_refuses_inference_configuration_and_delegation() {
    for (String source :
        new String[] {
          SOURCE.replace("tools: []", "model: 'reasoning', tools: []"),
          SOURCE.replace("'max-model-calls': 0", "'max-model-calls': 1"),
          SOURCE.replace("tools: []", "calls: ['worker'], tools: []"),
          SOURCE.replace("tools: []", "tools: ['agent_run']"),
          SOURCE.replace("'native-only': true", "'native-only': 'true'")
        }) {
      assertThrows(IllegalStateException.class, () -> parse(source));
    }
  }

  @Test
  void zero_allowance_cannot_spend_or_receive_an_automatic_or_operator_grant() {
    Budget allowance = Budget.noCalls();
    assertTrue(allowance.capped());
    assertEquals(0, allowance.limit());
    assertFalse(allowance.trySpend());
    assertFalse(allowance.increaseIfExhausted(5, ignored -> fail("native grant committed")));
    assertThrows(CallerFault.class, () -> allowance.changeTo(1));
    assertThrows(CallerFault.class, () -> allowance.changeToOrRefuse("run", 1));
    assertEquals(0, Budget.resumed(0, 0).spent());
    assertThrows(IllegalArgumentException.class, () -> Budget.resumed(0, 1));
    assertThrows(CallerFault.class, () -> Budget.of(0));
  }
}
