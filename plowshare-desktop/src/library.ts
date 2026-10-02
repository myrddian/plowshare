import { request, resultOf, type Request as WsRequest } from 'plowshare-client-ts/operations/direct';
import type { RetrievalReplies, LogSearchResponse } from 'plowshare-client-ts/operations/retrieval';
import type { Outcome } from 'plowshare-client-ts/binding/envelope';
import type { DesktopState } from './shared.ts';
import { emptyLibrary, libraryIdentity, memoryIdentity, type LibraryView, type Reading, type SearchKind, type SearchValue } from './library-shared.ts';

const problem = (e: unknown) => e instanceof Error ? e.message : 'Library information is unavailable.';
function text(value: unknown, label: string, maximum = 16000): string {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum) throw new Error(`Enter ${label}.`);
  return value.trim();
}
/** Library reads and explicit maintenance, using one WS request per operation. */
export class LibraryClient {
  private epoch = 0;
  private revisions = new Map<string, number>();
  private busy = false;
  private state: () => DesktopState;
  private send: (ask: WsRequest) => Promise<Outcome>;
  private emit: () => void;
  constructor(state: LibraryClient['state'], send: LibraryClient['send'], emit: () => void) { this.state=state; this.send=send; this.emit=emit; }
  private get value() { return this.state().library ??= emptyLibrary(); }
  reset() { this.epoch++; this.revisions.clear(); this.busy = false; const v = this.value; v.busy = false; for (const slot of [v.documents,v.memories,v.proposals,v.document,v.chunk,v.memory,v.citations,v.stance,v.search]) { slot.loading = false; slot.stale = true; } }
  private live() { if (!this.state().connected || this.state().mode !== 'live') throw new Error('Connect before reading the Library.'); }
  private async checked<T extends keyof RetrievalReplies>(ask: WsRequest<T>): Promise<RetrievalReplies[T]> {
    const answer = await this.send(ask as WsRequest), result = resultOf(ask as WsRequest, answer);
    if (result.kind === 'refused') throw new Error(answer.said ?? `The server refused ${ask.type}.`);
    if (result.kind === 'invalid-response') throw new Error(`The server returned incomplete ${ask.type} information. Previous information is retained.`);
    return answer.payload as RetrievalReplies[T];
  }
  private async read<T>(key: string, slot: Reading<T>, work: () => Promise<T>, apply: (value: T) => void = value => { slot.value = value; }) {
    this.live(); const epoch = this.epoch, revision = (this.revisions.get(key) ?? 0) + 1; this.revisions.set(key,revision); slot.loading = true; this.emit();
    const current = () => epoch === this.epoch && revision === this.revisions.get(key);
    try { const value = await work(); if (current()) { apply(value); slot.stale = false; delete slot.error; } }
    catch (error) { if (current()) slot.error = problem(error); }
    finally { if (current()) { slot.loading = false; this.emit(); } }
  }
  async open(view: LibraryView, project: string | null = this.value.project) {
    if (!['documents','memories','search'].includes(view)) throw new Error('Choose a Library view.');
    if (project !== null && (typeof project !== 'string' || !this.state().projects.some(row => row.name === project))) throw new Error('Choose an available project or Global.');
    if (this.busy && project !== this.value.project) throw new Error('Wait for the memory change before changing scope.');
    const v = this.value;
    if (project !== v.project) { this.reset(); v.project = project; v.memories = {}; v.proposals = {}; v.memory = {}; v.search = {}; delete v.selected; delete v.notice; delete v.error; }
    v.view = view; this.emit(); if (this.state().connected) await this.refresh();
  }
  async refresh() {
    if (this.value.view === 'documents') await this.documents(this.value.documents.query);
    if (this.value.view === 'memories') await this.memories();
    // Search is deliberately submitted by the person: navigation can call a model.
  }
  async documents(query: string, more = false) {
    if (typeof query !== 'string' || query.length > 4000) throw new Error('Enter a document filter of at most 4,000 characters.');
    const slot = this.value.documents, previous = slot.value;
    if (more && (!previous || query !== slot.query || previous.documents.length >= previous.total || slot.loading)) throw new Error('Refresh the document list before paging.');
    const offset = more ? previous!.documents.length : 0;
    await this.read('documents', slot, async () => {
      const page = await this.checked(request('document.list', { q: query, offset, limit: 50 }));
      if (page.offset !== offset || page.documents.length > page.limit || page.limit > 50 || (page.documents.length === 0 && page.total > offset) || page.total < offset + page.documents.length) throw new Error('Document page coordinates changed. Refresh before paging.');
      const documents = more ? [...previous!.documents,...page.documents] : [...page.documents];
      if (new Set(documents.map(row => row.documentId)).size !== documents.length) throw new Error('Documents changed while paging. Refresh to read a new list.');
      return { ...page, documents, offset: 0 };
    }, value => { slot.query = query; slot.value = value; });
  }
  private async memories() {
    const project = this.value.project;
    await Promise.all([this.read('memories',this.value.memories,() => this.checked(request('memory.index',{project}))), this.read('proposals',this.value.proposals,() => this.checked(request('proposal.list',{project})))]);
  }
  private knownDocument(id: string) {
    const v = this.value, search = v.search.value;
    return v.documents.value?.documents.some(row => row.documentId === id) || v.document.value?.documentId === id
      || (search?.kind === 'documents' && (search.reply as RetrievalReplies['document.search']).hits.some(row => row.documentId === id))
      || (search?.kind === 'retrieve' && (search.reply as RetrievalReplies['document.retrieve']).hits.some(row => row.chunk.documentId === id))
      || (search?.kind === 'rank' && (search.reply as RetrievalReplies['document.rank']).documents.some(row => row.documentId === id));
  }
  async document(id: string) {
    text(id,'a document',512); if (!this.knownDocument(id)) throw new Error('Choose a displayed document.');
    const v = this.value; v.selected = 'document'; if (v.document.id !== id) { v.document = {id}; v.chunk = {}; v.citations = {}; v.stance = {}; this.revisions.set('chunk',(this.revisions.get('chunk') ?? 0)+1); this.revisions.set('citations',(this.revisions.get('citations') ?? 0)+1); this.revisions.set('stance',(this.revisions.get('stance') ?? 0)+1); }
    await this.read('document',v.document,async () => { const row = await this.checked(request('document.detail',{document:id})); if (row.documentId !== id) throw new Error('The reply names a different document.'); return row; });
  }
  async chunk(id: string) {
    text(id,'a passage',512); const search = this.value.search.value;
    const known = search?.kind === 'documents' ? (search.reply as RetrievalReplies['document.search']).hits.find(row => row.chunkId === id)
      : search?.kind === 'retrieve' ? (search.reply as RetrievalReplies['document.retrieve']).hits.find(row => row.chunk.chunkId === id)?.chunk : undefined;
    if (!known) throw new Error('Choose a passage from the current search.');
    this.value.selected = 'chunk';
    if (this.value.chunk.id !== id) this.value.chunk = {id};
    await this.read('chunk',this.value.chunk,async () => { const row = await this.checked(request('document.chunk',{chunk:id})); if (row.chunkId !== id || row.documentId !== known.documentId || row.paragraphId !== known.paragraphId) throw new Error('The passage names a different source.'); return row; });
  }
  private knownMemory(id: string) {
    const v = this.value, search = v.search.value;
    return v.memories.value?.some(row => row.id === id) || v.proposals.value?.some(row => row.memoryId === id) || v.memory.value?.id === id
      || (search?.kind === 'recall' && (search.reply as RetrievalReplies['memory.recall']).memories.some(row => row.id === id))
      || (search?.kind === 'navigate' && (search.reply as RetrievalReplies['memory.navigate']).ids.includes(id));
  }
  async memory(id: string) {
    if (this.busy) throw new Error('Wait for the memory change before selecting another memory.');
    text(id,'a memory',512); if (!this.knownMemory(id)) throw new Error('Choose a displayed memory.');
    this.value.selected = 'memory';
    if (this.value.memory.id !== id) this.value.memory = {id};
    await this.read('memory',this.value.memory,async () => { const row = await this.checked(request('memory.read',{memory:id})); if (row.id !== id) throw new Error('The reply names a different memory.'); return row; });
  }
  async citations() {
    const id = this.value.document.value?.documentId; if (!id) throw new Error('Read a document before listing its citations.');
    await this.read('citations',this.value.citations,async () => { const reply = await this.checked(request('document.citations',{document:id,limit:100})); if (reply.scope !== 'document' || reply.citations.some(row => row.documentId !== null && row.documentId !== id)) throw new Error('Citations name a different source.'); return reply; });
  }
  async stance(claim: string) {
    const id = this.value.document.value?.documentId; if (!id) throw new Error('Read a document before checking a claim.'); claim = text(claim,'a claim');
    await this.read('stance',this.value.stance,async () => { const reply = await this.checked(request('document.stance',{document:id,claim})); if (reply.documentId !== id || reply.claim !== claim) throw new Error('The reply names a different claim or source.'); return reply; }, value => { this.value.stance.value = value; this.value.stance.claim = claim; });
  }
  async search(kind: SearchKind, query: string, mode: 'lexical'|'semantic'|'hybrid', more = false) {
    query = text(query,'a search question'); if (!['conversation','documents','retrieve','rank','recall','navigate'].includes(kind) || !['lexical','semantic','hybrid'].includes(mode)) throw new Error('Choose a search method.');
    const slot = this.value.search, previous = slot.value, project = this.value.project;
    if (more && (slot.loading || !previous || previous.kind !== 'conversation' || kind !== previous.kind || query !== previous.query || mode !== previous.mode || project !== previous.project)) throw new Error('Use the current conversation search before paging.');
    await this.read('search',slot,async () => {
      let reply: SearchValue['reply'];
      if (kind === 'conversation') {
        const prior = more ? previous!.reply as LogSearchResponse : undefined, offset = prior ? prior.hits.length : 0;
        if (prior && offset >= prior.total) throw new Error('No more search results.');
        if (prior && prior.retrieval?.effectiveMode !== 'lexical' && !prior.retrieval?.snapshot) throw new Error('This server did not provide a search snapshot. Submit a new search.');
        const page = await this.checked(request('conversation.search',{project,q:query,mode,offset,limit:50,...(prior?.retrieval?.snapshot ? {snapshot:prior.retrieval.snapshot} : {})}));
        if (page.offset !== offset || page.hits.length > page.limit || page.limit > 50 || page.total < offset + page.hits.length || (page.hits.length === 0 && page.total > offset) || (prior?.retrieval?.snapshot && page.retrieval?.snapshot !== prior.retrieval.snapshot)) throw new Error('Search snapshot or page coordinates changed. Submit a new search.');
        const hits = prior ? [...prior.hits,...page.hits] : page.hits;
        if (new Set(hits.map(hit => `${hit.conversationId}:${hit.ordinal}`)).size !== hits.length) throw new Error('Search results changed while paging. Submit a new search.');
        reply = {...page,hits,offset:0};
      } else if (kind === 'documents') reply = await this.checked(request('document.search',{query,mode:mode === 'semantic' ? 'vector' : mode,limit:50}));
      else if (kind === 'retrieve') reply = await this.checked(request('document.retrieve',{query,limit:20}));
      else if (kind === 'rank') reply = await this.checked(request('document.rank',{query,limit:20}));
      else if (kind === 'recall') reply = await this.checked(request('memory.recall',{project,question:query,limit:20}));
      else reply = await this.checked(request('memory.navigate',{project,question:query}));
      if ('query' in reply && reply.query !== query || 'question' in reply && reply.question !== undefined && reply.question !== query) throw new Error('The search reply names a different question.');
      return {kind,query,mode,project,reply};
    }, value => { slot.value = value; if (!more) delete this.value.selected; });
  }
  conversation(id: string) {
    const search = this.value.search.value;
    if (search?.kind !== 'conversation' || !(search.reply as LogSearchResponse).hits.some(hit => hit.conversationId === id)) throw new Error('Choose a conversation from the current search.');
    return id;
  }
  async maintain(action: 'invalidate'|'resolve'|'reembed'|'reconsider', identity: string, reason: string, accept?: boolean) {
    this.live(); if (!['invalidate','resolve','reembed','reconsider'].includes(action) || this.busy) throw new Error('Wait for the current memory change.');
    const epoch = this.epoch, v = this.value, project = v.project;
    const guard = () => { if (epoch !== this.epoch) throw new Error('The connection or scope changed. Refresh before retrying.'); this.live(); };
    this.busy = true; v.busy = true; delete v.error; delete v.notice; this.emit();
    let sent = false;
    try {
      if (action === 'invalidate') {
        const shown = v.memory.value; if (!shown || memoryIdentity(shown) !== identity || shown.home.project !== project || shown.state !== 'active') throw new Error('Choose an active memory in the selected scope.');
        reason = text(reason,'a reason',2000);
        const current = await this.checked(request('memory.read',{memory:shown.id})); guard();
        if (memoryIdentity(current) !== identity) throw new Error('This memory changed elsewhere. Refresh before deciding.');
        sent = true; const changed = await this.checked(request('memory.invalidate',{memory:shown.id,reason,by:this.state().handle})); guard();
        if (changed.id !== shown.id || changed.home.project !== shown.home.project || changed.state !== 'invalidated' || changed.invalidation === null) throw new Error('Memory invalidation was not confirmed for the displayed memory.'); v.memory.value = changed; v.notice = 'Memory invalidation confirmed.';
      } else if (action === 'resolve') {
        const shown = v.proposals.value?.find(row => libraryIdentity(row) === identity); if (!shown || shown.project !== project || shown.state !== 'pending' || typeof accept !== 'boolean') throw new Error('Choose a pending proposal in the selected scope.');
        reason = text(reason,'a resolution reason',2000);
        const current = (await this.checked(request('proposal.list',{project}))).find(row => row.id === shown.id); guard();
        if (!current || libraryIdentity(current) !== identity) throw new Error('This proposal changed elsewhere. Refresh before deciding.');
        sent = true; const changed = await this.checked(request('proposal.resolve',{proposal:shown.id,accept,reason,by:this.state().handle})); guard();
        if (changed.proposal.id !== shown.id || changed.proposal.memoryId !== shown.memoryId || changed.proposal.project !== project || changed.proposal.state !== (accept ? 'accepted' : 'rejected') || changed.proposal.resolvedAt === null || changed.proposal.resolvedBy === null) throw new Error('The displayed proposal resolution was not confirmed.');
        v.proposals.value = v.proposals.value!.map(row => row.id === shown.id ? changed.proposal : row); v.notice = `Proposal resolution confirmed.${changed.promotedId ? ` Promoted memory: ${changed.promotedId}.` : ''}${changed.demoted.length ? ` Demoted: ${changed.demoted.join(', ')}.` : ''}`;
      } else {
        if (identity !== libraryIdentity({project})) throw new Error('Review the current scope before maintaining it.'); guard(); sent = true;
        if (action === 'reembed') { const changed = await this.checked(request('memory.reembed',{project})); guard(); v.notice = `Embedding repair confirmed: ${changed.repaired.length} repaired; ${changed.failed.length} failed. Repaired: ${changed.repaired.join(', ') || 'none'}. Failed: ${changed.failed.join(', ') || 'none'}`; }
        else { const changed = await this.checked(request('proposal.reconsider',{project})); guard(); v.notice = `Reconsideration confirmed: ${changed.reopened.length} reopened; ${changed.refused.length} refused. Reopened: ${changed.reopened.join(', ') || 'none'}. Refused: ${changed.refused.join(', ') || 'none'}`; }
      }
      this.emit(); await this.memories(); guard();
    } catch (error) { if (epoch === this.epoch) v.error = `${problem(error)}${sent ? ' The change will not be replayed. Refresh to inspect its state.' : ''}`; throw error; }
    finally { if (epoch === this.epoch) { this.busy = false; v.busy = false; this.emit(); } }
  }
}
