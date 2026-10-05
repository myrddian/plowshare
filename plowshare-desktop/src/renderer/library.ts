import { errorMessage } from 'plowshare-client-ts/binding/values';
import { background } from './events.ts';
import { installPaneResize } from './pane-resize.ts';
import { installInformation } from './information.ts';
import type { DesktopState, Request } from '../shared.ts';
import {
  emptyLibrary,
  libraryIdentity,
  memoryIdentity,
  type LibraryView,
  type SearchKind,
} from '../library-shared.ts';
import type {
  RetrievalReplies,
  LogSearchResponse,
  PassageCoverage,
} from 'plowshare-client-ts/operations/retrieval';
import { mountIcons, icon } from './icons.ts';
import { escapeHtml as esc, markdownHtml } from './markdown.ts';
import { copyButton, installCopyControls } from './copy.ts';
mountIcons();
installCopyControls();
const $ = <T extends HTMLElement = HTMLElement>(selector: string): T =>
  document.querySelector(selector)!;
installPaneResize({
  container: $('#library-panel'),
  pane: $('#library-results'),
  other: $('#library-detail'),
  key: 'library',
  label: 'Resize library list',
  property: '--library-width',
  minimum: 220,
});
let memoryScreen = false,
  initialized = false;
let state: DesktopState,
  stamp = '',
  localError = '',
  identity = '',
  selectedProposal = '';
const sources = installInformation(
  window.plowshare,
  () => state,
  () => state.library?.project ?? '',
  $('#library-source-panel'),
  { kind: 'report', filterHost: $('#report-filter') },
);
const documentSources = installInformation(
  window.plowshare,
  () => state,
  () => state.library?.project ?? '',
  $('#document-source-panel'),
  { kind: 'source' },
);
const manualSources = installInformation(
  window.plowshare,
  () => state,
  () => '',
  $('#library-manual-panel'),
  {
    kind: 'source',
    manual: true,
    onManualChapter: (chapter) =>
      background(
        action({
          action: 'library-view',
          view: 'manual',
          project: null,
          chapter,
        }),
      ),
  },
);
let manualVisible = false,
  manualSelection = '';
let documentSourcesVisible = false;
let sourcesVisible = false,
  reportSelection = '';
const askingDocuments = new Set<string>();
let readerNotice = '';
const drafts = new Map<string, string>();
const disclosures = new Map<string, boolean>();
const prose = (text: string) =>
  `<div class="message-content" data-copy-source="${esc(text)}">${copyButton('Copy text', 'answer')}${markdownHtml(text)}</div>`;
const coverage = (value: PassageCoverage | null | undefined) =>
  value
    ? `Coverage: ${value.indexed} indexed / ${value.eligible} eligible; ${value.passages} passages; ${value.pending} pending, ${value.failed} failed, ${value.stale} stale.`
    : 'Index coverage was not reported by this server.';
const status = (slot: { loading?: boolean; stale?: boolean; error?: string }) =>
  `${slot.stale ? '<p class="library-note">Previous snapshot · refresh or submit a new search.</p>' : ''}${slot.loading ? '<p class="library-note">Reading…</p>' : ''}${slot.error ? `<p class="error-banner">${esc(slot.error)}</p>` : ''}`;
