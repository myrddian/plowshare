import type { DesktopApi, DesktopState } from '../shared.ts';
import type { InformationOperation, InformationScope } from 'plowshare-client-ts/operations/information';

const escape = (value: unknown) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]!));
const object = (value: unknown): Record<string, unknown> => value && typeof value==='object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const rows = (value: unknown) => Array.isArray(value) ? value.map(object) : [];

/** The library follows durable revision state; reconnecting never resubmits work. */
export function installInformation(api: DesktopApi, state: () => DesktopState, activeProject: () => string) {
  const dialog=document.createElement('dialog');dialog.className='information-dialog';dialog.setAttribute('aria-label','Information library');
  dialog.innerHTML=`<header><h2>Information library</h2><button type="button" data-close aria-label="Close information library">Close</button></header>
    <div class="information-toolbar"><label>Collection <select data-scope></select></label><label>View <select data-view><option value="list">Catalogue</option><option value="inventory">My revisions</option><option value="acquisitions">Acquisitions</option></select></label><button data-refresh>Refresh</button></div>
    <p data-error role="alert" hidden></p><div class="information-workspace"><section><div data-list></div><div class="information-toolbar"><button data-previous>Previous</button><button data-next>Next</button></div></section><section data-detail><p>Select a revision to inspect its processing and evidence.</p></section></div>
    <details><summary>Add information</summary><form data-add><label>Source name<input name="name" required maxlength="512"></label><label>Text<textarea name="text" rows="5"></textarea></label><label>Or a public source URL<input name="url" type="url"></label><button>Add source</button></form></details>
    <details><summary>Retain a draft report or feedback revision</summary><form data-report><label>Report name<input name="name" required></label><label>Report text<textarea name="text" rows="6" required></textarea></label><label>Input revision IDs, comma separated<input name="inputs" required></label><label>Evidence IDs, comma separated<input name="evidence"></label><label>Parent report revision for feedback<input name="feedback"></label><button>Retain draft report</button></form></details>`;
  document.body.append(dialog);
  const select=dialog.querySelector<HTMLSelectElement>('[data-scope]')!;
  const view=dialog.querySelector<HTMLSelectElement>('[data-view]')!;
  const list=dialog.querySelector<HTMLElement>('[data-list]')!;
  const detail=dialog.querySelector<HTMLElement>('[data-detail]')!;
  const error=dialog.querySelector<HTMLElement>('[data-error]')!;
  let offset=0, selected='', acquisition=false, busy=false, epoch=0;
  let sourceOffset=0;
  let sourceWindow: Record<string,unknown>={};
  const requests=new Map<string,string>();
  const scope=(): InformationScope => select.value==='shared'?{kind:'shared'}:select.value==='personal'?{kind:'personal',includeShared:true}:{kind:'project',project:select.value.slice(8),includeShared:true};
  const showError=(reason: unknown) => {error.hidden=false;error.textContent=reason instanceof Error?reason.message:String(reason);};
  async function call(operation: InformationOperation,payload: Record<string,unknown>={}) {
    const answer=await api.request({action:'information',operation,scope:scope(),payload});return answer.information;
  }
  function requestId(operation: string,payload: Record<string,unknown>) {
    const key=JSON.stringify([scope(),operation,payload]);let id=requests.get(key);
    if(!id){id=crypto.randomUUID();requests.set(key,id);}return id;
  }
  async function reload() {
    if(!dialog.open || busy)return;
    const stamp=epoch;
    try {
      const data=rows(await call(view.value as InformationOperation,{offset,limit:20}));
      if(stamp!==epoch || !dialog.open)return;
      list.innerHTML=data.map(row => `<button class="information-row" data-id="${escape(row.id)}" data-acquisition="${view.value==='acquisitions'}"><strong>${escape(row.source_name ?? row.title)}</strong><small>${escape(row.availability ?? row.state)}${row.ordinal?` · revision ${escape(row.ordinal)}`:''}</small></button>`).join('') || '<p>No information in this collection.</p>';
      dialog.querySelector<HTMLButtonElement>('[data-previous]')!.disabled=offset===0;
      dialog.querySelector<HTMLButtonElement>('[data-next]')!.disabled=data.length<20;
      if(selected)await inspect(selected,acquisition,stamp);
    } catch(reason){if(stamp===epoch){list.textContent='This collection is unavailable.';detail.textContent='';showError(reason);}}
  }
  async function inspect(id: string,isAcquisition: boolean,stamp=epoch) {
    if(selected!==id)sourceOffset=0;
    selected=id;acquisition=isAcquisition;
    const value=object(await call('status',isAcquisition?{acquisition:id}:{revision:id}));
    if(stamp!==epoch || selected!==id || acquisition!==isAcquisition || !dialog.open)return;
    if(isAcquisition){
      detail.innerHTML=`<h3>${escape(value.source_name)}</h3><p>${escape(value.state)} · ${escape(value.attempt)} attempts</p><p>${escape(value.url)}</p>${value.error?`<p role="alert">${escape(value.error)}</p>`:''}<p>Processing allowance: ${escape(value.allowance_total)} calls.</p><div class="information-toolbar">${['failed','blocked'].includes(String(value.state))?'<button data-control="retry">Retry acquisition</button>':''}${value.revision_id?`<button data-open-revision="${escape(value.revision_id)}">Open retained revision</button>`:''}</div>`;
      return;
    }
    const steps=rows(value.steps).filter(row => row.generation===value.generation);
    const report=object(value.report);
    const answerJob=state().jobs.filter(job=>job.source==='information' && job.revision===id).at(-1);
    const manage=value.can_manage===true;
    const control=(operation: string,label: string)=>`<button data-control="${operation}">${label}</button>`;
    const management=manage ? [
      ...(value.availability==='active'?[control('retry','Retry incomplete work'),control(value.excluded?'unexclude':'exclude',value.excluded?'Include in discovery':'Exclude from discovery'),control('withdraw','Withdraw'),control('share','Share'),control('unshare','Unshare')]:value.availability!=='deleted'?[control('restore','Restore')]:[]),
      ...(report.status==='draft'?[control('finalise','Finalise report')]:[]),
      ...(value.availability!=='deleted'?[control('delete','Delete retained content')]:[]),
    ].join('') : '';
    detail.innerHTML=`<h3>${escape(value.title ?? value.source_name)}</h3><p>Revision ${escape(value.ordinal)} · ${escape(value.availability)}${report.status?` · ${escape(report.status)} report`:''}</p><p class="information-id">${escape(id)}</p><p>${escape(value.allowance_spent)} / ${escape(value.allowance_total)} model calls spent.</p><table><thead><tr><th>Stage</th><th>State</th><th>Attempts</th></tr></thead><tbody>${steps.map(row=>`<tr><td>${escape(row.stage)}</td><td>${escape(row.compatible===false?`${row.state} · configuration changed; rebuild`:row.state)}</td><td>${escape(row.attempt)}</td></tr>${row.error?`<tr><td colspan="3" role="alert">${escape(row.error)}</td></tr>`:''}`).join('')}</tbody></table>
      <details><summary>Manage this revision</summary><div class="information-toolbar">${management}</div>
      ${manage?`<form data-allowance><label>Processing allowance<input name="total" type="number" min="${escape(value.allowance_spent)}" value="${escape(value.allowance_total)}" required></label><button>Change allowance</button></form>
      <form data-rebuild><label>Rebuild from <select name="stage"><option value="embed">Passage embeddings</option><option value="summarise">Summaries</option><option value="summary_embed">Summary embedding</option></select></label><button>Rebuild</button></form>
      <form data-link><label>Project reference<select name="project">${state().projects.map(row=>`<option>${escape(row.name)}</option>`).join('')}</select></label><button name="direction" value="link">Link</button><button name="direction" value="unlink">Unlink</button></form>
      ${value.source_uri?'<button data-control="refresh">Refresh original URL into a new revision</button>':''}
      <form data-revise><label>Replacement text<textarea name="text" rows="4" required></textarea></label><button>Retain a new revision</button></form>`:''}
      </details><form data-ask><label>Ask this document<input name="question" required></label><button>Ask</button></form><div data-answer>${answerJob?`<p>${escape(answerJob.status)} · ${escape(answerJob.ending ?? answerJob.id)}</p><pre>${escape(answerJob.text || answerJob.detail || 'Waiting for the document answer.')}</pre>`:''}</div>
      <details><summary>Findings and review</summary><pre>${escape(JSON.stringify(report.details ?? {},null,2))}</pre></details><details><summary>Inputs and citations</summary>${(Array.isArray(value.inputs)?value.inputs:[]).map(id=>`<button data-open-revision="${escape(id)}">Input ${escape(id)}</button>`).join('')}${(Array.isArray(value.citations)?value.citations:[]).map(id=>`<button data-evidence="${escape(id)}">Evidence ${escape(id)}</button>`).join('')}<div data-evidence-detail></div></details>
      <details><summary>Stage history</summary><pre>${escape(JSON.stringify(value.events ?? [],null,2))}</pre></details><div data-source></div>`;
    if(value.availability==='active') {
      try {
        const window=object(await call('read',{revision:id,offset:sourceOffset,limit:8192}));
        if(stamp!==epoch || selected!==id || !dialog.open)return;
        const source=detail.querySelector<HTMLElement>('[data-source]');if(!source)return;
        sourceWindow=window;
        source.innerHTML=`<h4>Retained text · ${escape(window.start)}–${escape(window.end)} of ${escape(window.total)}</h4><textarea data-passage readonly rows="12" aria-label="Retained source text">${escape(window.text)}</textarea><div class="information-toolbar"><button data-source-prev ${sourceOffset===0?'disabled':''}>Previous text</button><button data-source-next ${Number(window.end)>=Number(window.total)?'disabled':''}>Next text</button><button data-record-evidence>Record selected quotation</button></div><div data-new-evidence></div>`;
      } catch(reason){ if(stamp===epoch && selected===id){ const source=detail.querySelector<HTMLElement>('[data-source]');if(source)source.textContent=reason instanceof Error?reason.message:String(reason); } }
    }
  }
  async function mutate(operation: InformationOperation,body: Record<string,unknown>) {
    if(busy)return;busy=true;error.hidden=true;
    const stamp=epoch,key=JSON.stringify([scope(),operation,body]);
    try {
      await call(operation,{...body,requestId:requestId(operation,body)});
      requests.delete(key);
    } catch(reason){if(stamp===epoch)showError(reason);}finally{busy=false;await reload();}
  }
  dialog.addEventListener('click',event => {
    const stamp=epoch;
    const reportError=(reason: unknown)=>{if(stamp===epoch && dialog.open)showError(reason);};
    const target=(event.target as HTMLElement).closest<HTMLButtonElement>('button');if(!target)return;
    if(target.hasAttribute('data-close'))dialog.close();
    if(target.hasAttribute('data-source-prev')){sourceOffset=Math.max(0,sourceOffset-8192);void inspect(selected,false).catch(reportError);}
    if(target.hasAttribute('data-source-next')){sourceOffset=Number(sourceWindow.end);void inspect(selected,false).catch(reportError);}
    if(target.hasAttribute('data-record-evidence')){
      const passage=detail.querySelector<HTMLTextAreaElement>('[data-passage]');if(!passage)return;
      if(passage.selectionStart===passage.selectionEnd){showError('Select a quotation in the retained text.');return;}
      const body={revision:selected,start:Number(sourceWindow.start)+passage.selectionStart,end:Number(sourceWindow.start)+passage.selectionEnd,quote:passage.value.slice(passage.selectionStart,passage.selectionEnd),locator:'extracted-text:utf16'};
      const stamp=epoch,id=selected;
      void call('evidence.record',{...body,requestId:requestId('evidence.record',body)}).then(answer=>{
        if(stamp!==epoch || id!==selected)return;
        const place=detail.querySelector<HTMLElement>('[data-new-evidence]');if(place)place.textContent=`Evidence ID: ${String(object(answer).evidence)}`;
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
    if(target.hasAttribute('data-refresh'))void reload();
    if(target.hasAttribute('data-previous')){offset=Math.max(0,offset-20);void reload();}
    if(target.hasAttribute('data-next')){offset+=20;void reload();}
    if(target.dataset.id)void inspect(target.dataset.id,target.dataset.acquisition==='true').catch(reason=>{if(stamp===epoch){detail.textContent='';showError(reason);}});
    if(target.dataset.openRevision)void inspect(target.dataset.openRevision,false).catch(reportError);
    if(target.dataset.control){
      if(target.dataset.control==='delete' && !confirm('Delete this revision’s retained content? This cannot be restored.'))return;
      void mutate(target.dataset.control as InformationOperation,acquisition?{acquisition:selected}:{revision:selected});
    }
  });
  select.addEventListener('change',()=>{epoch++;offset=0;selected='';error.hidden=true;detail.textContent='Select a revision.';void reload();});
  view.addEventListener('change',()=>{epoch++;offset=0;selected='';error.hidden=true;detail.textContent='Select a revision.';void reload();});
  dialog.addEventListener('submit',event=>{
    const stamp=epoch;
    const reportError=(reason: unknown)=>{if(stamp===epoch && dialog.open)showError(reason);};
    event.preventDefault();const form=event.target as HTMLFormElement;const data=new FormData(form);
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
      const stamp=epoch,id=selected;
      void call('ask',{revision:id,question:String(data.get('question'))}).then(answer=>{
        if(stamp!==epoch || selected!==id)return;
        const place=detail.querySelector<HTMLElement>('[data-answer]');if(place)place.textContent=`Document answer job: ${String(object(answer).job)}. Its result will appear here.`;
      }).catch(reportError);
    }
    if(form.hasAttribute('data-allowance'))void mutate('allowance',{revision:selected,maxModelCalls:Number(data.get('total'))});
    if(form.hasAttribute('data-rebuild'))void mutate('rebuild',{revision:selected,stage:String(data.get('stage'))});
  });
  const timer=setInterval(()=>{if(dialog.open && !dialog.querySelector('input:focus,textarea:focus,select:focus'))void reload();},4000);
  dialog.addEventListener('close',()=>{epoch++;selected='';sourceWindow={};detail.textContent='Select a revision.';});
  return {
    open(){
      if(!state().connected || state().mode!=='live')throw new Error('Connect to your server to open the information library.');
      const project=activeProject();
      select.innerHTML='<option value="personal">Personal + shared</option><option value="shared">Shared</option>'+state().projects.map(row=>`<option value="project:${escape(row.name)}">${escape(row.name)}</option>`).join('');
      select.value=project?`project:${project}`:'personal';epoch++;selected='';offset=0;error.hidden=true;dialog.showModal();void reload();
    },
    destroy(){clearInterval(timer);dialog.remove();},
  };
}
