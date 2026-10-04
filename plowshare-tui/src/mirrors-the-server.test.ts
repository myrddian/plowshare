import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'

import {
    ACCESS_COOKIE, MUST_CHANGE_PASSWORD_HEADER, REFRESH_COOKIE, TOKEN_DELIVERY_BODY,
    TOKEN_DELIVERY_HEADER,
} from 'plowshare-client-ts/binding/auth'
import { CODES } from 'plowshare-client-ts/binding/codes'
import { CURRENT_VERSION } from 'plowshare-client-ts/binding/envelope'
import {
    DEFAULT_INHERIT, DEFAULT_OUTPUT_BYTES, DEFAULT_SIDE, DEFAULT_TIMEOUT_MILLIS, ASK, GATED, LOCAL, MAX_OUTPUT_BYTES,
    CAP_KEYS, MAX_TIMEOUT_MILLIS, MOST_FAILED_CHECKS, MOST_TIME_MINUTES, OFF, OPEN, SERVER, SHELLS, Unreadable, isShell,
    parseEnvironment, sideWith,
} from 'plowshare-client-ts/binding/environment'
import {
    CANCEL, DELETE, DEFINITIONS, EDIT, FILES_PATH, GLOB, GREP, HOOKS, MOVE, MACHINE_PARAM, MAX_LINE_CHARS, MAX_MATCHES,
    MAX_RECURSIVE_WILDCARDS, MAX_WINDOW_BYTES, MAX_WINDOW_LINES, OPS, PROJECT_PARAM, READ, SOURCE,
    REPLY_OK, REPLY_REFUSED, REPLY_UNAVAILABLE, RESULT_VERSION, ROOTS, ROOT_PARAM, RUN, SESSION_PARAM, STAT,
    Unreplaced, WRITE, replaced, requestIn,
} from 'plowshare-client-ts/binding/files'
import * as results from 'plowshare-client-ts/binding/files'
import {
    ACCEPTED, AGENT_LIST, AGENT_RUN, APPROVAL_ANSWER, APPROVAL_LIST, APPROVAL_REVOKE, AWAITING, CONVERSATION_COMPACTIONS, CONVERSATION_CONTEXT,
    CONVERSATION_LATEST,
    CONVERSATION_LIST, CONVERSATION_OPEN, CONVERSATION_TURNS, INBOX_LIST, INBOX_READ, JOB_CANCEL,
    JOB_STATUS, OK, ORCHESTRATION_DEFINITIONS, ORCHESTRATION_LIST, ORCHESTRATION_STATUS,
    PROJECT_LIST, EVENT_FIRE, FIRING_LIST, SCHEDULE_DEFINE, SCHEDULE_FORGET,
    SCHEDULE_LIST, SCHEDULE_PAUSE, SCHEDULE_READ, TRIGGER_DEFINE, TRIGGER_FORGET, TRIGGER_LIST,
    TRIGGER_PAUSE,
    CONVERSATION_APPENDED, CONVERSATION_FOLLOW, CONVERSATION_TRAJECTORY, DRAWN_KINDS, LOG_PAGE,
    CAP_KINDS, PERSON_ONLY_KINDS,
} from './logic/session.ts'
import {
    MILESTONE_KINDS, ORCHESTRATION_RECORD, ORCHESTRATION_RECORDED, RECORD_TAIL, TOOL_CALL,
} from './logic/record.ts'
import { ORCHESTRATION_CAPS, capsOf } from './logic/caps.ts'
import {
    CONTEXT_LINES, FOLD_SOURCES, MAX_LISTED, MAX_SHOWN_LINE_CHARS, MAX_SHOWN_LINES,
} from 'plowshare-client-ts/binding/editfacts'
import { MOST_FREE } from './logic/questions.ts'

/**
 * The wire types are *mirrored* from the server, and this is what keeps them so.
 *
 * Task 4's brief says "mirror the server's types; do not invent them", and the
 * socket spec's §3.3 names the risk in the same breath: <b>two mappings over
 * one set of exceptions is the drift this migration most risks.</b> The Java
 * side already refuses to hold two — `Code` is extracted from
 * `ApiExceptionHandler`'s table rather than written beside it, and
 * `FrameShapeTest.every_success_a_controller_can_answer_is_one_a_frame_can_say`
 * reads the controllers so a status added over there fails over here. This file
 * is the third link in that chain and the only one that crosses a language: a
 * constant added, renamed or removed in `Code.java` fails the TypeScript suite
 * the same day, rather than on the day a client is handed a code it silently
 * cannot switch on.
 *
 * <b>Why read the Java rather than copy a list into a fixture.</b> A fixture is
 * a third copy, and a third copy drifts from both. The enum is a hundred lines
 * of javadoc around sixteen declarations; the declarations are what this reads.
 *
 * <b>The self-check is not decoration</b>, and this repository has three
 * sightings of the disease it guards against — most recently a `check` that was
 * green while running neither pnpm task. A regex that stops matching (a
 * reformatted enum, a moved file, a renamed module) would otherwise leave every
 * assertion below comparing two empty lists and passing. So each read asserts
 * it found something first.
 *
 * MEASURED, one at a time, each restored afterwards: dropping `CREATED` from
 * `CODES` failed with the missing name; adding `IM_A_TEAPOT` failed with the
 * extra one; pointing `FRAMES` at a directory that does not exist failed the
 * self-check rather than passing over nothing.
 *
 * It lives at `src/` beside `neutrality.test.ts` and for that file's reason: it
 * imports `node:fs`, and `src/binding/tsconfig.json` has `types: []` and
 * includes its own test files, so the same import inside `binding/` would not
 * compile without installing `@types/node` — which is the edit the "injected
 * means structurally typed" ruling exists to prevent.
 */

const SRC = dirname(fileURLToPath(import.meta.url))
const FRAMES = join(SRC, '..', '..',
    'plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol/frames')

/**
 * The file wire, which task 4 added a fourth reason to read.
 *
 * `FileRequest`, `FileReply`, `Span`, `Window`, `Needle` and `GlobSpellings` sit
 * a level above `frames/`, in `protocol` itself — they are the records and the
 * bounds `binding/files.ts` mirrors, and this is the second directory this file
 * reads from that module for that reason.
 */
const PROTOCOL = join(SRC, '..', '..',
    'plowshare-protocol/src/main/java/io/aeyer/plowshare/protocol')

/**
 * The auth package, which task 5 added a second reason to read.
 *
 * The wire types above are mirrored from `plowshare-protocol`; the header and
 * cookie names below are mirrored from the server's own `auth` package, which
 * is where those constants live and where they would be renamed.
 */
const AUTH = join(SRC, '..', '..',
    'plowshare-server/src/main/java/io/aeyer/plowshare/server/auth')

/**
 * The frame surface, which task 6 added a third reason to read.
 *
 * `FrameTypes.java` declares fifty-three dotted discriminators and `logic/`
 * spells ten of them. <b>A frame type that drifts fails silently in the worst
 * possible way</b>: `FrameRouter` has no handler registered for the name, so
 * the server answers a refusal to a frame that used to work, and the client
 * reports the server's own sentence about a type nobody typed. This is the
 * guard rather than a comment saying "copied from the server".
 */
const WS = join(SRC, '..', '..',
    'plowshare-server/src/main/java/io/aeyer/plowshare/server/ws')

