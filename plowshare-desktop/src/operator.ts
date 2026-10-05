import { errorMessage } from 'plowshare-client-ts/binding/values';
import { decodeOperatorInput } from './operator-input.ts';
import { displayText } from 'plowshare-client-ts/binding/values';
import { isList } from 'plowshare-client-ts/binding/values';
import {
  decodeReply,
  decodePayload,
} from 'plowshare-client-ts/operations/schema';
import type { Replies } from 'plowshare-client-ts/operations/replies';
import type { MessageInstance } from 'plowshare-client-ts/operations/messaging';
import type { Operation } from 'plowshare-client-ts/operations/direct';
import type {
  OperatorData,
  OperatorInput,
  OperatorSubject,
  OperatorReceipt,
} from './operator-shared.ts';
import { CAP_KEYS } from 'plowshare-client-ts/binding/environment';
import { randomUUID } from 'node:crypto';
import {
  request,
  resultOf,
  type Request as WsRequest,
} from 'plowshare-client-ts/operations/direct';
import type { CheckedAnswer as Outcome } from 'plowshare-client-ts/operations/response';
import type { DesktopState } from './shared.ts';
import type { CapsFile, CapKey } from './caps.ts';
import { capPreview } from './caps.ts';
import { OPERATOR_KINDS, type OperatorKind } from './operator-shared.ts';
export { OPERATOR_KINDS, type OperatorKind } from './operator-shared.ts';
const string = (v: unknown, name: string, max = 8000) => {
  if (typeof v !== 'string' || !v.trim() || v.length > max)
    throw new Error(`Enter ${name} (at most ${max} characters).`);
  return v.trim();
};
const number = (v: unknown, name: string) => {
  if (!Number.isSafeInteger(v) || Number(v) < 1 || Number(v) > 1_000_000)
    throw new Error(`Enter a positive ${name}, at most 1000000.`);
  return Number(v);
};
export class OperatorClient {
  private state: () => DesktopState;
  private send: (ask: WsRequest) => Promise<Outcome>;
  private changed: () => void;
  private submit: (
    ask: WsRequest,
    conversation?: string,
    agent?: string,
  ) => Promise<Outcome>;
  private readCaps: (project: string) => Promise<CapsFile>;
  private saveCaps: (
    project: string,
    file: CapsFile,
    key: CapKey,
    value: number,
  ) => Promise<void>;
  private epoch = 0;
  private messageRevision = 0;
  private busy = false;
  private pending:
    | {
        ask?: WsRequest;
        file?: CapsFile;
        key?: CapKey;
        value?: number;
        selected?: OperatorSubject | null;
        selection: OperatorInput;
        generation: number;
      }
    | undefined;
  constructor(
    state: () => DesktopState,
    send: (ask: WsRequest) => Promise<Outcome>,
    changed: () => void,
    submit: (
      ask: WsRequest,
      conversation?: string,
      agent?: string,
    ) => Promise<Outcome>,
    readCaps: (project: string) => Promise<CapsFile>,
    saveCaps: (
      project: string,
      file: CapsFile,
      key: CapKey,
      value: number,
    ) => Promise<void>,
  ) {
    this.state = state;
    this.send = send;
    this.changed = changed;
    this.submit = submit;
    this.readCaps = readCaps;
    this.saveCaps = saveCaps;
  }
  reset() {
    this.epoch++;
    this.pending = undefined;
    if (this.state().operator) delete this.state().operator?.preview;
  }
  private async checked<K extends Operation>(
    ask: WsRequest<K>,
  ): Promise<Replies[K]> {
    const generation = this.epoch,
      identity = `${this.state().base}|${this.state().handle}`;
    const answer = await this.send(ask as WsRequest);
    if (
      generation !== this.epoch ||
      identity !== `${this.state().base}|${this.state().handle}`
    )
      throw new Error('The account connection changed. Review again.');
    const result = resultOf(ask as WsRequest, answer);
    if (!['completed', 'accepted', 'incomplete'].includes(result.kind))
      throw new Error(
        answer.said ||
          `Unreadable or refused ${ask.type} reply. Completion is unknown.`,
      );
    return decodeReply(ask.type, answer.payload);
  }
  private async read(
    kind: OperatorKind,
    project?: string,
  ): Promise<OperatorData> {
    const tier = project ? { project } : {};
    if (kind.startsWith('message-')) {
      if (!project) throw new Error('Choose a project for message instances.');
      const instances: MessageInstance[] = [];
      for (let offset = 0; ; offset += 200) {
        const page = await this.checked(
          request('message.instances', { project, offset, limit: 200 }),
        );
        instances.push(...page.instances);
        if (!page.more) break;
      }
      const agents =
        kind === 'message-open'
          ? await this.checked(request('agent.list', { project }))
          : [];
      return { instances, agents };
    }
    if (kind.startsWith('memory-') || kind === 'agent-curate')
      return { memories: await this.checked(request('memory.index', tier)) };
    if (kind.startsWith('conversation-')) {
      const active = await this.checked(request('conversation.list', tier)),
        archived = await this.checked(
          request('conversation.list', {
            ...tier,
            lifecycle: 'archived' as const,
          }),
        );
      return {
        conversations: [
          ...active.map((row) => ({ ...row, lifecycle: 'active' as const })),
          ...archived.map((row) => ({
            ...row,
            lifecycle: 'archived' as const,
          })),
        ],
      };
    }
    if (kind === 'job-limits')
      return {
        jobs: (await this.checked(request('job.list', {}))).filter(
          (job) =>
            job.state === 'RUNNING' &&
            job.limits != null &&
            typeof job.conversation === 'string' &&
            job.conversation.length > 0,
        ),
      };
    if (kind === 'approval-grant')
      return this.checked(request('approval.list', { mine: true }));
    if (kind === 'approval-revoke') {
      if (!project)
        throw new Error('Choose a project to inspect standing grants.');
      return this.checked(request('approval.list', { project }));
    }
    if (kind === 'board-topup') {
      const data = await this.checked(
        request('board.topics', { ...tier, limit: 100, offset: 0 }),
      );
      return {
        topics: data.topics.filter(
          (row) => row.topic.state === 'open' && row.topic.parent === null,
        ),
      };
    }
    if (!project) throw new Error('Choose a project for cap settings.');
    return {
      caps: await this.checked(request('orchestration.caps', { project })),
      file: await this.readCaps(project),
    };
  }
  private selected(
    kind: OperatorKind,
    data: OperatorData,
    input: OperatorInput,
  ): OperatorSubject | null | undefined {
    if (kind === 'message-deliveries')
      return data.deliveries?.find((row) => row.message === input.id);
    if (kind === 'message-open')
      return data.agents?.find((row) => row.name === input.agent);
    if (kind.startsWith('message-'))
      return data.instances?.find((row) => row.id === input.id);
    if (kind.startsWith('conversation-'))
      return data.conversations?.find((row) => row.id === input.id);
    if (kind === 'job-limits') {
      const job = data.jobs?.find((row) => row.id === input.id);
      return (
        job && {
          id: job.id,
          ...(job.agent === undefined ? {} : { agent: job.agent }),
          ...(job.conversation === undefined
            ? {}
            : { conversation: job.conversation }),
          state: job.state,
          ...(job.limits === undefined ? {} : { limits: job.limits }),
        }
      );
    }
    if (kind.startsWith('approval-'))
      return data.approvals?.find((row) => row.id === input.id);
    if (kind === 'board-topup')
      return data.topics?.find((row) => row.topic.id === input.id)?.topic;
    return null;
  }
  async prepare(kind: OperatorKind, project?: string) {
    if (this.busy) throw new Error('Wait for the current control operation.');
    if (!OPERATOR_KINDS.includes(kind) || !this.state().connected)
      throw new Error('Connect and choose a control.');
    if (project && !this.state().projects.some((row) => row.name === project))
      throw new Error('Choose an available project.');
    const generation = ++this.epoch;
    this.pending = undefined;
    const data = await this.read(kind, project);
    if (generation !== this.epoch)
      throw new Error('The selected control changed.');
    this.state().operator = {
      kind,
      ...(project === undefined ? {} : { project: project }),
      data,
      identity: JSON.stringify([
        this.state().base,
        this.state().handle,
        kind,
        project,
        data,
      ]),
    };
    this.changed();
  }
  async messages(identity: string, instance: string, offset = 0) {
    const view = this.state().operator;
    if (
      this.busy ||
      !view ||
      view.kind !== 'message-deliveries' ||
      view.identity !== identity
    )
      throw new Error('Read the message controls again.');
    if (!(view.data.instances ?? []).some((row) => row.id === instance))
      throw new Error('Choose an instance from the current listing.');
    if (!Number.isSafeInteger(offset) || offset < 0)
      throw new Error('Choose a valid message page.');
    const revision = ++this.messageRevision,
      generation = this.epoch;
    const page = await this.checked(
      request('message.deliveries', { instance, offset, limit: 200 }),
    );
    if (
      revision !== this.messageRevision ||
      generation !== this.epoch ||
      this.state().operator !== view
    )
      return;
    view.data = {
      ...view.data,
      deliveries: page.deliveries,
      messageInstance: instance,
      messageOffset: page.offset,
      messageMore: page.more,
    };
    this.pending = undefined;
    delete view.preview;
    delete view.error;
    this.changed();
  }
  preview(identity: string, input: OperatorInput) {
    input = decodeOperatorInput(input);
    const view = this.state().operator;
    if (
      !view ||
      view.identity !== identity ||
      this.busy ||
      !this.state().connected
    )
      throw new Error('Read the current control before reviewing a change.');
    this.messageRevision++;
    const tier = view.project ? { project: view.project } : {};
    let ask: WsRequest | undefined,
      file: CapsFile | undefined,
      key: CapKey | undefined,
      value: number | undefined;
    let summary = '';
    switch (view.kind) {
      case 'message-deliveries':
        ask = request('message.cancel', {
          message: string(input.id, 'message', 512),
        });
        summary =
          'Cancel handling this message and finish an expected reply if it has no final reply.';
        break;
      case 'message-open':
        if (!view.project) throw new Error('Choose a project.');
        ask = request('message.instance.open', {
          project: view.project,
          agent: string(input.agent, 'agent or bot', 512),
          makeDefault: input.makeDefault === 'true',
          requestId: randomUUID(),
        });
        summary =
          'Create a persistent instance with its own conversation and address.';
        break;
      case 'message-default':
        ask = request('message.instance.default', {
          instance: string(input.id, 'instance', 512),
        });
        summary =
          'Use this persistent instance for messages addressed to this agent or bot name in the project.';
        break;
      case 'message-stop':
        ask = request('message.instance.stop', {
          instance: string(input.id, 'instance', 512),
        });
        summary =
          'Stop this instance, cancel its pending message handling, and finish expected replies.';
        break;
      case 'message-archive':
        ask = request('message.instance.archive', {
          instance: string(input.id, 'instance', 512),
        });
        summary = 'Stop and archive this instance and its conversation.';
        break;
      case 'memory-write':
        ask = request('memory.write', {
          ...tier,
          proposal: {
            summary: string(input.summary, 'summary', 512),
            scope: string(input.scope, 'memory scope', 512),
            body: string(input.body, 'memory body', 100000),
            formedBy: this.state().handle,
            formedWhere: view.project ?? '',
          },
        });
        summary =
          'Submit this memory for server judgement. It may be merged, proposed or refused.';
        break;
      case 'memory-digest':
        ask = request('memory.digest', tier);
        summary = 'Start model-backed digest maintenance in this scope.';
        break;
      case 'agent-curate':
        if (!view.project) throw new Error('Choose a project for curation.');
        ask = request('agent.curate', {
          project: view.project,
          maxModelCalls: number(input.maxModelCalls, 'model-call limit'),
        });
        summary = 'Start model-backed agent curation for this project.';
        break;
      case 'conversation-lifecycle':
        if (!['active', 'archived'].includes(String(input.lifecycle)))
          throw new Error('Choose Active or Archived.');
        ask = request('conversation.lifecycle', {
          conversation: string(input.id, 'conversation', 512),
          lifecycle: String(input.lifecycle),
        });
        summary =
          'Change this conversation lifecycle. Archiving does not cancel a running job.';
        break;
      case 'conversation-resume':
        ask = request('conversation.resume', {
          conversation: string(input.id, 'conversation', 512),
          maxTurns: number(input.maxTurns, 'turn limit'),
          maxModelCalls: number(input.maxModelCalls, 'model-call limit'),
        });
        summary =
          'Resume this conversation as one new job with the reviewed limits.';
        break;
      case 'job-limits':
        ask = request('job.limits', {
          job: string(input.id, 'job', 512),
          maxTurns: number(input.maxTurns, 'turn limit'),
          maxModelCalls: number(input.maxModelCalls, 'model-call limit'),
        });
        summary =
          'Apply these limits to the existing job; this does not start another job.';
        break;
      case 'approval-grant': {
        const decision = String(input.decision);
        if (!['once', 'conversation', 'project', 'deny'].includes(decision))
          throw new Error('Choose an approval scope.');
        const prefix: unknown =
          decision === 'project'
            ? JSON.parse(string(input.prefix, 'command argument prefix', 8192))
            : undefined;
        if (
          prefix &&
          (!isList(prefix) ||
            !prefix.length ||
            !prefix.every((v) => typeof v === 'string' && v.length <= 4096))
        )
          throw new Error(
            'The command prefix must be a nonempty list of arguments.',
          );
        ask = request('approval.answer', {
          id: string(input.id, 'approval', 512),
          decision: decision as 'once' | 'conversation' | 'project' | 'deny',
          ...(isList(prefix) &&
          prefix.every((v): v is string => typeof v === 'string')
            ? { prefix }
            : {}),
        });
        summary =
          'Record this command decision. Allowing may continue the waiting job; project grants cover the reviewed argument prefix.';
        break;
      }
      case 'approval-revoke':
        ask = request('approval.revoke', {
          id: string(input.id, 'standing approval', 512),
        });
        summary = 'Revoke the displayed standing command grant.';
        break;
      case 'board-topup':
        ask = request('board.topup', {
          topic: string(input.id, 'topic', 512),
          maxModelCalls: number(input.maxModelCalls, 'model-call allowance'),
        });
        summary = 'Raise the topic pot to this new total model-call allowance.';
        break;
      case 'caps':
        file = view.data.file;
        if (
          !file ||
          !CAP_KEYS.some((k) => k === input.key) ||
          input.key === undefined
        )
          throw new Error('Read the cap configuration and choose a valid key.');
        key = input.key;
        value = input.value;
        if (value === undefined) throw new Error('Enter a cap value.');
        summary = capPreview(file, key, value);
        break;
    }
    if (!ask && (key === undefined || value === undefined))
      throw new Error('Incomplete control operation.');
    if (ask) decodePayload(ask.type, ask.payload);
    const payload = ask ? ask.payload : { key: key!, value: value! };
    const selected = this.selected(view.kind, view.data, input);
    if (
      view.kind === 'board-topup' &&
      (input.maxModelCalls ?? 0) <= (selected?.potTotal ?? 0)
    )
      throw new Error('Enter a new total above the current topic pot.');
    if (selected === undefined)
      throw new Error('Choose an item from the current listing.');
    if (
      view.kind === 'message-deliveries' &&
      !['queued', 'running', 'awaiting'].includes(selected?.state ?? '')
    )
      throw new Error('Choose a pending message to cancel.');
    this.pending = {
      ...(ask === undefined ? {} : { ask: ask }),
      ...(file === undefined ? {} : { file: file }),
      ...(key === undefined ? {} : { key: key }),
      ...(value === undefined ? {} : { value: value }),
      selected,
      selection: { ...input },
      generation: this.epoch,
    };
    view.preview = {
      identity: JSON.stringify([identity, payload, summary]),
      summary,
      payload,
      ...(selected ? { subject: selected } : {}),
    };
    delete view.error;
    this.changed();
  }
  async apply(identity: string) {
    const view = this.state().operator,
      pending = this.pending;
    if (
      this.busy ||
      !view?.preview ||
      view.preview.identity !== identity ||
      !pending ||
      pending.generation !== this.epoch
    )
      throw new Error('Review this change again before applying it.');
    this.busy = true;
    view.busy = true;
    this.changed();
    let sent = false,
      capsSaved = false;
    try {
      const fresh =
        view.kind === 'message-deliveries'
          ? {
              ...view.data,
              deliveries: [
                await this.checked(
                  request('message.delivery', {
                    message: pending.selection.id ?? '',
                  }),
                ),
              ],
            }
          : await this.read(view.kind, view.project);
      if (pending.generation !== this.epoch || !this.state().connected)
        throw new Error('The connection changed before submission.');
      if (
        JSON.stringify(this.selected(view.kind, fresh, pending.selection)) !==
        JSON.stringify(pending.selected)
      )
        throw new Error(
          'The displayed item changed. Read it and review again.',
        );
      sent = true;
      if (pending.file) {
        await this.saveCaps(
          view.project!,
          pending.file,
          pending.key!,
          pending.value!,
        );
        capsSaved = true;
        view.notice =
          'Configuration saved locally; server cap confirmation is pending.';
        this.changed();
        const caps = await this.checked(
          request('orchestration.caps', { project: view.project! }),
        );
        view.receipt = {
          code: 'OK',
          ...(caps.said === null ? {} : { said: caps.said }),
        };
        view.notice = caps.said
          ? `Configuration saved locally; server reported: ${caps.said}`
          : 'Configuration saved; the server reloaded project caps.';
      } else {
        const selected = pending.selected;
        const answer = await this.submit(
          pending.ask!,
          String(
            selected?.conversation ??
              (pending.ask!.type === 'conversation.resume'
                ? (pending.selection.id ?? '')
                : ''),
          ),
          displayText(selected?.agent ?? pending.ask!.type),
        );
        const payload = decodeReply(pending.ask!.type, answer.payload);
        const receipt: OperatorReceipt = {
          code: answer.code,
          ...(answer.said === undefined ? {} : { said: answer.said }),
        };
        if (payload && typeof payload === 'object') {
          if ('id' in payload && typeof payload.id === 'string')
            receipt.id = payload.id;
          if ('job' in payload && typeof payload.job === 'string')
            receipt.job = payload.job;
          if ('revoked' in payload && typeof payload.revoked === 'boolean')
            receipt.revoked = payload.revoked;
          if ('kind' in payload && typeof payload.kind === 'string')
            receipt.kind = payload.kind;
          if ('busy' in payload && typeof payload.busy === 'boolean')
            receipt.busy = payload.busy;
          if ('note' in payload && typeof payload.note === 'string')
            receipt.note = payload.note;
        }
        view.receipt = receipt;
        view.notice =
          answer.code === 'ACCEPTED'
            ? 'Job accepted. Follow its durable status in Jobs.'
            : 'Change confirmed.';
      }
      this.pending = undefined;
      delete view.preview;
      try {
        view.data = await this.read(view.kind, view.project);
      } catch (error) {
        view.error = `Change confirmed; refreshing failed: ${errorMessage(error)}`;
      }
    } catch (error) {
      view.error = `${capsSaved ? 'Configuration was saved locally; server application is unconfirmed. Inspect caps before repeating. ' : sent ? 'Submission may have reached the server. Inspect its receipt/status before repeating. ' : ''}${error instanceof Error ? error.message : errorMessage(error)}`;
      this.pending = undefined;
      delete view.preview;
      throw new Error(view.error, { cause: error });
    } finally {
      this.busy = false;
      view.busy = false;
      this.changed();
    }
  }
}
