import { isPayloadCommand } from 'plowshare-client-ts/operations/commands';

export const HELP = `First-run setup: plowshare-cli --server <server-origin> setup
Usage: plowshare-cli [options] setup|login|logout
       plowshare-cli connection list|add|select|rename|remove ARGS...
       plowshare-cli [options] memory <verb> [JSON payload or text]
       plowshare-cli [options] search <text or JSON payload>
       plowshare-cli [options] job status|poll|result|wait|cancel <job-id>
       plowshare-cli [options] job watch <job-id>
       plowshare-cli [options] conversation follow <conversation-id>
       plowshare-cli --project NAME --root /absolute/path [--sync] client root
       plowshare-cli --project NAME --root /absolute/path sync on|off|status|conflicts
       plowshare-cli --project NAME --root /absolute/path sync hidden <paths...>
       plowshare-cli --project NAME --root /absolute/path sync resolve <path> mine|theirs|merge|done
       plowshare-cli [options] conversation|agent|document|information|web|project <verb> [JSON]

Memory verbs: index, read, recall, write, navigate, digest, curate, proposals,
              resolve, reconsider, invalidate, reembed
read takes an id; recall/navigate take question text. Other arguments are JSON.
Conversation: open, list, latest, lifecycle, turns, compactions, chat, trajectory,
              context, projection, resume, follow
Agent: list, define, run. Job: list, limits, plus status/cancel aliases above.
Document: ask, retrieve, list, detail/outline, chunk, rank, stance, citations, search
Information: all scoped source/evidence/report/lifecycle/migration operations.
     Supply scope:{kind:"personal"|"shared"} or {kind:"project",project:NAME}.
     Mutations retain a stable UUID requestId. --wait supports information ask.
Approval: list, answer, revoke. Orchestration: definitions, list, status, answer,
     start, receipt, wait, resume, cancel, caps, record. Inbox: list, read. Todos: read.
Schedule: list, define, read, pause, forget. Trigger: list, define, pause, forget.
Event: fire. Firing: list. Provider: list, deregister. Board: topup.
Buffer: purge. Retention: sweep. Union: status, conflicts (reads only).
Web: search, fetch. Project: create, list, define, lend, unlend, workspace, move, forget,
     access, member-add, member-role, member-remove (paths refer to the server's disk).
A server-side project scopes work in the Plowshare agent framework: agents,
skills, conversations, memory, information and any server workspace files.
SDK integrations submit agentic or information tasks within that scope.
project create provisions MANAGED server files, or registers DISJOINT files without
client sync. writePaths names relative writable areas; DISJOINT defaults to [].
admin accounts lists server accounts; admin account create/update/reset manages accounts.
admin sessions lists active logins; admin session revoke signs an account out everywhere.
admin service accounts lists machine identities; admin service account create/update manages them.
admin service tokens lists token metadata; admin service token create/rotate/revoke manages scoped credentials.
Token create needs handle, name and scopes [{project,role}]; expiresInDays defaults to 30 (1–365).
admin audit lists durable operator history. These commands require a server administrator.
project define registers an existing server workspace (administrator required).
project access inspects your role and grants; project Managers may add/remove members
and assign VIEWER, CONTRIBUTOR or MANAGER using project member-role.
Personal remains private to its account; roles never widen writable workspace areas.
--project selects that framework project for work; --root explicitly serves local files.
Single-id reads and document queries accept text; other payloads are JSON.
web search requires query, pageSize, max and page; fetch accepts a URL.

Options (before or after the command; -- ends option parsing):
  --new-conversation     open a fresh conversation for agent run (default without an id)
  --standalone           agent run without a reusable conversation; supports images
  --validate             validate input offline; show operation and effective scope
  --json                 JSON result on stdout (observers/root/sync emit NDJSON)
  --connection NAME     saved connection (or PLOWSHARE_CONNECTION)
  --account HANDLE      explicit account with --server
  --server ORIGIN        server HTTP(S) origin (or PLOWSHARE_URL; required online)
  --url ORIGIN           alias for --server
  --project NAME         server framework project (PLOWSHARE_PROJECT otherwise)
  --global               override the environment tier with global
  --payload -            read the command's JSON payload from stdin
  --wait                 wait for an accepted job or job status/cancel
  --watch                wait with live job progress (JSON is newline-delimited)
  --root /ABSOLUTE/PATH   explicitly serve fenced local files; commands default off
  --sync                 reconnect an enabled union while rooted; never enables it
  --poll-ms N            status polling interval (default 1000)
  --timeout-ms N         whole invocation deadline (default 30000)
  --help                 show command or group help without signing in
  --version              show the installed CLI version without signing in

Authentication: run login once; clients share saved tokens per server.
For service automation, PLOWSHARE_TOKEN supplies an ephemeral pss_ bearer; no login or refresh.
Setup and login prompt. PLOWSHARE_HANDLE/PLOWSHARE_PASSWORD remain ephemeral overrides.
JSON project:null selects global; omitted budgets and paging stay server-owned.
Exit: 0 completed, 1 refused/failed, 2 usage/auth, 3 accepted/running/cancelling,
      4 incomplete/cancelled/awaiting, 5 unknown/transport/deadline/protocol.
Disconnect, timeout or Ctrl-C never replays or cancels a server mutation.`;