/**
 * The view package, read for the one record this client destructures.
 *
 * <b>`agent.list` is the only frame whose answer this client reads field by
 * field off a server record rather than off a protocol type.</b>
 * `AgentListHandler` answers with `AgentView`s, and `logic/session.ts` reads
 * every one of its components by name — to decide who a person is talking to,
 * to say what a roster says about each row, and, since `/agents <name>`, to
 * show what one agent holds. `served` and `bot` are booleans, so a rename does
 * not fail — it reads as `false`, which turns every agent into one this server
 * will not serve and every bot into an agent. That is a silent wrong answer at
 * sign-in, which is exactly the failure this file exists to make loud.
 */
const API = join(SRC, '..', '..',
    'plowshare-server/src/main/java/io/aeyer/plowshare/server/api')

/**
 * The events package, read for the four records scheduling destructures:
 * the proposal `schedule.read` answers with, and the schedule, trigger and
 * firing rows the listings answer with. A renamed `paused` reads as `false`
 * and a renamed `emits` joins no trigger to its schedule, both silently.
 */
const EVENTS = join(SRC, '..', '..',
    'plowshare-server/src/main/java/io/aeyer/plowshare/server/events')

/**
 * The orchestration engine's own package, read for one thing only: the push its
 * config builds as a `Map` literal. It is not a record, so `componentsOf` cannot
 * see it and the three keys are pinned as the literals they are.
 */
const ORCHESTRATIONS = join(SRC, '..', '..',
    'plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations')

function java(file: string): string {
    return readFileSync(join(FRAMES, file), 'utf8')
}

function serverAuth(file: string): string {
    return readFileSync(join(AUTH, file), 'utf8')
}

function serverWs(file: string): string {
    return readFileSync(join(WS, file), 'utf8')
}

function serverApi(file: string): string {
    return readFileSync(join(API, file), 'utf8')
}

function protocol(file: string): string {
    return readFileSync(join(PROTOCOL, file), 'utf8')
}

/** `static final int NAME = 96 * 1024;` read and multiplied, underscores dropped. */
function intConstant(source: string, name: string): number | undefined {
    const found = new RegExp(`int ${name} =\\s*([0-9_ *]+);`).exec(source)?.[1]
    return found === undefined
        ? undefined
        : found.split('*').map((part) => Number(part.replaceAll('_', '').trim()))
            .reduce((product, factor) => product * factor, 1)
}

/** The value of a `static final String NAME = "…"` declaration. */
function stringConstant(source: string, name: string): string | undefined {
    return new RegExp(`String ${name} =\\s*"([^"]*)"`).exec(source)?.[1]
}

/** The value of a `private String field = "…"` default. */
function fieldDefault(source: string, field: string): string | undefined {
    return new RegExp(`private String ${field} = "([^"]*)"`).exec(source)?.[1]
}

