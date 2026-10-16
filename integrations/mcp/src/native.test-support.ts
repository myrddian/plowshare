import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { decodeNativeConfig } from './native-config.ts';
import type { NativeMcpConfig } from './native-config.ts';
import { rawTool } from './wire.test-support.ts';
import type { McpProtocol } from './contracts.ts';
import { object } from './codec.ts';

interface NativeObservation {
  readonly method: string;
  readonly pid: number;
  readonly child: number | null;
  readonly home: boolean;
  readonly selected: string | null;
  readonly requestId: string | number | null;
  readonly cancelledRequestId: string | number | null;
}
function requestIdentity(value: unknown): value is string | number | null {
  return (
    value === null ||
    typeof value === 'string' ||
    (typeof value === 'number' && Number.isSafeInteger(value))
  );
}
function observation(source: unknown): NativeObservation {
  const row = object(source);
  const requestId = row.requestId,
    cancelledRequestId = row.cancelledRequestId;
  if (
    typeof row.method !== 'string' ||
    typeof row.pid !== 'number' ||
    !Number.isSafeInteger(row.pid) ||
    (row.child !== null &&
      (typeof row.child !== 'number' || !Number.isSafeInteger(row.child))) ||
    typeof row.home !== 'boolean' ||
    (row.selected !== null && typeof row.selected !== 'string') ||
    !requestIdentity(requestId) ||
    !requestIdentity(cancelledRequestId)
  )
    throw new Error('Invalid native wire observation');
  return {
    method: row.method,
    pid: row.pid,
    child: row.child,
    home: row.home,
    selected: row.selected,
    requestId,
    cancelledRequestId,
  };
}

export async function nativeFixture(
  protocol: McpProtocol = '2026-07-28',
  mode = 'echo',
): Promise<{
  config: NativeMcpConfig;
  file: string;
  directory: string;
  seen: () => Promise<readonly NativeObservation[]>;
  close: () => Promise<void>;
}> {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-native-mcp-'));
  const script = join(directory, 'fixture.mjs'),
    log = join(directory, 'wire.jsonl');
  await writeFile(
    script,
    `
import {createInterface} from 'node:readline';
import {appendFileSync} from 'node:fs';
import {spawn} from 'node:child_process';
const protocol=${JSON.stringify(protocol)}, mode=${JSON.stringify(mode)}, tool=${JSON.stringify(rawTool)}, log=${JSON.stringify(log)};
const child=mode==='tree'?spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'}):null;
process.stderr.write('fixture-secret-stderr\\n');
for await(const line of createInterface({input:process.stdin,terminal:false})) {
 const message=JSON.parse(line), method=message.method;
 appendFileSync(log,JSON.stringify({method,pid:process.pid,child:child?.pid??null,home:process.env.HOME!==undefined,selected:process.env.SELECTED??null,requestId:message.id??null,cancelledRequestId:message.params?.requestId??null})+'\\n');
 if(message.id===undefined)continue;
 let result;
 if(method==='server/discover')result={resultType:'complete',supportedVersions:[protocol],capabilities:{tools:{}},ttlMs:0,cacheScope:'public',_meta:{'io.modelcontextprotocol/serverInfo':{name:'fixture',version:'1'}}};
 else if(method==='initialize')result={protocolVersion:protocol,capabilities:{tools:{}},serverInfo:{name:'fixture',version:'1'}};
 else if(method==='tools/list')result={...(protocol==='2026-07-28'?{resultType:'complete',ttlMs:0,cacheScope:'public'}:{}),tools:[tool]};
 else if(method==='tools/call') {
  if(mode==='loss')process.exit(0);
  if(mode==='malformed'){process.stdout.write('fixture-secret-not-json\\n');continue;}
  if(mode==='hang')continue;
  result={...(protocol==='2026-07-28'?{resultType:'complete'}:{}),content:[{type:'text',text:'observed'}],structuredContent:message.params.arguments};
 } else result={};
 process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:message.id,result})+'\\n');
}
process.exit(0);
`,
    { mode: 0o600 },
  );
  const config = decodeNativeConfig({
    owner: { origin: 'https://plowshare.example.invalid', account: 'fixture' },
    instance: 'test-instance',
    profile: 'echo',
    project: 'fixture',
    protocol,
    prefix: 'native_',
    journalDirectory: join(directory, 'journals'),
    timeoutMs: 2000,
    timeoutSeconds: 2,
    shutdownGraceMs: 30,
    transport: {
      kind: 'stdio',
      executable: process.execPath,
      args: [script],
      directory,
      environment: { SELECTED: 'NATIVE_SELECTED' },
    },
  });
  const file = join(directory, 'profile.json');
  await writeFile(file, JSON.stringify(config), { mode: 0o600 });
  return {
    config,
    file,
    directory,
    async seen() {
      try {
        return (await readFile(log, 'utf8'))
          .trim()
          .split('\n')
          .filter(Boolean)
          .map((line) => observation(JSON.parse(line)));
      } catch (error) {
        if (
          error instanceof Error &&
          'code' in error &&
          error.code === 'ENOENT'
        )
          return [];
        throw error;
      }
    },
    async close() {
      await rm(directory, { recursive: true, force: true });
    },
  };
}