export interface Options {
  readonly json: boolean;
  readonly help: boolean;
  readonly version?: boolean;
  readonly validate: boolean;
  readonly newConversation: boolean;
  readonly standalone: boolean;
  readonly base: string | undefined;
  readonly connectionName?: string;
  readonly account?: string;
  readonly commandParts?: readonly string[];
  readonly project?: string;
  readonly command: string;
  readonly inputPayload: boolean;
  readonly wait: boolean;
  readonly watch: boolean;
  readonly pollMs: number;
  readonly timeoutMs: number;
  readonly root?: string;
  readonly sync: boolean;
}
export class Usage extends Error {}

export function options(
  args: readonly string[],
  env: Readonly<Record<string, string | undefined>>,
): Options {
  let json = false,
    help = false,
    validate = false,
    wait = false,
    watch = false,
    inputPayload = false;
  let newConversation = false,
    standalone = false,
    version = false;
  let root: string | undefined,
    sync = false;
  let connectionName: string | undefined, account: string | undefined;
  let url = env['PLOWSHARE_URL'];
  let project = env['PLOWSHARE_PROJECT'] || undefined;
  let pollMs = 1000,
    timeoutMs = 30000,
    cursor = 0,
    scopeChosen = false;
  const value = (flag: string): string => {
    const next = args[++cursor];
    if (next === undefined || next === '' || next.startsWith('--'))
      throw new Usage(`${flag} needs a value`);
    return next;
  };
  const duration = (flag: string): number => {
    const raw = value(flag),
      number = Number(raw);
    if (
      !/^\d+$/.test(raw) ||
      !Number.isSafeInteger(number) ||
      number < 1 ||
      number > 2147483647
    ) {
      throw new Usage(`${flag} needs an integer between 1 and 2147483647`);
    }
    return number;
  };
  const parts: string[] = [];
  for (; cursor < args.length; cursor++) {
    const flag = args[cursor]!;
    if (flag === '--') {
      parts.push(...args.slice(cursor + 1));
      break;
    }
    if (!flag.startsWith('-')) {
      parts.push(flag);
      continue;
    }
    switch (flag) {
      case '--new-conversation':
        newConversation = true;
        break;
      case '--standalone':
        standalone = true;
        break;
      case '--validate':
        validate = true;
        break;
      case '--json':
        json = true;
        break;
      case '--help':
      case '-h':
        help = true;
        break;
      case '--version':
        version = true;
        break;
      case '--wait':
        wait = true;
        break;
      case '--watch':
        watch = true;
        wait = true;
        break;
      case '--root':
        root = value(flag);
        break;
      case '--sync':
        sync = true;
        break;
      case '--connection':
        connectionName = value(flag);
        break;
      case '--account':
        account = value(flag);
        break;
      case '--server':
      case '--url':
        url = value(flag);
        break;
      case '--project':
        if (scopeChosen) throw new Usage('choose --project or --global once');
        project = value(flag);
        scopeChosen = true;
        break;
      case '--global':
        if (scopeChosen) throw new Usage('choose --project or --global once');
        project = undefined;
        scopeChosen = true;
        break;
      case '--payload':
        if (value(flag) !== '-')
          throw new Usage('--payload accepts - for stdin');
        inputPayload = true;
        break;
      case '--poll-ms':
        pollMs = duration(flag);
        break;
      case '--timeout-ms':
        timeoutMs = duration(flag);
        break;
      default:
        throw new Usage('unknown option; see --help');
    }
  }
  if (help || version)
    return {
      json,
      help,
      version,
      validate,
      newConversation,
      standalone,
      base: undefined,
      command: parts.join(' ').trim(),
      inputPayload: false,
      wait: false,
      watch: false,
      pollMs,
      timeoutMs,
      sync: false,
    };
  if (newConversation && standalone)
    throw new Usage('choose --new-conversation or --standalone');
  if (project !== undefined && project.trim() === '')
    throw new Usage('project must be a nonblank name; use --global explicitly');
  let base: string | undefined;
  if (url !== undefined) {
    try {
      const parsed = new URL(url);
      if (
        !['http:', 'https:'].includes(parsed.protocol) ||
        parsed.username ||
        parsed.password ||
        parsed.pathname !== '/' ||
        parsed.search ||
        parsed.hash
      )
        throw new Error();
      base = parsed.origin;
    } catch {
      throw new Usage(
        '--server/--url must be an HTTP(S) origin without credentials, path, query or fragment',
      );
    }
  }
  if (parts[0] === 'job' && parts[1] === 'watch') {
    watch = true;
    wait = true;
    parts[1] = 'status';
  }
  if (
    parts[0] === 'job' &&
    ['wait', 'poll', 'result'].includes(parts[1] ?? '')
  ) {
    wait ||= parts[1] === 'wait';
    parts[1] = 'status';
  }
  if (parts[0] === 'orchestration' && parts[1] === 'wait') {
    wait = true;
    parts[1] = 'status';
  }
  const command = parts.join(' ');
  if (command === '') throw new Usage('a command is required; see --help');
  if (inputPayload && !isPayloadCommand(command)) {
    throw new Usage(
      '--payload - replaces the entire payload; do not also supply an argument',
    );
  }
  if (sync && root === undefined) throw new Usage('--sync requires --root');
  if (
    (command === 'client root' || command.startsWith('sync ')) &&
    root === undefined
  )
    throw new Usage('client root and sync require --root and a named project');
  return {
    json,
    help,
    validate,
    newConversation,
    standalone,
    base,
    commandParts: parts,
    ...(connectionName === undefined ? {} : { connectionName }),
    ...(account === undefined ? {} : { account }),
    ...(project === undefined ? {} : { project }),
    ...(root === undefined ? {} : { root }),
    sync,
    command,
    inputPayload,
    wait,
    watch,
    pollMs,
    timeoutMs,
  };
}