/** Every path a `@PostMapping`/`@GetMapping` on this controller names. */
function routesOf(source: string): string[] {
    return [...source.matchAll(/@(?:Post|Get)Mapping\((?:path = )?"([^"]+)"/g)]
        .map((found) => found[1] ?? '')
}

/** The enum constants of `Code.java`: an ALL_CAPS name opening a declaration. */
function constantsOf(source: string): string[] {
    return [...source.matchAll(/^[ \t]+([A-Z][A-Z_0-9]*)\(/gm)].map((found) => found[1] ?? '')
}

/**
 * The component names of a record header, in declaration order.
 *
 * Annotations are dropped and generics are flattened before the split, because
 * both carry punctuation that a naive read mistakes for structure:
 * `@JsonProperty("protocol_version")` ends a component list early, and the comma
 * inside `Map<String, Object>` starts a component that is not there. Both were
 * found by this test failing on `Envelope.java` rather than reasoned about.
 */
function componentsOf(source: string, record: string): string[] {
    const opened = source.indexOf(`public record ${record}(`)
    const header = opened < 0 ? '' : (source.slice(opened).split(/\)\s*\{/)[0] ?? '')
    return header
        .replace(/@\w+\([^)]*\)/g, '')
        .replace(/<[^<>]*>/g, '')
        .replace(`public record ${record}(`, '')
        .split(',')
        .map((component) => component.trim().split(/\s+/).at(-1) ?? '')
        .filter((name) => name.length > 0)
}

describe('the codes this client knows are the codes the server can send', () => {
    it('names every constant of Code.java, and none it does not have', () => {
        const declared = constantsOf(java('Code.java'))

        expect(declared.length).toBeGreaterThan(0)
        expect([...CODES].sort()).toEqual([...declared].sort())
    })

    it('reads the four successes off the server rather than asserting its own', () => {
        // Derived from the file, not from a list here: the four are exactly the
        // constants whose status is 2xx. A fifth added over there arrives here.
        const successes = [...java('Code.java').matchAll(/^[ \t]+([A-Z][A-Z_0-9]*)\((2\d\d),/gm)]
            .map((found) => found[1] ?? '')

        expect(successes).toEqual(['OK', 'ACCEPTED', 'NO_CONTENT', 'CREATED'])
    })
})

describe('the frame types a conversation is made of are the server\'s own', () => {
    /*
     * Task 6's half of this file, and the reason it is here rather than in a
     * second guard: `logic/session.ts` may not import `binding/`, so its six
     * frame types and its two code names are a third spelling of things the
     * server declares once. The brief's instruction is to extend this test
     * rather than to write another one, which is the same argument the header
     * makes against a fixture — a third copy drifts from both.
     *
     * Each case asserts the Java was read before it compares, for the reason
     * every other read in this file does.
     *
     * MEASURED, one at a time and restored afterwards, as the header's own
     * cases were: renaming `CONVERSATION_OPEN` to `'conversation.opened'` in
     * `logic/session.ts` failed with "expected 'conversation.opened' to be
     * 'conversation.open'"; pointing `WS` at a directory that does not exist
     * failed with an ENOENT naming `FrameTypes.java` rather than passing over
     * nothing.
     */

    const SPOKEN: readonly (readonly [string, string])[] = [
        ['CONVERSATION_OPEN', CONVERSATION_OPEN],
        ['CONVERSATION_TURNS', CONVERSATION_TURNS],
        ['AGENT_RUN', AGENT_RUN],
        ['JOB_STATUS', JOB_STATUS],
        // The two listings, added the day the terminal could see anything
        // beyond the conversation it was having. Both were routed already —
        // this client spells them, it did not add them.
        ['PROJECT_LIST', PROJECT_LIST],
        ['CONVERSATION_LIST', CONVERSATION_LIST],
        // The roster, added the day the terminal stopped being told who to
        // talk to. Routed since the socket surface was built and unreached.
        ['AGENT_LIST', AGENT_LIST],
        // The log and the seams in it, added the day talking to a bot
        // continued the conversation it was already having. The first was
        // routed and unreached for four tasks; the second was routed and
        // unreached by anything at all.
        ['CONVERSATION_COMPACTIONS', CONVERSATION_COMPACTIONS],
        // And the one type this client sends that mirrors no endpoint, which
        // makes this read the only guard on its spelling: nothing under
        // `api/` would fail if it were renamed.
        ['CONVERSATION_LATEST', CONVERSATION_LATEST],
        // The one a person reaches for when they want a run to stop, added the
        // day Ctrl-C stopped meaning "abandon it and let it spend". Routed
        // since the socket surface was built and never called by this client.
        ['JOB_CANCEL', JOB_CANCEL],
        // The inbox, added the day a scheduled run started leaving something
        // for a person to come back to. The console reached it first (Task
        // 13); this is the same two frames from a second client.
        ['INBOX_LIST', INBOX_LIST],
        ['INBOX_READ', INBOX_READ],
        // Scheduling out of one sentence: the reading, the pair it saves as,
        // and the four management frames that list, pause, forget and fire
        // them. Every one was routed before this client spoke it.
        ['SCHEDULE_READ', SCHEDULE_READ],
        ['SCHEDULE_DEFINE', SCHEDULE_DEFINE],
        ['SCHEDULE_LIST', SCHEDULE_LIST],
        ['SCHEDULE_PAUSE', SCHEDULE_PAUSE],
        ['SCHEDULE_FORGET', SCHEDULE_FORGET],
        ['TRIGGER_DEFINE', TRIGGER_DEFINE],
        ['TRIGGER_LIST', TRIGGER_LIST],
        ['TRIGGER_PAUSE', TRIGGER_PAUSE],
        ['TRIGGER_FORGET', TRIGGER_FORGET],
        ['EVENT_FIRE', EVENT_FIRE],
        ['FIRING_LIST', FIRING_LIST],
        // How full the conversation is, for the status line. Asked only by a
        // surface that draws one.
        ['CONVERSATION_CONTEXT', CONVERSATION_CONTEXT],
        // Asking a person before a command runs (spec 2026-09-15): the open
        // questions and standing approvals, the answer, and taking one back.
        // Frames only — there is no HTTP route to fall back on.
        ['APPROVAL_LIST', APPROVAL_LIST],
        ['APPROVAL_ANSWER', APPROVAL_ANSWER],
        ['APPROVAL_REVOKE', APPROVAL_REVOKE],
        // Orchestrations, read-only: what this tier can start, what this
        // account has started, and how one of those is going. Three of
        // `OrchestrationFrames`' five, and deliberately the three that change
        // nothing — `answer` and `cancel` stay routed and unspoken.
        ['ORCHESTRATION_DEFINITIONS', ORCHESTRATION_DEFINITIONS],
        ['ORCHESTRATION_LIST', ORCHESTRATION_LIST],
        ['ORCHESTRATION_STATUS', ORCHESTRATION_STATUS],
        // The log, read as the conversation's source (spec 2026-09-28): the page it is read
        // from, and the follow that says when it grew.
        ['CONVERSATION_TRAJECTORY', CONVERSATION_TRAJECTORY],
        ['CONVERSATION_FOLLOW', CONVERSATION_FOLLOW],
        // A project's caps, read and applied to its live runs (spec 2026-09-29 §2).
        // `/cap` sends it after writing the project's own file, and for `/cap` alone.
        ['ORCHESTRATION_CAPS', ORCHESTRATION_CAPS],
    ]

    it('spells each of the thirty-three exactly as FrameTypes.java declares it', () => {
        const source = serverWs('FrameTypes.java')

        for (const [name, spelled] of SPOKEN) {
            const declared = stringConstant(source, name)

            expect(declared).toBeTruthy()
            expect(spelled).toBe(declared)
        }
    })

    it('spells them the way FrameRouter will accept, which is noun.verb', () => {
        // `FrameTypes.requireWellFormed` refuses anything else at wiring time.
        // A client type that did not match it could never have a handler.
        for (const [, spelled] of SPOKEN) {
            expect(spelled).toMatch(/^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$/)
        }
    })

    it('reads the log the way EntryView and EntryPageView write it', () => {
        // A renamed `speaker` reads as absent, which reads as a person's — a harness delivery
        // back to being "you said". A renamed `through` leaves every push looking new.
        const view = componentsOf(serverApi('EntryView.java'), 'EntryView')
        for (const component of [
            'ordinal', 'turnOrdinal', 'kind', 'excerpt', 'length', 'cut', 'toolCalls', 'speaker',
            'speakerName', 'outcome',
        ]) {
            expect(view).toContain(component)
        }
        const page = componentsOf(serverApi('EntryPageView.java'), 'EntryPageView')
        // A renamed `oldest` sends `/earlier` back from nowhere; a renamed `more` reads as the
        // beginning, and the hint that there is anything earlier is never drawn.
        for (const component of ['entries', 'total', 'through', 'oldest', 'more']) {
            expect(page).toContain(component)
        }
        // A renamed `salient` leaves the explorer unable to name a call by anything but its raw
        // arguments; a renamed `opened` loses where a delegated child logs.
        const asked = componentsOf(serverApi('EntryView.java'), 'AskedView')
        for (const component of ['id', 'name', 'arguments', 'length', 'cut', 'salient', 'opened']) {
            expect(asked).toContain(component)
        }
        const opened = componentsOf(serverApi('EntryView.java'), 'OpenedView')
        for (const component of ['conversation', 'agent']) {
            expect(opened).toContain(component)
        }
    })

    it('reads a run\'s conductor conversation the way OrchestrationFrames.RunView writes it', () => {
        // A renamed `conductorConversation` strands the explorer with a run it cannot open —
        // no conversation id to read the log of.
        const run = componentsOf(serverWs('OrchestrationFrames.java'), 'RunView')
        expect(run).toContain('conductorConversation')
    })

    it('asks for the log backwards, and by kind, under the names LogWindow binds', () => {
        // A misspelt field is ignored by the binding, not refused: `tail` read as absent is a
        // forward read of the oldest forty, drawn as though they were the newest.
        const window = componentsOf(serverWs('LogWindow.java'), 'LogWindow')
        // A renamed `drawn` is the same silence: the answers that asked for tools come back and
        // fill the page, and a tool-heavy conversation opens on a handful of turns.
        for (const component of ['offset', 'limit', 'after', 'before', 'tail', 'kinds', 'drawn']) {
            expect(window).toContain(component)
        }
        const kinds = readFileSync(join(WS, '..', 'agents', 'EntryKind.java'), 'utf8')
        for (const kind of DRAWN_KINDS) {
            expect(kinds).toContain(`("${kind}", ChatMessage.Role.`)
        }
    })

    it('takes no longer an "Other" or a note than StructuredAnswers does', () => {
        const source = readFileSync(join(WS, '..', 'agents', 'StructuredAnswers.java'), 'utf8')
        expect(source).toContain(`MOST_FREE = ${MOST_FREE};`)
    })

    it('spells the two speakers as Speaker.Kind does', () => {
        const source = readFileSync(join(WS, '..', 'agents', 'Speaker.java'), 'utf8')
        expect(source).toContain('PERSON("person")')
        expect(source).toContain('HARNESS("harness")')
        expect(source).toContain('"orchestration " +')
    })

    it('reads the appended push by the keys ConversationAppended pushes it under', () => {
        const source = serverWs('ConversationAppended.java')
        expect(source).toContain(`KIND = "${CONVERSATION_APPENDED}"`)
        expect(source).toContain(
            'Map.of("kind", KIND, "conversation", conversationId, "through", through)')
    })

    it('pages the log no wider than the server will', () => {
        const source = readFileSync(join(WS, '..', 'requests', 'RequestedWindow.java'), 'utf8')
        expect(intConstant(source, 'MOST_ENTRIES_A_PAGE')).toBe(LOG_PAGE)
    })

    it('reads every AgentView component that AgentView.java still declares', () => {
        // `bot` and `description` were both appended, and `served` has been
        // there since the disable rule; all three are read by name. `bot` and
        // `served` are booleans that a rename would turn into a confident
        // `false` rather than an error; `description` is a string that a
        // rename would turn into a confident `''`, which reads as a row with
        // nothing to say about itself rather than as a rename gone unnoticed.
        const declared = componentsOf(serverApi('AgentView.java'), 'AgentView')

        expect(declared.length).toBeGreaterThan(0)
        // `model` would read as absent under a rename, and the status line
        // would quietly stop naming what it is talking to. The last four are
        // what `/agents <name>` shows, and they fail quietest of all: a renamed
        // `tools` reads as an empty list, which is a detail view saying an agent
        // holds no capabilities rather than saying it could not find out. This
        // frame is the only place any of the four is on the wire.
        for (const component of ['name', 'served', 'withheld', 'bot', 'description', 'model',
            'preferred', 'tools', 'calls', 'scopes', 'orchestrations']) {
            expect(declared).toContain(component)
        }
    })

    it('sends the two changing frames in the bodies the server binds', () => {
        const source = serverWs('OrchestrationFrames.java')

        // `AnswerBody` and `IdBody` are what `Payloads.as` binds the payloads
        // this client sends onto, and a renamed component becomes a null the
        // server then refuses with "needs the answer text" — a refusal about
        // the person's own answer, which they did supply.
        //
        // Read as literals and not through `componentsOf`, because both are
        // package-private and that helper matches `public record` alone. Widening
        // it for two request bodies would loosen every other assertion in this
        // file, which is the wrong trade for a shape this exact.
        // `choices` carries a question with options' answer (spec 2026-09-29-orchestration-studio
        // §2.3); `answer` stays, the words a person gives instead of or beside them.
        expect(source).toContain('record AnswerBody(String id, String answer, JsonNode choices)')
        expect(source).toContain('record IdBody(String id)')
        // One reader for both answers, which holds only while both records
        // carry the same two components in the same order.
        expect(componentsOf(source, 'Answered')).toEqual(['id', 'state'])
        expect(componentsOf(source, 'Cancelled')).toEqual(['id', 'state'])
    })

    it('reads the changed push by the keys the server pushes it under', () => {
        // Not a record: `OrchestrationsConfig.pushChanges` builds a Map literal,
        // so the three keys are string literals over there and `changedOf` has
        // to match them. A rename there leaves the alert silent — the push still
        // arrives, this client just stops recognising the one state that needs a
        // person — so it is pinned by reading the literals rather than trusted.
        const source = readFileSync(join(ORCHESTRATIONS, 'OrchestrationsConfig.java'), 'utf8')

        expect(source).toContain('CHANGED = "orchestration.changed"')
        expect(source).toContain('Map.of("kind", CHANGED, "orchestration", run.id(), "state"')
    })

    it('reads every OutcomeView component a failed run is explained out of', () => {
        // `text` and `detail` are the two this client spent a while not reading,
        // and they fail as quietly as anything in this file: a rename leaves
        // both absent, `describeFailure` returns undefined for want of anything
        // to pass on, and a run that died of a dead endpoint is reported as
        // "could not reach something it depends on" and nothing else -- which is
        // exactly the state that made them worth reading. `answered` is the
        // boolean that decides which of the two fields `text` is.
        const declared = componentsOf(serverApi('JobView.java'), 'OutcomeView')

        expect(declared.length).toBeGreaterThan(0)
        for (const component of ['ending', 'answered', 'text', 'steps', 'modelCalls', 'detail']) {
            expect(declared).toContain(component)
        }
    })

    it('reads the orchestration components OrchestrationFrames.java still declares', () => {
        // Three listings read off four records. The quiet failures: a renamed
        // `served` reads as `false` and files every definition under "could not
        // be read"; a renamed `state` reads as '' and shows a run with no state
        // at all; a renamed `todos` empties the stage list of a run that has
        // stages. `withheld` is a single sentence here and not a list, unlike
        // `AgentView`'s — a definition is refused for one reason.
        const source = serverWs('OrchestrationFrames.java')

        const definition = componentsOf(source, 'DefinitionView')
        expect(definition.length).toBeGreaterThan(0)
        for (const component of ['name', 'description', 'tier', 'stages', 'triggers', 'served',
            'withheld']) {
            expect(definition).toContain(component)
        }
        expect(componentsOf(source, 'StageView')).toContain('id')
        expect(componentsOf(source, 'StageView')).toContain('doneWhen')
        const run = componentsOf(source, 'RunView')
        expect(run.length).toBeGreaterThan(0)
        // `result`, `failure` and `pendingCap` are the outcome of a run, and
        // `OrchestrationRecord` sets each under exactly one condition: the
        // result once the state is FINISHED, the failure once it is terminal any
        // other way, the cap only while it is ASKING. All three read as absent
        // under a rename, which shows a run that ended as one that ended for no
        // stated reason — the quietest failure on this screen.
        for (const component of ['id', 'definition', 'tier', 'project', 'state', 'pendingCap',
            'result', 'failure', 'parent', 'depth', 'waitingFor', 'createdAt', 'endedAt',
            'stalledSince']) {
            expect(run).toContain(component)
        }
        // A renamed `callerAgent` drops the question dialog's line saying the bot has it too, and
        // the person answers as though nobody else might first.
        expect(run).toContain('callerAgent')
        expect(componentsOf(source, 'MessageView')).toContain('kind')
        expect(componentsOf(source, 'MessageView')).toContain('author')
        expect(componentsOf(source, 'MessageView')).toContain('text')
        // A renamed `structure` reads as a question with no options: the dialog falls back to
        // words, which still answers, and nobody sees the choices the conductor offered.
        expect(componentsOf(source, 'MessageView')).toContain('structure')
        expect(componentsOf(source, 'ChildView')).toEqual(['id', 'state'])
        // The three wrappers, whose one key each is what a reader opens.
        expect(componentsOf(source, 'Definitions')).toEqual(['definitions'])
        expect(componentsOf(source, 'Listed')).toEqual(['orchestrations'])
        expect(componentsOf(source, 'Status'))
            .toEqual(['orchestration', 'todos', 'messages', 'children'])
        // And a run's stages, which are the conductor's todos: this client
        // reads them off `TodoView` and renders them as stages.
        const todo = componentsOf(serverWs('TodoView.java'), 'TodoView')
        expect(todo.length).toBeGreaterThan(0)
        for (const component of ['text', 'status', 'summary', 'stage']) {
            expect(todo).toContain(component)
        }
    })

    it('reads the scheduling components the events records still declare', () => {
        const read: readonly (readonly [string, readonly string[]])[] = [
            ['ScheduleProposal', ['cron', 'zone', 'when', 'agent', 'task', 'intoConversation',
                'project', 'conversation', 'nextFires', 'names']],
            ['ScheduleRecord', ['name', 'cron', 'zone', 'emits', 'paused', 'nextFireAt']],
            ['TriggerRecord', ['name', 'event', 'project', 'conversation', 'agent', 'task',
                'paused']],
            ['FiringRecord', ['id', 'event', 'trigger', 'status', 'reason', 'arrivedAt']],
        ]
        for (const [record, components] of read) {
            const declared = componentsOf(
                readFileSync(join(EVENTS, `${record}.java`), 'utf8'), record)
            expect(declared.length).toBeGreaterThan(0)
            for (const component of components) {
                expect(declared).toContain(component)
            }
        }
        const names = componentsOf(
            readFileSync(join(EVENTS, 'ScheduleProposal.java'), 'utf8'), 'Names')
        expect(names).toEqual(['schedule', 'trigger', 'event'])
    })

    it('reads the approval components ApprovalFrames.java still declares', () => {
        // `command` is the one that fails loudest and `defaultPrefix` the one
        // that fails quietest: a renamed `command` drops every question as
        // unreadable, and a renamed `defaultPrefix` reads as empty and starts
        // every project prefix at the program alone. `busy` is a boolean a
        // rename would turn into a confident `false`, and `job` an absent turn.
        const source = serverWs('ApprovalFrames.java')

        const view = componentsOf(source, 'View')
        expect(view.length).toBeGreaterThan(0)
        // `commands` and `judged` are an acceptance set's (V67): a renamed `commands` would drop
        // every set as a row with no command.
        for (const component of ['id', 'conversation', 'agent', 'side', 'command', 'cwd', 'reason',
            'state', 'scope', 'prefix', 'defaultPrefix', 'commands', 'judged']) {
            expect(view).toContain(component)
        }
        expect(componentsOf(source, 'Listed')).toEqual(['approvals'])
        expect(componentsOf(source, 'Answered')).toEqual(['id', 'state', 'job', 'busy', 'note'])
        expect(componentsOf(source, 'Revoked')).toEqual(['id', 'revoked'])
        // And what an answer is sent as: the body's three fields, and the four
        // decisions in the words the server reads.
        expect(source).toMatch(/record AnswerBody\(String id, String decision, List<String> prefix\)/)
        // `mine` is what listingMyApprovals sends, for the questions no screen has open.
        expect(source).toMatch(/record ListBody\(String conversation, String project, Boolean mine\)/)
        const approval = readFileSync(join(WS, '..', 'approvals', 'RunApproval.java'), 'utf8')
        expect(['once', 'conversation', 'project']).toEqual(
            ['ONCE', 'CONVERSATION', 'PROJECT'].map((name) => stringConstant(approval, name)))
        expect(source).toContain('"deny"')
    })

    it('knows the AWAITING ending Outcome.Ending declares', () => {
        const outcome = readFileSync(join(WS, '..', 'agents', 'Outcome.java'), 'utf8')
        const ending = outcome.slice(outcome.indexOf('public enum Ending'))

        expect(ending.length).toBeGreaterThan(0)
        expect(ending).toMatch(new RegExp(`\\b${AWAITING}\\b`))
    })

    it('knows the CALL_FAILURES ending Outcome.Ending declares', () => {
        const outcome = readFileSync(join(WS, '..', 'agents', 'Outcome.java'), 'utf8')
        const ending = outcome.slice(outcome.indexOf('public enum Ending'))

        expect(ending.length).toBeGreaterThan(0)
        expect(ending).toMatch(/\bCALL_FAILURES\b/)
    })

    it('reads the TurnView components that TurnView.java still declares', () => {
        // Every one of the five, because a continued conversation is rendered
        // out of them: a renamed `ordinal` reads as 0, which puts every turn in
        // one place and lets a seam land anywhere; a renamed `ending` reads as
        // '' and dresses a truncated turn as an ordinary one.
        const declared = componentsOf(serverApi('TurnView.java'), 'TurnView')

        expect(declared.length).toBeGreaterThan(0)
        for (const component of ['ordinal', 'utterance', 'answer', 'ending', 'promptTokens']) {
            expect(declared).toContain(component)
        }
    })

    it('reads the two ContextView numbers the status line divides', () => {
        // Both read as absent under a rename, which a status line says as a
        // dash: a conversation that looks unmeasured rather than a field gone.
        const source = serverApi('ContextView.java')

        expect(componentsOf(source, 'ContextView')).toContain('sent')
        expect(componentsOf(source, 'ContextView')).toContain('prefix')
        expect(componentsOf(source, 'Prefix')).toContain('contextLength')
        expect(componentsOf(source, 'Prefix')).toContain('model')
    })

    it('reads the CompactionView components that CompactionView.java still declares', () => {
        // `throughOrdinal` is the one that fails silently: it reads as 0 under
        // a rename, and a seam at ordinal 0 is a seam at the top of the
        // scrollback — a fold reported in the wrong place, which is worse than
        // one not reported at all.
        const declared = componentsOf(serverApi('CompactionView.java'), 'CompactionView')

        expect(declared.length).toBeGreaterThan(0)
        expect(declared).toContain('throughOrdinal')
        expect(declared).toContain('summary')
    })

    it('names two codes that Code.java still declares, and keeps them apart', () => {
        // `logic/` reads ACCEPTED and OK by name to decide whether a turn has a
        // handle to follow. If either were renamed over there, every turn would
        // be refused with a sentence about a code nobody sent.
        const declared = constantsOf(java('Code.java'))

        expect(declared.length).toBeGreaterThan(0)
        expect(declared).toContain(ACCEPTED)
        expect(declared).toContain(OK)
        expect(ACCEPTED).not.toBe(OK)
    })
})

describe('the harness questions this client knows are the server\'s own', () => {
    it('knows every pending_cap the engine asks with, and which of them only a person may answer', () => {
        // A kind the server adds and this client does not know opens no dialog, and a person-only
        // one is left out of the waiting list when a phase asks it — the question goes unseen.
        const source = readFileSync(join(ORCHESTRATIONS, 'Orchestrations.java'), 'utf8')
        const constant = (name: string): string | undefined =>
            new RegExp(`static final String ${name} = "([a-z_]+)";`).exec(source)?.[1]
        expect([constant('TURN_CAP'), constant('CALL_BUDGET'), constant('TIME_CAP')]).toEqual([...CAP_KINDS])
        expect([constant('STUCK'), constant('UNCOVERED'), constant('CHECK_FAILURES'), constant('INSTALL'),
            constant('CONCERNS'), constant('PRODUCT_CHECK')]).toEqual([...PERSON_ONLY_KINDS])
        expect(source).toMatch(/PERSON_ONLY\s*=\s*Set\.of\(\s*STUCK,\s*UNCOVERED,\s*CHECK_FAILURES,\s*INSTALL,\s*CONCERNS,\s*PRODUCT_CHECK\)/)
    })

    it('reads the caps `orchestration.caps` answers with, each setting CapsFrames.CapsView names', () => {
        // A setting the server adds and this client does not read is a cap `/cap` never shows.
        const view = componentsOf(serverWs('CapsFrames.java'), 'CapsView')
        expect(view).toEqual(['project', 'steps', 'budget', 'autoContinue', 'time', 'failedChecks', 'autoIncrease',
            'applied', 'said'])
        const caps = capsOf({ code: OK, payload: {
            project: 'story', steps: { source: 'definition' }, budget: { source: 'definition' },
            autoContinue: { source: 'definition' }, time: { value: 90, source: '.plowshare/environment.yml' },
            autoIncrease: { value: true, source: '.plowshare/environment.yml' },
            failedChecks: { value: 5, source: 'default' }, applied: 0 } })
        expect(caps?.time).toEqual({ value: 90, source: '.plowshare/environment.yml' })
        expect(caps?.autoIncrease).toEqual({ value: true, source: '.plowshare/environment.yml' })
        expect(caps?.failedChecks).toEqual({ value: 5, source: 'default' })
    })
})

describe('the envelope this client speaks is the envelope the server checks', () => {
    it('claims the version Envelope.CURRENT_VERSION declares', () => {
        const declared = /CURRENT_VERSION = "([^"]+)"/.exec(java('Envelope.java'))

        expect(declared?.[1]).toBeTruthy()
        expect(CURRENT_VERSION).toBe(declared?.[1])
    })

    it('is the snake_case name the server binds, not the Java field name', () => {
        expect(java('Envelope.java')).toContain('@JsonProperty("protocol_version")')
    })

    it('has the four fields Envelope.java declares and no more', () => {
        // `id`, `type`, `protocolVersion`, `payload`. A fifth is a wire change,
        // and this client reading four of five would ignore it in silence.
        expect(componentsOf(java('Envelope.java'), 'Envelope'))
            .toEqual(['id', 'type', 'protocolVersion', 'payload'])
    })

    it('has the three fields Outcome.java declares and no more', () => {
        // `{ code, said?, payload? }` — spec §3.3. A fourth component is a
        // decision for whoever adds it rather than a field to drop on the floor.
        expect(componentsOf(java('Outcome.java'), 'Outcome'))
            .toEqual(['code', 'said', 'payload'])
    })

    it('keeps said absent-able on the wire, which is what NON_NULL buys', () => {
        // The client design's §5.2 rests on absence being distinguishable from
        // an empty sentence. That is true of this client only because it is true
        // of the server, and this is the line that makes it true there.
        expect(java('Outcome.java')).toContain('@JsonInclude(JsonInclude.Include.NON_NULL)')
    })
})

