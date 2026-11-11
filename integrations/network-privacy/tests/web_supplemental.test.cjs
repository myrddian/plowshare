'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// Runs the shipped dashboard renderer against a small DOM boundary that
// records textContent, attributes and children. It has no innerHTML, so any
// attempt to parse markup would show up as a missing node, never as HTML.
function dashboard() {
  const nodes = new Map();
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, {value:'', textContent:'', disabled:false, checked:false, classList:{toggle() {}}, listeners:{}, attributes:{}, setAttribute(name,value) {this.attributes[name] = value;}, focus() {}, scrollIntoView() {}, addEventListener(name,handler) {this.listeners[name] = handler;}, children:[], replaceChildren(...children) {this.children = children;}, querySelector() {return null;}, querySelectorAll() {return [];}, reset() {}});
    return nodes.get(id);
  }
  const create = tag => ({tag, children:[], textContent:'', append(...children) {this.children.push(...children);}});
  const context = vm.createContext({
    document:{getElementById:node, createElement:create, createTextNode:text => ({tag:'#text', textContent:text, children:[]})},
    location:{hash:''}, URLSearchParams, CSS:{escape:value=>value}, PrivacyMarkdown:{render:source=>({source})},
    setInterval() {}, fetch:async () => ({ok:false, status:401}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8'), context);
  return {context, node};
}
const texts = node => [node.textContent, ...(node.children || []).flatMap(texts)].filter(Boolean);
const find = (nodes, tag) => nodes.flatMap(node => [node, ...find(node.children || [], tag)]).filter(node => node.tag === tag);

function section(overrides = {}) {
  return {
    described_at:'2026-11-12T07:10:00Z', updated_at:'2026-11-12T07:10:00Z',
    descriptions:[{source:'upnp', observed_at:'2026-11-12T07:10:00Z', manufacturer:'Sony',
      model_name:'BRAVIA KD-55X85K', model_number:null, firmware:'6.6.0',
      services:['screen casting (DIAL)', 'remote control (Sony IRCC)']}],
    advisories:{status:'searched', checked_at:'2026-11-12T07:10:00Z', model:'BRAVIA KD-55X85K',
      model_source:'device', total:3, omitted:1, unavailable:null,
      advisories:[{cve:'CVE-2026-0001', severity:'HIGH', published_on:'2026-02-03', known_exploited:true,
        description:'Remote code execution in the cast service.'}]},
    vendor_notes:[{manufacturer:'Sony', feature:'Samba Interactive TV', applies_to:'some BRAVIA TV models',
      collects:'Sony describes it as content recognition.', turn_off:'System Preferences → Samba Interactive TV → Disable.',
      confidence:'high', sources:[{title:'Sony support article', url:'https://www.sony.com.au/electronics/support/articles/00182856'}]}],
    ...overrides,
  };
}

test('the device block shows reported facts, advisories and vendor notes with sources', () => {
  const {context} = dashboard();
  context.body = {enabled:true, supplemental:section()};
  const tree = vm.runInContext('renderSupplemental(body)', context);
  const all = tree.flatMap(texts).join('\n');
  assert.match(all, /Reported by the device/);
  assert.match(all, /BRAVIA KD-55X85K/);
  assert.match(all, /screen casting \(DIAL\) · remote control \(Sony IRCC\)/);
  assert.match(all, /UPnP on the local network/);
  assert.match(all, /own claims, not confirmed identity/);
  assert.match(all, /NVD · checked .* · model BRAVIA KD-55X85K from the device’s own description · 3 found/);
  assert.match(all, /CVE-2026-0001/);
  assert.match(all, /High · published 2026-02-03 · known exploited/);
  assert.match(all, /2 more not listed here/);
  assert.match(all, /not proof that this device is affected/);
  assert.match(all, /Samba Interactive TV \(some BRAVIA TV models\)/);
  assert.match(all, /To turn it off: System Preferences → Samba Interactive TV → Disable\. Menus may vary by model year\./);
  const [link] = find(tree, 'a');
  assert.equal(link.textContent, 'Sony support article');
  assert.equal(link.href, 'https://www.sony.com.au/electronics/support/articles/00182856');
  assert.equal(link.rel, 'noopener noreferrer');
});

test('untrusted device strings stay text and unsafe source links are not linked', () => {
  const {context} = dashboard();
  const markup = '<img src=x onerror=alert(1)>';
  context.body = {enabled:true, supplemental:section({
    descriptions:[{...section().descriptions[0], model_name:markup, firmware:'<b>1</b>'}],
    vendor_notes:[{...section().vendor_notes[0], sources:[{title:'Elsewhere', url:'javascript:alert(1)'}]}],
  })};
  const tree = vm.runInContext('renderSupplemental(body)', context);
  // The markup is the text value of one node; nothing was parsed from it.
  assert.ok(find(tree, 'dd').some(node => node.textContent === markup));
  assert.ok(find(tree, 'dd').some(node => node.textContent === '<b>1</b>'));
  assert.equal(find(tree, 'img').length, 0);
  assert.equal(find(tree, 'b').length, 0);
  assert.equal(find(tree, 'a').length, 0);
  assert.match(tree.flatMap(texts).join('\n'), /Source: \nElsewhere/);
  const source = fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/app.js'), 'utf8');
  const start = source.indexOf('function renderSupplemental');
  assert.doesNotMatch(source.slice(start, source.indexOf('\n}\n', start)), /innerHTML|insertAdjacentHTML/);
});

test('empty states say whether descriptions are off, pending or unanswered', () => {
  const {context} = dashboard();
  const empty = {described_at:null, updated_at:null, descriptions:[], advisories:null, vendor_notes:[]};
  context.off = {enabled:false, supplemental:empty};
  assert.match(vm.runInContext('renderSupplemental(off)', context).flatMap(texts).join('\n'),
    /Device descriptions are off — turn them on in Settings\.[\s\S]*Not looked up yet\./);
  context.pending = {enabled:true, supplemental:empty};
  assert.match(vm.runInContext('renderSupplemental(pending)', context).flatMap(texts).join('\n'), /Not described yet/);
  context.silent = {enabled:true, supplemental:{...empty, described_at:'2026-11-12T07:10:00Z',
    advisories:{status:'model_unknown', checked_at:'2026-11-12T07:10:00Z', model:null, model_source:null,
      total:null, omitted:0, unavailable:null, advisories:[]}}};
  const silent = vm.runInContext('renderSupplemental(silent)', context).flatMap(texts).join('\n');
  assert.match(silent, /did not describe itself when last asked/);
  assert.match(silent, /that is not a finding/);
  assert.match(silent, /no public model name was available/);
  assert.doesNotMatch(silent, /Worth checking/);
});

test('device descriptions are a saved monitoring setting that needs quick checks', async () => {
  const {context, node} = dashboard();
  await new Promise(resolve => setImmediate(resolve));
  vm.runInContext("renderMonitorSettings({editable:true,pihole_configured:false,detail:''})", context);
  assert.equal(node('device-descriptions').disabled, false);
  node('device-descriptions').checked = true;
  node('device-descriptions').listeners.input();
  assert.equal(node('quick-scan').checked, true);
  node('quick-scan').checked = false;
  node('quick-scan').listeners.input();
  assert.equal(node('device-descriptions').checked, false);
  node('device-descriptions').checked = true;
  node('quick-scan').checked = true;
  const requests = [];
  context.fetch = async (url, options) => {
    requests.push({url, body:JSON.parse(options.body)});
    return {ok:true, json:async () => ({editable:true, pending:true, detail:'Settings saved.', device_descriptions:true})};
  };
  vm.runInContext('refresh = async () => {}; portSelection = () => [443];', context);
  node('targets').value = '192.0.2.10';
  await node('monitor-form').listeners.submit({preventDefault() {}});
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/monitoring');
  assert.equal(requests[0].body.deviceDescriptions, true);
  assert.equal(requests[0].body.quickScan, true);
  vm.runInContext("renderMonitorSettings({editable:false,pihole_configured:true,detail:''})", context);
  assert.equal(node('device-descriptions').disabled, true);
});
