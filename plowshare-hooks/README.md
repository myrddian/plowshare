# @plowshare/hooks

For a complete authoring walkthrough, placement map, all callback events and a
named-event trigger example, read the [manual hook guide](../docs/manual/12-hooks.md).
Run and in-turn hooks compose harness → Personal → project → pinned local.
Standalone log and information-service callbacks use their project/local chains;
the manual distinguishes those seams. The current origin list also includes
`board` for participant work.

Types for Plowshare hooks. A hook is a single TypeScript file that exports a `Hook` instance as its default export. It can act at fourteen stages. Five are inside a run: `prompt.pre`, `prompt.post`, `tool.pre`, `tool.post` and `step.post`. Nine are on the log, which is any run's record: `log.open`, `log.close`, `stage.pre`, `stage.post`, `approval.pre`, `approval.post`, `fold.post`, `delivery.pre` and `delivery.post`. The server fires all fourteen. `stage.pre` and `stage.post` fire when an orchestration's conductor moves a stage, after the harness's own checks; `approval.pre` before a person is asked to allow a command (it can refuse the command or add to the question, never approve it); `approval.post` after a person answered or revoked; `fold.post` once a long log's folder has written its summary and before the fold is saved: it sees that summary, and its `{ keep }` follows it word for word. A log stage may name `origins: [...]` (`turn`, `delegation`, `submission`, `event`, `curator`, `memory`, `orchestration`) and fires for every origin when it names none. It never takes `tools:`.

Hooks are deployed to the server at `projects/<id>/hooks/` on the Plowshare server, as `.ts` or `.js` files. A project's hooks are loaded lazily, the first time one of its stages fires, and reloaded on the next fire after any file in the directory changes — no restart. Files run in filename order. The hook's name (logged and recorded) is its required declared `name`, which must be unique within the directory; two files declaring the same name is a load error, not an override. A person can also keep hooks beside their own code; see "Your own hooks" below.

Ordinary JavaScript is fine in a hook — functions, constants, classes, module-level helpers. What is refused is TypeScript that is not erasable (syntax that needs code generated rather than types removed: `enum`, a namespace with runtime code, constructor parameter properties, `export =`, `import x = require(...)`), and value imports or re-exports, since hooks have no IO to resolve another module with. Only `import type { X }` or `import { type X }` is possible. Tool stages, `stage.pre`, `stage.post` and `approval.pre` fail closed (a hook that throws, or a hook file that did not load, refuses); prompt stages and every other log stage fail open (they contribute nothing). Every decision is recorded. Module-level state acts as a cache and is never persisted as a record. The server has no host access: no Java interop, filesystem or network, threads, processes, environment, native or other-language access.

Deployment note: the server strips TypeScript with swc4j, whose native libraries exist for macOS (arm64, x86_64) and glibc Linux (x86_64, arm64) only. On any other platform (musl Linux such as Alpine, Windows) a `.ts` hook is a file that did not load, so tool calls in its project are refused.

Example:

```ts
import type { Hook } from '@plowshare/hooks'

export default {
    name: 'no-secrets-in-writes',
    stages: {
        'tool.pre': {
            tools: ['file_edit'],
            handle(call) {
                const written = String(call.args.content ?? call.args.new ?? '')
                if (/BEGIN (RSA|OPENSSH) PRIVATE KEY/.test(written)) {
                    return { deny: 'this looks like a private key; it was not written' }
                }
                return { allow: true }
            },
        },
    },
} satisfies Hook
```

## Allowing `run`

`run` is gated by the project's `environment.yml` before any hook is asked. Where a side's mode is
`gated`, a command starts only if a `tool.pre` hook for `run` returns `{ allow: true }` — returning
nothing is not enough. `context.environment` says which side the command would run on:

```ts
import type { Hook } from '@plowshare/hooks'

const allowed = new Set(['./gradlew', 'npm', 'git'])

export default {
    name: 'run-allowlist',
    stages: {
        'tool.pre': {
            tools: ['run'],
            handle(call) {
                const program = String((call.args.command as unknown[] | undefined)?.[0] ?? '')
                return allowed.has(program)
                    ? { allow: true }
                    : { deny: `${program} is not on this project's allowlist` }
            },
        },
    },
} satisfies Hook
```

## Log stages

`log.open` adds text to a log's fixed opening. The text is sent after the agent's prompt in every request of that log and never changes afterwards. `log.close` and `delivery.post` notify the log owner's inbox. `delivery.pre` appends a note to the delivered text. `log.open`, `log.close`, `delivery.*`, `approval.post` and `fold.post` fail open: a broken hook adds nothing and is recorded; `stage.pre`, `stage.post` and `approval.pre` fail closed, like tool stages: a broken hook denies.

```ts
import type { Hook } from '@plowshare/hooks'

export default {
    name: 'house-style',
    stages: {
        'log.open': {
            origins: ['submission', 'event'],
            handle(e) {
                return { add: `You are running unattended in ${e.context.project ?? 'the global tier'}.` }
            },
        },
        'log.close': {
            origins: ['submission', 'event'],
            handle(e) {
                return e.ending === 'answered' ? undefined : { notify: `${e.context.log} ended ${e.ending}` }
            },
        },
    },
} satisfies Hook
```

## Holding a stage to a rule

`stage.post` runs after the harness's own checks passed, so a hook sees a stage that is otherwise
ready to be done:

```ts
import type { Hook } from '@plowshare/hooks'