describe('the door this client knocks on is the door the server opened', () => {
    /*
     * Task 5's half of this file. The four auth endpoints are the only HTTP
     * this client speaks, and every one of them turns on a string the server
     * declares: two header names, the one value of one of them, and the two
     * cookie names. NONE OF THOSE FAIL LOUDLY WHEN THEY DRIFT. An unrecognised
     * `X-Plowshare-Token-Delivery` is not an error — the server answers the 204
     * it answers a browser with, and this client complains about a status three
     * lines later; a renamed `ps_refresh` means a refresh that presents no
     * cookie at all, which reads as an expired session. So they are held here,
     * against the Java that declares them, the same way the codes and the
     * protocol version are.
     *
     * Each read asserts it found something before it compares, for the reason
     * this file's header gives: a regex that stops matching would otherwise
     * leave every assertion comparing undefined to undefined.
     */

    it('sends the header AuthController invented, spelled the way it spelled it', () => {
        const declared = stringConstant(serverAuth('AuthController.java'),
            'TOKEN_DELIVERY_HEADER')

        expect(declared).toBeTruthy()
        expect(TOKEN_DELIVERY_HEADER).toBe(declared)
    })

    it('sends the one value of it that means anything', () => {
        // Any other value, including a near miss, reads the same as the header
        // being absent — `wantsTokenInBody` is an equalsIgnoreCase against this
        // and nothing looser.
        const declared = stringConstant(serverAuth('AuthController.java'),
            'TOKEN_DELIVERY_BODY')

        expect(declared).toBeTruthy()
        expect(TOKEN_DELIVERY_BODY).toBe(declared)
    })

    it('reads the must-change header GET /v1/auth/session writes', () => {
        const declared = stringConstant(serverAuth('AuthController.java'),
            'MUST_CHANGE_PASSWORD_HEADER')

        expect(declared).toBeTruthy()
        expect(MUST_CHANGE_PASSWORD_HEADER).toBe(declared)
    })

    it('defaults the two cookie names to what AuthProperties defaults them to', () => {
        const properties = serverAuth('AuthProperties.java')

        expect(fieldDefault(properties, 'accessCookie')).toBeTruthy()
        expect(ACCESS_COOKIE).toBe(fieldDefault(properties, 'accessCookie'))
        expect(REFRESH_COOKIE).toBe(fieldDefault(properties, 'refreshCookie'))
    })

    it('names four routes that AuthController still maps', () => {
        const routes = routesOf(serverAuth('AuthController.java'))

        expect(routes.length).toBeGreaterThan(0)
        for (const route of ['/v1/auth/login', '/v1/auth/refresh', '/v1/auth/ticket',
            '/v1/auth/session']) {
            expect(routes).toContain(route)
        }
    })

    it('presents a bearer on exactly the two routes AuthFilter gates', () => {
        // Which call carries `Authorization` is decided by this set and not by
        // preference: login and refresh are open, so a token on them would be
        // noise, and ticket and session are gated, so a call without one is a
        // 401 answered before the controller is ever entered.
        const open = /OPEN =\s*Set\.of\(([^)]*)\)/.exec(serverAuth('AuthFilter.java'))?.[1] ?? ''

        expect(open).toContain('"/v1/auth/login"')
        expect(open).toContain('"/v1/auth/refresh"')
        expect(open).not.toContain('/v1/auth/ticket')
        expect(open).not.toContain('/v1/auth/session')
    })
})

