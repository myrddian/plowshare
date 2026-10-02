import { execFileSync, spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
export const git = (...args) => execFileSync('git', args).toString().trim();

/** Native acceptance uses real Git smart HTTP for bytes and WS for every control operation. */
export class SyncFixture {
  rows = new Map(); processes = new Set(); number = 0;
  constructor(directory) { this.directory = directory; }
  row(project) {
    let row = this.rows.get(project);
    if (!row) { row = { enabled:false, state:'OFFLINE', conflicts:[], hub:join(this.directory, `${project}.git`) }; this.rows.set(project, row); }
    return row;
  }
  async ask(type, payload, claimed) {
    const row = this.row(payload.project), url = `/v1/sync/${encodeURIComponent(payload.project)}.git`;
    if (!['union.status','union.conflict.list'].includes(type) && !claimed) return {code:'BAD_REQUEST',said:'This session does not root the project'};
    switch (type) {
      case 'union.status': return {code:'OK',payload:{eligible:true,enabled:row.enabled,state:row.state,syncHidden:[],maxFileBytes:5242880,openConflicts:row.conflicts.length,url}};
      case 'union.conflict.list': return {code:'OK',payload:{conflicts:row.conflicts.map(each=>({baseBlob:null,oursBlob:null,theirsBlob:null,runId:null,openedAt:'2026-10-02T00:00:00Z',...each}))}};
      case 'union.enable':
        await mkdir(this.directory,{recursive:true}); git('init','-q','--bare','-b','main',row.hub); git('--git-dir',row.hub,'config','http.receivepack','true');
        row.state='SYNCING'; return {code:'OK',payload:{url}};
      case 'union.begin': row.state='SYNCING'; return {code:'OK',payload:{url}};
      case 'union.ready':
        if (git('--git-dir',row.hub,'rev-parse','main')!==payload.commit) return {code:'CONFLICT',said:'The hub moved'};
        row.enabled=true;row.state='LIVE';return {code:'OK',payload:{}};
      case 'union.abort': row.state='OFFLINE';return {code:'OK',payload:{}};
      case 'union.disable': row.enabled=false;row.state='OFFLINE';return {code:'OK',payload:{}};
      case 'union.conflict.open': { const n=++this.number;row.conflicts.push({...payload,n});return {code:'OK',payload:{n}}; }
      case 'union.conflict.resolve': row.conflicts=row.conflicts.filter(each=>each.n!==payload.n);return {code:'OK',payload:{}};
      default:return {code:'BAD_REQUEST',said:`Unknown union operation: ${type}`};
    }
  }
  http(req,res) {
    const url=new URL(req.url,'http://fixture');
    const child=spawn('git',['http-backend'],{env:{...process.env,GIT_PROJECT_ROOT:this.directory,GIT_HTTP_EXPORT_ALL:'1',
      PATH_INFO:decodeURIComponent(url.pathname.slice('/v1/sync'.length)),REQUEST_METHOD:req.method,QUERY_STRING:url.search.slice(1),
      CONTENT_TYPE:req.headers['content-type']??'',REMOTE_USER:'fixture',SERVER_PROTOCOL:'HTTP/1.1'}});
    this.processes.add(child); let headers=Buffer.alloc(0),begun=false;
    child.stdin.on('error',()=>{});child.stderr.resume();req.pipe(child.stdin);
    child.stdout.on('data',chunk=>{
      if(begun){res.write(chunk);return;}
      headers=Buffer.concat([headers,chunk]);const boundary=headers.indexOf('\r\n\r\n');if(boundary<0)return;
      const lines=headers.subarray(0,boundary).toString().split('\r\n'),fields={};let status=200;
      for(const line of lines){const split=line.indexOf(':');const name=line.slice(0,split),value=line.slice(split+1).trim();if(name.toLowerCase()==='status')status=Number(value.split(' ')[0]);else fields[name]=value;}
      res.writeHead(status,fields);begun=true;res.write(headers.subarray(boundary+4));headers=Buffer.alloc(0);
    });
    child.on('error',()=>{if(!begun)res.writeHead(500);res.end();});
    child.on('close',()=>{this.processes.delete(child);if(!begun&&!res.headersSent)res.writeHead(500);res.end();});
    res.on('close',()=>{if(!child.killed)child.kill();});
  }
  async edit(project,text) {
    const row=this.row(project),bot=join(this.directory,`agent-${++this.number}`);
    git('clone','-q',row.hub,bot);await writeFile(join(bot,'notes.md'),text);
    git('-C',bot,'-c','user.name=fixture-agent','-c','user.email=fixture@local','commit','-qam','server edit');
    git('-C',bot,'push','-q','origin','main');row.state='OFFLINE';
  }
  close() { for(const child of this.processes)child.kill(); }
}