async function action(request: Request) {
  if (request.action !== 'information') readerNotice = '';
  try {
    const reply = await window.plowshare.request(request);
    localError = '';
    update(reply.state);
  } catch (error) {
    localError = error instanceof Error ? error.message : errorMessage(error);
    render();
  }
}
function update(next: DesktopState) {
  const nextIdentity = JSON.stringify([next.base, next.handle, next.mode]);
  if (identity !== nextIdentity) {
    sources.close();
    sourcesVisible = false;
    manualSources.close();
    manualVisible = false;
    manualSelection = '';
    documentSources.close();
    documentSourcesVisible = false;
    askingDocuments.clear();
    readerNotice = '';
    drafts.clear();
    disclosures.clear();
    selectedProposal = '';
    stamp = '';
    identity = nextIdentity;
  }
  state = next;
  render();
}
function remember() {
  for (const detail of document.querySelectorAll<HTMLDetailsElement>(
    '[data-library-disclosure]',
  ))
    disclosures.set(detail.dataset.libraryDisclosure!, detail.open);
  for (const input of document.querySelectorAll<
    HTMLInputElement | HTMLTextAreaElement
  >('[data-library-draft]'))
    drafts.set(input.dataset.libraryDraft!, input.value);
}
function restore() {
  for (const detail of document.querySelectorAll<HTMLDetailsElement>(
    '[data-library-disclosure]',
  ))
    detail.open = disclosures.get(detail.dataset.libraryDisclosure!) ?? false;
  for (const input of document.querySelectorAll<
    HTMLInputElement | HTMLTextAreaElement
  >('[data-library-draft]'))
    input.value = drafts.get(input.dataset.libraryDraft!) ?? '';
}
function render() {
  if (!state || !initialized) return;
  const v = state.library ?? emptyLibrary();
  const allowed = memoryScreen
    ? ['memories', 'search']
    : ['manual', 'sources', 'documents', 'search'];
  if (!allowed.includes(v.view)) {
    if (manualVisible) {
      manualSources.close();
      manualVisible = false;
    }
    if (sourcesVisible) {
      sources.close();
      sourcesVisible = false;
    }
    if (documentSourcesVisible) {
      documentSources.close();
      documentSourcesVisible = false;
    }
    return;
  }
  const manualView = v.view === 'manual';
  $('#library-manual-panel').hidden = !manualView;
  if (manualView) {
    const selection = JSON.stringify(v.manual);
    if (!manualVisible || manualSelection !== selection) {
      manualVisible = true;
      manualSelection = selection;
      manualSources.open(undefined, v.manual?.chapter);
    }
  } else if (manualVisible) {
    manualSources.close();
    manualVisible = false;
  }
  remember();
  $('#library-title').textContent = memoryScreen
    ? 'Memories'
    : v.view === 'manual'
      ? 'Manual'
      : 'Library';
  $('#library-scope-label').firstChild!.textContent = memoryScreen
    ? 'Memory scope'
    : 'Conversation scope';
  $('#library-description').textContent = memoryScreen
    ? 'Remembered knowledge, proposals and memory search'
    : v.view === 'manual'
      ? 'Plowshare help and documentation'
      : 'Generated reports, consumed documents and search';
  $('.activity-tabs').setAttribute(
    'aria-label',
    memoryScreen ? 'Memories' : 'Library',
  );
  for (const option of $<HTMLSelectElement>('#search-kind').options)
    option.hidden = memoryScreen
      ? !['recall', 'navigate'].includes(option.value)
      : ['recall', 'navigate'].includes(option.value);
  $('#library-connection').textContent =
    state.mode === 'demo'
      ? 'Offline demo · connect to read Library'
      : `${state.connection} · ${state.handle}`;
  const errors = [
    localError,
    v.error,
    !state.connected
      ? 'Reconnect in the main window. Shown information may be out of date.'
      : '',
  ].filter(Boolean);
  $('#library-error').hidden = !errors.length;
  $('#library-error').textContent = errors.join(' ');
  $('#library-notice').hidden = !(v.notice || readerNotice);
  $('#library-notice').textContent = readerNotice || v.notice || '';
  for (const tab of ['manual', 'sources', 'documents', 'memories', 'search']) {
    const button = $(`#library-${tab}`);
    button.hidden = !allowed.includes(tab);
    button.setAttribute('aria-selected', String(v.view === tab));
    button.tabIndex = v.view === tab ? 0 : -1;
  }
  const sourceView = v.view === 'sources';
  const readerView = sourceView || manualView;
  $('.activity-workspace').classList.toggle('reports-view', sourceView);
  $('#document-sources').hidden = v.view !== 'documents';
  const showDocuments =
    v.view === 'documents' && $<HTMLDetailsElement>('#document-sources').open;
  if (showDocuments && !documentSourcesVisible) {
    documentSourcesVisible = true;
    documentSources.open();
  } else if (!showDocuments && documentSourcesVisible) {
    documentSources.close();
    documentSourcesVisible = false;
  }
  $('.library-controls').hidden = readerView;
  $('#library-panel').hidden = readerView || showDocuments;
  $('#library-scope-note').hidden = readerView;
  $('#library-refresh').hidden = readerView;
  if (sourceView) {
    const selection = JSON.stringify([v.project, v.report]);
    if (!sourcesVisible || reportSelection !== selection) {
      sourcesVisible = true;
      reportSelection = selection;
      sources.open(v.report?.revision);
    }
    return;
  }
  if (sourcesVisible) {
    sources.close();
    sourcesVisible = false;
  }
  if (manualView) return;
  $('#library-panel').setAttribute('aria-labelledby', `library-${v.view}`);
  $('#library-panel').classList.toggle('search-view', v.view === 'search');
  $('#document-filter').hidden = v.view !== 'documents';
  $('#search-form').hidden = v.view !== 'search';
  $('#library-scope-label').hidden = v.view === 'documents';
  $('#library-scope-note').textContent =
    v.view === 'documents'
      ? 'Documents belong to the server corpus. PDF and image conversion runs on the server.'
      : `Memory and conversation reads use ${v.project ?? 'Global'}. Document searches use the server corpus.${v.view === 'memories' ? ' Reading a memory records a use.' : ''}`;
  const scope = $<HTMLSelectElement>('#library-scope');
  const options =
    '<option value="">Global</option>' +
    state.projects
      .map(
        (row) => `<option value="${esc(row.name)}">${esc(row.name)}</option>`,
      )
      .join('');
  if (scope.innerHTML !== options) scope.innerHTML = options;
  scope.value = v.project ?? '';
  scope.disabled = !!v.busy;
  $('#library-refresh').toggleAttribute(
    'disabled',
    !state.connected || !!v.busy,
  );
  const next = JSON.stringify([
    v,
    selectedProposal,
    state.connected,
    state.jobs.filter((job) => job.source === 'information'),
    Array.from(askingDocuments),
  ]);
  if (stamp === next) return;
  stamp = next;
  const results = $('#library-results'),
    detail = $('#library-detail'),
    scroll = detail.scrollTop;
  const focused = document.activeElement as
    HTMLInputElement | HTMLTextAreaElement | null;
  const focusKey = focused?.dataset?.libraryDraft,
    selection = focusKey
      ? [focused?.selectionStart, focused?.selectionEnd]
      : undefined;
  if (v.view === 'documents') {
    results.innerHTML =
      status(v.documents) +
      (v.documents.value
        ? `<p class="library-note">${v.documents.value.documents.length} / ${v.documents.value.total} documents${v.documents.query ? ` · ${esc(v.documents.query)}` : ''}</p>${v.documents.value.naming ? `<p>${esc(v.documents.value.naming)}</p>` : ''}${v.documents.value.documents.map((row) => `<button class="library-row ${v.document.id === row.documentId ? 'selected' : ''}" data-document="${esc(row.documentId)}"><strong>${esc(row.title || row.sourceName)}</strong><small>${esc(row.sourceName)}</small></button>`).join('')}${v.documents.value.documents.length < v.documents.value.total ? '<button id="documents-more">Load more documents</button>' : ''}`
        : '<p class="activity-empty">Find documents in the server corpus.</p>');
    detail.innerHTML = documentDetail();
  } else if (v.view === 'memories') {
    results.innerHTML =
      status(v.memories) +
      (v.memories.value
        ? `<h3>${v.memories.value.length} memories</h3>${v.memories.value.map((row) => `<button class="library-row ${v.memory.id === row.id ? 'selected' : ''}" data-memory="${esc(row.id)}"><strong>${esc(row.summary || row.id)}</strong><small>${esc(row.scope)} · ${row.unsearchable === true ? 'Not searchable' : row.unsearchable === false ? 'Searchable' : 'Searchability not reported'}</small></button>`).join('')}`
        : '') +
      status(v.proposals) +
      `<h3>Proposals</h3>${v.proposals.value?.map((row) => `<button class="library-row ${selectedProposal === row.id ? 'selected' : ''}" data-proposal="${esc(row.id)}"><strong>${esc(row.action)} · ${esc(row.state)}</strong><small>${esc(row.reason)}</small></button>`).join('') || '<p>No proposals loaded.</p>'}<details class="library-maintenance" data-library-disclosure="${esc('maintain:' + v.project)}"><summary>Maintain ${esc(v.project ?? 'Global')}</summary><p>Repair missing embeddings, or reopen rejected proposals for this scope. Each action changes server state once.</p><button data-maintain="reembed" ${v.busy ? 'disabled' : ''}>Repair embeddings</button><button data-maintain="reconsider" ${v.busy ? 'disabled' : ''}>Reconsider rejected proposals</button></details>`;
    const proposal = v.proposals.value?.find(
      (row) => row.id === selectedProposal,
    );
    detail.innerHTML = proposal
      ? `<header><div class="eyebrow">${esc(proposal.project ?? 'Global')} · ${esc(proposal.state)}</div><h2>${esc(proposal.action)} proposal</h2><p class="library-source">${esc(proposal.id)} · ${esc(proposal.createdAt)} · ${esc(proposal.proposedBy)}</p></header>${prose(proposal.reason)}<button data-memory="${esc(proposal.memoryId)}">Read source memory</button>${proposal.resolution ? prose(proposal.resolution) : ''}${proposal.state === 'pending' ? `<div class="library-form"><label>Resolution reason<textarea id="proposal-reason" data-library-draft="${esc('proposal:' + libraryIdentity(proposal))}"></textarea></label><p>Accept applies this proposal to the memory archive. Reject records your decision.</p><div class="library-actions"><button data-resolve="true" ${v.busy ? 'disabled' : ''}>Accept proposal</button><button data-resolve="false" ${v.busy ? 'disabled' : ''}>Reject proposal</button></div></div>` : ''}`
      : memoryDetail();
  } else {
    results.innerHTML = searchResults();
    detail.innerHTML =
      v.selected === 'chunk'
        ? chunkDetail()
        : v.selected === 'memory'
          ? memoryDetail()
          : v.selected === 'document'
            ? documentDetail()
            : '<p class="activity-empty">Choose a source from the search results.</p>';
  }
  restore();
  detail.scrollTop = scroll;
  if (focusKey) {
    const input = Array.from(
      detail.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>(
        '[data-library-draft]',
      ),
    ).find((node) => node.dataset.libraryDraft === focusKey);
    if (input) {
      input.focus({ preventScroll: true });
      if (selection && selection[0] != null && selection[1] != null)
        input.setSelectionRange(selection[0], selection[1]);
    }
  }
}
function documentDetail() {
  const v = state.library ?? emptyLibrary(),
    d = v.document.value;
  if (!d)
    return (
      status(v.document) +
      '<p class="activity-empty">Choose a document to read its text.</p>'
    );
  const reader = v.reader,
    page = reader?.id === d.documentId ? reader.value : undefined;
  const answer = state.jobs
    .filter(
      (job) => job.source === 'information' && job.revision === d.documentId,
    )
    .at(-1);
  const waiting =
    askingDocuments.has(d.documentId) ||
    (!!answer &&
      ['starting', 'running', 'cancelling', 'unknown'].includes(answer.status));
  return (
    status(v.document) +
    `<header><div class="eyebrow">DOCUMENT</div><h2>${esc(d.title || d.sourceName)}</h2><p class="library-source">${esc(d.sourceName)}</p></header>
 <details class="library-maintenance library-reader-question" data-library-disclosure="${esc('question-form:' + d.documentId)}"><summary>Ask the reader about this document</summary><form id="document-question-form" class="library-form"><label>Ask about this document<input id="document-question" required placeholder="What does this document say about…?" data-library-draft="${esc('question:' + d.documentId)}"></label><p class="library-note">Answers use this document and the configured models. Reading can take a few minutes.</p><button class="primary-button" ${waiting ? 'disabled' : ''}>${waiting ? 'Reading…' : 'Ask reader'}</button></form></details>
 ${answer ? `<section class="library-reader-answer" role="status"><h3>${answer.status === 'finished' ? 'Reader’s answer' : 'Reader progress'}</h3>${prose(answer.text || answer.detail || 'Your question was accepted. Waiting for the reader’s answer.')}</section>` : ''}
 <div class="library-reading-layout"><section class="library-original"><h3>Source text</h3>${status(reader ?? {})}${page ? `<p class="library-note">Saved text · characters ${page.total ? page.start + 1 : 0}–${page.end} of ${page.total}</p><div class="library-raw-text" tabindex="0" aria-label="Saved document text">${esc(page.text)}</div><div class="library-actions"><button data-reader-offset="${Math.max(0, page.start - 8192)}" ${page.start === 0 || reader?.loading ? 'disabled' : ''}>Previous text</button><button data-reader-offset="${page.end}" ${page.end >= page.total || reader?.loading ? 'disabled' : ''}>Next text</button></div>` : reader?.error ? '<p>The saved text could not be read. Summaries remain available beside it.</p>' : '<p>Loading saved source text…</p>'}</section>
 <aside class="library-summary-note" aria-label="Document summary"><h3>Summary</h3><p class="library-note">Generated reading notes. Compare them with the source text.</p>${d.summary ? prose(d.summary) : '<p>No summary available.</p>'}${d.vocabulary ? `<details data-library-disclosure="${esc('vocabulary:' + d.documentId)}"><summary>Key terms</summary>${prose(d.vocabulary)}</details>` : ''}</aside></div>
 <details class="library-maintenance" data-library-disclosure="${esc('outline:' + d.documentId)}"><summary>Outline and section summaries</summary><ul class="library-outline">${d.chapters.map((ch, i) => `<li><strong>${esc(ch.title ?? `Part ${i + 1}`)}</strong>${ch.summary ? prose(ch.summary) : ''}${ch.sections.map((sec, j) => `<div><h4>${esc(sec.title ?? `Section ${j + 1}`)}</h4>${sec.summary ? prose(sec.summary) : ''}</div>`).join('')}</li>`).join('')}</ul></details>
 <section class="library-citations"><button id="document-citations" class="secondary-button">Read recent citations</button>${status(v.citations)}${v.citations.value ? `<h3>${v.citations.value.citations.length} recent citations</h3>${v.citations.value.citations.map((c) => `<article class="library-hit"><strong>${esc(c.sourceName)} · paragraph ${c.paragraphOrdinal}</strong><p class="library-source">${esc(c.standing)} · ${esc(c.agent)}</p>${c.paragraphText ? prose(c.paragraphText) : '<p>Original paragraph is unavailable.</p>'}<details><summary>Citation details</summary><p class="library-source">${esc(c.citedAt)} · Conversation ${esc(c.conversationId ?? 'not recorded')} · turn ${c.turnOrdinal ?? 'not recorded'}</p></details></article>`).join('')}` : ''}</section>
 <details class="library-maintenance" data-library-disclosure="${esc('claim:' + d.documentId)}"><summary>Does this document support a claim?</summary><div class="library-form"><label>Claim<textarea id="document-claim" data-library-draft="${esc('claim:' + d.documentId)}" placeholder="Enter a claim to examine against this source"></textarea></label><p>The reader will explain what supports or challenges this claim, with evidence from the document.</p><button id="document-stance" class="primary-button" ${waiting ? 'disabled' : ''}>Ask reader to examine claim</button></div></details>
 <details class="library-maintenance" data-library-disclosure="${esc('technical:' + d.documentId)}"><summary>Technical details</summary><p class="library-source">Document ID: ${esc(d.documentId)}<br>Ingested ${esc(d.ingestedAt)} by ${esc(d.ingestedBy)} · ${d.byteSize.toLocaleString()} bytes</p><p>Embedding comparison is a rough search signal. It does not establish whether the document supports a claim.</p><button id="document-vector-check">Compute embedding comparison for the claim above</button>${status(v.stance)}${v.stance.value ? `<p>Topicality ${v.stance.value.topical}; stance ${v.stance.value.stance}; basis ${esc(v.stance.value.basis)}</p>` : ''}</details>`
  );
}
function memoryDetail() {
  const v = state.library ?? emptyLibrary(),
    m = v.memory.value;
  if (!m)
    return (
      status(v.memory) +
      '<p class="activity-empty">Choose a memory or proposal.</p>'
    );
  return (
    status(v.memory) +
    `<header><div class="eyebrow">${esc(m.home.project ?? 'Global')} · ${esc(m.state)}${m.pinned ? ' · pinned' : ''}</div><h2>${esc(m.summary || m.id)}</h2><p class="library-source">Formed ${esc(m.formed.at)} by ${esc(m.formed.by)} · ${esc(m.formed.where)}<br>${m.uses} uses · last used ${esc(m.lastUsed ?? 'never')}</p></header><p>Applies to: ${esc(m.scope)}</p>${prose(m.body)}<details class="library-maintenance" data-library-disclosure="${esc('memory-details:' + m.id)}"><summary>Memory details</summary><p class="library-source">Memory ID: ${esc(m.id)}</p>${m.supersedes ? `<p>Supersedes ${esc(m.supersedes)}</p>` : ''}${m.supersededBy ? `<p>Superseded by ${esc(m.supersededBy)}</p>` : ''}</details>${m.invalidation ? `<p>Invalidated ${esc(m.invalidation.at)} by ${esc(m.invalidation.by)}</p>${prose(m.invalidation.reason)}` : ''}${m.state === 'active' && m.home.project === v.project ? `<details class="library-maintenance" data-library-disclosure="${esc('invalidate:' + m.id)}"><summary>Invalidate this memory</summary><p>Marks this memory as invalid in the archive. Its body and provenance remain inspectable.</p><div class="library-form"><label>Reason<textarea id="memory-reason" data-library-draft="${esc('memory:' + memoryIdentity(m))}"></textarea></label><button id="memory-invalidate" ${v.busy ? 'disabled' : ''}>Confirm invalidation</button></div></details>` : ''}`
  );
}
function chunkDetail() {
  const v = state.library ?? emptyLibrary(),
    c = v.chunk.value;
  return (
    status(v.chunk) +
    (c
      ? `<h2>${esc(c.title || c.sourceName)}</h2><p class="library-source">${esc(c.sourceName)} · paragraph ${c.paragraphOrdinal}</p><div class="library-reading-layout"><section class="library-original"><h3>Source passage</h3><div class="library-raw-text">${esc(c.text)}</div></section><aside class="library-summary-note"><h3>Summary</h3><p class="library-note">Generated note about the paragraph containing this passage.</p>${c.paragraphSummary ? prose(c.paragraphSummary) : '<p>No summary available.</p>'}</aside></div><button data-document="${esc(c.documentId)}" class="secondary-button">Read full document</button><details class="library-maintenance"><summary>Technical details</summary><p class="library-source">Document ${esc(c.documentId)}<br>Paragraph ${esc(c.paragraphId)} · passage ${esc(c.chunkId)}</p></details>`
      : '<p>Reading passage…</p>')
  );
}
function searchResults() {
  const slot = state.library?.search,
    value = slot?.value;
  if (!value)
    return (
      status(slot ?? {}) +
      '<p class="activity-empty">Choose a method and submit a question.</p>'
    );
  let html =
    status(slot) +
    `<h3>${esc(value.query)}</h3><p class="library-note">${esc(value.kind)} · ${value.kind === 'conversation' || value.kind === 'recall' || value.kind === 'navigate' ? esc(value.project ?? 'Global') : 'Server corpus'}</p>`;
  if (value.kind === 'conversation') {
    const r = value.reply as LogSearchResponse,
      meta = r.retrieval;
    html += `<div class="library-coverage">${r.hits.length} loaded / ${r.total} ${esc(meta?.totalMeaning ?? 'matches (meaning not reported)')}<br>${r.reach.searched} searched; ${r.reach.ejected} ejected; ${r.reach.recordedOnly} recorded only.<br>${meta ? `Requested ${esc(meta.requestedMode)}; effective ${esc(meta.effectiveMode)} · ${meta.complete ? 'Complete' : 'Incomplete'}${meta.truncated ? ' · truncated' : ''}<br>Provenance: ${esc(meta.provenance)}<br>${esc(coverage(meta.coverage))}<br>Snapshot ${esc(meta.snapshot ?? 'none')} · generation ${esc(meta.generation ?? 'not reported')} · query embedding calls ${meta.queryEmbeddingCalls}${meta.fallback ? `<br>Fallback: ${esc(meta.fallback)}` : ''}` : 'Retrieval provenance and completeness were not reported by this server.'}</div>`;
    html += r.hits
      .map(
        (hit) =>
          `<article class="library-hit"><strong>${esc(hit.kind)} · entry ${hit.ordinal} · turn ${hit.turnOrdinal}</strong><p>${esc(hit.snippet)}</p><p class="library-source">${esc(hit.conversationId)} · ${esc(hit.handle ?? 'speaker not recorded')} · rank ${hit.rank}${hit.supersededBy ? ` · superseded by entry ${hit.supersededBy}` : ''}<br>${hit.evidence ? `Retrieved by ${esc(hit.evidence.retrievedBy)} · passage ${hit.evidence.passagePosition ?? 'not reported'} · source revision ${esc(hit.evidence.sourceRevision ?? 'not reported')}` : 'Evidence metadata not reported.'}</p><button data-conversation="${esc(hit.conversationId)}">Inspect trajectory ${icon('external')}</button></article>`,
      )
      .join('');
    if (
      r.hits.length < r.total &&
      (meta?.effectiveMode === 'lexical' || !!meta?.snapshot)
    )
      html += '<button id="search-more">Load more from this search</button>';
  } else if (value.kind === 'documents') {
    const r = value.reply as RetrievalReplies['document.search'];
    html +=
      `<div class="library-coverage">Mode ${esc(r.mode ?? 'not reported')}; ${r.searchable} searchable; ${r.unsearchable} unsearchable. Up to ${r.limit} hits.</div>` +
      r.hits
        .map(
          (hit) =>
            `<article class="library-hit"><strong>${esc(hit.title || hit.sourceName)} · paragraph ${hit.paragraphOrdinal}</strong><p>${esc(hit.paragraphText)}</p><details><summary>Search details</summary><p class="library-source">Similarity ${hit.similarity} · paragraph ID ${esc(hit.paragraphId)}. Similarity is a retrieval signal, not evidence for a claim.</p></details><button data-chunk="${esc(hit.chunkId)}">Read passage</button><button data-document="${esc(hit.documentId)}">Read document</button></article>`,
        )
        .join('');
  } else if (value.kind === 'retrieve') {
    const r = value.reply as RetrievalReplies['document.retrieve'];
    html +=
      `<p>Up to ${r.limit} passages. Coverage metadata is not provided by this operation.</p>` +
      r.hits
        .map(
          (hit) =>
            `<article class="library-hit"><strong>${esc(hit.chunk.title || hit.chunk.sourceName)} · paragraph ${hit.chunk.paragraphOrdinal}</strong>${prose(hit.chunk.text)}<details><summary>Search details</summary><p>Retrieval score ${hit.score}. This is a search signal, not a confidence score.</p></details><button data-chunk="${esc(hit.chunk.chunkId)}">Read source passage</button></article>`,
        )
        .join('');
  } else if (value.kind === 'rank') {
    const r = value.reply as RetrievalReplies['document.rank'];
    html +=
      `<div class="library-coverage">${r.rankable} rankable documents; ${r.unranked} unranked.</div>` +
      r.documents
        .map(
          (doc) =>
            `<button class="library-row" data-document="${esc(doc.documentId)}"><strong>${esc(doc.title || doc.sourceName)}</strong><small>${esc(doc.sourceName)}</small>${doc.summary ? `<span>${esc(doc.summary)}</span>` : ''}</button>`,
        )
        .join('');
  } else if (value.kind === 'recall') {
    const r = value.reply as RetrievalReplies['memory.recall'];
    html +=
      `<div class="library-coverage">${r.memories.length} returned; ${r.unsearchable} unsearchable memories.</div>` +
      r.memories
        .map(
          (m) =>
            `<button class="library-row" data-memory="${esc(m.id)}"><strong>${esc(m.summary)}</strong><small>${esc(m.home.project ?? 'Global')} · ${esc(m.state)} · formed by ${esc(m.formed.by)}</small><span>${esc(m.body)}</span></button>`,
        )
        .join('');
  } else {
    const r = value.reply as RetrievalReplies['memory.navigate'],
      meta = r.retrieval;
    html += `<div class="library-coverage">${r.complete ? 'Complete' : 'Incomplete'} navigation · level ${esc(r.level)} · ${r.modelCalls} model calls.<br>${meta ? `Tier ${esc(meta.tier)}; seed ${esc(meta.seedMode)}${meta.globalFallback ? ' · global fallback' : ''}<br>${esc(coverage(meta.coverage))}<br>Generation ${esc(meta.generation ?? 'not reported')} · query embedding calls ${meta.queryEmbeddingCalls}${meta.fallback ? `<br>Fallback: ${esc(meta.fallback)}` : ''}` : 'Retrieval coverage and provenance were not reported by this server.'}</div>${prose(r.text)}${r.ids.map((id) => `<button class="library-row" data-memory="${esc(id)}">Read memory ${esc(id)}</button>`).join('')}`;
  }
  return html;
}
for (const view of [
  'manual',
  'sources',
  'documents',
  'memories',
  'search',
] as LibraryView[])
  $(`#library-${view}`).addEventListener('click', () => {
    selectedProposal = '';
    background(
      action({
        action: 'library-view',
        view,
        project: state.library?.project ?? null,
      }),
    );
  });
