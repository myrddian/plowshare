import { icon } from './icons.ts';
import { markdownHtml } from './markdown.ts';
import { installPaneResize } from './pane-resize.ts';
import type { DesktopApi, DesktopState } from '../shared.ts';
import type { InformationOperation, InformationScope } from 'plowshare-client-ts/operations/information';
import { MANUAL_INDEX, MANUAL_TAG, manualChapterTag, manualRevision } from '../manual.ts';

const escape = (value: unknown) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!));
const object = (value: unknown): Record<string, unknown> => value && typeof value==='object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const rows = (value: unknown) => Array.isArray(value) ? value.map(object) : [];

/** The library follows durable revision state; reconnecting never resubmits work. */
export function installInformation(api: DesktopApi, state: () => DesktopState, activeProject: () => string, host: HTMLElement, options: { kind?: 'source' | 'report'; filterHost?: HTMLElement; manual?: boolean; onManualChapter?: (chapter: string) => void } = {}) {
  const dialog=host; dialog.classList.add('information-panel');
  let active=false, inspectRevision=0, detailSignature='', sourceCanAsk=false;
  let cachedText: { key: string; html: string } | undefined;
  dialog.innerHTML=`<p class="information-intro">Keep the sources behind your work here. Choose a source to read it, ask a question, or save a quotation as evidence.</p>
    <div class="information-reader-toolbar"><div class="information-toolbar" data-collection-controls><label>Show sources from<select data-scope aria-label="Source collection"></select></label><label>Browse<select data-view aria-label="Source view"><option value="list">Sources &amp; reports</option><option value="inventory">My saved versions</option><option value="acquisitions">URL imports</option></select></label><button data-refresh>Refresh sources</button><button data-add-open class="primary-button">Add source</button></div><div data-question hidden></div></div>
    <p data-scope-note class="information-help"></p><p data-error role="alert" hidden></p><p data-notice role="status" hidden></p>
    <details data-faceting class="information-faceting"><summary>Filter sources <span data-facet-total></span></summary><form data-facet-search class="information-toolbar"><label>Search saved text<input name="search" type="search" maxlength="512" placeholder="Words or phrases"></label><button>Search</button><button type="button" data-facet-clear>Clear filters</button></form><div data-facet-selection class="information-toolbar" aria-label="Selected filters"></div><div data-facet-choices class="information-facet-choices"></div><details class="information-tag-relations"><summary>Related tag groups</summary><div data-tag-graph></div></details></details>
    <div class="information-workspace"><section class="information-list-pane" aria-label="Sources"><div data-list></div><div class="information-toolbar information-pagination"><button data-previous>Previous sources</button><button data-next>More sources</button></div></section><section data-detail aria-label="Selected source"><div class="information-empty"><h2>Read, ask, and collect evidence</h2><p>Choose a source from the list to read its text or ask a question grounded in that source.</p><p>Use Add source to save text or import a public web page.</p></div></section></div>
    <dialog data-add-dialog aria-labelledby="source-add-title"><header class="dialog-header"><h2 id="source-add-title">Add a source</h2><button type="button" data-add-close>Close</button></header><p>Save text or import a public web page. Processing uses your server’s configured models.</p><p data-add-error role="alert" hidden></p><form data-add><label>Source name<input name="name" autofocus required maxlength="512" placeholder="A name you will recognise"></label><label>Text<textarea name="text" rows="5" placeholder="Paste the source text"></textarea></label><label>Or a public web page URL<input name="url" type="url" placeholder="https://…"></label><p>When a URL is supplied, the web page is imported instead of the pasted text.</p><button class="primary-button">Save source</button></form></dialog>
    <details class="information-report"><summary>Advanced: save a report with evidence references</summary><p>Use this when you already have source version and evidence IDs. Read and ask about sources above for everyday work.</p><form data-report><label>Report name<input name="name" required></label><label>Report text<textarea name="text" rows="6" required></textarea></label><label>Source version IDs, comma separated<input name="inputs" required></label><label>Evidence IDs, comma separated<input name="evidence"></label><label>Previous report version ID (for feedback)<input name="feedback"></label><button>Save draft report</button></form></details>`;
  const select=dialog.querySelector<HTMLSelectElement>('[data-scope]')!;
  const view=dialog.querySelector<HTMLSelectElement>('[data-view]')!;
  const list=dialog.querySelector<HTMLElement>('[data-list]')!;
  const detail=dialog.querySelector<HTMLElement>('[data-detail]')!;
  const question=dialog.querySelector<HTMLElement>('[data-question]')!;
  const notice=dialog.querySelector<HTMLElement>('[data-notice]')!;
  const addDialog=dialog.querySelector<HTMLDialogElement>('[data-add-dialog]')!;
  const error=dialog.querySelector<HTMLElement>('[data-error]')!;
  let offset=0, selected='', acquisition=false, busy=false, epoch=0;
  let reloading=false, resetGroupDraft=false;
  let filters: Record<string,string|string[]>={};
  const facetNames=['kind','tags','autoTag','tagGroup','author','documentAuthor','when','subtype'] as const;
  const facetLabels: Record<string,string>={kind:'Resource kind',tags:'Your tags',autoTag:'Automatic tags',tagGroup:'Tag group',author:'Owner',documentAuthor:'Document author',when:'Saved month (UTC)',subtype:'Format or language'};
  function renderFacets(value: Record<string,unknown>) {
    const choices=dialog.querySelector<HTMLElement>('[data-facet-choices]')!;
    dialog.querySelector<HTMLElement>('[data-facet-total]')!.textContent=`· ${String(value.total)} ${value.total===1?'version':'versions'}`;
    const facets=object(value.facets);
    const html=facetNames.filter(name=>name!=='kind'||!options.kind).map(name=>{
      const selected=filters[name];
      const available=rows(facets[name]).filter(row=>Array.isArray(selected)?!selected.includes(String(row.value)):selected!==row.value);
      return `<label>${escape(facetLabels[name])}<select data-facet="${name}" aria-label="${escape(facetLabels[name])}" ${available.length?'':'disabled'}><option value="">Narrow by ${escape(facetLabels[name].toLowerCase())}</option>${available.map(row=>`<option value="${escape(row.value)}">${escape(row.value)} (${escape(row.count)})</option>`).join('')}</select>${object(value.hasMore)[name]?'<small>Showing the 100 most frequent values.</small>':''}</label>`;
    }).join('');
    if(choices.innerHTML!==html)choices.innerHTML=html;
    const graph=dialog.querySelector<HTMLElement>('[data-tag-graph]')!;
    const grouped=new Map<string,Record<string,unknown>[]>();
    for(const edge of rows(object(value.tagGraph).edges)){const name=String(edge.group);grouped.set(name,[...(grouped.get(name)??[]),edge]);}
    const graphHtml=[...grouped.entries()].slice(0,20).map(([group,edges])=>`<section><button type="button" data-group-browse="${escape(group)}">${escape(group)}</button><p>${edges.slice(0,12).map(edge=>`${escape(edge.tag)} (${escape(edge.count)})`).join(', ')}${edges.length>12?' …':''}</p></section>`).join('') || '<p>No related tag groups in these results yet.</p>';
    const graphContent=graphHtml+(grouped.size>20||object(value.tagGraph).hasMore?'<small>Showing the most frequent relationships. Narrow the filters to see more.</small>':'');
    if(graph.innerHTML!==graphContent)graph.innerHTML=graphContent;
    const selected=dialog.querySelector<HTMLElement>('[data-facet-selection]')!;
    const selection=Object.entries(filters).flatMap(([name,value])=>(Array.isArray(value)?value:[value]).map(item=>`<button type="button" data-facet-remove="${escape(name)}" data-value="${escape(item)}" aria-label="Remove ${escape(facetLabels[name]??name)} filter ${escape(item)}">${escape(facetLabels[name]??'Search')}: ${escape(item)} ×</button>`)).join('');
    if(selected.innerHTML!==selection)selected.innerHTML=selection;
  }
  const sourceResize = installPaneResize({ container: dialog, pane: dialog.querySelector<HTMLElement>('.information-list-pane')!, other: detail, key: 'sources', label: 'Resize source list', property: '--sources-width', minimum: 220 });
  if (options.kind === 'report') {
    dialog.classList.add('information-reports');
    select.parentElement!.firstChild!.textContent = '';
    select.title = 'Filter report collection';
    dialog.querySelector<HTMLElement>('[data-scope-note]')!.hidden = true;
    select.setAttribute('aria-label', 'Report collection');
    detail.setAttribute('aria-label', 'Selected report');
    dialog.querySelector<HTMLElement>('.information-intro')!.hidden = true;
    view.parentElement!.hidden = true;
    dialog.querySelector<HTMLElement>('[data-add-open]')!.hidden = true;
    dialog.querySelector<HTMLElement>('.information-report')!.hidden = true;
    const refresh = dialog.querySelector<HTMLButtonElement>('[data-refresh]')!;
    refresh.innerHTML = icon('refresh'); refresh.title = 'Refresh reports'; refresh.setAttribute('aria-label', 'Refresh reports');
    if (options.filterHost) {
      options.filterHost.append(select.parentElement!, refresh);
      dialog.querySelector<HTMLElement>('[data-collection-controls]')!.hidden = true;
      refresh.addEventListener('click', () => { detailSignature=''; cachedText=undefined; void reload(); });
    }
    dialog.querySelector<HTMLElement>('[data-previous]')!.textContent = 'Previous reports';
    dialog.querySelector<HTMLElement>('[data-next]')!.textContent = 'More reports';
    detail.innerHTML = '<div class="information-empty"><h2>Generated reports</h2><p>Choose a report to read its findings and supporting evidence.</p></div>';
    dialog.querySelector<HTMLElement>('.information-list-pane')!.setAttribute('aria-label', 'Reports');
  } else if (options.kind === 'source') {
    view.options[0].text = 'Document sources';
    dialog.querySelector<HTMLElement>('.information-report')!.hidden = true;
  }
  const addTitle = addDialog.querySelector<HTMLElement>('h2')!;
  addTitle.id = `source-add-title-${host.id}`; addDialog.setAttribute('aria-labelledby', addTitle.id);
  const emptyDetail=detail.innerHTML;
  const asking=new Set<string>();
  let sourceOffset=0, sourceFormat: 'rendered' | 'source' = 'rendered';
  let sourceWindow: Record<string,unknown>={};
  const requests=new Map<string,string>();
  if (options.manual) {
    dialog.classList.add('information-reports', 'information-manual');
    dialog.querySelector<HTMLElement>('.information-intro')!.textContent = 'Read the Plowshare manual. Choose a chapter or follow a chapter link.';
    select.disabled = true;
    select.parentElement!.hidden = true;
    detail.setAttribute('aria-label', 'Selected manual chapter');
    dialog.querySelector<HTMLElement>('[data-refresh]')!.textContent = 'Refresh chapters';
    dialog.querySelector<HTMLElement>('[data-previous]')!.textContent = 'Previous chapters';
    dialog.querySelector<HTMLElement>('[data-next]')!.textContent = 'More chapters';
    view.parentElement!.hidden = true;
    dialog.querySelector<HTMLElement>('[data-add-open]')!.hidden = true;
    dialog.querySelector<HTMLElement>('.information-report')!.hidden = true;
  }
  const scope=(): InformationScope => select.value==='shared'?{kind:'shared'}:select.value==='personal'?{kind:'personal',includeShared:true}:{kind:'project',project:select.value.slice(8),includeShared:true};
  const showError=(reason: unknown) => {error.hidden=false;error.textContent=reason instanceof Error?reason.message:String(reason);if(addDialog.open){const message=addDialog.querySelector<HTMLElement>('[data-add-error]')!;message.hidden=false;message.textContent=error.textContent;}};
  async function call(operation: InformationOperation,payload: Record<string,unknown>={}) {
    const answer=await api.request({action:'information',operation,scope:scope(),payload});return answer.information;
  }
  function requestId(operation: string,payload: Record<string,unknown>) {
    const key=JSON.stringify([scope(),operation,payload]);let id=requests.get(key);
    if(!id){id=crypto.randomUUID();requests.set(key,id);}return id;
  }
  async function reload() {
    if(!active || busy || reloading || !state().connected)return;
    reloading=true; const stamp=epoch;
    try {
      const faceting=dialog.querySelector<HTMLElement>('[data-faceting]')!;
      faceting.hidden=view.value!=='list';
      // Manual navigation stays in its shared collection even after clearing search filters.
      const filter = options.manual ? { ...filters, tags: [...new Set([...(Array.isArray(filters.tags) ? filters.tags : []), MANUAL_TAG])] } : filters;
      const payload={offset,limit:20,...(view.value==='list'?{filter,...(options.kind?{kind:options.kind}:{})}:{})};
      const [result,facets]=await Promise.all([call(view.value as InformationOperation,payload),view.value==='list'?call('facets',{filter,...(options.kind?{kind:options.kind}:{})}):Promise.resolve(undefined)]);
      const data=rows(result);
      if(stamp!==epoch || !active)return;
      if(facets)renderFacets(object(facets));
      if (view.value === 'list' && options.kind && data.some(row => row.kind !== options.kind)) throw new Error('The server returned a different resource type. Update the server before using this collection.');
      if(stamp!==epoch || !active)return;
      const listHtml=data.map(row => `<button class="information-row ${selected===String(row.id)?'selected':''}" aria-pressed="${selected===String(row.id)}" data-id="${escape(row.id)}" data-acquisition="${view.value==='acquisitions'}"><strong>${escape(row.title ?? row.source_name)}</strong><small>${escape(row.report_status ?? row.availability ?? row.state)}${row.ordinal?` · revision ${escape(row.ordinal)}`:''}</small>${!options.manual&&Array.isArray(row.tags)&&row.tags.length?`<small>Your tags: ${row.tags.map(escape).join(', ')}</small>`:''}${!options.manual&&Array.isArray(row.autoTag)&&row.autoTag.length?`<small>Automatic: ${row.autoTag.map(escape).join(', ')}</small>`:''}</button>`).join('') || (Object.keys(filters).length?'<div class="information-empty"><h3>No matching versions</h3><p>Remove a filter or clear the filters to browse again.</p></div>':options.manual ? '<div class="information-empty"><h3>No manual chapters</h3><p>Ask your administrator to install the shared manual.</p></div>' : options.kind === 'report' ? '<div class="information-empty"><h3>No reports yet</h3><p>Generated reports will appear here when research work finishes.</p></div>' : '<div class="information-empty"><h3>No sources here yet</h3><p>Add a source or choose another collection.</p></div>');
      if(list.innerHTML!==listHtml)list.innerHTML=listHtml;
      dialog.querySelector<HTMLButtonElement>('[data-previous]')!.disabled=offset===0;
      dialog.querySelector<HTMLButtonElement>('[data-next]')!.disabled=data.length<20;
      if(selected)await inspect(selected,acquisition,stamp);
    } catch(reason){if(stamp===epoch){list.textContent='This collection is unavailable.';detail.textContent='';question.hidden=true;question.innerHTML='';showError(reason);}}finally{reloading=false;if(stamp!==epoch&&active)void reload();}
  }
  async function inspect(id: string,isAcquisition: boolean,stamp=epoch) {
    if(selected!==id){question.hidden=true;question.innerHTML='';detail.dataset.sourceId='';sourceOffset=0;sourceFormat='rendered';detailSignature='';detail.innerHTML='<p role="status">Opening source…</p>';}
    const inspection=++inspectRevision;
    selected=id;acquisition=isAcquisition;sourceCanAsk=false;
    const value=object(await call('status',isAcquisition?{acquisition:id}:{revision:id}));
    if(stamp!==epoch || inspection!==inspectRevision || selected!==id || acquisition!==isAcquisition || !active)return;
    for(const row of list.querySelectorAll<HTMLElement>('[data-id]')){row.classList.toggle('selected',row.dataset.id===id);row.setAttribute('aria-pressed',String(row.dataset.id===id));}
    if(isAcquisition){
      question.hidden=true;question.innerHTML='';
      detail.innerHTML=`<h3>${escape(value.source_name)}</h3><p>${escape(value.state)} · ${escape(value.attempt)} attempts</p><p>${escape(value.url)}</p>${value.error?`<p role="alert">${escape(value.error)}</p>`:''}<p>Import budget: ${escape(value.allowance_total)} calls.</p><div class="information-toolbar">${['failed','blocked'].includes(String(value.state))?'<button data-control="retry">Retry URL import</button>':''}${value.revision_id?`<button data-open-revision="${escape(value.revision_id)}">Read imported source</button>`:''}</div>`;
      return;
    }
    const steps=rows(value.steps).filter(row => row.generation===value.generation);
    const extraction=steps.find(row=>row.stage==='extract');
    const derived=steps.find(row=>row.stage==='derive');
    const extracted=!!extraction&&['ready','skipped'].includes(String(extraction.state));
    sourceCanAsk=value.availability==='active'&&extracted&&!!derived&&['ready','skipped'].includes(String(derived.state));
    const extractionFailed=!!extraction&&['failed','blocked','cancelled'].includes(String(extraction.state));
    const blank=String(extraction?.error ?? '').includes('extracted text is blank');
    const textKey=JSON.stringify([scope(),id,value.generation,extraction,sourceOffset]);
    const report=object(value.report);
    const answerJob=state().jobs.filter(job=>job.source==='information' && job.revision===id).at(-1);
    const manage=!options.manual && value.can_manage===true;
    const control=(operation: string,label: string)=>`<button data-control="${operation}">${label}</button>`;
    const management=manage ? [
      ...(value.availability==='active'?[control('retry','Retry incomplete work'),control(value.excluded?'unexclude':'exclude',value.excluded?'Include in discovery':'Exclude from discovery'),control('withdraw','Withdraw'),control('share','Share'),control('unshare','Unshare')]:value.availability!=='deleted'?[control('restore','Restore')]:[]),
      ...(report.status==='draft'?[control('finalise','Finalise report')]:[]),
      ...(value.availability!=='deleted'?[control('delete','Delete retained content')]:[]),
    ].join('') : '';
    const nextSignature=JSON.stringify([id,value,answerJob?.status,answerJob?.text,answerJob?.detail,sourceOffset]);
    if(detailSignature===nextSignature){renderAnswer();return;}
    const sameSource=detail.dataset.sourceId===id;
    const expanded=sameSource?Array.from(detail.querySelectorAll<HTMLDetailsElement>('details')).map(node=>node.open):[];
    const drafts=sameSource?Array.from(dialog.querySelectorAll<HTMLInputElement|HTMLTextAreaElement|HTMLSelectElement>('[data-detail] input[name],[data-detail] textarea[name],[data-detail] select[name],[data-question] input[name]')).map(node=>[node.name,node.value]):[];
    const scroll=detail.scrollTop;
    const renderedScroll=sameSource?detail.querySelector<HTMLElement>('[data-rendered-passage]')?.scrollTop ?? 0:0;
    detail.dataset.sourceId=id; detailSignature=nextSignature;
    const attention=steps.some(row=>row.state==='failed'||row.state==='blocked'||row.state==='ready'&&row.compatible===false);
    const preparing=steps.some(row=>!['ready','skipped'].includes(String(row.state)));
    const legacy=steps.some(row=>row.state==='skipped');
    const questionHelp=sourceCanAsk?'The answer uses this saved source and may take a moment. It uses the configured model.':value.availability!=='active'?'Questions are unavailable for this version.':extractionFailed?'Questions are unavailable because text extraction did not finish.':!extracted?'Questions will be available after text extraction and passage preparation finish.':'The saved text is readable. Questions will be available after passage preparation finishes.';
    const textUnavailable=value.availability!=='active'?'<p>Source text is unavailable for this version.</p>':!extracted?`<h3>${blank?'No readable text was extracted':extractionFailed?'Source text is unavailable':'Preparing source text'}</h3><p>${blank?'The server tried to extract this source but found no readable text.':extractionFailed?'Text extraction did not finish. See Processing details for the server’s reason.':'Text extraction has not finished yet. This view will update when it is ready.'}</p>${extractionFailed&&manage?`<p>${blank?'Open Manage source and paste readable text into Replacement text to save a new version. Retrying the same saved content may fail again.':'Open Manage source to retry incomplete work or supply replacement text as a new version.'}</p><button data-repair-source>Open Manage source</button>`:''}`:'';
    const questionBusy=asking.has(id)||!!answerJob&&['starting','running','cancelling','unknown'].includes(answerJob.status);
    const status= value.availability!=='active'?String(value.availability):attention?'Needs attention':!steps.length?'Processing status unavailable':preparing?'Preparing source':legacy?'Saved legacy text':'Processing complete';
    question.hidden=false;
    question.innerHTML=`<form data-ask data-ready="${sourceCanAsk}" class="information-ask"><label>Ask about this source<input name="question" aria-describedby="source-question-help-${host.id}" placeholder="What does this source say about…?" required></label><button class="primary-button" title="${escape(questionHelp)}" ${questionBusy||!sourceCanAsk?'disabled':''}>${questionBusy?'Reading…':'Ask source'}</button><p class="information-help" id="source-question-help-${host.id}">${escape(questionHelp)}</p></form>`;
    detail.innerHTML=`<header class="information-source-heading"><h2>${escape(value.title ?? value.source_name)}</h2>${value.documentAuthor?`<p class="information-document-author">Document author: ${escape(value.documentAuthor)}${value.documentAuthorSource==='organisation'?' · issuing organisation':value.documentAuthorSource==='account'?' · owner account (fallback)':''}</p>`:''}<p>Version ${escape(value.ordinal)} · ${escape(status)}${report.status?` · ${escape(report.status)} report`:''}</p>${attention?'<p role="status">Some processing needs attention. Open Processing details below for the reason and recovery options.</p>':''}</header>
      <div data-answer role="status">${answerJob?`<p>${escape(answerJob.status==='finished'?'Answer':answerJob.status==='running'?'Finding an answer…':answerJob.status)}</p><div class="markdown-body">${markdownHtml(answerJob.text || answerJob.detail || 'Waiting for the source answer.')}</div>`:''}</div>
      <div data-source>${textUnavailable || (cachedText?.key===textKey?cachedText.html:'<p>Loading source text…</p>')}</div>
      <div class="information-source-tools"><details><summary>${options.kind==='report'?'Evidence':'Evidence and source references'}</summary><p>Saved quotations connect findings to the exact text they came from.</p>${(Array.isArray(value.inputs)?value.inputs:[]).map(id=>`<button data-open-revision="${escape(id)}">Read source version ${escape(id)}</button>`).join('')}${(Array.isArray(value.citations)?value.citations:[]).map(id=>`<button data-evidence="${escape(id)}">Read saved quotation ${escape(id)}</button>`).join('')}<div data-evidence-detail></div></details>
      <details data-manage-source ${manage?'':'hidden'}><summary>Manage source</summary><div class="information-toolbar">${management}</div>
      ${manage?`<form data-allowance><label>Processing budget (maximum model calls)<input name="total" type="number" min="${escape(value.allowance_spent)}" value="${escape(value.allowance_total)}" required></label><button>Update budget</button></form>
      <form data-rebuild><label>Rebuild from <select name="stage"><option value="embed">Passage embeddings</option><option value="summarise">Summaries</option><option value="summary_embed">Summary embedding</option><option value="autoTag">Automatic tags and document author</option><option value="tagGroups">Tag groups</option></select></label><button>Rebuild</button></form>
      <form data-link><label>Use this source in a project<select name="project">${state().projects.map(row=>`<option>${escape(row.name)}</option>`).join('')}</select></label><button name="direction" value="link">Link</button><button name="direction" value="unlink">Unlink</button></form>
      ${value.source_uri?'<button data-control="refresh">Refresh original URL into a new revision</button>':''}
      <form data-revise><label>Replacement text<textarea name="text" rows="4" required></textarea></label><button>Save a new version</button></form>`:''}
      </details>
      <details class="information-processing"><summary>${options.kind==='report'?'Processing':'Processing details'}</summary><p>These steps prepare the saved text for search and questions. “Ready” means that step has finished.</p><p>${escape(value.allowance_spent ?? 'Unknown')} / ${escape(value.allowance_total ?? 'unknown')} model calls used.</p><table><thead><tr><th>Step</th><th>Status</th><th>Attempts</th></tr></thead><tbody>${steps.map(row=>`<tr><td>${escape(({extract:'Extract text',derive:'Organise passages',embed:'Prepare passage search',summarise:'Write summaries',summary_embed:'Prepare summary search',autoTag:'Generate tags and document author',tagGroups:'Group related tags'} as Record<string,string>)[String(row.stage)] ?? row.stage)}</td><td>${escape(row.state==='ready'&&row.compatible===false?`${row.state} · settings changed; rebuild needed`:row.state)}</td><td>${escape(row.attempt)}</td></tr>${row.error?`<tr><td colspan="3" role="alert">${escape(row.error)}</td></tr>`:''}`).join('')}</tbody></table><p class="information-id">Source version ID: ${escape(id)}</p><details><summary>Processing history</summary><pre>${escape(JSON.stringify(value.events ?? [],null,2))}</pre></details>${report.details?`<details><summary>Report review data</summary><pre>${escape(JSON.stringify(report.details,null,2))}</pre></details>`:''}</details></div>
      <details class="information-tag-metadata"><summary>Tags</summary>
      <section class="information-tags"><h3>Tags</h3><p>Your tags: ${Array.isArray(value.tags)&&value.tags.length?value.tags.map(escape).join(', '):'None yet'}</p><p>Automatic tags: ${Array.isArray(value.autoTag)&&value.autoTag.length?value.autoTag.map(escape).join(', '):value.auto_tag_generated&&steps.some(row=>row.stage==='autoTag'&&row.state==='ready')?'No topical tags found':`Not ready${steps.find(row=>row.stage==='autoTag')?` · ${escape(steps.find(row=>row.stage==='autoTag')!.state)}`:''}`}</p>${manage?`<form data-tags><label>Your tags, separated by commas<input name="tags" value="${escape(Array.isArray(value.tags)?value.tags.join(', '):'')}" maxlength="2080"></label><button>Save tags</button></form>`:''}</section>
      <details class="information-tag-groups"><summary>Tag groups · ${value.tagGroupsSource==='manual'?'your override':'automatic'}</summary>${Object.entries(object(value.tagGroups)).map(([group,tags])=>`<p><strong>${escape(group)}</strong>: ${Array.isArray(tags)?tags.map(escape).join(', '):''}</p>`).join('')||'<p>No groups assigned yet.</p>'}${manage?`<form data-tag-groups><label>One group per line: category: tag, tag<textarea name="groups" rows="4" maxlength="35000" placeholder="databases: postgresql, sqlite">${escape(Object.entries(object(value.tagGroups)).map(([group,tags])=>`${group}: ${Array.isArray(tags)?tags.join(', '):''}`).join('\n'))}</textarea></label><button>Save groups</button><button type="button" data-use-auto-groups>Use automatic groups</button><small>Use existing tags. Saving replaces automatic group choices; a blank list removes all groups.</small></form>`:''}</details>
      </details>`;
    Array.from(detail.querySelectorAll<HTMLDetailsElement>('details')).forEach((node,i)=>{node.open=expanded[i]??false;});
    for(const [name,value] of drafts){if(name==='groups'&&resetGroupDraft)continue;const field=Array.from(dialog.querySelectorAll<HTMLInputElement|HTMLTextAreaElement|HTMLSelectElement>('[data-detail] [name],[data-question] [name]')).find(node=>node.name===name);if(field)field.value=value;}
    resetGroupDraft=false;
    detail.scrollTop=scroll;
    applySourceFormat();
    const retainedReport=detail.querySelector<HTMLElement>('[data-rendered-passage]');if(retainedReport)retainedReport.scrollTop=renderedScroll;
    if(value.availability==='active' && extracted && cachedText?.key!==textKey) {
      try {
        const wholeReport=options.kind==='report' || options.manual===true;
        let window=object(await call('read',{revision:id,offset:wholeReport?0:sourceOffset,limit:wholeReport?32768:8192}));
        if(wholeReport) {
          const total=Number(window.total), parts=[String(window.text ?? '')];
          if (options.manual && total > 262144) throw new Error('This manual chapter exceeds the supported size. Ask your server administrator to split it.');
          if(window.revision!==id || Number(window.start)!==0 || Number(window.end)!==parts[0].length || !Number.isSafeInteger(total) || total<parts[0].length) throw new Error('The report read returned inconsistent text offsets.');
          while(Number(window.end)<total) {
            if(stamp!==epoch || inspection!==inspectRevision || selected!==id || !active)return;
            const offset=Number(window.end), next=object(await call('read',{revision:id,offset,limit:32768}));
            const text=String(next.text ?? '');
            if(next.revision!==window.revision || Number(next.start)!==offset || Number(next.end)!==offset+text.length || Number(next.total)!==total || !text.length || Number(next.end)>total) throw new Error('The report read returned inconsistent text offsets.');
            parts.push(text);window=next;
          }
          window={...window,start:0,text:parts.join('')};
        }
        if(stamp!==epoch || inspection!==inspectRevision || selected!==id || !active)return;
        const source=detail.querySelector<HTMLElement>('[data-source]');if(!source)return;
        sourceWindow=window;
        source.innerHTML=`<div class="information-text-heading"><h3>Source text</h3><span class="information-help">${wholeReport?`${escape(window.total)} characters`:`Characters ${escape(Number(window.start)+1)}–${escape(window.end)} of ${escape(window.total)}`}</span>${wholeReport?'<button data-source-format type="button" aria-pressed="false">Show source</button>':''}</div>${wholeReport?`<div data-rendered-passage class="markdown-body information-rendered-report" tabindex="0" role="region" aria-label="${options.manual ? 'Rendered manual chapter' : 'Rendered report'}">${markdownHtml(String(window.text), options.manual)}</div>`:''}<textarea data-passage readonly rows="12" aria-label="Saved source text" ${wholeReport?'hidden':''}>${escape(window.text)}</textarea><div class="information-toolbar information-text-actions">${wholeReport?'':`<button data-source-prev ${sourceOffset===0?'disabled':''}>Previous text</button><button data-source-next ${Number(window.end)>=Number(window.total)?'disabled':''}>Next text</button>`}<button data-record-evidence title="Select a quotation in the source text, then save it as evidence">Save selected quotation</button></div><div data-new-evidence></div>`;
        cachedText={key:textKey,html:source.innerHTML};
        applySourceFormat();
        const renderedReport=detail.querySelector<HTMLElement>('[data-rendered-passage]');if(renderedReport)renderedReport.scrollTop=renderedScroll;
      } catch(reason){
        if(stamp===epoch && inspection===inspectRevision && selected===id && active){
          const source=detail.querySelector<HTMLElement>('[data-source]');
          const message=(reason instanceof Error?reason.message:String(reason)).replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '');
          if(source){source.innerHTML=`<h3>Source text is unavailable</h3><p>${message==='this revision has not been extracted'?'The server has no saved extraction for this version. Open Processing details to check its extraction status.':escape(message)}</p><p>Use Refresh sources to try reading again.</p>`;cachedText={key:textKey,html:source.innerHTML};}
        }
      }
    }
  }
  function applySourceFormat() {
    const rendered=detail.querySelector<HTMLElement>('[data-rendered-passage]');
    const passage=detail.querySelector<HTMLTextAreaElement>('[data-passage]');
    const toggle=detail.querySelector<HTMLButtonElement>('[data-source-format]');
    if(!rendered || !passage || !toggle)return;
    rendered.hidden=sourceFormat==='source';passage.hidden=sourceFormat==='rendered';
    toggle.textContent=sourceFormat==='source'?'Rendered view':'Show source';
    toggle.setAttribute('aria-pressed',String(sourceFormat==='source'));
  }
  async function mutate(operation: InformationOperation,body: Record<string,unknown>) {
    if(busy)return;busy=true;select.disabled=view.disabled=true;error.hidden=true;
    const stamp=epoch,key=JSON.stringify([scope(),operation,body]);
    try {
      await call(operation,{...body,requestId:requestId(operation,body)});
      requests.delete(key); if(operation==='tags.groups')resetGroupDraft=true; detailSignature='';cachedText=undefined; notice.hidden=false; notice.textContent=operation==='acquire'?'URL import accepted. Open URL imports to follow its progress.':operation==='upload'?'Source saved. Choose it from the list to start reading.':'Source change confirmed.'; if(operation==='acquire'||operation==='upload'){addDialog.close(); (addDialog.querySelector('form') as HTMLFormElement).reset(); if(operation==='acquire')view.value='acquisitions';}
    } catch(reason){if(stamp===epoch)showError(reason);}finally{busy=false;select.disabled=view.disabled=false;await reload();}
  }
  dialog.addEventListener('click',event => {
    const stamp=epoch;
    const reportError=(reason: unknown)=>{if(stamp===epoch && active)showError(reason);};
    const target=(event.target as HTMLElement).closest<HTMLButtonElement>('button');if(!target)return;
    if (options.manual && target.dataset.manualChapter) {
      options.onManualChapter?.(target.dataset.manualChapter);
      return;
    }
    if(target.hasAttribute('data-add-open')){addDialog.querySelector<HTMLElement>('[data-add-error]')!.hidden=true;addDialog.showModal();}
    if(target.hasAttribute('data-add-close'))addDialog.close();
    if(target.hasAttribute('data-source-format')){sourceFormat=sourceFormat==='rendered'?'source':'rendered';applySourceFormat();}
    if(target.hasAttribute('data-source-prev')){sourceOffset=Math.max(0,sourceOffset-8192);void inspect(selected,false).catch(reportError);}
    if(target.hasAttribute('data-source-next')){sourceOffset=Number(sourceWindow.end);void inspect(selected,false).catch(reportError);}
    if(target.hasAttribute('data-record-evidence')){
      const passage=detail.querySelector<HTMLTextAreaElement>('[data-passage]');if(!passage)return;
      let start=passage.selectionStart,end=passage.selectionEnd;
      if(passage.hidden) {
        const rendered=detail.querySelector<HTMLElement>('[data-rendered-passage]'), selection=document.getSelection();
        if(!selection?.rangeCount || !rendered?.contains(selection.getRangeAt(0).commonAncestorContainer)){showError('Select a quotation in the report.');return;}
        const quote=selection.toString();start=passage.value.indexOf(quote);end=start+quote.length;
        if(!quote || start<0 || passage.value.indexOf(quote,start+1)!==-1){showError('Use Show source to select the exact quotation when it includes formatting or occurs more than once.');return;}
      }
      if(start===end){showError('Select a quotation in the retained text.');return;}
      const body={revision:selected,start:Number(sourceWindow.start)+start,end:Number(sourceWindow.start)+end,quote:passage.value.slice(start,end),locator:'extracted-text:utf16'};
      const stamp=epoch,id=selected;
      void call('evidence.record',{...body,requestId:requestId('evidence.record',body)}).then(answer=>{
        if(stamp!==epoch || id!==selected)return;
        const place=detail.querySelector<HTMLElement>('[data-new-evidence]');if(place)place.textContent=`Quotation saved. Evidence ID: ${String(object(answer).evidence)}`;
      }).catch(reportError);
    }
    if(target.dataset.evidence){
      const stamp=epoch,id=selected;
      void call('evidence.read',{evidence:target.dataset.evidence}).then(answer=>{
        if(stamp!==epoch || id!==selected)return;
        const value=object(answer),place=detail.querySelector<HTMLElement>('[data-evidence-detail]');
        if(place)place.innerHTML=`<blockquote>${escape(value.quote)}</blockquote><p>${escape(value.locator)} ${escape(value.start_offset)}–${escape(value.end_offset)}</p><button data-open-revision="${escape(value.revision_id)}">Open original retained revision</button>`;
      }).catch(reportError);
    }
    if(target.hasAttribute('data-repair-source')){const manage=detail.querySelector<HTMLDetailsElement>('[data-manage-source]');if(manage){manage.open=true;manage.scrollIntoView({block:'nearest'});manage.querySelector<HTMLTextAreaElement>('[data-revise] textarea')?.focus();}}
    if(target.hasAttribute('data-use-auto-groups'))void mutate('tags.groups',{revision:selected,groups:null});
    if(target.hasAttribute('data-refresh')){detailSignature='';cachedText=undefined;void reload();}
    if(target.hasAttribute('data-previous')){offset=Math.max(0,offset-20);void reload();}
    if(target.hasAttribute('data-next')){offset+=20;void reload();}
    if(target.dataset.id)void inspect(target.dataset.id,target.dataset.acquisition==='true').catch(reason=>{if(stamp===epoch){detail.textContent='';question.hidden=true;question.innerHTML='';showError(reason);}});
    if(target.dataset.openRevision)void inspect(target.dataset.openRevision,false).catch(reportError);
    if(target.dataset.control){
      if(target.dataset.control==='delete' && !confirm('Delete this revision’s retained content? This cannot be restored.'))return;
      void mutate(target.dataset.control as InformationOperation,acquisition?{acquisition:selected}:{revision:selected});
    }
  });
  const collectionNote=()=>{dialog.querySelector<HTMLElement>('[data-scope-note]')!.textContent=select.value==='personal'?'Your personal sources, plus sources shared with you.':select.value==='shared'?'Sources shared with you.':`Sources available in ${select.selectedOptions[0]?.textContent}.`;};
  const resetSelection=()=>{epoch++;offset=0;selected='';detailSignature='';cachedText=undefined;sourceCanAsk=false;error.hidden=true;detail.innerHTML=emptyDetail;question.hidden=true;question.innerHTML='';collectionNote();void reload();};
  const resetFilters=()=>{filters={};const search=dialog.querySelector<HTMLInputElement>('[data-facet-search] input')!;search.value='';resetSelection();};
  select.addEventListener('change',resetFilters);
  dialog.addEventListener('change',event=>{
    const target=event.target as HTMLSelectElement;
    const name=target.dataset.facet;if(!name || !target.value)return;
    if(name==='tags'||name==='autoTag')filters[name]=[...new Set([...(Array.isArray(filters[name])?filters[name] as string[]:[]),target.value])];
    else filters[name]=target.value;
    resetSelection();
  });
  dialog.addEventListener('click',event=>{
    const target=(event.target as HTMLElement).closest<HTMLButtonElement>('button');if(!target)return;
    if(target.dataset.groupBrowse){filters.tagGroup=target.dataset.groupBrowse;resetSelection();}
    if(target.hasAttribute('data-facet-clear'))resetFilters();
    const name=target.dataset.facetRemove;if(!name)return;
    const value=filters[name];
    if(Array.isArray(value)){const remaining=value.filter(item=>item!==target.dataset.value);if(remaining.length)filters[name]=remaining;else delete filters[name];}
    else {delete filters[name];if(name==='search')dialog.querySelector<HTMLInputElement>('[data-facet-search] input')!.value='';}
    resetSelection();
  });
  view.addEventListener('change',resetSelection);
  dialog.addEventListener('submit',event=>{
    const stamp=epoch;
    const reportError=(reason: unknown)=>{if(stamp===epoch && active)showError(reason);};
    event.preventDefault();const form=event.target as HTMLFormElement;const data=new FormData(form);
    if(form.hasAttribute('data-facet-search')){const text=String(data.get('search')??'').trim();if(text)filters.search=text;else delete filters.search;resetSelection();}
    if(form.hasAttribute('data-tags'))void mutate('tags',{revision:selected,tags:String(data.get('tags')??'').split(',').map(tag=>tag.trim()).filter(Boolean)});
    if(form.hasAttribute('data-tag-groups')){
      const groups: Record<string,string[]>=Object.create(null);
      for(const line of String(data.get('groups')??'').split('\n').map(line=>line.trim()).filter(Boolean)){
        const colon=line.indexOf(':');if(colon<1){showError('Use category: tag, tag on each line.');return;}
        const group=line.slice(0,colon).trim(),tags=line.slice(colon+1).split(',').map(tag=>tag.trim()).filter(Boolean);
        if(!tags.length){showError('Each group needs at least one existing tag.');return;}
        groups[group]=[...new Set([...(groups[group]??[]),...tags])];
      }
      void mutate('tags.groups',{revision:selected,groups});
    }
    if(form.hasAttribute('data-report')){
      const ids=(name: string)=>String(data.get(name) ?? '').split(',').map(id=>id.trim()).filter(Boolean);
      const feedback=String(data.get('feedback') ?? '').trim();
      void mutate('record.report',{name:String(data.get('name')),text:String(data.get('text')),inputs:ids('inputs'),evidence:ids('evidence'),...(feedback?{feedback}:{})});
    }
    if(form.hasAttribute('data-add')){
      const name=String(data.get('name') ?? ''),url=String(data.get('url') ?? '').trim(),text=String(data.get('text') ?? '');
      if(!url && !text.trim()){showError('Enter source text or a URL.');return;}
      void mutate(url?'acquire':'upload',url?{name,url}:{name,text});
    }
    if(form.hasAttribute('data-link'))void mutate((event as SubmitEvent).submitter?.getAttribute('value')==='unlink'?'unlink':'link',{revision:selected,collectionProject:String(data.get('project'))});
    if(form.hasAttribute('data-revise'))void mutate('revise',{revision:selected,text:String(data.get('text'))});
    if(form.hasAttribute('data-ask')){
      if(!sourceCanAsk)return;
      const stamp=epoch,id=selected;
      const question=String(data.get('question'));
      if(asking.has(id)||state().jobs.some(job=>job.source==='information'&&job.revision===id&&['starting','running','cancelling','unknown'].includes(job.status)))return;
      if(state().jobs.some(job=>job.source==='information'&&job.revision===id&&job.task===question&&job.status!=='interrupted')){notice.hidden=false;notice.textContent='This question has already been submitted. Its answer or progress is shown below. Edit the question to ask something new.';return;}
      asking.add(id); const button=form.querySelector<HTMLButtonElement>('button');if(button)button.disabled=true;
      void call('ask',{revision:id,question}).then(answer=>{
        if(stamp!==epoch || selected!==id)return;
        const place=detail.querySelector<HTMLElement>('[data-answer]');if(place)place.textContent=`Your question was accepted. The answer will appear here.`;
      }).catch(reportError).finally(()=>{asking.delete(id);if(stamp===epoch)renderAnswer();});
    }
    if(form.hasAttribute('data-allowance'))void mutate('allowance',{revision:selected,maxModelCalls:Number(data.get('total'))});
    if(form.hasAttribute('data-rebuild'))void mutate('rebuild',{revision:selected,stage:String(data.get('stage'))});
  });
  function renderAnswer(snapshot=state()) {
    if(!active || !selected || acquisition)return;
    const job=snapshot.jobs.filter(row=>row.source==='information'&&row.revision===selected).at(-1);
    const place=detail.querySelector<HTMLElement>('[data-answer]');
    if(place&&job){const html=`<p>${escape(job.status==='finished'?'Answer':job.status==='running'?'Finding an answer…':job.status)}</p><div class="markdown-body">${markdownHtml(job.text||job.detail||'Waiting for the source answer.')}</div>`;if(place.innerHTML!==html)place.innerHTML=html;}
    const button=question.querySelector<HTMLButtonElement>('[data-ask] button');if(button)button.disabled=!sourceCanAsk||asking.has(selected)||!!job&&['starting','running','cancelling','unknown'].includes(job.status);
  }
  const unsubscribe=api.subscribe(renderAnswer);
  const timer=setInterval(()=>{if(active && !dialog.querySelector('input:focus,textarea:focus,select:focus'))void reload();},4000);
  const close=()=>{active=false;epoch++;inspectRevision++;selected='';detailSignature='';cachedText=undefined;sourceCanAsk=false;sourceWindow={};dialog.hidden=true;if(options.filterHost)options.filterHost.hidden=true;addDialog.close();detail.innerHTML=emptyDetail;question.hidden=true;question.innerHTML='';};
  return {
    open(revision?: string, chapter = MANUAL_INDEX){
      active=true;dialog.hidden=false;if(options.filterHost)options.filterHost.hidden=false;
      const project=activeProject();
      select.innerHTML=`<option value="personal">${options.kind==='report'?'My + shared':'My collection + shared'}</option><option value="shared">Shared with me</option>`+state().projects.map(row=>`<option value="project:${escape(row.name)}">${escape(row.name)}</option>`).join('');
      select.value=options.manual?'shared':project?`project:${project}`:'personal';question.hidden=true;question.innerHTML='';epoch++;selected='';offset=0;detailSignature='';cachedText=undefined;sourceCanAsk=false;error.hidden=true;collectionNote();
      detail.innerHTML = emptyDetail;
      if(!state().connected || state().mode!=='live'){detail.innerHTML='';list.textContent=`Connect to your server in the main window to read ${options.manual ? 'the manual' : options.kind === 'report' ? 'reports' : 'sources'}.`;return;}
      if (options.manual) {
        const stamp = epoch;
        filters = {};
        view.value = 'list';
        detail.innerHTML = '<p role="status">Opening the manual…</p>';
        // Resolve the stable supplied name/tag afresh; updates may have a new revision UUID.
        void (async () => {
          const result = await call('list', { kind: 'source', filter: { tags: [MANUAL_TAG, manualChapterTag(chapter)] }, offset: 0, limit: 2 });
          if (stamp !== epoch || !active) return;
          if (!Array.isArray(result)) throw new Error('The manual catalogue is unavailable.');
          const id = manualRevision(result, chapter);
          if (id) await inspect(id, false, stamp);
          else detail.innerHTML = '<div class="information-empty"><h2>Manual unavailable</h2><p>Ask your server administrator to install or update the Plowshare manual.</p></div>';
        })().catch(reason => {
          if (stamp === epoch && active) { detail.innerHTML = ''; showError(reason); }
        });
      } else if(revision){const stamp=epoch;void inspect(revision,false,stamp).catch(reason=>{if(stamp===epoch && active)showError(reason);});}
      void reload();
    },
    refresh(){if(active)void reload();},
    close,
    destroy(){close();sourceResize.destroy();unsubscribe();clearInterval(timer);dialog.replaceChildren();},
  };
}