describe('the file wire this client answers is the protocol module\'s own', () => {
    it('opens on the path and with the parameters the handler reads', () => {
        const handler = serverWs('FileChannelHandler.java')
        expect(FILES_PATH).toBe(stringConstant(handler, 'PATH'))
        expect(SESSION_PARAM).toBe(stringConstant(handler, 'SESSION_PARAM'))
        expect(PROJECT_PARAM).toBe(stringConstant(handler, 'PROJECT_PARAM'))
        expect(MACHINE_PARAM).toBe(stringConstant(handler, 'MACHINE_PARAM'))
        expect(ROOT_PARAM).toBe(stringConstant(handler, 'ROOT_PARAM'))
    })

    it('knows every op and every outcome', () => {
        const request = protocol('FileRequest.java')
        const reply = protocol('FileReply.java')
        expect([ROOTS, READ, STAT, GLOB, WRITE, GREP, EDIT, DELETE, MOVE, RUN, CANCEL]).toEqual(
            ['ROOTS', 'READ', 'STAT', 'GLOB', 'WRITE', 'GREP', 'EDIT', 'DELETE', 'MOVE', 'RUN', 'CANCEL']
                .map((name) => stringConstant(request, name)))
        expect(OPS).toEqual([ROOTS, READ, STAT, GLOB, WRITE, GREP, EDIT, DELETE, MOVE, RUN, CANCEL, SOURCE])
        expect([REPLY_OK, REPLY_REFUSED, REPLY_UNAVAILABLE]).toEqual(
            ['OK', 'REFUSED', 'UNAVAILABLE'].map((name) => stringConstant(reply, name)))
        expect(componentsOf(request, 'FileRequest')).toEqual(
            ['id', 'op', 'path', 'pattern', 'content', 'offset', 'limit', 'needle', 'ignoreCase',
                'purpose', 'replacing', 'to', 'createOnly', 'argv', 'env', 'inherit', 'timeoutMillis',
                'outputBytes', 'shells', 'stdin'])
        expect(DEFINITIONS).toBe(stringConstant(request, 'DEFINITIONS'))
        expect(HOOKS).toBe(stringConstant(request, 'HOOKS'))
        expect(componentsOf(reply, 'FileReply')).toEqual(
            ['id', 'outcome', 'sentence', 'paths', 'span', 'found', 'exitCode', 'timedOut', 'stdout',
                'stdoutCut', 'stderr', 'stderrCut', 'millis', 'result', 'source'])
        expect(componentsOf(protocol('Span.java'), 'Span')).toEqual(
            ['lines', 'offset', 'totalLines', 'more', 'stoppedBy'])
    })

    it('reads every field a run carries off the frame, and nothing of the wrong shape', () => {
        expect(requestIn(JSON.stringify({
            id: 'r', op: RUN, path: '/repo', argv: ['make', 'test'], env: { A: 'b' }, inherit: ['PATH'],
            timeoutMillis: 1000, outputBytes: 2048, shells: false, stdin: '3\n',
        }))).toEqual({
            id: 'r', op: RUN, path: '/repo', argv: ['make', 'test'], env: { A: 'b' }, inherit: ['PATH'],
            timeoutMillis: 1000, outputBytes: 2048, shells: false, stdin: '3\n',
        })
        expect(requestIn(JSON.stringify({ id: 'r', op: RUN, argv: ['make', 1], env: { A: 2 }, inherit: 'PATH' })))
            .toEqual({ id: 'r', op: RUN })
    })

    it('holds the bounds the server holds', () => {
        expect(MAX_WINDOW_LINES).toBe(intConstant(protocol('Window.java'), 'MAX_WINDOW_LINES'))
        expect(MAX_WINDOW_BYTES).toBe(intConstant(protocol('Window.java'), 'MAX_WINDOW_BYTES'))
        expect(MAX_MATCHES).toBe(intConstant(protocol('Needle.java'), 'MAX_MATCHES'))
        expect(MAX_LINE_CHARS).toBe(intConstant(protocol('Needle.java'), 'MAX_LINE_CHARS'))
        expect(MAX_RECURSIVE_WILDCARDS).toBe(
            intConstant(protocol('GlobSpellings.java'), 'MAX_RECURSIVE_WILDCARDS'))
    })
})

