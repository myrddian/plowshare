import type { Asked, BackPage, Entry } from '../../logic/session.ts'
import type { Reads } from '../exploring.ts'

/**
 * A canned session for `pnpm demo`: a failing test, an edit, a delegation two levels deep, a
 * fold, a hook's denial, and one call still running. Written as the server's page would carry
 * it, so every part of the trajectory UI has something real-shaped to draw — no server needed.
 *
 * <p><b>Delegation results say `ok`</b>, not `answered`: `ok` is the word the server actually
 * sends for a call whose agent finished without error, and this fixture is meant to be read as
 * a page the server produced. `outcomeClass` treats both the same, so the choice changes nothing
 * about what is drawn — it only keeps the data honest about which side made it up.
 */

const at = (second: number): string => new Date(Date.UTC(2026, 8, 29, 10, 0, second)).toISOString()
let clock = 0

const row = (ordinal: number, turn: number, kind: string, text: string, extra: Partial<Entry> = {}): Entry => {
    clock += 2
    return { ordinal, turnOrdinal: turn, kind, state: 'stands', text, length: [...text].length, recordedAt: at(clock), ...extra }
}

/**
 * An answer that asked for `calls`, each `[id, tool, salient, arguments, extra]`: the arguments in
 * the tool's own shape — `run` a command, the file tools a path — as the server pages them, and
 * the salient one beside them, as `ToolLines` picks it.
 */
const ask = (
        ordinal: number, turn: number, took: number,
        calls: readonly (readonly [string, string, string, Readonly<Record<string, string>>, Partial<Asked>?])[],
        text = ''): Entry =>
    row(ordinal, turn, 'answer', text, {
        tookMillis: took, asked: calls.length, wireModel: 'qwen3.5-9b', dispatch: 'primary', completion: 'called_tools',
        calls: calls.map(([id, name, salient, args, extra]): Asked => {
            const written = JSON.stringify(args)
            return { id, name, arguments: written, length: [...written].length, cut: false, salient, ...extra }
        }),
    })

const result = (ordinal: number, turn: number, id: string, outcome: string, took: number, text: string,
        extra: Partial<Entry> = {}): Entry =>
    row(ordinal, turn, 'tool_result', text, { toolCallId: id, outcome, tookMillis: took, handle: `h-${ordinal}`, ...extra })

const TEST_OUTPUT = [
    '> Task :plowshare-server:compileJava UP-TO-DATE', '> Task :plowshare-server:test',
    'TokenizerTest > counts_multibyte() FAILED', '    org.opentest4j.AssertionFailedError: expected: <3> but was: <9>',
    '        at TokenizerTest.counts_multibyte(TokenizerTest.java:41)', '41 tests completed, 1 failed', 'BUILD FAILED in 4s',
].join('\n')

const TOKENIZER = 'plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/RatioTokenizer.java'
const TEST_TOKENIZER = './gradlew :plowshare-server:test --tests TokenizerTest'

