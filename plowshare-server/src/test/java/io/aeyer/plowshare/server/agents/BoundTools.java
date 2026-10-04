package io.aeyer.plowshare.server.agents;

import java.util.Set;
import java.util.TreeSet;

/**
 * The tool names this server binds, for a test that has to hand a known-tool set to {@code
 * AgentRegistry.load}.
 *
 * <p><b>Named for what it holds rather than for who reads it.</b> An earlier name, {@code
 * ShippedTools}, glued two different sets into one phrase: a tool can be in this set while no
 * shipped definition declares it. What ships is a directory of agents; what this is, is the boot's
 * side of the same question.
 *
 * <p><b>One owner, because three tests need it and a set written out three times is the copy that
 * drifts.</b> {@code AgentRegistry.load} validates the whole directory at once, so every test that
 * reaches {@code src/main/resources/agents} for one definition has to hand over a set covering
 * every <em>other</em> shipped definition too — which is why {@code ScribeTest} and {@code
 * CuratorTest} each carried a set naming one memory tool, and why both broke on the first shipped
 * agent that declared anything else.
 *
 * <p><b>It is a second derivation of {@code JobRuntime.knownTools()} and not a substitute for one,
 * and the difference is asserted rather than argued.</b> {@code knownTools()} derives its answer
 * from the tools actually registered plus two flags; this reassembles the composition rule by hand,
 * and the two <em>can</em> disagree — the day {@code AgentsConfig} registers a third shared tool
 * the way {@code MemoryTools.Recall} was added, that method grows and this does not. An earlier
 * version of this sentence said a set assembled here "could only ever agree by construction", which
 * was the argument for not checking; it was false, and {@code
 * AgentsConfigTest.the_known_tool_set_the_tests_assemble_is_the_set_the_boot_binds} is the check it
 * was arguing away.
 *
 * <p>One owner therefore fixes the <em>rename</em> case outright and reduces the new-name case from
 * three break sites to one; that one assertion is what closes it.
 *
 * <p>Assembled from the tool layer's own constants rather than from literals, so that renaming a
 * tool moves both halves together instead of leaving a test waving through a name nothing answers
 * to.
 */
public final class BoundTools {

  private BoundTools() {}

  /**
   * Every name {@code JobRuntime.knownTools} returns for a runtime that was given both mechanisms —
   * an agent graph and a filesystem — which is the runtime {@code AgentsConfig} wires.
   */
  public static Set<String> boundByThisServer() {
    Set<String> names = new TreeSet<>(FileTools.NAMES);
    names.add(RunTool.NAME);
    // The todo list, gated by nothing: AgentsConfig.jobRuntime requires the board.
    names.addAll(TodoTools.NAMES);
    names.add(MemoryTools.RECALL_NAME);
    names.add(MemoryTools.READ_NAME);
    names.add(MemoryTools.WRITE_NAME);
    names.add(MemoryNavigateTool.NAME);
    // The corpus, gated by nothing: DocumentsConfig is unconditional, the
    // corpus has no tier a definition could change, and a corpus with
    // nothing in it is an ordinary state the tool answers in words rather
    // than a wiring a deployment declined.
    names.add(DocumentTools.SEARCH_NAME);
    names.add(InformationTool.READ);
    names.add(InformationTool.WRITE);
    // The corpus as a listing, gated by nothing for the same reason as the
    // search one line up. It reads DocumentStore rather than
    // RetrievalService, which is not a second gate: DocumentsConfig binds
    // both unconditionally, and a corpus holding nothing is a state this
    // tool answers in words rather than a wiring a deployment declined.
    names.add(DocumentTools.LIST_NAME);
    names.addAll(RetrievalTools.NAMES);
    names.addAll(ArchiveReadTools.NAMES);
    names.add(ConversationContextTool.NAME);
    names.add(ConversationSearchTool.NAME);
    // The per-document ask, gated by nothing either, and for the corpus's
    // reason one line up rather than for delegation's. A context that binds
    // no Deliberation is not a deployment that declined this tool: the tool
    // is still registered and still offered, and what it answers is
    // Asking.NONE's ending in words. The gated things here are the ones a
    // deployment can genuinely be without -- an agent directory, a
    // filesystem -- and a corpus is not one of them.
    names.add(AskTool.NAME);
    // The harness's own page read, gated by nothing: FetchConfig binds
    // FetchService unconditionally, the same reason the corpus lines above
    // are unconditional, and a fetch that fails is a refusal this tool
    // answers in words rather than a wiring a deployment declined.
    names.add(FetchTool.NAME);
    // search, gated by nothing for the same reason fetch one line up is
    // not: SearchConfig binds SearchService unconditionally, and a search
    // that refuses is a refusal this tool answers in words rather than a
    // wiring a deployment declined.
    names.add(SearchTool.NAME);
    names.addAll(OutgoingTool.NAMES);
    // A read of the server clock, gated by nothing and requiring no scope.
    names.add(GetDateTool.NAME);
    // The bounded persisted-log read, wired from stores ArchiveConfig binds
    // on every boot. A project with no conversation rows is an empty state,
    // not a deployment without this tool.
    names.add(ConversationTrajectoryTool.NAME);
    names.add(ConversationSearchTool.NAME);
    names.add(AgentRunTool.NAME);
    names.add(SendMessageTool.NAME);
    // Gated by nothing, unlike the two above. Every run has a transcript by
    // signature, so the tool can always be built -- see
    // AgentsConfigTest.the_runtime_binds_the_memory_tools_delegation_and_
    // the_file_tools, which argues why that is not the superset trap.
    names.add(ResultTools.READ_NAME);
    names.add(ResultTools.LIST_NAME);
    return names;
  }
}