describe('an edit replaces what Replacement.apply replaces', () => {
    /*
     * The one rule with three implementations, held to one table rather than
     * to this file's reading of the Java: `ReplacementTest` runs the same JSON,
     * so a case added there fails here the same day. The refusal sentences are
     * each side's own and are not in the table; the kind and the count are.
     */
    interface Case {
        readonly name: string
        readonly text: string
        readonly old: string
        readonly new: string
        readonly result?: string
        readonly refused?: string
        readonly count?: number
    }

    const table = JSON.parse(readFileSync(join(SRC, '..', '..',
        'plowshare-protocol/src/test/resources/io/aeyer/plowshare/protocol/replacements.json'),
    'utf8')) as Case[]

    it('reads a table with something in it', () => {
        expect(table.length).toBeGreaterThan(0)
    })

    it.each(table.map((one) => [one.name, one] as const))('%s', (_name, one) => {
        if (one.refused === undefined) {
            expect(replaced(one.text, one.old, one.new)).toBe(one.result)
            return
        }
        let thrown: unknown
        try {
            replaced(one.text, one.old, one.new)
        } catch (trouble) {
            thrown = trouble
        }
        expect(thrown).toBeInstanceOf(Unreplaced)
        expect((thrown as Unreplaced).kind).toBe(one.refused)
        expect((thrown as Unreplaced).count).toBe(one.count)
    })
})