export default {
    name: 'summaries-name-files',
    stages: {
        'stage.post': {
            origins: ['orchestration'],
            handle(e) {
                return /\.\w+\b/.test(e.summary)
                    ? { note: `${e.stage.id} is done; its summary names what changed` }
                    : { deny: 'say which files changed in the summary' }
            },
        },
    },
} satisfies Hook
```

## Keeping a marker through a fold

A long log is folded: a folder summarises its older turns, and the summary stands in for them.
`fold.post` fires once the folder has written that summary and before the fold is saved, and
`summary` is the folder's own text. `{ keep }` is appended to it word for word, so a marker the
folder paraphrased or lost survives. Read `summary` first and keep the marker only when it is
missing; otherwise it piles up, once more on every fold. What all hooks keep together is capped
at 512 tokens, and a keep that would pass the cap is dropped whole and recorded. `{ notify }`
reaches the log owner's inbox only once the fold is saved, and never for a fold that was not.
All of a fold's `fold.post` hooks share one time limit, so a slow hook leaves less for the ones
after it.

```ts
import type { Hook } from '@plowshare/hooks'

export default {
    name: 'skill-marker',
    stages: {
        'fold.post': {
            handle(e) {
                return e.summary.includes('skill deploy@3')
                    ? undefined
                    : { keep: 'skill deploy@3 was loaded' }
            },
        },
    },
} satisfies Hook
```

## Your own hooks

A TUI session that has rooted a project also serves that project's `.plowshare/hooks/` to the
server. The same `.ts` and `.js` files, with the same `Hook` contract, run on the server in the
same sandbox as project hooks, after the project's own hooks, at every stage. Their records say
`tier: 'local'`. The TUI runs none of them.

- **Read when a conversation opens, kept for its life.** The server reads the directory once, when
  a conversation, a submission or an orchestration is opened from your session, and keeps that
  copy until the log ends, even if you disconnect. An edit takes effect in the next conversation,
  not the one you are in. A delegated agent and a nested orchestration keep the copy their parent
  opened with.
- **Module state stays yours.** Your conversations that opened with the same files share one
  loaded copy, so module-level state carries between them. Another account with byte-identical
  files, such as a teammate with the same committed `.plowshare/hooks/`, gets a copy of its own.
- **Bounded.** At most 32 hook files, 256 KiB each and 1 MiB together; only `*.ts` and `*.js`
  directly under `.plowshare/hooks/`, not dotfiles. A directory past any bound is not read at all,
  and the log records why.
- **A broken file** fails like a broken project file: tool stages, `stage.*` and `approval.pre`
  refuse for the rest of that conversation, and the other stages go on without it.
- **An `allow` counts only for a command on the local side.** `{ allow: true }` from your hook
  approves a `run` only where `context.environment.side` is `'local'`, and only on a machine
  signed in as the log's owner — a session another account holds does not count. Elsewhere it is no allow at
  all, and the command meets its environment's own mode instead: `gated` refuses it, `ask` asks a
  person, `open` runs it. An `allow` also holds only for the exact arguments it saw — a later
  rewrite, yours or another hook's, needs its own allow. `deny`, `ask`, `rewrite`, `note`, `redact`,
  `add`, `keep` and `notify` work as they do for a project hook.

```ts
import type { Hook } from '@plowshare/hooks'

export default {
    name: 'not-while-i-am-away',
    stages: {
        'tool.pre': {
            tools: ['run'],
            handle(call) {
                if (call.context.environment?.side !== 'local') {
                    return undefined
                }
                const program = String((call.args.command as unknown[] | undefined)?.[0] ?? '')
                return program === 'rm' ? { deny: 'no deletions on my machine' } : { allow: true }
            },
        },
    },
} satisfies Hook
```

## Information and document stages

Information intake, acquisition, processing, evidence/report recording and owner
lifecycle actions also use `stage.pre` and `stage.post`. These are service-owned
seams, including internal tool calls and durable background work. Their logs use
`submission`; a hook filtered to `orchestration` alone will not see them.

`event.context.document` supplies `operation`, nullable `resource`/`revision`,
`generation`, `stage`, `attempt` and optional `sourceUri`. Prepared intake has no
allocated resource yet; acquisition may identify its ticket before it has a source
revision. Existing orchestration contexts keep their original meanings. The chain
is harness → project → the owner's pinned local hooks; a caller cannot borrow
another account's session or let a hook override scope/readiness/generation checks.

Pre denial prevents work. Post denial prevents completion/publication while valid
private checkpoints and paid model responses survive. Explicit repair rechecks the
gates. Positive sharing/finalisation gates a prepared transition before applying it.
Withdrawal, exclusion, unshare, unlink and deletion commit their safety reduction
before discretionary hooks, so a broken hook cannot reopen access. Completed
idempotent receipt replay does not fire those transition hooks again.

See [the information guide](../docs/information-system.md) for stages, operations,
retention and migration.