/** Turn 1 is folded: the summary at #3 stands for its two rows, which the server marks superseded by it. */
export const DEMO_ROOT: Entry[] = [
    row(1, 1, 'utterance', 'what is in this repo?', { speaker: 'person', speakerName: 'enzo', supersededBy: 3 }),
    row(2, 1, 'answer', 'Six modules: server, protocol, client, console, hooks and the TUI.',
        { tookMillis: 2100, wireModel: 'qwen3.5-9b', completion: 'answered', supersededBy: 3 }),
    row(3, 2, 'summary', 'Earlier: the person asked what the repo holds; six modules.', {}),
    row(4, 3, 'utterance', 'fix the failing tokenizer test', { speaker: 'person', speakerName: 'enzo' }),
    row(5, 3, 'thinking', 'The tokenizer counts bytes. A multibyte string would overcount — look at RatioTokenizer first.'),
    ask(6, 3, 3100, [['c1', 'file_read', TOKENIZER, { path: TOKENIZER }]], 'Let me look at the tokenizer.'),
    result(7, 3, 'c1', 'ok', 41, 'public final class RatioTokenizer implements Tokenizer {\n    public int count(String text) {\n        return text.getBytes(UTF_8).length / ratio;\n    }\n}'),
    ask(8, 3, 2400, [['c2', 'run', TEST_TOKENIZER, { command: TEST_TOKENIZER }]]),
    result(9, 3, 'c2', 'exit 1', 4200, TEST_OUTPUT),
    ask(10, 3, 2800, [['c3', 'file_edit', TOKENIZER, {
        path: TOKENIZER, old: 'return text.getBytes(UTF_8).length / ratio;', new: 'return text.codePointCount(0, text.length()) / ratio;',
    }]]),
    result(11, 3, 'c3', 'ok', 12, 'edited RatioTokenizer.java: 1 replacement'),
    ask(12, 3, 2200, [['c4', 'run', 'git push origin HEAD', { command: 'git push origin HEAD' }]]),
    result(13, 3, 'c4', 'denied', 3, 'a hook denied this command: pushing is not allowed from an agent run'),
    row(14, 3, 'hook', 'tool.pre deny-push: git push is never run by an agent'),
    ask(15, 3, 1900, [['c5', 'run', TEST_TOKENIZER, { command: TEST_TOKENIZER }]]),
    result(16, 3, 'c5', 'ok', 6800, 'BUILD SUCCESSFUL in 6s\n41 tests completed'),
    ask(17, 3, 2600, [['c6', 'agent_run', 'code_reviewer', { agent: 'code_reviewer', task: 'Review the tokenizer fix in RatioTokenizer.java.' },
        { opened: { conversation: 'demo_child', agent: 'code_reviewer' } }]], 'Asking the reviewer to check it.'),
    result(18, 3, 'c6', 'ok', 48_000, 'The change is right; one nit: name the constant.'),
    row(19, 3, 'answer', 'Fixed: the tokenizer now counts code points, not bytes. The reviewer agrees; one naming nit left.', { tookMillis: 1800, wireModel: 'qwen3.5-9b', completion: 'answered' }),
    row(20, 4, 'utterance', 'and the docs?', { speaker: 'person', speakerName: 'enzo' }),
    ask(21, 4, 2000, [['c7', 'file_grep', '"RatioTokenizer" docs/', { pattern: 'RatioTokenizer', path: 'docs/' }]]),
]

export const DEMO_CHILD: Entry[] = [
    row(1, 1, 'utterance', 'Review the tokenizer fix in RatioTokenizer.java.', { speaker: 'harness', speakerName: 'harness' }),
    row(2, 1, 'thinking', 'The change reads right; whether it actually passes is a different question — run the tests before saying so.'),
    ask(3, 1, 2300, [['k1', 'file_read', TOKENIZER, { path: TOKENIZER }]]),
    result(4, 1, 'k1', 'ok', 30, 'return text.codePointCount(0, text.length()) / ratio;'),
    ask(5, 1, 2100, [['k2', 'agent_run', 'test_runner', { agent: 'test_runner', task: 'Run the tokenizer tests.' },
        { opened: { conversation: 'demo_grandchild', agent: 'test_runner' } }]]),
    result(6, 1, 'k2', 'ok', 21_000, '41 tests, all passing.'),
    row(7, 1, 'answer', 'The change is right; one nit: name the constant.', { tookMillis: 1500, completion: 'answered' }),
]

export const DEMO_GRANDCHILD: Entry[] = [
    row(1, 1, 'utterance', 'Run the tokenizer tests.', { speaker: 'harness', speakerName: 'harness' }),
    ask(2, 1, 1200, [['g1', 'run', './gradlew :plowshare-server:test', { command: './gradlew :plowshare-server:test' }]]),
    result(3, 1, 'g1', 'ok', 19_000, 'BUILD SUCCESSFUL in 19s'),
    row(4, 1, 'answer', '41 tests, all passing.', { tookMillis: 800, completion: 'answered' }),
]

const LOGS: Readonly<Record<string, readonly Entry[]>> = {
    demo_root: DEMO_ROOT, demo_child: DEMO_CHILD, demo_grandchild: DEMO_GRANDCHILD,
}

const paged = (entries: readonly Entry[]): BackPage => ({
    entries, through: entries.at(-1)?.ordinal ?? 0, more: false, total: entries.length,
    ...(entries.length === 0 ? {} : { oldest: entries[0]?.ordinal as number }),
})

/** What the demo hands `explore` in place of a socket: every conversation is already whole, so
 *  `before` and `after` have nothing to add and `follow` never resolves anything further. */
export function demoReads(): Reads {
    return {
        tail: async (conversation) => paged(LOGS[conversation] ?? []),
        before: async () => paged([]),
        after: async () => [],
        follow: async () => undefined,
    }
}