describe('a change answers with the facts FileResult declares', () => {
    /*
     * A change on this client answers with facts and no sentence, and the server
     * words them (spec 2026-09-30, the file side reports facts; the server words
     * them). `editfacts.test.ts` and `enforcer.test.ts` run the protocol's shared
     * tables; what they cannot catch is the Java moving on without this client — a
     * kind or a reason added, a component renamed, a bound raised, a look-alike
     * added — so those are read out of the Java here.
     */
    const result = protocol('FileResult.java')
    const replacement = protocol('Replacement.java')
    const facts = protocol('EditFacts.java')

    /** A Java string literal's text, its escapes undone. */
    const unescaped = (literal: string): string => literal.replace(/\\(.)/g, '$1')

    it('has the components FileResult, Excerpt and Difference declare', () => {
        expect(componentsOf(result, 'FileResult')).toEqual(
            ['version', 'kind', 'op', 'path', 'to', 'reason', 'argument', 'roots', 'detail', 'bytes',
                'limit', 'count', 'lines', 'first', 'last', 'removed', 'excerpt', 'near', 'differences',
                'foreign', 'format', 'image', 'status', 'pattern'])
        expect(componentsOf(protocol('Excerpt.java'), 'Excerpt')).toEqual(
            ['from', 'to', 'total', 'lines', 'gap', 'clipped'])
        expect(componentsOf(result, 'Difference')).toEqual(['sent', 'there'])
        expect(RESULT_VERSION).toBe(intConstant(result, 'VERSION'))
    })

    it('spells every kind, reason and match FileResult declares, and no other', () => {
        const declared = [...result.matchAll(/public static final String ([A-Z_0-9]+) = "([^"]*)";/g)]
            .map(([, name, value]) => [name ?? '', value ?? ''] as const)
        expect(declared.length).toBeGreaterThan(20)
        const mirrored = results as unknown as Record<string, unknown>
        for (const [name, value] of declared) {
            expect(mirrored[name], name).toBe(value)
        }
    })

    it('holds the bounds Replacement holds', () => {
        expect(CONTEXT_LINES).toBe(intConstant(replacement, 'CONTEXT_LINES'))
        expect(MAX_SHOWN_LINES).toBe(intConstant(replacement, 'MAX_SHOWN_LINES'))
        expect(MAX_SHOWN_LINE_CHARS).toBe(intConstant(replacement, 'MAX_SHOWN_LINE_CHARS'))
        expect(MAX_LISTED).toBe(intConstant(replacement, 'MAX_LISTED'))
    })

    it('folds the look-alike characters EditFacts folds, to the same ASCII', () => {
        const folds = [...facts.matchAll(
            /for \(char c : "([^"]*)"\s*\.toCharArray\(\)\) \{\s*folds\.put\(c, '(\\.|[^'])'\);/g)]
            .map((found) => [found[1] ?? '', unescaped(found[2] ?? '')])
        // Four groups, measured when this was written: dashes, spaces, single and double quotes.
        expect(folds.length).toBeGreaterThanOrEqual(4)
        expect(FOLD_SOURCES).toEqual(folds)
    })

    it('keeps no words for a change: the server holds every one, character names among them', () => {
        const port = readFileSync(join(SRC, '..', '..', 'plowshare-client-ts', 'src', 'binding', 'editfacts.ts'), 'utf8')
        const enforcer = readFileSync(join(SRC, 'view', 'files', 'enforcer.ts'), 'utf8')
        for (const words of ['NO-BREAK SPACE', 'The closest lines', 'to create it', 'The new text is on']) {
            expect(port, words).not.toContain(words)
            expect(enforcer, words).not.toContain(words)
        }
        expect(enforcer).not.toContain('so nothing was')
    })

    it('keeps no words for any other file action either: a run\'s own consent is the one left', () => {
        // Step 2 of the spec: every refusal of a read, a stat, a glob and a search,
        // the fence's among them, is facts. These are openings the enforcer and the
        // binding used to write; the server's FileWords holds each of them now.
        const binding = readFileSync(join(SRC, '..', '..', 'plowshare-client-ts', 'src', 'binding', 'files.ts'), 'utf8')
        const enforcer = readFileSync(join(SRC, 'view', 'files', 'enforcer.ts'), 'utf8')
        for (const words of ['there is no file at', 'is outside this session', 'not UTF-8 text',
            'is not a regular file', 'will not read more than', 'a glob pattern is required',
            'is an absolute pattern', 'is not a usable glob:', 'files match on this machine',
            'could not be listed', 'does not know how to', 'is no longer there',
            'this client failed while answering', 'cannot serve', 'cannot run: a search',
            'file_grep searches', 'the MCP client converts']) {
            expect(enforcer, words).not.toContain(words)
            expect(binding, words).not.toContain(words)
        }
    })
})

describe('an environment file reads as EnvironmentFile reads it', () => {
    /*
     * The grammar that decides whether commands run on this machine, with a second
     * implementation here. `EnvironmentFileTest` runs the same JSON, so a case added
     * there fails here the same day. The refusal sentences are each side's; the
     * line at fault is in the table.
     */
    interface Case {
        readonly name: string
        readonly text: string
        readonly expect?: { readonly local: unknown; readonly server: unknown; readonly caps?: unknown }
        readonly refusedLine?: number
    }

    const table = JSON.parse(readFileSync(join(SRC, '..', '..',
        'plowshare-protocol/src/test/resources/io/aeyer/plowshare/protocol/environments.json'),
    'utf8')) as Case[]

    it('reads a table with something in it', () => {
        expect(table.length).toBeGreaterThan(0)
    })

    it.each(table.map((one) => [one.name, one] as const))('%s', (_name, one) => {
        if (one.refusedLine === undefined) {
            expect(parseEnvironment(one.text)).toEqual({ caps: null, ...one.expect })
            return
        }
        let thrown: unknown
        try {
            parseEnvironment(one.text)
        } catch (trouble) {
            thrown = trouble
        }
        expect(thrown).toBeInstanceOf(Unreadable)
        expect((thrown as Unreadable).line).toBe(one.refusedLine)
    })

    it('holds the constants and defaults EnvironmentFile holds', () => {
        const source = protocol('EnvironmentFile.java')
        expect([OFF, GATED, ASK, OPEN, LOCAL, SERVER]).toEqual(
            ['OFF', 'GATED', 'ASK', 'OPEN', 'LOCAL', 'SERVER'].map((name) => stringConstant(source, name)))
        expect(/DEFAULT_TIMEOUT = Duration\.ofMinutes\((\d+)\)/.exec(source)?.[1]).toBe(
            String(DEFAULT_TIMEOUT_MILLIS / 60_000))
        expect(/MAX_TIMEOUT = Duration\.ofMinutes\((\d+)\)/.exec(source)?.[1]).toBe(
            String(MAX_TIMEOUT_MILLIS / 60_000))
        expect(/DEFAULT_OUTPUT_BYTES = 1024L \* 1024;/.test(source)).toBe(true)
        expect(DEFAULT_OUTPUT_BYTES).toBe(1024 * 1024)
        expect(/MAX_OUTPUT_BYTES = 8L \* 1024 \* 1024;/.test(source)).toBe(true)
        expect(MAX_OUTPUT_BYTES).toBe(8 * 1024 * 1024)
        expect(/MOST_TIME_MINUTES = 7 \* 24 \* 60;/.test(source)).toBe(true)
        expect(MOST_TIME_MINUTES).toBe(7 * 24 * 60)
        expect(/MOST_FAILED_CHECKS = (\d+);/.exec(source)?.[1]).toBe(String(MOST_FAILED_CHECKS))
        const settings = /CAP_SETTINGS =\s*List\.of\(([^)]*)\)/.exec(source)?.[1] ?? ''
        expect([...settings.matchAll(/"([^"]+)"/g)].map((found) => found[1])).toEqual([...CAP_KEYS])
        const inherit = /DEFAULT_INHERIT = List\.of\(([^)]*)\)/.exec(source)?.[1] ?? ''
        expect([...inherit.matchAll(/"([^"]+)"/g)].map((found) => found[1])).toEqual([...DEFAULT_INHERIT])
        const shells = /SHELLS =\s*Set\.of\(([^)]*)\)/.exec(source)?.[1] ?? ''
        const declared = [...shells.matchAll(/"([^"]+)"/g)].map((found) => found[1] ?? '')
        expect(declared.length).toBeGreaterThan(0)
        expect([...SHELLS].sort()).toEqual(declared.sort())
        expect(DEFAULT_SIDE.mode).toBe(OFF)
        expect(DEFAULT_SIDE.shells).toBe(false)
    })

    it('replaces only the keys a section set, and knows a shell by its basename', () => {
        expect(sideWith(DEFAULT_SIDE, { mode: OPEN, timeoutMillis: 1000 })).toEqual(
            { ...DEFAULT_SIDE, mode: OPEN, timeoutMillis: 1000 })
        expect(sideWith(DEFAULT_SIDE, null)).toBe(DEFAULT_SIDE)
        expect(isShell('/bin/bash')).toBe(true)
        expect(isShell('C:\\Windows\\System32\\PowerShell.EXE')).toBe(true)
        expect(isShell('make')).toBe(false)
        expect(isShell('bashful')).toBe(false)
        expect(isShell(undefined)).toBe(false)
    })
})

