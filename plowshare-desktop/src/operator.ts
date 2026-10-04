import { randomUUID } from 'node:crypto';
import { request, resultOf, type Request as WsRequest } from 'plowshare-client-ts/operations/direct';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { DesktopState } from './shared.ts';
import type { CapsFile, CapKey } from './caps.ts';
import { capPreview } from './caps.ts';
import { OPERATOR_KINDS, type OperatorKind, type OperatorView } from './operator-shared.ts';
export { OPERATOR_KINDS, type OperatorKind, type OperatorView } from './operator-shared.ts';
const object = (v: unknown): Record<string, unknown> => v && typeof v === 'object' && !Array.isArray(v) ? v as Record<string, unknown> : {};
const rows = (v: unknown) => Array.isArray(v) ? v.map(object) : [];
const string = (v: unknown, name: string, max = 8000) => { if (typeof v !== 'string' || !v.trim() || v.length > max) throw new Error(`Enter ${name} (at most ${max} characters).`); return v.trim(); };
const number = (v: unknown, name: string) => { if (!Number.isSafeInteger(v) || Number(v) < 1 || Number(v) > 1_000_000) throw new Error(`Enter a positive ${name}, at most 1000000.`); return Number(v); };
export class OperatorClient {
  private state: () => DesktopState; private send: (ask: WsRequest) => Promise<Outcome>; private changed: () => void;
  private submit: (ask: WsRequest, conversation?: string, agent?: string) => Promise<Outcome>;
  private readCaps: (project: string) => Promise<CapsFile>; private saveCaps: (project: string, file: CapsFile, key: CapKey, value: number) => Promise<void>;
  private epoch = 0; private messageRevision = 0; private busy = false; private pending?: { ask?: WsRequest; file?: CapsFile; key?: CapKey; value?: number; selected?: unknown; generation: number };
  constructor(state: () => DesktopState, send: (ask: WsRequest) => Promise<Outcome>, changed: () => void,
    submit: (ask: WsRequest, conversation?: string, agent?: string) => Promise<Outcome>,
    readCaps: (project: string) => Promise<CapsFile>, saveCaps: (project: string, file: CapsFile, key: CapKey, value: number) => Promise<void>) {
    this.state = state; this.send = send; this.changed = changed; this.submit = submit; this.readCaps = readCaps; this.saveCaps = saveCaps;
  }
  reset() { this.epoch++; this.pending = undefined; if (this.state().operator) delete this.state().operator?.preview; }
  private async checked(ask: WsRequest) {
    const generation = this.epoch, identity = `${this.state().base}|${this.state().handle}`;
    const answer = await this.send(ask);
    if (generation !== this.epoch || identity !== `${this.state().base}|${this.state().handle}`) throw new Error('The account connection changed. Review again.');
    const result = resultOf(ask, answer);
    if (!['completed', 'accepted', 'incomplete'].includes(result.kind)) throw new Error(answer.said || `Unreadable or refused ${ask.type} reply. Completion is unknown.`);
    return answer.payload;
  }
  private async read(kind: OperatorKind, project?: string): Promise<Record<string, unknown>> {
    const tier = project ? { project } : {};
    if (kind.startsWith('message-')) {
      if (!project) throw new Error('Choose a project for message instances.');
      const instances: Record<string, unknown>[] = [];
      for (let offset = 0; ; offset += 200) {
        const page = object(await this.checked(request('message.instances', { project, offset, limit: 200 })));
        instances.push(...rows(page.instances));
        if (!page.more) break;
      }
      const agents = kind === 'message-open' ? rows(await this.checked(request('agent.list', { project }))) : [];
      return { instances, agents };
    }
    if (kind.startsWith('memory-') || kind === 'agent-curate') return { memories: await this.checked(request('memory.index', tier)) };
    if (kind.startsWith('conversation-')) {
      const active = await this.checked(request('conversation.list', tier)), archived = await this.checked(request('conversation.list', { ...tier, lifecycle: 'archived' }));
      return { conversations: [...rows(active).map(row => ({...row, lifecycle: 'active'})), ...rows(archived).map(row => ({...row, lifecycle: 'archived'}))] };
    }
    if (kind === 'job-limits') return { jobs: rows(await this.checked(request('job.list', {}))).filter(job => job.state === 'RUNNING' && job.limits != null && typeof job.conversation === 'string' && job.conversation.length > 0) };
    if (kind === 'approval-grant') return object(await this.checked(request('approval.list', { mine: true })));
    if (kind === 'approval-revoke') { if (!project) throw new Error('Choose a project to inspect standing grants.'); return object(await this.checked(request('approval.list', { project }))); }
    if (kind === 'board-topup') { const data = object(await this.checked(request('board.topics', { ...tier, limit: 100, offset: 0 }))); return {...data, topics: rows(data.topics).filter(row => object(row.topic).state === 'open' && object(row.topic).parent == null)}; }
    if (!project) throw new Error('Choose a project for cap settings.');
    return { caps: await this.checked(request('orchestration.caps', { project })), file: await this.readCaps(project) };
  }
  private selected(kind: OperatorKind, data: Record<string, unknown>, payload: Record<string, unknown>) {
    if (kind === 'message-deliveries') return rows(data.deliveries).find(row => row.message === payload.message);
    if (kind === 'message-open') return rows(data.agents).find(row => row.name === payload.agent);
    if (kind.startsWith('message-')) return rows(data.instances).find(row => row.id === payload.instance);
    if (kind.startsWith('conversation-')) return rows(data.conversations).find(row => row.id === payload.conversation);
    if (kind === 'job-limits') { const job = rows(data.jobs).find(row => row.id === payload.job); return job && { id: job.id, agent: job.agent, conversation: job.conversation, state: job.state, limits: job.limits }; }
    if (kind.startsWith('approval-')) return rows(data.approvals).find(row => row.id === payload.id);
    if (kind === 'board-topup') return rows(data.topics).find(row => object(row.topic).id === payload.topic)?.topic;
    return null;
  }
  async prepare(kind: OperatorKind, project?: string) {
    if (this.busy) throw new Error('Wait for the current control operation.');
    if (!OPERATOR_KINDS.includes(kind) || !this.state().connected) throw new Error('Connect and choose a control.');
    if (project && !this.state().projects.some(row => row.name === project)) throw new Error('Choose an available project.');
    const generation = ++this.epoch; this.pending = undefined;
    const data = await this.read(kind, project);
    if (generation !== this.epoch) throw new Error('The selected control changed.');
    this.state().operator = { kind, project, data, identity: JSON.stringify([this.state().base, this.state().handle, kind, project, data]) }; this.changed();
  }
  async messages(identity: string, instance: string, offset = 0) {
    const view = this.state().operator;
    if (this.busy || !view || view.kind !== 'message-deliveries' || view.identity !== identity) throw new Error('Read the message controls again.');
    if (!rows(view.data.instances).some(row => row.id === instance)) throw new Error('Choose an instance from the current listing.');
    if (!Number.isSafeInteger(offset) || offset < 0) throw new Error('Choose a valid message page.');
    const revision = ++this.messageRevision, generation = this.epoch;
    const page = object(await this.checked(request('message.deliveries', { instance, offset, limit: 200 })));
    if (revision !== this.messageRevision || generation !== this.epoch || this.state().operator !== view) return;
    view.data = { ...view.data, deliveries: page.deliveries, messageInstance: instance, messageOffset: page.offset, messageMore: page.more };
    this.pending = undefined; delete view.preview; delete view.error; this.changed();
  }
  preview(identity: string, input: Record<string, unknown>) {
    const view = this.state().operator;
    if (!view || view.identity !== identity || this.busy || !this.state().connected) throw new Error('Read the current control before reviewing a change.');
    this.messageRevision++;
    const tier = view.project ? { project: view.project } : {}; let ask: WsRequest | undefined, file: CapsFile | undefined, key: CapKey | undefined, value: number | undefined;
    let summary = '';
    switch (view.kind) {
      case 'message-deliveries': ask = request('message.cancel', { message: string(input.id, 'message', 512) }); summary = 'Cancel handling this message and finish an expected reply if it has no final reply.'; break;
      case 'message-open': if (!view.project) throw new Error('Choose a project.'); ask = request('message.instance.open', { project: view.project, agent: string(input.agent, 'agent or bot', 512), makeDefault: input.makeDefault === 'true', requestId: randomUUID() }); summary = 'Create a persistent instance with its own conversation and address.'; break;
      case 'message-default': ask = request('message.instance.default', { instance: string(input.id, 'instance', 512) }); summary = 'Use this persistent instance for messages addressed to this agent or bot name in the project.'; break;
      case 'message-stop': ask = request('message.instance.stop', { instance: string(input.id, 'instance', 512) }); summary = 'Stop this instance, cancel its pending message handling, and finish expected replies.'; break;
      case 'message-archive': ask = request('message.instance.archive', { instance: string(input.id, 'instance', 512) }); summary = 'Stop and archive this instance and its conversation.'; break;
      case 'memory-write': ask = request('memory.write', { ...tier, proposal: { summary: string(input.summary, 'summary', 512), scope: string(input.scope, 'memory scope', 512), body: string(input.body, 'memory body', 100000), formedBy: this.state().handle, formedWhere: view.project ?? '' } }); summary = 'Submit this memory for server judgement. It may be merged, proposed or refused.'; break;
      case 'memory-digest': ask = request('memory.digest', tier); summary = 'Start model-backed digest maintenance in this scope.'; break;
      case 'agent-curate': if (!view.project) throw new Error('Choose a project for curation.'); ask = request('agent.curate', { project: view.project, maxModelCalls: number(input.maxModelCalls, 'model-call limit') }); summary = 'Start model-backed agent curation for this project.'; break;
      case 'conversation-lifecycle': if (!['active', 'archived'].includes(String(input.lifecycle))) throw new Error('Choose Active or Archived.'); ask = request('conversation.lifecycle', { conversation: string(input.id, 'conversation', 512), lifecycle: String(input.lifecycle) }); summary = 'Change this conversation lifecycle. Archiving does not cancel a running job.'; break;
      case 'conversation-resume': ask = request('conversation.resume', { conversation: string(input.id, 'conversation', 512), maxTurns: number(input.maxTurns, 'turn limit'), maxModelCalls: number(input.maxModelCalls, 'model-call limit') }); summary = 'Resume this conversation as one new job with the reviewed limits.'; break;
      case 'job-limits': ask = request('job.limits', { job: string(input.id, 'job', 512), maxTurns: number(input.maxTurns, 'turn limit'), maxModelCalls: number(input.maxModelCalls, 'model-call limit') }); summary = 'Apply these limits to the existing job; this does not start another job.'; break;
      case 'approval-grant': {
        const decision = String(input.decision); if (!['once', 'conversation', 'project', 'deny'].includes(decision)) throw new Error('Choose an approval scope.');
        const prefix = decision === 'project' ? JSON.parse(string(input.prefix, 'command argument prefix', 8192)) : undefined;
        if (prefix && (!Array.isArray(prefix) || !prefix.length || !prefix.every(v => typeof v === 'string' && v.length <= 4096))) throw new Error('The command prefix must be a nonempty list of arguments.');
        ask = request('approval.answer', { id: string(input.id, 'approval', 512), decision: decision as 'once' | 'conversation' | 'project' | 'deny', ...(prefix ? { prefix } : {}) }); summary = 'Record this command decision. Allowing may continue the waiting job; project grants cover the reviewed argument prefix.'; break;
      }
      case 'approval-revoke': ask = request('approval.revoke', { id: string(input.id, 'standing approval', 512) }); summary = 'Revoke the displayed standing command grant.'; break;
      case 'board-topup': ask = request('board.topup', { topic: string(input.id, 'topic', 512), maxModelCalls: number(input.maxModelCalls, 'model-call allowance') }); summary = 'Raise the topic pot to this new total model-call allowance.'; break;
      case 'caps': file = view.data.file as unknown as CapsFile; key = String(input.key) as CapKey; value = Number(input.value); summary = capPreview(file, key, value); break;
    }
    const payload = ask?.payload as Record<string, unknown> ?? { key, value };
    const selected = this.selected(view.kind, view.data, payload);
    if (view.kind === 'board-topup' && Number(payload.maxModelCalls) <= Number(object(selected).potTotal)) throw new Error('Enter a new total above the current topic pot.');
    if (selected === undefined) throw new Error('Choose an item from the current listing.');
    if (view.kind === 'message-deliveries' && !['queued', 'running', 'awaiting'].includes(String(object(selected).state))) throw new Error('Choose a pending message to cancel.');
    this.pending = { ask, file, key, value, selected, generation: this.epoch };
    view.preview = { identity: JSON.stringify([identity, payload, summary]), summary, payload, ...(selected ? {subject: object(selected)} : {}) }; delete view.error; this.changed();
  }
  async apply(identity: string) {
    const view = this.state().operator, pending = this.pending;
    if (this.busy || !view?.preview || view.preview.identity !== identity || !pending || pending.generation !== this.epoch) throw new Error('Review this change again before applying it.');
    this.busy = true; view.busy = true; this.changed(); let sent = false, capsSaved = false;
    try {
      const fresh = view.kind === 'message-deliveries'
        ? { ...view.data, deliveries: [await this.checked(request('message.delivery', { message: String(view.preview.payload.message) }))] }
        : await this.read(view.kind, view.project);
      if (pending.generation !== this.epoch || !this.state().connected) throw new Error('The connection changed before submission.');
      if (JSON.stringify(this.selected(view.kind, fresh, view.preview.payload)) !== JSON.stringify(pending.selected)) throw new Error('The displayed item changed. Read it and review again.');
      sent = true;
      if (pending.file) {
        await this.saveCaps(view.project!, pending.file, pending.key!, pending.value!); capsSaved = true;
        view.notice = 'Configuration saved locally; server cap confirmation is pending.'; this.changed();
        view.receipt = await this.checked(request('orchestration.caps', { project: view.project! }));
        view.notice = object(view.receipt).said ? `Configuration saved locally; server reported: ${String(object(view.receipt).said)}` : 'Configuration saved; the server reloaded project caps.';
      } else {
        const selected = object(pending.selected);
        const answer = await this.submit(pending.ask!, String(selected.conversation ?? (pending.ask!.type === 'conversation.resume' ? view.preview.payload.conversation : '')), String(selected.agent ?? pending.ask!.type));
        view.receipt = { code: answer.code, payload: answer.payload, said: answer.said };
        view.notice = answer.code === 'ACCEPTED' ? 'Job accepted. Follow its durable status in Jobs.' : 'Change confirmed.';
      }
      this.pending = undefined; delete view.preview;
      try { view.data = await this.read(view.kind, view.project); } catch (error) { view.error = `Change confirmed; refreshing failed: ${String(error)}`; }
    } catch (error) {
      view.error = `${capsSaved ? 'Configuration was saved locally; server application is unconfirmed. Inspect caps before repeating. ' : sent ? 'Submission may have reached the server. Inspect its receipt/status before repeating. ' : ''}${error instanceof Error ? error.message : String(error)}`;
      this.pending = undefined; delete view.preview; throw new Error(view.error);
    } finally { this.busy = false; view.busy = false; this.changed(); }
  }
}