for (const view of [
  'manual',
  'sources',
  'documents',
  'memories',
  'search',
] as LibraryView[])
  $(`#library-${view}`).addEventListener('keydown', (event) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      const tabs: LibraryView[] = memoryScreen
          ? ['memories', 'search']
          : ['manual', 'sources', 'documents', 'search'],
        next =
          tabs[
            (tabs.indexOf(view) +
              (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) %
              tabs.length
          ];
      if (next === undefined) return;
      $(`#library-${next}`).focus();
      selectedProposal = '';
      background(
        action({
          action: 'library-view',
          view: next,
          project: state.library?.project ?? null,
        }),
      );
    }
  });
$('#library-scope').addEventListener('change', () => {
  selectedProposal = '';
  background(
    action({
      action: 'library-view',
      view: state.library?.view ?? 'memories',
      project: $<HTMLSelectElement>('#library-scope').value || null,
    }),
  );
});
$('#library-refresh').addEventListener('click', () =>
  background(action({ action: 'library-refresh' })),
);
$('#document-filter').addEventListener('submit', (event) => {
  event.preventDefault();
  background(
    action({
      action: 'library-documents',
      query: $<HTMLInputElement>('#document-query').value,
    }),
  );
});
$('#search-form').addEventListener('submit', (event) => {
  event.preventDefault();
  background(
    action({
      action: 'library-search',
      kind: $<HTMLSelectElement>('#search-kind').value as SearchKind,
      query: $<HTMLInputElement>('#search-query').value,
      mode: $<HTMLSelectElement>('#search-mode').value as
        'lexical' | 'semantic' | 'hybrid',
    }),
  );
});
$('#search-kind').addEventListener('change', () => {
  $('#search-mode-label').hidden = !['conversation', 'documents'].includes(
    $<HTMLSelectElement>('#search-kind').value,
  );
});
$('#library-panel').addEventListener('input', remember);
document.addEventListener('click', (event) => {
  const target = event.target as HTMLElement,
    v = state?.library;
  if (!v) return;
  remember();
  const documentId =
    target.closest<HTMLElement>('[data-document]')?.dataset.document;
  if (documentId) {
    background(action({ action: 'library-document', id: documentId }));
  }
  const memory = target.closest<HTMLElement>('[data-memory]')?.dataset.memory;
  if (memory) {
    selectedProposal = '';
    background(action({ action: 'library-memory', id: memory }));
  }
  const chunk = target.closest<HTMLElement>('[data-chunk]')?.dataset.chunk;
  if (chunk) {
    background(action({ action: 'library-chunk', id: chunk }));
  }
  const proposal =
    target.closest<HTMLElement>('[data-proposal]')?.dataset.proposal;
  if (proposal) {
    selectedProposal = proposal;
    stamp = '';
    render();
  }
  const conversation = target.closest<HTMLElement>('[data-conversation]')
    ?.dataset.conversation;
  if (conversation)
    background(action({ action: 'library-conversation', id: conversation }));
  if (target.closest('#documents-more'))
    background(
      action({
        action: 'library-documents',
        query: v.documents.query,
        more: true,
      }),
    );
  if (target.closest('#search-more') && v.search.value)
    background(
      action({
        action: 'library-search',
        kind: v.search.value.kind,
        query: v.search.value.query,
        mode: v.search.value.mode,
        more: true,
      }),
    );
  if (target.closest('#document-citations'))
    background(action({ action: 'library-citations' }));
  if (
    target.closest('#document-stance') &&
    $<HTMLTextAreaElement>('#document-claim').value.trim()
  )
    background(
      askDocument(
        `Does this document support the following claim? Explain what supports or challenges it, cite the source passages, and state any limitations. Claim: ${$<HTMLTextAreaElement>('#document-claim').value}`,
      ),
    );
  if (target.closest('#document-vector-check'))
    background(
      action({
        action: 'library-stance',
        claim: $<HTMLTextAreaElement>('#document-claim').value,
      }),
    );
  const offset = target.closest<HTMLElement>('[data-reader-offset]')?.dataset
    .readerOffset;
  if (offset !== undefined && v.document.value)
    background(
      action({
        action: 'library-source-text',
        id: v.document.value.documentId,
        offset: Number(offset),
      }),
    );
  if (target.closest('#memory-invalidate') && v.memory.value)
    background(
      action({
        action: 'library-maintain',
        kind: 'invalidate',
        identity: memoryIdentity(v.memory.value),
        reason: $<HTMLTextAreaElement>('#memory-reason').value,
      }),
    );
  const resolution =
    target.closest<HTMLElement>('[data-resolve]')?.dataset.resolve;
  if (resolution) {
    const p = v.proposals.value?.find((row) => row.id === selectedProposal);
    if (p)
      background(
        action({
          action: 'library-maintain',
          kind: 'resolve',
          identity: libraryIdentity(p),
          accept: resolution === 'true',
          reason: $<HTMLTextAreaElement>('#proposal-reason').value,
        }),
      );
  }
  const maintenance =
    target.closest<HTMLElement>('[data-maintain]')?.dataset.maintain;
  if (maintenance === 'reembed' || maintenance === 'reconsider')
    background(
      action({
        action: 'library-maintain',
        kind: maintenance,
        identity: libraryIdentity({ project: v.project }),
        reason: '',
      }),
    );
  const link = target.closest<HTMLElement>('[data-web-link]')?.dataset.webLink;
  if (link) {
    event.preventDefault();
    background(action({ action: 'open-link', url: link }));
  }
});
async function askDocument(question: string) {
  const id = state.library?.document.value?.documentId;
  if (!id) return;
  if (!question.trim()) {
    localError = 'Enter a question or claim for the reader.';
    render();
    return;
  }
  if (
    state.jobs.some(
      (job) =>
        job.source === 'information' &&
        job.revision === id &&
        job.task === question &&
        job.status !== 'interrupted',
    )
  ) {
    readerNotice =
      'This question has already been submitted. Its answer or progress is shown below. Edit the question to ask something new.';
    render();
    return;
  }
  if (
    askingDocuments.has(id) ||
    state.jobs.some(
      (job) =>
        job.source === 'information' &&
        job.revision === id &&
        ['starting', 'running', 'cancelling', 'unknown'].includes(job.status),
    )
  )
    return;
  readerNotice = '';
  askingDocuments.add(id);
  render();
  try {
    await action({
      action: 'information',
      operation: 'ask',
      scope: { kind: 'personal', includeShared: true },
      payload: { revision: id, question },
    });
  } finally {
    askingDocuments.delete(id);
    render();
  }
}
document.addEventListener('submit', (event) => {
  if ((event.target as HTMLElement).id === 'document-question-form') {
    event.preventDefault();
    background(askDocument($<HTMLInputElement>('#document-question').value));
  }
});
$('#document-sources').addEventListener('toggle', render);
window.plowshare.subscribe(update);
background(
  window.plowshare.request({ action: 'bootstrap' }).then((reply) => {
    memoryScreen = reply.libraryScreen === 'memories';
    initialized = true;
    if (memoryScreen) $<HTMLSelectElement>('#search-kind').value = 'recall';
    update(reply.state);
  }),
);

window.addEventListener('workspace-refresh', () => {
  if (sourcesVisible) sources.refresh();
  else {
    $('#library-refresh').click();
    if (documentSourcesVisible) documentSources.refresh();
  }
});
