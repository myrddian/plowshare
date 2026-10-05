import { decodeRequest } from 'plowshare-client-ts/operations/schema';
import type { Replies } from 'plowshare-client-ts/operations/replies';
import { isList } from 'plowshare-client-ts/binding/values';
import type {
  Operation,
  Payloads,
} from 'plowshare-client-ts/operations/direct';
import type { Claim } from 'plowshare-client-ts/binding/auth';
import tools from './tools.json' with { type: 'json' };
import {
  array,
  integer,
  line,
  optional,
  paths,
  project,
  record,
  required,
  separator,
  text,
} from './values.js';
import type { Data } from './values.js';
import * as memory from './memory.js';
import * as jobs from './jobs.js';
import * as projects from './projects.js';
import * as conversations from './conversations.js';
import * as documents from './documents.js';
import * as web from './web.js';

/** Explicit 35-tool MCP policy. Durable WS payloads use the neutral core's types. */
export interface Backend {
  invoke<T extends Operation>(
    type: T,
    payload: Payloads[T],
  ): Promise<Replies[T]>;
  runSession(): string | null;
  root(
    project: string,
    path: string,
  ): Promise<{ previous?: Claim; current: Claim }>;
}
export { tools };
export const names = new Set(tools.map((tool) => tool.name));
function offset(args: Data): number {
  let value: number;
  try {
    value = integer(args, 'offset') ?? 0;
  } catch {
    throw new Error(
      `'offset' should be a whole number, the position to start at, with an invalid value. Leave it out to start at the beginning.`,
    );
  }
  if (value < 0)
    throw new Error(
      `'offset' counts from 0 and there is nothing before that; this asked for ${value}. Leave it out to start at the beginning.`,
    );
  return value;
}
const limit = (args: Data): { limit?: number } => {
  const value = integer(args, 'limit');
  return value === undefined ? {} : { limit: value };
};
export async function call(
  backend: Backend,
  name: string,
  args: Data,
): Promise<string> {
  const ask = backend.invoke.bind(backend);
  switch (name) {
    case 'information': {
      const operation = args['operation'];
      const model = [
        'upload',
        'acquire',
        'list',
        'facets',
        'status',
        'read',
        'search',
        'rank',
        'ask',
        'evidence.record',
        'evidence.read',
        'record.report',
        'events',
        'inventory',
        'acquisitions',
      ];
      if (typeof operation !== 'string' || !model.includes(operation))
        throw new Error('unsupported model information operation');
      const payload = args['payload'];
      if (!payload || typeof payload !== 'object' || isList(payload))
        throw new Error('payload must be an object');
      const checked = decodeRequest(`information.${operation}`, payload);
      const value = await ask(checked.type, checked.payload);
      return value == null
        ? ''
        : typeof value === 'string'
          ? value
          : JSON.stringify(value);
    }
    case 'memory_index': {
      const home = project(args, 'memories that hold everywhere');
      return memory.index(await ask('memory.index', { project: home }), home);
    }
    case 'memory_read': {
      const raw = args['ids'];
      if (raw === undefined)
        throw new Error("memory_read needs at least one id in 'ids'");
      if (
        !isList(raw) ||
        raw.some((value) => typeof value !== 'string' || value.trim() === '')
      )
        throw new Error('memory_read needs a list of nonblank text ids');
      const ids = raw.map((value) => text(value));
      if (!ids.length)
        throw new Error("memory_read needs at least one id in 'ids'");
      const out: string[] = [];
      for (const id of ids)
        out.push(memory.memory(await ask('memory.read', { memory: id })));
      return out.join(separator);
    }
    case 'memory_recall': {
      const question = required(args, 'question'),
        home = project(args, 'memories that hold everywhere');
      return memory.recall(
        await ask('memory.recall', { question, project: home, ...limit(args) }),
        question,
        home,
      );
    }
    case 'memory_write': {
      const proposal = {
          summary: required(args, 'summary'),
          scope: required(args, 'scope'),
          body: required(args, 'body'),
          formedBy: optional(args, 'formed_by') ?? 'unknown',
          formedWhere: optional(args, 'formed_where') ?? '',
        },
        home = project(args, 'memories that hold everywhere');
      return memory.write(
        await ask('memory.write', { project: home, proposal }),
        home,
      );
    }
    case 'memory_navigate':
    case 'memory_digest': {
      const home = args['project'] == null ? null : text(args['project']);
      if (name === 'memory_digest')
        return `Started digest job ${line(record(await ask('memory.digest', { project: home }))['id'])}. Poll with agent_poll.`;
      const question = args['question'] == null ? null : text(args['question']);
      if (question == null || question.trim() === '')
        throw new Error('question is required');
      const found = record(
        await ask('memory.navigate', { project: home, question }),
      );
      return `Reached ${found['level']}; complete=${found['complete']}; ids=[${array(found['ids']).map(line).join(', ')}]\n> ${text(found['text']).replaceAll('\n', '\n> ')}`;
    }
    case 'agent_run': {
      const agent = required(args, 'agent'),
        task = required(args, 'task'),
        home = project(args);
      return jobs.started(
        await ask('agent.run', {
          agent,
          task,
          project: home,
          conversation: null,
          session: backend.runSession(),
        }),
        home,
      );
    }
    case 'agent_poll':
    case 'agent_result':
    case 'agent_cancel': {
      const id = required(args, 'job_id');
      return jobs.job(
        await ask(name === 'agent_cancel' ? 'job.cancel' : 'job.status', {
          job: id,
        }),
        name === 'agent_poll'
          ? 'poll'
          : name === 'agent_result'
            ? 'result'
            : 'cancel',
      );
    }
    case 'memory_curate': {
      const home = project(args);
      if (home === null)
        throw new Error(
          "'project' is required. There is no global curator pass: promotion is what puts a project's memory into the global archive, so a pass over global would have nowhere to promote to.",
        );
      const calls = integer(args, 'max_model_calls');
      return jobs.curated(
        await ask('agent.curate', {
          project: home,
          ...(calls === undefined ? {} : { maxModelCalls: calls }),
        }),
        home,
      );
    }
    case 'memory_proposals': {
      const home = project(args);
      return jobs.proposals(
        await ask('proposal.list', { project: home }),
        home,
      );
    }
    case 'memory_resolve': {
      const proposal = required(args, 'proposal_id'),
        decision = required(args, 'decision').trim().toLowerCase();
      if (decision !== 'accept' && decision !== 'reject')
        throw new Error(
          `'decision' must be "accept" or "reject", not "${line(decision)}". Accepting promotes the memory into the global archive; rejecting records that it belongs where it is, which is what stops it being proposed again.`,
        );
      const reason = optional(args, 'reason'),
        by = required(args, 'by'),
        accept = decision === 'accept';
      return jobs.resolved(
        await ask('proposal.resolve', {
          proposal,
          accept,
          by,
          ...(reason === null ? {} : { reason }),
        }),
        accept,
      );
    }
    case 'project_define':
    case 'project_workspace_set':
    case 'project_lend':
    case 'project_unlend': {
      const project = required(args, 'project'),
        verb = name.slice('project_'.length);
      const result =
        verb === 'define'
          ? await ask('project.define', {
              name: project,
              workspace: required(args, 'workspace'),
              exclusions: paths(args, 'exclusions'),
            })
          : verb === 'workspace_set'
            ? await ask('project.workspace', {
                project,
                workspace: required(args, 'workspace'),
              })
            : await ask(verb === 'lend' ? 'project.lend' : 'project.unlend', {
                project,
                roots: paths(args, 'roots', true),
              });
      return projects.projectView(result, verb);
    }
    case 'project_move': {
      const project = required(args, 'project'),
        to = required(args, 'to');
      await ask('project.move', { project, to });
      return projects.moved(project, to);
    }
    case 'project_forget': {
      const project = required(args, 'project');
      await ask('project.forget', { project });
      return projects.forgotten(project);
    }
    case 'conversation_list': {
      const home = project(args, 'global tier'),
        start = offset(args);
      return conversations.conversations(
        await ask('conversation.list', { project: home }),
        home,
        start,
      );
    }
    case 'conversation_search': {
      const q = required(args, 'q'),
        home = project(args, 'global tier'),
        start = offset(args);
      return conversations.searched(
        await ask('conversation.search', {
          q,
          project: home,
          offset: start,
          limit: 20,
        }),
        q,
        home,
        start,
      );
    }
    case 'conversation_chat':
    case 'conversation_trajectory': {
      const conversation = required(args, 'conversation'),
        start = offset(args);
      return conversations.page(
        await ask(
          name === 'conversation_chat'
            ? 'conversation.chat'
            : 'conversation.trajectory',
          { conversation, offset: start, limit: 20 },
        ),
        start,
        name,
        conversation,
      );
    }
    case 'conversation_context': {
      const conversation = required(args, 'conversation'),
        agent = optional(args, 'agent');
      return conversations.context(
        await ask('conversation.context', {
          conversation,
          ...(agent === null ? {} : { agent }),
        }),
        conversation,
        agent,
      );
    }
    case 'document_search': {
      const question = optional(args, 'question', true);
      if (question === null)
        throw new Error(
          "document_search needs a 'question': one sentence saying what you want to know. Nothing was searched, which is not the same as nothing being found.",
        );
      const page = limit(args);
      return documents.searched(
        await ask('document.search', { query: question, ...page }),
        page.limit,
      );
    }
    case 'document_citations': {
      const conversation = optional(args, 'conversation', true),
        document = optional(args, 'document', true);
      if (conversation !== null && document !== null)
        throw new Error(
          "document_citations answers one question at a time: 'conversation' is what one conversation cited and 'document' is what has cited one paper. Send one of them, or neither for the most recent citations in the corpus.",
        );
      return documents.citations(
        await ask('document.citations', {
          ...(conversation === null ? {} : { conversation }),
          ...(document === null ? {} : { document }),
          ...limit(args),
        }),
      );
    }
    case 'document_ask': {
      const document = required(args, 'document', true),
        question = required(args, 'question', true);
      return documents.asked(
        await ask('document.ask', { document, question }),
        document,
      );
    }
    case 'document_retrieve': {
      const question = required(args, 'question', true),
        document = optional(args, 'document', true),
        most = integer(args, 'limit', 'document') ?? 5;
      return documents.retrieved(
        await ask('document.retrieve', {
          query: question,
          ...(document === null ? {} : { document }),
          limit: most,
        }),
        question,
      );
    }
    case 'document_list': {
      const naming = optional(args, 'naming', true),
        most = integer(args, 'limit', 'document'),
        start = integer(args, 'offset', 'document');
      return documents.listed(
        await ask('document.list', {
          ...(naming === null ? {} : { q: naming }),
          ...(most === undefined ? {} : { limit: most }),
          ...(start === undefined ? {} : { offset: start }),
        }),
      );
    }
    case 'document_rank': {
      const question = required(args, 'question', true),
        most = integer(args, 'limit', 'document');
      return documents.ranked(
        await ask('document.rank', {
          query: question,
          ...(most === undefined ? {} : { limit: most }),
        }),
        question,
      );
    }
    case 'document_outline':
      return documents.outline(
        await ask('document.detail', {
          document: required(args, 'document', true),
        }),
      );
    case 'search':
      return web.searched(
        await ask('web.search', {
          query: required(args, 'query'),
          pageSize: integer(args, 'page_size') ?? 10,
          max: integer(args, 'max') ?? 30,
          page: integer(args, 'page') ?? 1,
        }),
      );
    case 'fetch':
      return web.fetched(
        await ask('web.fetch', {
          url: required(args, 'url'),
          offset: integer(args, 'offset') ?? 0,
        }),
      );
    case 'client_root_project_here': {
      const project = required(args, 'project'),
        path = required(args, 'path'),
        { previous, current } = await backend.root(project, path);
      let out = `This machine, '${line(current.machine)}', now serves the project '${line(current.project)}'. Its files are at ${line(current.root)}.\n\n`;
      if (previous !== undefined)
        out += `It stopped rooting '${line(previous.project)}' to do it: this client roots one project at a time, because a project is one place. Nothing on the server changed, and that project's memories are untouched — what ended is this machine answering for its files.\n\n`;
      return (
        out +
        `A run in '${line(current.project)}' now reads and writes there through this process, whichever client started it, as far as the running agent's own declared scopes allow. That one directory is the whole of what this machine lends: the leash is checked here, at the moment a file would be opened, and no path outside it is reachable however a run asks.\n\nCall this again to serve somewhere else. It reconnects rather than editing what was declared, because a presence is declared when the channel opens.`
      );
    }
    default:
      throw new Error('unknown tool: ' + name);
  }
}
