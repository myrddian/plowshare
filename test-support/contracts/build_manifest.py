#!/usr/bin/env python3
"""Build/check the source-backed phase 0 inventory, optionally package its handoff.

Uses only the Python standard library. Run from any directory. This is a source
inventory, not a substitute for executing a frontend or validating WS payloads.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
FIXTURE = "test-support/contracts/mcp-compatibility.json"
WS = "plowshare-server/src/main/java/io/aeyer/plowshare/server/ws"
SNAPSHOT_BASE = "public-source"

AGENTS = "plowshare-server/src/main/java/io/aeyer/plowshare/server/agents"
# Audited adapter identities, checked against both their literal schema names
# and their actual constructors in the production runtime assembly below.
INTERNAL_BINDINGS = {
    "memory_read": ("MemoryTools", "MemoryTools.Read"),
    "memory_recall": ("MemoryTools", "MemoryTools.Recall"),
    "memory_write": ("MemoryTools", "MemoryTools.Write"),
    "memory_navigate": ("MemoryNavigateTool", "MemoryNavigateTool"),
    "memory_index": ("ArchiveReadTools", "ArchiveReadTools.Index"),
    "document_search": ("DocumentTools", "DocumentTools.Search"),
    "document_list": ("DocumentTools", "DocumentTools.AgentList"),
    "document_ask": ("AskTool", "AskTool"),
    "document_retrieve": ("RetrievalTools", "RetrievalTools.Retrieve"),
    "document_rank": ("RetrievalTools", "RetrievalTools.Rank"),
    "document_outline": ("RetrievalTools", "RetrievalTools.Outline"),
    "document_citations": ("RetrievalTools", "RetrievalTools.Citations"),
    "conversation_trajectory": ("ConversationTrajectoryTool", "ConversationTrajectoryTool"),
    "conversation_search": ("ConversationSearchTool", "ConversationSearchTool"),
    "conversation_list": ("ArchiveReadTools", "ArchiveReadTools.Conversations"),
    "conversation_chat": ("ArchiveReadTools", "ArchiveReadTools.Chat"),
    "conversation_context": ("ConversationContextTool", "ConversationContextTool"),
    "search": ("SearchTool", "SearchTool"), "fetch": ("FetchTool", "FetchTool"),
    "agent_run": ("AgentRunTool", "AgentRunTool"),
}
OPERATOR_BOUNDARIES = {
    "information": "scoped information capability wrapper; internal agents use root-bound information_read/information_write",
    **dict.fromkeys(["project_define", "project_workspace_set", "project_lend", "project_unlend", "project_move", "project_forget"],
                    "operator control: project administration bounds the run's filesystem authority"),
    **dict.fromkeys(["memory_digest", "memory_curate", "memory_proposals", "memory_resolve"],
                    "operator control: memory maintenance and proposal judgement retain their existing grants policy"),
    **dict.fromkeys(["agent_poll", "agent_result", "agent_cancel"],
                    "operator job lifecycle: internal delegation uses agent_run and returns its outcome directly"),
    "client_root_project_here": "local platform presence: explicit filesystem rooting by the caller",
}
EXPLICIT_FRAMES = {
    "memory_curate": "agent.curate", "memory_proposals": "proposal.list",
    "memory_resolve": "proposal.resolve", "agent_poll": "job.status",
    "agent_result": "job.status", "agent_cancel": "job.cancel",
    "project_workspace_set": "project.workspace", "document_outline": "document.detail",
    "search": "web.search", "fetch": "web.fetch",
    "client_root_project_here": None, "information": "information.list",
}
DESKTOP_FRAMES = {
    "project.list", "conversation.list", "conversation.open", "conversation.trajectory",
    "agent.list", "agent.run", "job.status", "job.cancel", "job.stream",
    "approval.list", "approval.answer", "board.topics", "board.messages", "swarm.status",
    "conversation.follow", "inbox.list", "inbox.read", "orchestration.list", "orchestration.status",
}
BACKGROUND_PARITY = [{'id': 'orchestration-discovery',
  'frames': ['orchestration.definitions', 'orchestration.list', 'orchestration.status'],
  'sources': ['plowshare-tui/src/logic/session.ts', 'plowshare-tui/src/logic/record.ts'],
  'existingTui': 'project-scoped definitions; account-wide recent runs; separate bounded live-state listings, root '
                 'trees, stages and phases',
  'desktopStatus': 'implemented; project definition browser, bounded account runs, root/child/stage inspection, source '
                   'and grant metadata'},
 {'id': 'orchestration-records',
  'frames': ['orchestration.record'],
  'pushes': ['orchestration.recorded', 'orchestration.changed'],
  'sources': ['plowshare-tui/src/logic/record.ts',
              'plowshare-tui/src/logic/explorer.ts',
              'plowshare-tui/src/view/main.ts'],
  'existingTui': 'watch live records, page/filter by stable ordinal, reread settled tool outcomes, inspect actor '
                 'conductor and delegated conversations',
  'desktopStatus': 'implemented; stable ordinal paging/filtering, settlement rereads and actor trajectory windows'},
 {'id': 'human-attention',
  'frames': ['orchestration.list',
             'orchestration.status',
             'orchestration.answer',
             'orchestration.cancel',
             'approval.list',
             'approval.answer'],
  'sources': ['plowshare-tui/src/logic/questions.ts',
              'plowshare-tui/src/logic/caps.ts',
              'plowshare-tui/src/logic/draftview.ts',
              'plowshare-tui/src/logic/approval.ts',
              'plowshare-tui/src/view/main.ts'],
  'existingTui': 'coalesced account attention checks on startup/push/15-second timer; deferred and structured person '
                 'questions, cap/stuck/check/acceptance decisions and remotely settled dialogs',
  'desktopStatus': 'implemented; explicit current-question decisions, exact reviewed installation, deferred questions, '
                   'run cancellation and command grant scopes/revocation'},
 {'id': 'account-inbox',
  'frames': ['inbox.list', 'inbox.read'],
  'pushes': ['inbox.changed'],
  'sources': ['plowshare-tui/src/logic/session.ts', 'plowshare-tui/src/view/main.ts'],
  'existingTui': 'quiet unread count; display up to 20 unread items; validate whole page and receipt only displayed '
                 'IDs; receipt failure does not discard visible content',
  'desktopStatus': 'implemented; quiet startup unread count, account push badge, native Inbox includes read and unread '
                   'items with persisted readAt status, loaded-prefix paging and explicit single displayed-item '
                   'receipts; receipt refusal preserves content; read items remain after refresh and older pages never '
                   'affect unread counts'},
 {'id': 'later-conversation-delivery',
  'frames': ['conversation.follow', 'conversation.trajectory'],
  'pushes': ['conversation.appended'],
  'sources': ['plowshare-tui/src/logic/session.ts',
              'plowshare-tui/src/view/main.ts',
              'plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/Watchers.java',
              'plowshare-server/src/main/java/io/aeyer/plowshare/server/delivery/PersonDelivery.java',
              'plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/Delivery.java'],
  'existingTui': 'follow/catch-up with ordinal reconciliation and harness provenance; server owns deferred delivery, '
                 'inbox fallback, retries and settlement',
  'desktopStatus': 'implemented for selected chat and open trajectory windows; complete-set follows, ordinal paging '
                   'and reconnect catch-up preserve scrollback and harness provenance; older servers show a warning '
                   'without downgrade'},
 {'id': 'project-cap-settings',
  'frames': ['orchestration.caps'],
  'sources': ['plowshare-tui/src/logic/caps.ts', 'plowshare-tui/src/view/main.ts',
              'sdk/node/src/settings.ts'],
  'existingTui': 'local project JSON or legacy environment.yml changes before continuation answer, then effective settings '
                 'read/applied; failed write sends no answer',
  'desktopStatus': 'implemented; reviewed local project settings change then orchestration.caps reload, preserving '
                   'saved/unconfirmed status on failure'},
 {'id': 'scheduled-event-work',
  'frames': ['schedule.save', 'schedule.sync', 'schedule.files', 'schedule.read',
             'schedule.define',
             'schedule.list',
             'schedule.pause',
             'schedule.forget',
             'trigger.define',
             'trigger.list',
             'trigger.pause',
             'trigger.forget',
             'event.fire',
             'firing.list'],
  'sources': ['plowshare-tui/src/logic/session.ts', 'plowshare-tui/src/view/main.ts'],
  'existingTui': 'review proposals before defining work; pause/forget schedules and triggers; fire events and inspect '
                 'firings',
  'desktopStatus': 'implemented; reviewed schedule and trigger definitions, pause/forget, event fire and firing '
                   'inspection'}]
TUI_FLOW_TOOLS = {
    "agent_run", "agent_poll", "agent_result", "agent_cancel", "conversation_list",
    "conversation_trajectory", "conversation_context",
}
MEMORY_EXTRA = {
    "memory_reconsider": "proposal.reconsider", "memory_invalidate": "memory.invalidate",
    "memory_reembed": "memory.reembed",
}


def read(path):
    return (ROOT / path).read_text()


def inventory():
    views_source = "sdk/typescript/src/operations/views.ts"
    job_view_source = "sdk/typescript/src/binding/job-view.ts"
    retrieval_source = "sdk/typescript/src/operations/retrieval.ts"
    retrieval_text = read(retrieval_source)
    retrieval_types = [name for name in re.findall(r"^export interface (\w+)\s*\{", retrieval_text, re.M) if name != "RetrievalReplies"]
    retrieval_operations = re.findall(r"'([^']+)':", re.search(r"export interface RetrievalReplies \{([^}]+)\}", retrieval_text, re.S).group(1))
    retrieval_fixture = "test-support/contracts/ws-retrieval-fixtures.json"
    assert len(retrieval_types) == 37 and len(retrieval_operations) == 21, "shared retrieval response inventory changed"
    assert set(json.loads(read(retrieval_fixture))["replies"]) == set(retrieval_operations), "retrieval reply fixtures are stale"
    response_readers = re.findall(r"^export function (\w+)\(", read(views_source), re.M)
    response_types = re.findall(r"^export interface (\w+)\s*\{", read(views_source), re.M)
    assert len(response_readers) == 14 and len(response_types) == 11, "shared view response inventory changed"
    for path in (ROOT / "plowshare-desktop/src").rglob("*.ts"):
        assert not re.search(r"(?:from\s*|import\s*\()\s*['\"][^'\"]*plowshare-tui", path.read_text()), f"desktop reaches TUI source: {path}"
    fixture = json.loads(read(FIXTURE))
    tools = fixture["toolsList"]["result"]["tools"]
    assert len(tools) == 35 and len({t["name"] for t in tools}) == 35
    assert json.loads(read("plowshare-mcp/src/tools.json")) == tools, "TS MCP menu differs from Java's reviewed contract"
    mcp_source = read("plowshare-mcp/src/adapter.ts")
    assert set(re.findall(r"case '([^']+)':", mcp_source)) == {t["name"] for t in tools}, "TS MCP handlers differ from its menu"
    assert set(INTERNAL_BINDINGS).isdisjoint(OPERATOR_BOUNDARIES)
    assert {t["name"] for t in tools} == set(INTERNAL_BINDINGS) | set(OPERATOR_BOUNDARIES), "MCP/model classification is stale"
    assembly = read(f"{AGENTS}/AgentsConfig.java") + read(f"{AGENTS}/JobRuntime.java")
    for name, (source, constructor) in INTERNAL_BINDINGS.items():
        schema_source = read(f"{AGENTS}/{source}.java")
        if name == "agent_run":
            assert "NAME = AgentRegistry.AGENT_RUN" in schema_source
            schema_source = read(f"{AGENTS}/AgentRegistry.java")
        assert f'"{name}"' in schema_source, f"missing model schema name: {name}"
        assert f"new {constructor}(" in assembly, f"unbound model adapter: {name}"
    grants = {name: [] for name in INTERNAL_BINDINGS}
    for directory in ["agents", "bots"]:
        for path in sorted((ROOT / f"plowshare-server/src/main/resources/{directory}").glob("*.md")):
            match = re.search(r"^tools:\s*\[([^\]]*)\]", path.read_text(), re.M)
            if match:
                for name in [name.strip() for name in match[1].split(",")]:
                    if name in grants:
                        grants[name].append(str(path.relative_to(ROOT)))
    declarations = {tool: row for row in fixture["legacyDeclarations"] for tool in row["tools"]}
    constants = dict(re.findall(r'public static final String (\w+) = "([\w.]+)";', read(f"{WS}/FrameTypes.java")))
    # Literal FrameArea registrations are the server routing authority.
    areas = {}
    for path in sorted((ROOT / "plowshare-server/src/main/java").rglob("*Frames.java")):
        text = path.read_text()
        match = re.search(r'\bframes\(\)\s*\{', text)
        if "implements FrameArea" not in text or match is None:
            continue
        # Read just the registration method, including Map.of and lambda bodies.
        start, end, depth = match.end(), match.end(), 1
        while depth:
            if text[end] == "{":
                depth += 1
            elif text[end] == "}":
                depth -= 1
            end += 1
        for constant in sorted(set(re.findall(r'FrameTypes\.(\w+)', text[start:end]))):
            frame = constants[constant]
            assert frame not in areas, f"duplicate frame registration: {frame}"
            areas[frame] = str(path.relative_to(ROOT))
    # InformationFrames registers the shared protocol operation table as a family.
    information = re.search(r'ALL\s*=\s*List.of\((.*?)\);', read(
        "plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames/InformationOperations.java"), re.S)
    assert information is not None
    information_frames = ["information." + verb for verb in re.findall(r'"([a-z.]+)"', information.group(1))]
    for frame in information_frames:
        assert frame not in areas
        areas[frame] = f"{WS}/InformationFrames.java"
    # Request builders and constants are deliberately separate: declaring a name
    # does not prove that a helper exists or that a frontend calls it.
    builders = {}
    declared_ts = {}
    operation_paths = list((ROOT / "plowshare-tui/src/logic").glob("*.ts")) + list((ROOT / "sdk/typescript/src/operations").glob("*.ts"))
    for path in sorted(operation_paths):
        if path.name.endswith(".test.ts"):
            continue
        text = path.read_text()
        local = dict(re.findall(r"export const (\w+) = ['\"]([a-z]+(?:\.[a-z]+)+)['\"]", text))
        for constant, frame in local.items():
            declared_ts.setdefault(frame, []).append({"source": str(path.relative_to(ROOT)), "constant": constant})
        for match in re.finditer(r'export function (\w+)\b([\s\S]*?)(?=\nexport |\Z)', text):
            for constant in set(re.findall(r'\btype:\s*([A-Z][A-Z_]+)', match.group(2))):
                if constant in local:
                    builders.setdefault(local[constant], []).append({"source": str(path.relative_to(ROOT)), "function": match.group(1)})
    direct_source = "sdk/typescript/src/operations/direct.ts"
    catalog = read(direct_source).split("export const MEMORY_OPERATIONS = {", 1)[1].split("} as const", 1)[0]
    direct_memory_frames = set(re.findall(r"\w+: '([a-z.]+)'", catalog))
    assert len(direct_memory_frames) == 12
    direct_frames = direct_memory_frames | {"conversation.search", "job.status", "job.cancel"}
    cli_source = "sdk/typescript/src/operations/catalog.ts"
    cli_catalog = read(cli_source).split("export const CLI_OPERATIONS = {", 1)[1].split("} as const", 1)[0]
    cli_frames = direct_frames | set(re.findall(r"'[^']+': '([a-z.]+)'", cli_catalog))
    one_shot_frames = set(cli_frames)
    observer_source = "sdk/typescript/src/operations/observation.ts"
    observer_catalog = read(observer_source).split("export const OBSERVER_OPERATIONS = {", 1)[1].split("} as const", 1)[0]
    observer_frames = set(re.findall(r"'[^']+': '([a-z.]+)'", observer_catalog))
    assert observer_frames == {"job.stream", "conversation.follow"}
    union_source = "sdk/typescript/src/operations/union.ts"
    sync_catalog = read(union_source).split("export const SYNC_OPERATIONS = {", 1)[1].split("} as const", 1)[0]
    sync_frames = set(re.findall(r"\w+: '([a-z.]+)'", sync_catalog))
    sync_source = "sdk/node/src/sync/syncer.ts"
    constants = dict(re.findall(r"export const (\w+) = '([a-z.]+)'", read(union_source)))
    for frame in sync_frames:
        assert any(name in read(sync_source) for name, value in constants.items() if value == frame), frame
    assert len(sync_frames) == 8
    cli_frames |= observer_frames | sync_frames
    assert cli_frames <= set(areas), "CLI frame inventory is stale"
    administrative_source = "sdk/typescript/src/operations/administration.ts"
    bound_catalog = read(administrative_source).split("export const BOUND_OPERATIONS = {", 1)[1].split("} as const", 1)[0]
    bound_frames = dict(re.findall(r"'([a-z.]+)': '([^']+)'", bound_catalog))
    typed_frames = set()
    for source in [direct_source, cli_source, administrative_source, "sdk/typescript/src/operations/information-payloads.ts", "sdk/typescript/src/operations/usage.ts", "sdk/typescript/src/operations/messaging.ts", "sdk/typescript/src/operations/relay.ts"]:
        typed_frames.update(re.findall(r"^\s*'([a-z]+(?:\.[a-z]+)+)':", read(source), re.M))
    assert typed_frames == set(areas), "typed payload catalog differs from the registered WS surface"
    assert one_shot_frames.isdisjoint(bound_frames) and cli_frames | set(bound_frames) == typed_frames
    assert cli_frames & set(bound_frames) == observer_frames | sync_frames
    usage_source = "sdk/typescript/src/operations/usage.ts"
    for frame in ("usage.subscribe", "usage.unsubscribe"):
        builders[frame] = [{"source": usage_source, "function": "UsageClient / UsageWatch socket-owned view lifecycle"}]
    response_families = {}
    for family, name, expected_types, expected_operations in [
        ("administrative", "AdministrativeReplies", 34, 35),
        ("conversation", "ConversationReplies", 41, 45),
        ("inspection", "InspectionReplies", 14, 7),
        ("information", "InformationReplies", 33, 40),
        ("messaging", "MessagingReplies", 5, 9),
    ]:
        source = f"sdk/typescript/src/operations/{family}-replies.ts" if family != "messaging" else "sdk/typescript/src/operations/messaging.ts"
        text = read(source)
        types = [value for value in re.findall(r"^export interface (\w+)\s*\{", text, re.M) if value != name]
        operations = re.findall(r"'([^']+)':", re.search(rf"export interface {name} \{{([^}}]+)\}}", text, re.S).group(1))
        if family == "information":
            types.extend(re.findall(r"^export interface (\w+)\s*\{", read("sdk/typescript/src/operations/information-views.ts"), re.M))
        assert len(types) == expected_types and len(operations) == expected_operations, family
        response_families[family] = {"source": source, "wireDtos": types, "operations": operations,
                                     "fixtures": f"test-support/contracts/ws-{family}-fixtures.json"}
    reply_source = "sdk/typescript/src/operations/replies.ts"
    reply_extra = set(re.findall(r"^\s*'([^']+)':", read(reply_source), re.M))
    usage_replies = re.search(r"export interface UsageReplies \{(.*?)\}", read(usage_source), re.S).group(1)
    response_operations = set(retrieval_operations) | reply_extra | set(re.findall(r"'([^']+)':", usage_replies))
    relay_replies = re.search(r"export interface RelayReplies \{(.*?)\n\}", read("sdk/typescript/src/operations/relay.ts"), re.S).group(1)
    response_operations.update(re.findall(r"'([^']+)':", relay_replies))
    for family in response_families.values():
        response_operations.update(family["operations"])
    adapter_replies = {"incoming.catalog", "incoming.receive", "incoming.status", "incoming.cancel"}
    assert adapter_replies <= set(bound_frames) and adapter_replies <= response_operations
    assert response_operations == typed_frames, "typed SDK response coverage is incomplete"
    response_operations -= set(bound_frames)
    assert response_operations == one_shot_frames, f"one-shot WS response coverage is incomplete: missing={sorted(one_shot_frames-response_operations)} extra={sorted(response_operations-one_shot_frames)}"
    for frame in bound_frames:
        builders.setdefault(frame, []).append({"source": administrative_source, "function": "request (bound platform adapter)"})
    for frame in one_shot_frames - direct_frames:
        builders.setdefault(frame, []).append({"source": cli_source, "function": "request / parseCommand"})
    for frame in observer_frames:
        builders.setdefault(frame, []).append({"source": observer_source, "function": "persistent observation (CLI lifetime)"})
    for frame in direct_frames:
        builders.setdefault(frame, []).append({"source": direct_source, "function": "request / parseDirect"})

    for frame in information_frames:
        builders[frame] = [{"source": "sdk/typescript/src/operations/information.ts", "function": "InformationClient.call"}]
    # Source references are inventory evidence; native/runtime tests prove traffic.
    desktop_sources = {}
    for path in sorted((ROOT / "plowshare-desktop/src").rglob("*.ts")):
        if path.name.endswith(".test.ts") or "fixture" in path.name:
            continue
        for frame in set(re.findall(r"['\"]([a-z]+(?:\.[a-z]+)+)['\"]", path.read_text())) & set(areas):
            desktop_sources.setdefault(frame, []).append(str(path.relative_to(ROOT)))
    DESKTOP_FRAMES.update(desktop_sources)
    assert set(DESKTOP_FRAMES) <= set(areas), "desktop frame inventory is stale"
    for flow in BACKGROUND_PARITY:
        assert set(flow["frames"]) <= set(areas), f"background parity frames are stale: {flow['id']}"
        assert all((ROOT / source).is_file() for source in flow["sources"]), f"background parity sources are missing: {flow['id']}"
    push_types = {"conversation.appended", "orchestration.recorded", "orchestration.changed", "inbox.changed"}
    assert set(declared_ts) <= set(areas) | push_types, "TUI discriminator inventory is stale"

    rows = []
    for tool in tools:
        name = tool["name"]
        frame = EXPLICIT_FRAMES.get(name, name.replace("_", "."))
        legacy = declarations[name]
        if frame is not None:
            assert frame in areas and frame in legacy["frames"], f"unmapped tool {name}: {frame}"
        row = {
            "id": name,
            "legacyMcp": {"status": "retired-java-baseline", "schemaPointer": f"{FIXTURE}#/toolsList/result/tools/{tools.index(tool)}", "fixturePrefix": name + "/"},
            "legacyCli": {"commands": legacy["commands"]},
            "transport": {"kind": "local-file-presence" if frame is None else "websocket", "frame": frame, "serverSource": areas.get(frame)},
            "contract": {
                "inputs": "exact MCP schema at schemaPointer; effective Java defaults and pagination in backendCalls fixtures",
                "wireTranslation": "Java backendCalls are NOT WS payloads; translate against serverSource and handler tests",
                "authentication": "connection credentials through injected auth binding; server enforces caller authorization",
                "scope": "explicit local root" if frame is None else (
                    "required project; no global curation" if name == "memory_curate" else
                    "named memory IDs, including superseded/invalidated records" if name == "memory_read" else
                    "project or omitted global home" if name.startswith("memory_") or name in {"agent_run", "conversation_list", "conversation_search"} else
                    "server filesystem administration" if name.startswith("project_") else
                    "named conversation" if name.startswith("conversation_") else
                    "named job" if name.startswith("agent_") else
                    "authenticated personal/project/shared document selection" if name.startswith("document_") else "external web resource"),
            },
            "typescript": {
                "dedicatedBuilders": builders.get(frame, []),
                "tuiUserSurface": "direct-ws-command" if frame in direct_frames else "conversation-flow" if name in TUI_FLOW_TOOLS else "missing-direct-command",
                "desktop": "implemented-user-flow" if frame in DESKTOP_FRAMES else "missing-live-operation",
                "sharedPackage": "shared-request-builder" if any(b["source"].startswith("sdk/typescript/") for b in builders.get(frame, [])) else "node-platform-adapter" if frame is None else "not-extracted",
                "mcpAdapter": "implemented-explicit-node-presence" if frame is None else "implemented-shared-ws",
                "mcpSource": "plowshare-mcp/src/adapter.ts", "headlessCli": "implemented-direct-ws" if frame in cli_frames else "not-implemented",
            },
            "internalAgent": {
                "adapter": "registered" if name in INTERNAL_BINDINGS else "operator-or-platform-boundary",
                "grant": "definition-dependent; registration does not grant every agent access",
                **({"source": f"{AGENTS}/{INTERNAL_BINDINGS[name][0]}.java", "shippedGrants": grants[name]}
                   if name in INTERNAL_BINDINGS else {"reason": OPERATOR_BOUNDARIES[name]}),
            },
            "work": ["live-mcp-deployment-validation", "typescript-cli-parity"],
        }
        if name.startswith("memory_"):
            row["work"].append("direct-memory-command")
        if name == "agent_run":
            row["semantics"] = "Legacy MCP submits a standalone job with conversation=null; TUI/desktop submit conversation turns. Internal delegation has separate caller rules."
        if name in {"agent_poll", "agent_result", "agent_cancel"}:
            row["semantics"] = "Preserve job outcomes and answered flag; cancellation acknowledgement is not completion. MCP polling and result rendering remain distinct."
        if frame is None:
            row["typescript"]["tuiUserSurface"] = "existing-node-filesystem-presence"
            row["platformSource"] = "sdk/node/src/rooter.ts"
            row["work"].append("explicit-node-presence-boundary")
            row["typescript"]["desktop"] = "implemented-explicit-node-presence"
            row["desktopPlatformSource"] = "plowshare-desktop/src/files.ts"
            row["work"].append("desktop-union-sync")
        if name == "information":
            row["transport"]["frames"] = information_frames
            row["contract"]["scope"] = "personal plus shared by default; explicit project membership; per-revision publication"
            row["typescript"].update(tuiUserSurface="implemented-information-command", desktop="implemented-information-library",
                                     headlessCli="implemented-information-command")
            row["internalAgent"] = {"adapter": "registered-information_read-and-information_write", "grant": "Farnsworth, Aristoxenus, Daedalus, Interlocutor, Librarian and research conductor read/write; root account and home pinned by server", "reason": "external capability wrapper; internal runtime binds information_read and information_write with root authority"}
            row["semantics"] = "MCP permits source/evidence/draft report operations only. Human CLI/client controls expose owner publication, management and operator migration."
            row["work"] = ["live-mcp-deployment-validation"]
        rows.append(row)

    memory = [{"id": r["id"], "frame": r["transport"]["frame"], "legacyMcp": True,
               "typescriptCommand": "implemented-tui-cli-shared-ws", "phase": "typed-operations-and-commands"}
              for r in rows if r["id"].startswith("memory_")]
    for name, frame in MEMORY_EXTRA.items():
        assert frame in areas
        memory.append({"id": name, "frame": frame, "legacyMcp": False, "typescriptCommand": "implemented-tui-cli-shared-ws",
                       "phase": "typed-operations-and-commands", "serverSource": areas[frame]})
    assert len(memory) == 12

    return {
        "formatVersion": 1,
        "snapshot": {"date": "2026-10-02", "baseCommit": SNAPSHOT_BASE,
                     "scope": "source inventory of the client consolidation; acceptance is recorded separately",
                     "method": "Java executable registry fixture, literal server registrations, TS source scan, audited interface overrides",
                     "limits": "Builders do not prove user commands. Synthetic fixtures do not prove live endpoints. Legacy declarations are not authoritative agent grants."},
        "counts": {"legacyMcpTools": len(rows), "legacyMcpCases": len(fixture["cases"]),
                   "registeredRequestFrames": len(areas), "directMemoryCommandsRequired": len(memory), "headlessCliRequestFrames": len(cli_frames), "headlessCliOneShotFrames": len(one_shot_frames), "headlessCliObserverFrames": len(observer_frames), "headlessCliSyncFrames": len(sync_frames), "typedRequestFrames": len(typed_frames),
                   "mcpModelAdapters": len(INTERNAL_BINDINGS), "mcpOperatorOrPlatformBoundaries": len(OPERATOR_BOUNDARIES),
                   "typescriptMcpTools": len(rows), "typescriptMcpServerTools": len([r for r in rows if r["transport"]["frame"] is not None])},
        "javaSdk": {
            "module": "plowshare-sdk", "protocol": "plowshare-v1", "transport": "persistent authenticated websocket",
            "typedFacade": "sdk/java/src/main/java/io/aeyer/plowshare/sdk/WsServerClient.java",
            "rawSurface": "all registered request frames; complete outcome JSON retained",
            "legacyEntryPoints": "Java CLI/MCP retired; TypeScript CLI/MCP are the executable clients",
            "httpException": "multipart image upload; authentication and file presence are platform concerns",
            "outboundA2a": "plowshare-a2a; A2A 1.0 JSON-RPC SendMessage/GetTask/CancelTask, configured peers, durable outbox",
        },
        "boundaries": {
            "authority": "Java server owns durable state, retrieval and execution",
            "transport": "WS required for every supported operation; no silent HTTP fallback or mutation replay",
            "core": "neutral operations/binding; no Ink, React, Electron, Node globals or filesystem imports",
            "presence": "explicit platform adapter; never infer cwd or silently fall back after a file claim is lost",
            "scope": "omitted project means global home where supported, not all projects; blank is not global",
            "console": "separate frontend; this migration does not replace it",
        },
        "httpExceptions": [
            {"capability": "authentication/bootstrap", "source": "sdk/typescript/src/binding/auth.ts",
             "reason": "existing HTTP sign-in, password change, refresh and ticket contracts; authenticated sockets are injected",
             "migrationCondition": "server provides an equivalent supported bootstrap/authentication contract"},
            {"capability": "Git object transfer", "source": "sdk/node/src/sync/git.ts",
             "reason": "existing authenticated smart HTTP Git endpoint; union control, readiness and conflicts use WS; server has no WS Git-object transport",
             "migrationCondition": "server provides an equivalent supported WS Git-object transfer contract"},
        ],
        "legacyMcp": rows,
        "requiredUserMemoryOperations": memory,
        "retrievalExtensions": [
            {"id": "conversation-search", "existing": "lexical conversation.search over eligible retained text; scoped model adapter and direct CLI/TUI commands",
             "missing": [], "implemented": "passage index, lexical/semantic/hybrid ranking and evidence coverage",
             "spec": "implementation rationale"},
            {"id": "memory-navigation", "existing": "memory.navigate model-guided historical navigation and internal memory_navigate tool",
             "missing": [], "implemented": "semantic digest seeds with bounded structural fallback and explicit incomplete outcomes",
             "spec": "implementation rationale"},
        ],
        "websocketInventory": [
            {"frame": frame, "serverRegistration": source, "typescriptBuilders": builders.get(frame, []),
             "typescriptConstants": declared_ts.get(frame, []), "desktopConsumer": frame in DESKTOP_FRAMES, "desktopSourceReferences": desktop_sources.get(frame, []),
             "headlessCliConsumer": frame in cli_frames,
             "typedPayload": frame in typed_frames, "headlessCliBoundary": bound_frames.get(frame),
             "legacyMcpTools": [r["id"] for r in rows if r["transport"]["frame"] == frame or frame in r["transport"].get("frames", [])]}
            for frame, source in sorted(areas.items())
        ],
        "responseOnlyTypes": ["frame.refused"],
        "observedPushTypes": sorted(push_types | {"information.changed"}),
        "dynamicBuilder": {"source": union_source, "function": "unionAsk",
                           "note": "neutral union builder; per-operation use and Git lifecycle shared in the Node platform"},
        "extraction": {
            "readyFoundations": ["sdk/typescript/src/binding/connection.ts", "sdk/typescript/src/binding/auth.ts",
                                 "sdk/typescript/src/binding/envelope.ts", "plowshare-tui/src/logic/session.ts"],
            "bindingPackage": {"source": "sdk/typescript", "status": "extracted",
                               "consumers": ["plowshare-tui", "plowshare-desktop", "plowshare-cli", "plowshare-client-node", "plowshare-mcp"],
                               "installation": "separate pnpm installs; consumers link the locally built package",
                               "exports": "compiled JavaScript/declarations under binding/*, jobs and operations/*; no runtime dependencies"},
            "authentication": {"source": "sdk/node/src/credentials.ts",
                               "consumers": ["plowshare-cli", "plowshare-tui", "plowshare-mcp", "plowshare-desktop"],
                               "login": "CLI login/logout and desktop sign-in/startup restoration; per-origin POSIX permission-protected tokens; server/account remembered separately; passwords never persisted",
                               "renewal": "cross-process lock, reload before rotation, pending fence, atomic save before WS ticket; no uncertain-refresh replay",
                               "server": "V81 durable account session chains and token digests; transactional refresh/revocation; bootstrap/operator/tickets remain ephemeral",
                               "documentation": "docs/client-login.md",
                               "remaining": "OS keychain integration and automatic abandoned-lock recovery"},
            "jobLifecycle": {"source": "sdk/typescript/src/jobs/lifecycle.ts",
                             "status": "shared identity/event buffering/generation/cancellation/outcome policy used by both consumers",
                             "transport": "no I/O in lifecycle; consumers reconcile with WS job.status and trajectory",
                             "remaining": "unknown submissions require explicit trajectory reconciliation; no mutation replay"},
            "neutralityGuard": "plowshare-tui/src/neutrality.test.ts",
            "responses": {"source": views_source, "readers": response_readers, "types": response_types,
                          "jobSource": job_view_source, "wireDtos": ["JobView", "JobOutcome", "JobLimits", "JobPace"],
                          "consumers": ["plowshare-tui", "plowshare-desktop", "plowshare-cli", "plowshare-mcp"],
                          "semantics": "matching nonblank job identity, state and terminal answered flag required; unknown fields retained; malformed replies leave completion unresolved; malformed lists/pages do not invent empty snapshots",
                          "desktop": "no TUI source imports or build inputs; shared public package exports",
                          "retrieval": {"source": retrieval_source, "wireDtos": retrieval_types, "operations": retrieval_operations,
                                        "fixtures": retrieval_fixture,
                                        "validation": "shared dispatch requires actual findings, provenance and coverage; validates nested evidence and synthetic hierarchy; preserves stale citation null ids, future names and raw replies; no coercion, empty-result fallback or mutation replay",
                                        "compatibility": "legacy recall question/limit, document search mode and index unsearchable metadata may be absent; essential findings and coverage never default",
                                        "acceptance": "37 Java record field/type mirrors, all 21 nonempty replies, external MCP SDK with 16 retrieval tools, malformed replies and real CLI/TUI WS checks"},
                          "records": {"source": "sdk/typescript/src/operations/records.ts",
                                      "wireDtos": ["RecordView", "RecordPageView"],
                                      "consumers": ["plowshare-tui", "plowshare-cli"],
                                      "validation": "complete raw record pages retain ordering, total/limit/through/oldest/more, bodies and future fields; malformed pages fail after one WS read; TUI keeps its established display projection",
                                      "requests": "tail, before/after ordinals, kind filters, settled tool rereads and latest question"},
                          "families": response_families,
                          "oneShotContracts": {"source": reply_source, "operations": sorted(response_operations),
                                               "count": len(response_operations),
                                               "validation": "all one-shot dispatch success paths validate response family and code; raw outcomes/future fields preserved; nested malformed rows cannot become empty success; identities and page coordinates checked without retry or fallback",
                                               "compatibility": "explicit legacy retrieval/job/accepted metadata exceptions retained; persistent observers and sync use their platform lifecycle checks"},
                          "remaining": "release performance and live usage/provider probes remain open; live model acceptance is in parity-signoff-2026-10-02.md"},
            "lifecycleConsumers": ["plowshare-tui/src/view/main.ts", "plowshare-desktop/src/client.ts"],
            "operations": {"source": "sdk/typescript/src/operations", "status": f"48 existing request builders extracted; {len(typed_frames)} typed payloads and shared WS dispatch",
                           "tui": "12 direct memory verbs, conversation.search, job.status/cancel",
                           "headlessCli": f"{len(cli_frames)} WS operations: {len(one_shot_frames)} one-shot commands and {len(observer_frames)} persistent observer subscriptions and {len(sync_frames)} rooted sync mutations; explicit WS job waiting/watching, saved login or ephemeral environment credentials, JSON/readable output and exits; offline command/group help and installed CLI version",
                           "boundOperations": bound_frames,
                           "remaining": "Java CLI/MCP retired; CLI migration audit and runtime transport acceptance are recorded in legacy-cli-audit.json and parity-signoff-2026-10-02.md"},
            "observation": {"source": observer_source, "status": "job watch, --watch submissions and conversation follow implemented in CLI",
                            "semantics": "job identity filters bounded early progress; durable status alone establishes completion; conversation pushes retain high-water cursors without claiming content replay",
                            "lifetime": "explicit deadline/interrupt/loss closes the observer; no reconnect, replay or implicit job cancellation; JSON observers emit NDJSON",
                            "limits": "token delivery remains scoped to the server's original run session; watching an existing job in a new session polls durable status without promising a token replay"},
            "mcp": {"source": "plowshare-mcp", "status": "35 legacy stdio tools; 34 server tools use shared WS; explicit local rooting uses Node file WS",
                    "acceptance": "181 Java fixture cases, external SDK with all server tools/options, real presence/loss/refusal and headless shutdown checks",
                    "changes": ["WS refusal codes replace HTTP status numbers", "connection errors are redacted uncertainty with no replay/cancellation", "null conversation allowance renders unlimited", "headless commands default off", "file presence requires ready acknowledgement", "all web text line separators quoted"],
                    "remaining": "distribution and OS keychain integration; live acceptance evidence is separate"},
            "nodePlatform": {"source": "sdk/node", "consumers": ["plowshare-tui", "plowshare-mcp", "plowshare-cli"],
                             "status": "fenced file/command runner and exclusive per-session claim extracted; frontend-free auth/presence composition and shared Git union workflow; explicit CLI --root/client root/--sync/sync commands"},
            "fileContents": {
                "source": "plowshare-server/src/main/java/io/aeyer/plowshare/server/files/FileContents.java",
                "status": "server-side PDF text conversion and home-scoped image storage; existing text windows preserved",
                "transport": "source=1 capability on the existing file WS; source metadata then correlated 64 KiB Base64 byte ranges; no HTTP upload",
                "bounds": "8 MiB source files; four concurrent transfers/conversions; deadline and disconnect handling",
                "cache": "content hash; 8388608 text-character budget and 128 entries; fresh fenced metadata required on every access; oversized text served uncached",
                "safety": "final hash checked before conversion/cache; incomplete transfers never cached; image cache checks home-scoped storage",
                "compatibility": "non-advertising clients retain text protocol; Java legacy local converter bypassed for modern source reads; directory grep remains text-only",
                "acceptance": "Java source/server regression tests plus Node enforcer connected to the real Java WS handler for PDF windows, edits, image storage and path refusals",
            },
            "next": "live builder install/run and memory navigation acceptance; distribution remains separate",
            "lifecycleAcceptance": ["early events before ACCEPTED", "concurrent job routing", "cancel until terminal status",
                                    "approval continuation creates/tracks new job", "stale connection generation protection",
                                    "disconnect marks outcome unknown", "reconnect reconciles handles without mutation replay",
                                    "published reasoning remains separate from answer text",
                                    "server answered flag determines successful completion; canonical ending names never produce duplicate answers or false stopped warnings; terminal details replace stale polling errors",
                                    "per-job outcome pace uses the neutral reader and formatter; missing measurements remain absent and estimates stay marked; latest measured pace remains conversation-scoped",
                                    "desktop view follows serialize the complete set; closing views never cancels jobs; paged ordinal catch-up preserves earlier history and newer arrivals; reconnect never replays mutations; old-server refusal is visible without single-log downgrade",
                                    "account activity refreshes independently of chat jobs; bounded live-state listings preserve old roots and refuse partial snapshots; inbox receipts require explicit displayed-item selection and are never replayed; stale account responses are ignored",
                                    "context readings remain scoped by conversation and agent; superseded responses cannot replace newer readings or another connection's data"],
            "platformWork": ["plowshare-client-node"],
        },
        "desktopProjects": {
            "sources": ["plowshare-desktop/src/workspace.ts", "plowshare-desktop/src/project-config.ts", "plowshare-desktop/src/renderer/app.ts"],
            "composition": "one DesktopClient per project with independent event/file session and existing job lifecycle; account client for global conversations and Inbox/Runs/Board; aggregate display and route requests/follows by owner",
            "authentication": "single account token owner; serialize refresh/ticket/channel opens; retain rotation even on socket-open failure; no extra password sign-ins",
            "botIdentity": "optional authored display-name and resolved definition origin; desktop consolidates identity, resolved grants, timestamped offered tools, withheld permissions and local folder state",
            "definitionRefresh": "WS agent.list re-reads the asking caller's rooted-session definitions via DefinitionResolver.refreshForCaller; preserves other sessions and sessionless cache entries; server directory stamps alone cannot detect client edits",
            "configuration": "~/.config/plowshare/desktop-projects.json; XDG_CONFIG_HOME supported; server/account/project/path/machine/enabled mappings, atomic 0600 writes, no credentials; corrupt config reported and preserved",
            "restoration": "enabled local mappings after login; paused projects remain paused; missing/moved/other-machine paths remain unavailable; project selection attaches only approved enabled local mappings; explicit folder approval recovers canonical same-machine server paths when no local mapping exists; same-session bot discovery after attachment; Refresh reloads rosters; saved mappings survive server project-list refusal; quit waits for in-flight authentication",
            "tests": ["plowshare-desktop/src/workspace.test.ts", "plowshare-desktop/src/project-config.test.ts", "plowshare-desktop/scripts/projects-smoke.mjs", "plowshare-desktop/scripts/bots-smoke.mjs"],
            "limits": "same-project exclusive server presence remains; durable accepted-job receipts and explicit reconciliation implemented; union/sync controls implemented"
        },
        "desktopFilePresence": {
            "source": "plowshare-desktop/src/files.ts", "stateSource": "plowshare-desktop/src/files-shared.ts",
            "transport": "/v1/files; same authenticated per-project session as its /v1/events channel; refresh rotation serialized by account authentication owner in main",
            "selection": "native folder picker or explicit recovery of canonical same-machine server path; explicit project or local marker/directory name; marker written after accepted claim; renderer cannot supply a filesystem path",
            "capabilities": ["roots", "read", "stat", "glob", "grep", "write", "edit", "delete", "move", "run", "cancel", "harness definitions and hooks"],
            "lifecycle": "one root per project client session; simultaneous distinct projects; exact readiness; failed folder move restores prior claim; saved enabled roots restored after login; no alternate-path fallback or agent-turn replay; withdrawal/disconnect/quit stop local commands",
            "tests": ["plowshare-desktop/src/files.test.ts", "plowshare-desktop/scripts/files-smoke.mjs"],
            "limits": "no desktop union/sync integration or local environment editor; PDF/image conversion remains server-side; server still owns membership, approval and exclusive-presence policy"
        },
        "informationSystem": {"implementation": "docs/information-system.md", "operations": information_frames,
                              "reports": "run-unique resource names, research-question display titles after extraction, feedback revisions preserve identity; Runs opens the exact retained main report revision in its project scope without finalising",
                              "handoffTest": "plowshare-desktop/scripts/report-handoff-smoke.mjs",
                              "desktop": "plowshare-desktop/src/renderer/information.ts", "console": "plowshare-console/src/screens/information.ts",
                              "tests": "real authenticated WebSocket, PostgreSQL lifecycle, adapter and native Electron fixtures; no live research quality claim"},
        "desktopWindows": [
            {"view": "conversation", "source": "plowshare-desktop/src/renderer/app.ts",
             "authority": "main-process validated commands", "execution": "Trajectory opens a separate native window; no transcript dump or execution sidebar tab",
             "capabilities": ["Projects section with nested conversations, persistent folder mappings and concurrent project clients", "safe TUI Markdown rendering", "model-reported prompt/context usage with unknown and stale states", "observed run phase with continuous animation and reduced-motion support", "model-call allowance", "conversation-scoped last measured run pace through the TUI reader and formatter", "context pressure and outcome alerts", "bounded composer growth and scrollback return", "responsive context drawer and expandable metadata", "local name/action navigator and persisted sidebar visibility", "explicit answer Markdown and displayed code copying through the main process"],
             "limits": "no unsent-draft token estimate or union/sync integration; sample context readings labelled in demo"},
            {"view": "trajectory", "source": "plowshare-desktop/src/renderer/trajectory.ts",
             "binding": "one read-only window per conversation, independent of main selection; closes on account/server/demo change",
             "capabilities": ["TUI step/call-result pairing", "published reasoning", "timings", "search and filtering", "selected-entry detail", "follow current updates", "earlier-entry loading", "explicit displayed section and record copying through the main process"],
             "limits": "server-provided excerpts; live following requires multi-conversation server support; no arbitrary commands or file access"},
            {"view": "activity", "source": "plowshare-desktop/src/renderer/activity.ts",
             "binding": "one account Inbox/Runs native window independent of main chat; closes on account/server/demo change",
             "authority": "validated activity reads and explicit receipts for displayed inbox IDs; no agent turns, orchestration decisions or cancellations",
             "capabilities": ["live unread badge", "validated read/unread mailbox pages and older history", "mailbox status and kind selectors over loaded history: Unread default, All and Read plus source kinds including unknown values; combines with text search without receipts or badge changes", "explicit single-item receipts", "safe Markdown notices/results", "recent and bounded live-state run discovery", "stages, children, parent navigation and structured question inspection", "account notification refresh and 15-second reconciliation", "previous snapshots retained on refusal"],
             "limits": "20 read/unread items initially with Load older paging; 20 recent runs plus up to 200 per live state with saturation warning; no full orchestration records, actor conversations, decisions or history restored after app quit"},
        ],
        "desktopBackgroundParity": {
            "auditDesktopCommit": "fe557d63",
            "trace": "implementation rationale",
            "scope": "source audit and backlog; live conversation delivery and native account Inbox/Runs implemented; orchestration records, decisions, definitions and scheduling remain gaps",
            "distinction": "a chat job may answer while its durable orchestration remains live; an account inbox item is a delivery or notice, not a job",
            "flows": BACKGROUND_PARITY,
            "subscriptionContract": {
                "frame": "conversation.follow",
                "legacyPayload": {"conversation": "cnv_1"},
                "multiViewPayload": {"conversations": ["cnv_1", "cnv_2"]},
                "unsubscribePayload": {"conversations": []},
                "semantics": "atomic complete-set replacement; mutually exclusive fields; duplicate IDs follow once; validate every ID before mutation; refusal preserves existing set; disconnect clears every follow",
                "builder": "sdk/typescript/src/operations/session.ts#followingLogs",
                "serverStatus": "implemented; multi-session/multi-log delivery and interleaved job routing have focused regression coverage",
                "desktopStatus": "implemented; union of selected chat and native inspection views, serialized set updates, coalesced ordinal catch-up and reconnect restoration; older servers warn without downgrade; closed views do not cancel work",
                "tests": ["plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/AgentFramesTest.java",
                          "plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/ConversationAppendedTest.java",
                          "plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/EventChannelTest.java",
                          "plowshare-tui/src/logic/session.test.ts", "plowshare-desktop/src/client.test.ts",
                          "plowshare-desktop/scripts/smoke.mjs"],
            },
            "acceptance": ["answered chat does not finish its orchestration", "later harness delivery appears without duplicating own streamed answer",
                           "old live roots remain discoverable behind newer finished phases within documented listing bounds",
                           "inbox receipts only for validated displayed IDs; receipt failure preserves visible items",
                           "read refusal preserves attention state", "deferred and remotely settled structured questions",
                           "inspection closes while read is pending and restores chat follow on return",
                           "reconnect rereads durable state without replaying decisions"],
            "testEvidence": ["plowshare-desktop/src/activity.test.ts", "plowshare-desktop/scripts/smoke.mjs", "plowshare-tui/src/view/composition.test.ts", "plowshare-tui/src/logic/record.test.ts",
                             "plowshare-tui/src/logic/session.test.ts", "plowshare-tui/src/logic/questions.test.ts",
                             "plowshare-tui/src/logic/caps.test.ts", "plowshare-tui/src/logic/draftview.test.ts",
                             "plowshare-tui/src/logic/explorer.test.ts", "plowshare-tui/src/logic/approval.test.ts"],
        },
        "workPackages": [
            {"ownerRole": "shared-client agent", "items": ["connection/auth extraction", "job lifecycle consolidation", "TUI and desktop consumer migration"],
             "acceptance": "neutrality and both consumers pass, including lifecycleAcceptance; file presence remains behind an explicit platform boundary"},
            {"ownerRole": "client commands agent", "items": ["typed operations", "12 direct memory commands", "direct document/web/project commands"],
             "acceptance": "CLI and TUI expose operations with scope, paging and visible errors; direct use does not require granting an internal tool"},
            {"ownerRole": "TypeScript MCP agent", "items": ["35 legacy tools", "headless authentication", "stdio compatibility"],
             "acceptance": "consume reviewed fixtures through the new adapter; preserve caller identity, standalone jobs, errors and stdout protocol discipline"},
            {"ownerRole": "Node platform agent", "items": ["file presence", "credential storage", "union/sync integration"],
             "acceptance": "explicit rooting, fencing, exclusive claim, loss handling and teardown without frontend dependencies in core"},
            {"ownerRole": "desktop background parity agent", "items": ["extend implemented account activity coordinator with deferred human questions", "orchestration definitions and deeper run-tree inspection", "native orchestration records and actor navigation", "human questions and full approval decisions", "project settings boundary and scheduled/event work"],
             "acceptance": "desktopBackgroundParity acceptance; preserve minimal chat and separate inspection; reuse neutral models and server delivery authority"},
            {"ownerRole": "server document tools agent", "items": ["live model probes for changed grants/prompts"],
             "status": "four adapters and five archive reads registered with selected grants; adapter/runtime tests reuse server services directly"},
            {"ownerRole": "retrieval agent", "items": ["conversation search command/tool", "semantic/hybrid conversation retrieval", "memory navigation command and seeds"],
             "acceptance": "separate retrieval spec, scope authorization, coverage accounting and usable evidence references"},
        ],
        "pendingPhase0Decisions": ["production client launcher/package names", "OS keychain credential custody and automatic abandoned-lock recovery",
                                  "supported server compatibility floor", "desktop distribution target platforms",
                                  "same-project concurrent filesystem presence policy"],
        "evidence": ["implementation rationale",
                     "sdk/typescript/src/binding/job-view.test.ts", "sdk/typescript/src/operations/views.test.ts",
                     "sdk/typescript/src/operations/retrieval.test.ts", retrieval_fixture,
                     "sdk/typescript/src/jobs/lifecycle.test.ts",
                     "implementation rationale",
                     "plowshare-desktop/src/client.test.ts", "plowshare-desktop/scripts/smoke.mjs",
                     "plowshare-cli/src/socket.test.ts", "plowshare-cli/src/options.test.ts", "plowshare-cli/src/presence.test.ts",
                     "plowshare-mcp/src/compatibility.test.ts", "plowshare-mcp/src/socket.test.ts", "plowshare-mcp/src/evidence.test.ts",
                     "plowshare-server/src/test/java/io/aeyer/plowshare/server/files/FileContentsTest.java",
                     "plowshare-server/src/test/java/io/aeyer/plowshare/server/ws/FileChannelTest.java"],
    }



if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify without rewriting")
    args = parser.parse_args()
    result = inventory()
    target = HERE / "client-capabilities.json"
    if args.check:
        if json.loads(target.read_text()) != result:
            raise SystemExit("Manifest is stale. Review source changes, then regenerate.")
    else:
        target.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(f"Manifest verified: {result['counts']}")
