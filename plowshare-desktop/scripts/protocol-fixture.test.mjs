import assert from 'node:assert/strict';
import { once } from 'node:events';
import test from 'node:test';
import WebSocket from 'ws';
import { fixtureSocket } from '../../scripts/sdk-packet-fixture.mjs';
import { protocolFixture } from './protocol-fixture.mjs';

const selection = (extra = {}) => ({id: 'opening', type: 'transport.dispatch',
  protocol_version: 'plowshare-v1', payload: {mode: 'concurrent-v1'}, ...extra});
const read = (socket) => new Promise(resolve => socket.on('message', bytes => resolve(JSON.parse(bytes.toString()))));

async function open(t, fixture, protocol) {
  const physical = new WebSocket(`${fixture.base.replace('http:', 'ws:')}/v1/events?ticket=fixture-ticket&session=fixture-test`, protocol);
  t.after(() => physical.terminate());
  await once(physical, 'open');
  return {physical, socket: fixtureSocket(physical)};
}

for (const protocol of [undefined, 'plowshare-segments-v1']) {
  test(`Desktop smoke fixture selects concurrent dispatch without counting control (${protocol ?? 'text'})`, {timeout: 5000}, async t => {
    const fixture = await protocolFixture();
    t.after(() => fixture.close());
    const {socket} = await open(t, fixture, protocol);
    const selected = read(socket);
    socket.send(JSON.stringify(selection()));
    assert.deepEqual(await selected, {...selection(), payload: {code: 'OK',
      payload: {mode: 'concurrent-v1', maxPending: 64, maxIdBytes: 128}}});
    assert.equal(fixture.frames.length, 0);
    const replied = read(socket);
    socket.send(JSON.stringify({id: 'projects', type: 'project.list', protocol_version: 'plowshare-v1', payload: {}}));
    assert.equal((await replied).id, 'projects');
    assert.deepEqual(fixture.frames.map(frame => frame.type), ['project.list']);
  });
}

for (const [name, changes] of [
  ['unsupported mode', {payload: {mode: 'ordered'}}],
  ['extra selection field', {payload: {mode: 'concurrent-v1', maxPending: 64}}],
  ['acting authority', {actingFor: 'another-account'}],
  ['wrong protocol', {protocol_version: 'unknown'}],
  ['empty ID', {id: ''}],
  ['boundary whitespace', {id: ' opening'}],
  ['control ID', {id: 'opening\u0085'}],
  ['oversized UTF-8 ID', {id: '😀'.repeat(33)}],
  ['unpaired surrogate ID', {id: '\uD800'}],
]) {
  test(`Desktop smoke fixture refuses ${name} without a domain operation`, {timeout: 5000}, async t => {
    const fixture = await protocolFixture();
    t.after(() => fixture.close());
    const {physical, socket} = await open(t, fixture);
    const closed = once(physical, 'close');
    socket.send(JSON.stringify(selection(changes)));
    assert.equal((await closed)[0], 1008);
    assert.equal(fixture.frames.length, 0);
  });
}

for (const first of ['project.list', 'transport.dispatch']) {
  test(`Desktop smoke fixture refuses dispatch selection after ${first}`, {timeout: 5000}, async t => {
    const fixture = await protocolFixture();
    t.after(() => fixture.close());
    const {physical, socket} = await open(t, fixture);
    const initial = read(socket);
    socket.send(JSON.stringify(first === 'transport.dispatch' ? selection() :
      {id: 'projects', type: first, protocol_version: 'plowshare-v1', payload: {}}));
    await initial;
    const closed = once(physical, 'close');
    socket.send(JSON.stringify(selection({id: 'second-opening'})));
    assert.equal((await closed)[0], 1008);
    assert.deepEqual(fixture.frames.map(frame => frame.type), first === 'project.list' ? [first] : []);
  });
}
