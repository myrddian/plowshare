import { SWARM_PAGE, swarmMembers, readingSwarmActivity, swarmActivityOf } from 'plowshare-client-ts/operations/swarm';
import type { DesktopState } from './shared.ts';
import type { BoardView, TopicSummary, Reading } from './board-shared.ts';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';

export { topicsOf, detailOf, swarmOf } from 'plowshare-client-ts/operations/board';
import { topicsOf, detailOf, swarmOf } from 'plowshare-client-ts/operations/board';

/** Lazy inspection: no polling after its native window closes, no mutations or read receipts. */
export class BoardClient {
  private epoch = 0;
  private pages = 1;
  private timer?: ReturnType<typeof setInterval>;
  private flights = new Map<string, Promise<void>>();
  private state: () => DesktopState;
  private ask: (type: string, payload: unknown) => Promise<Outcome>;
  private emit: () => void;
  constructor(state: () => DesktopState, ask: (type: string, payload: unknown) => Promise<Outcome>, emit: () => void) {
    this.state = state; this.ask = ask; this.emit = emit;
  }
  reset() {
    this.epoch++; clearInterval(this.timer); this.timer = undefined; this.flights.clear();
    const b = this.state().board; b.topics.loading = false; b.swarm.loading = false;
    for (const detail of Object.values(b.details)) detail.loading = false;
    for (const reading of Object.values(b.activity ?? {})) reading.loading = false;
  }
  async open(view?: BoardView, project?: string) {
    if (view !== undefined && view !== 'board' && view !== 'swarm') throw new Error('Choose Board or Swarm.');
    this.reset(); const b = this.state().board; b.view = view;
    if (project !== b.project) { b.project = project; b.selected = undefined; if (this.state().mode === 'live') b.topics = {}; this.pages = 1; }
    this.emit();
    if (!view) return;
    b.memberLimit ??= SWARM_PAGE;
    await this.refresh(); this.startTimer();
  }
  private startTimer() {
    if (this.state().mode !== 'live' || !this.state().connected || !this.state().board.view || this.timer) return;
    this.timer = setInterval(() => { void this.refresh(); }, 4000); this.timer.unref();
  }
  async reconnect() { if (this.state().board.view) { await this.refresh(); this.startTimer(); } }
  private read<T>(key: string, target: Reading<T>, type: string, payload: unknown, parse: (v: unknown) => T | Promise<T>, accept?: (v: T) => void) {
    if (this.state().mode !== 'live' || !this.state().connected) return Promise.resolve();
    const existing = this.flights.get(key); if (existing) return existing;
    const epoch = this.epoch; target.loading = true; this.emit();
    const work = (async () => {
      try {
        const answer = await this.ask(type, payload);
        if (epoch !== this.epoch) return;
        if (answer.code !== 'OK') throw new Error(answer.said ?? `${type} is unavailable. Update the server and try Refresh.`);
        const value = await parse(answer.payload); if (epoch !== this.epoch) return; target.value = value; target.updatedAt = new Date().toISOString(); delete target.error; accept?.(value);
      } catch (error) { if (epoch === this.epoch) target.error = error instanceof Error ? error.message : String(error); }
      finally { if (epoch === this.epoch) { target.loading = false; this.flights.delete(key); this.emit(); } }
    })(); this.flights.set(key, work); return work;
  }
  async refresh() {
    const b = this.state().board; if (!b.view) return;
    if (b.view === 'swarm') await this.members();
    else await Promise.all([this.topics(), b.selected ? this.detail(b.selected) : Promise.resolve()]);
  }
  private async members() {
    const b = this.state().board, epoch = this.epoch;
    await this.read('swarm', b.swarm, 'swarm.status', {}, swarmOf);
    if (epoch !== this.epoch || b.swarm.error || !b.swarm.value) return;
    const visible = swarmMembers(b.swarm.value, b.project).slice(0, b.memberLimit ?? SWARM_PAGE);
    const conversations = [...new Set(visible.map(m => m.seat.seat.conversation))];
    const activity = b.activity ??= {};
    // Bound fan-out, including after a view closes while a batch is pending.
    for (let at = 0; at < conversations.length && epoch === this.epoch; at += 4) {
      await Promise.all(conversations.slice(at, at + 4).map(conversation => {
        const ask = readingSwarmActivity(conversation);
        return this.read(`member:${conversation}`, activity[conversation] ??= {}, ask.type, ask.payload, swarmActivityOf);
      }));
    }
  }
  private topics() {
    const b = this.state().board, epoch = this.epoch, pages = this.pages;
    let more = false;
    return this.read('topics', b.topics, 'board.topics', { project: b.project, limit: 200 }, async v => {
      let page = topicsOf(v); if (page.offset !== 0) throw new Error('The server returned a different topic page.');
      const rows = [...page.topics];
      for (let i = 1; i < pages && page.more; i++) {
        const answer = await this.ask('board.topics', { project: b.project, offset: i * 200, limit: 200 });
        if (epoch !== this.epoch) return [];
        if (answer.code !== 'OK') throw new Error(answer.said ?? 'Could not read every topic page.');
        page = topicsOf(answer.payload); if (page.offset !== i * 200) throw new Error('The server returned a different topic page.');
        rows.push(...page.topics);
      }
      more = page.more;
      return [...new Map(rows.map(row => [row.topic.id, row])).values()];
    }, () => { b.topics.more = more; });
  }
  async more() {
    const b = this.state().board;
    if (b.view === 'swarm') { b.memberLimit = (b.memberLimit ?? SWARM_PAGE) + SWARM_PAGE; await this.members(); return; }
    if (b.view !== 'board' || !b.topics.more || b.topics.loading) return;
    this.pages++; await this.topics();
  }
  conversation(id: string) {
    const b = this.state().board, detail = b.selected ? b.details[b.selected]?.value : undefined;
    const member = b.view === 'swarm' && b.swarm.value ? swarmMembers(b.swarm.value, b.project).slice(0, b.memberLimit ?? SWARM_PAGE).find(m => m.seat.seat.conversation === id) : undefined;
    const allowed = b.view === 'board' && detail && (detail.seats.some(s => s.seat.conversation === id)
      || detail.messages.some(m => m.conversation === id) || detail.topic.originConversation === id);
    if (!member && !allowed) throw new Error('Choose a displayed board conversation or swarm member.');
    if (this.state().mode === 'demo' && !this.state().history[id]) {
      this.state().history[id] = { entries: [...(b.activity?.[id]?.value?.entries ?? [])], more: false };
    }
    if (!this.state().conversations.some(c => c.id === id)) this.state().conversations.push({ id,
      project: member ? member.topic?.topic.project : detail?.topic.project,
      title: member ? `Swarm · ${member.seat.seat.occupant}` : `Board · ${detail!.topic.title}` });
  }
  async select(id: string) {
    const b = this.state().board;
    const known = [...(b.topics.value ?? []), ...(b.swarm.value?.topics ?? [])].some(t => t.topic.id === id) || b.swarm.value?.ready.some(r => r.topic === id)
      || Object.values(b.details).some(d => d.value?.topic.parent === id || d.value?.decisions.some(c => c.child === id));
    if (!b.view || !known) throw new Error('Choose an available board topic.');
    b.selected = id; this.emit(); await this.detail(id);
  }
  private detail(id: string) {
    const b = this.state().board, target = b.details[id] ??= {};
    return this.read(`detail:${id}`, target, 'board.messages', { topic: id }, v => { const d = detailOf(v); if (d.topic.id !== id) throw new Error('The server returned a different topic.'); return d; });
  }
}