describe('the record this client reads is the record the server keeps', () => {
    it('asks in the shape RecordFrames binds, and reads the page RecordPageView writes', () => {
        const frames = serverWs('RecordFrames.java').replace(/\s+/g, ' ').replace(/\(\s+/g, '(')
        expect(frames).toContain(
            'record RecordWindow(String root, Integer after, Integer before, Boolean tail,')
        expect(frames).toContain('Integer limit, List<String> kinds)')
        expect(serverWs('FrameTypes.java')).toContain(`ORCHESTRATION_RECORD = "${ORCHESTRATION_RECORD}"`)
        expect(componentsOf(serverApi('RecordPageView.java'), 'RecordPageView'))
            .toEqual(['root', 'rows', 'total', 'limit', 'through', 'oldest', 'more'])
        expect(componentsOf(serverApi('RecordView.java'), 'RecordView'))
            .toEqual(['ordinal', 'at', 'run', 'actor', 'kind', 'text', 'detail', 'body'])
        // V64: a row without a body is sent without the key, which `recordPageOf` reads as none.
        expect(serverApi('RecordView.java')).toContain('@JsonInclude(JsonInclude.Include.NON_NULL) String body')
    })

    it('reads the recorded push by the keys RecordKeeper pushes it under', () => {
        const source = readFileSync(join(ORCHESTRATIONS, 'RecordKeeper.java'), 'utf8')
        expect(source).toContain(`RECORDED = "${ORCHESTRATION_RECORDED}"`)
        expect(source).toContain('Map.of("kind", RECORDED, "root", root, "through", through)')
        // A settle's push names its line too, under the key `recordedOf` reads it by.
        expect(source).toContain('"settled", settled')
    })

    it('names every kind RecordKind spells, and no other', () => {
        const source = readFileSync(join(ORCHESTRATIONS, 'RecordKind.java'), 'utf8')
        const spelled = [...source.matchAll(/^[ \t]+[A-Z_]+\("([a-z_]+)"\)/gm)].map((found) => found[1])
        expect(spelled.length).toBeGreaterThan(0)
        expect(spelled).toEqual([...MILESTONE_KINDS, TOOL_CALL])
    })

    it('reads the record no wider than the server pages it', () => {
        const source = readFileSync(join(WS, '..', 'requests', 'RequestedWindow.java'), 'utf8')
        expect(intConstant(source, 'MOST_ENTRIES_A_PAGE')).toBe(RECORD_TAIL)
    })
})
