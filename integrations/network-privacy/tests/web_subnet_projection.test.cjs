'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const test = require('node:test');

test('large sweep projections keep total coverage explicit and agent context bounded', async () => {
  const {projectEvidence, manifest} = await import(pathToFileURL(path.join(__dirname,'../network-privacy-watch/orchestrations/investigate_network.js')));
  const source = {scan_id:'fixture-scan', collector:'fixture-collector', mode:'tcp', issues:[], changes:[], snapshot:{tcp:[],dns:[],devices:[]}};
  for(let index=1;index<=254;index++) {
    source.snapshot.devices.push({address:`192.0.2.${index}`,device_id:`ip:192.0.2.${index}`,label:'Example device'});
    for(let port=1;port<=8;port++) source.snapshot.tcp.push({address:`192.0.2.${index}`,port,status:'closed'});
    source.changes.push(`TCP 192.0.2.${index}:443 closed -> open`);
  }
  const original = JSON.stringify(source);
  const result = JSON.parse(projectEvidence(source, original));
  assert.equal(result.projection.complete, false);
  assert.equal(result.projection.counts.devices, 254);
  assert.equal(result.projection.counts.tcp, 2032);
  assert.ok(JSON.stringify(result).length <= 16000);
  assert.match(result.projection.detail, /next_offset/);
  assert.equal(JSON.stringify(source), original);
  assert.equal(manifest['max-model-calls'], 24);
});

test('historical small sources remain byte-identical for assessments', async () => {
  const {projectEvidence} = await import(pathToFileURL(path.join(__dirname,'../network-privacy-watch/orchestrations/investigate_network.js')));
  const text=' {"scan_id":"fixture-scan"} ';
  assert.equal(projectEvidence(JSON.parse(text),text),text);
});
