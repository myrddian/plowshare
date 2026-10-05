#!/usr/bin/env python3
"""Source-backed legacy CLI migration audit; --check refuses stale evidence.

This inventories dispatch and spelling. Runtime parity is measured separately
by the TypeScript real-socket client acceptance suites. The retired command
baseline is frozen data; no executable Java client is required.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASELINE = 'test-support/contracts/legacy-cli-baseline.json'
CATALOG = 'sdk/typescript/src/operations/catalog.ts'
DIRECT = 'sdk/typescript/src/operations/direct.ts'
RUN = 'plowshare-cli/src/run.ts'
# Every exceptional spelling is explicit. Adding a Java command without a TS
# mapping fails the audit, rather than silently accepting missing functionality.
ALIASES = {'document show': 'document outline', 'project rename': 'project move',
           'search': 'web search', 'fetch': 'web fetch', 'information': 'information <operation>'}
PAYLOADS = {
    'memory write': 'proposal:{summary,scope,body,formedBy,formedWhere}; project selects tier',
    'memory resolve': 'proposal,accept,by,reason; accept|reject becomes a boolean',
    'memory curate': 'project,maxModelCalls',
    'conversation search': 'q,project,offset,limit; mode:lexical preserves legacy matching',
    'conversation chat': 'conversation,offset,limit',
    'conversation trajectory': 'conversation,offset,limit',
    'conversation context': 'conversation,agent',
    'document retrieve': 'query,document,limit', 'document rank': 'query,limit',
    'document stance': 'document,claim', 'document list': 'q,offset,limit',
    'document show': 'document', 'document search': 'query,limit',
    'document ask': 'document,question,maxModelCalls',
    'document citations': 'conversation,document,limit',
    'search': 'query,pageSize,max,page; preserve max across pages',
    'fetch': 'url,offset', 'project define': 'name,workspace,exclusions',
    'project workspace': 'project,workspace', 'project lend': 'project,roots',
    'project unlend': 'project,roots', 'project rename': 'project,to',
    'project forget': 'project', 'job status': 'job', 'job cancel': 'job',
    'memory read': 'memory; one id per invocation (loop for legacy multi-id reads)',
    'memory recall': 'question,project,limit', 'memory navigate': 'question,project',
    'memory index': 'project', 'memory digest': 'project', 'memory proposals': 'project',
    'conversation list': 'project', 'information': 'scoped operation payload; preserve requestId for mutations',
}

def build():
    read = lambda path: (ROOT / path).read_text()
    baseline = json.loads(read(BASELINE))["commands"]
    catalog = read(CATALOG).split('export const CLI_OPERATIONS = {', 1)[1].split('} as const', 1)[0]
    mappings = dict(re.findall(r"'([^']+)': '([^']+)'", catalog))
    direct = read(DIRECT).split('export const MEMORY_OPERATIONS = {', 1)[1].split('} as const', 1)[0]
    mappings.update({'memory '+verb: frame for verb, frame in re.findall(r"(\w+): '([^']+)'", direct)})
    mappings.update({'conversation search': 'conversation.search', 'job status': 'job.status', 'job cancel': 'job.cancel'})
    rows = []
    for declaration in baseline:
        name = declaration['legacy']
        target = ALIASES.get(name, name)
        frame = 'information.*' if name == 'information' else mappings[target]
        rows.append({**declaration, 'typescript': target, 'frame': frame, 'payload': PAYLOADS[name]})
    assert set(PAYLOADS)=={row['legacy'] for row in rows}, 'Legacy command audit is incomplete/stale'
    rows.extend([
        {'legacy':'run','typescript':'agent run / conversation open + agent run', 'frame':'agent.run',
         'payload':'agent,task,project OR conversation; --root explicitly supplies local file presence',
         'difference':'--wait watches terminal outcome; --watch streams progress. Set budget at conversation.open, not agent.run.'},
        {'legacy':'talk','typescript':'bin/plowshare-talk', 'frame':'conversation.open + agent.run',
         'payload':'existing lightweight TUI; shared Node authentication and WS/file bindings',
         'difference':'Recover explicit conversation and accepted job handles; uncertain submissions are never replayed.'},
    ])
    return {'formatVersion':1, 'kind':'source-audit-not-runtime-proof', 'commands':rows,
            'counts':{'legacyCommands':len(rows),'legacyTableCommands':len(baseline)},
            'legacyExplicitDefaults':{'web.search':{'pageSize':10,'max':30,'page':1},'web.fetch':{'offset':0},'provenance':{'formedBy':'OS user unless --by','formedWhere':'empty unless --where'}},
            'reviewedDifferences':[
                'Headless CLI uses JSON payloads and WS field names; legacy positional/flag grammar is not retained. --server and --url select the explicit server origin, --root replaces --workspace, and global options may precede or follow the command.',
                'Structured Outcome JSON replaces Java prose; raw fields and incomplete/refused outcomes remain visible.',
                'Java exits 0 success, 1 incomplete/nonanswered, 2 usage/refusal/unavailable. TS exits 0 success, 1 refusal/unavailable job ending, 2 usage/auth bootstrap, 3 accepted/running/cancelling, 4 incomplete/nonanswered, 5 unknown/deadline/protocol.',
                'Global scope is explicit with --global or JSON project:null; blank project is refused. Do not rely on inherited PLOWSHARE_PROJECT in migration scripts.',
                'One memory.read per invocation replaces Java multi-id reads; no implicit provenance defaults for JSON writes/resolutions.',
                'Auth/bootstrap stays HTTP; operational reads, mutations and observers are WS. Existing sync Git bytes retain their documented HTTP transport.',
                'PDF/image conversion is server-side; clients stream bytes and read cached text windows.',
            ],
            'evidence':{'sources': {path:hashlib.sha256((ROOT/path).read_bytes()).hexdigest() for path in [BASELINE,CATALOG,DIRECT,RUN]},
                        'runtime':['plowshare-cli/src/socket.test.ts','plowshare-mcp/src/socket.test.ts',
                                   'plowshare-tui/src/view/composition.test.ts','plowshare-desktop/scripts/transport-smoke.mjs']}}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    result=build();target=HERE/'legacy-cli-audit.json'
    if args.check:
        assert json.loads(target.read_text())==result, 'Legacy CLI audit is stale; review source and regenerate'
    else: target.write_text(json.dumps(result,indent=2)+'\n')
    print('Legacy CLI audit verified:',result['counts'])
