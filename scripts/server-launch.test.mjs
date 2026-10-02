import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, copyFile, rm, readdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { setTimeout as pause } from 'node:timers/promises';

test('server launch snapshots survive replacing or cleaning the build jar and identical launches reuse the snapshot', { timeout: 10000 }, async () => {
  const directory = await mkdtemp(join(tmpdir(), 'plowshare-server-launch-'));
  const bin = join(directory,'bin'), libs = join(directory,'plowshare-server','build','libs'), cache = join(directory,'runtime');
  let child;
  try {
    await mkdir(bin,{recursive:true}); await mkdir(libs,{recursive:true});
    await copyFile(resolve('bin/plowshare'),join(bin,'plowshare')); await writeFile(join(directory,'secrets.env'),'',{mode:0o600});
    await writeFile(join(libs,'server.jar'),'original server bytes'); await writeFile(join(libs,'server-plain.jar'),'plain jar must not launch');
    const marker = join(directory,'marker'), proceed = join(directory,'proceed');
    await writeFile(join(bin,'java'), `#!/usr/bin/env node\nimport fs from 'node:fs';\nconst jar=process.argv[3];\nfs.writeFileSync(process.env.FIXTURE_MARKER,jar);\nwhile(!fs.existsSync(process.env.FIXTURE_PROCEED)) await new Promise(resolve=>setTimeout(resolve,10));\nprocess.stdout.write(fs.readFileSync(jar));\n`, {mode:0o755});
    // Use an .mjs extension via a tiny executable wrapper for Node's module mode.
    await copyFile(join(bin,'java'),join(bin,'java.mjs'));
    await writeFile(join(bin,'java'),'#!/bin/sh\nexec node "$(dirname "$0")/java.mjs" "$@"\n',{mode:0o755});
    const env = { ...process.env, PATH: bin + ':' + process.env.PATH, PLOWSHARE_RUNTIME_DIR: cache, PLOWSHARE_SECRETS: join(directory,'secrets.env'), PLOWSHARE_CONSOLE_TOKEN: 'launch-fixture', FIXTURE_MARKER: marker, FIXTURE_PROCEED: proceed };
    const launch = () => {
      child = spawn('bash',[join(bin,'plowshare'),'--no-build'],{env}); let output='',error='';
      child.stdout.on('data',bytes=>{output+=bytes}); child.stderr.on('data',bytes=>{error+=bytes});
      return new Promise((resolve,reject)=>{child.on('error',reject);child.on('close',code=>code===0?resolve(output):reject(new Error(error)));});
    };
    let running = launch(), path; running.catch(() => {});
    for(let i=0;i<200;i++){try{path=await readFile(marker,'utf8');break;}catch{await pause(10);}}
    assert.ok(path,'The launcher reached Java'); assert.match(path,new RegExp(cache+'/[a-f0-9]{64}\\.jar$'));
    await writeFile(join(libs,'server.jar'),'replacement bytes'); await rm(join(libs,'server.jar'));
    await writeFile(proceed,'go'); assert.equal(await running,'original server bytes');
    await writeFile(join(libs,'server.jar'),'original server bytes'); assert.equal(await launch(),'original server bytes');
    assert.deepEqual((await readdir(cache)).filter(name=>name.endsWith('.jar')), [path.split('/').at(-1)]);
    await writeFile(join(libs,'server.jar'),'new server bytes'); assert.equal(await launch(),'new server bytes');
    assert.equal((await readdir(cache)).filter(name=>name.endsWith('.jar')).length,2);
    assert.equal((await readdir(cache)).some(name=>name.startsWith('.launch.')),false);
  } finally {child?.kill();await rm(directory,{recursive:true,force:true});}
});
