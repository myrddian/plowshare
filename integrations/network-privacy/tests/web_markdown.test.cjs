'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// The renderer is tested through its shipped bundle and DOM boundary. An HTML
// assignment fails the test instead of letting a mock silently accept injection.
class Node {
  constructor(tag, text = '') { this.tag = tag; this.text = text; this.children = []; this.attributes = {}; }
  set textContent(value) { this.text = value; this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  set innerHTML(value) { throw new Error('Markdown must not write HTML'); }
  append(...children) { this.children.push(...children); }
  setAttribute(name, value) { this.attributes[name] = value; }
}
function reader() {
  const context = vm.createContext({URL, document:{createElement:tag => new Node(tag), createTextNode:text => new Node('#text',text)}});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/plowshare_privacy/web/markdown.js'), 'utf8'), context);
  return source => context.PrivacyMarkdown.render(source);
}
function nodes(root, tag) {
  return [...(root.tag === tag ? [root] : []), ...root.children.flatMap(child => nodes(child, tag))];
}
test('reports render headings, nested lists, tables, quotes, code and citations', () => {
  const render = reader();
  const source = '# Privacy review\n\n**Evidence** and *uncertainty* with `file_read`.\n\n3. First\n   - Nested\n4. Second\n\n> Keep source evidence.\n\n| Device | Port |\n| :--- | ---: |\n| TV | 443 |\n\n```json\n{"observed": true}\n```\n\n[Source](https://example.invalid/review)\n\n---';
  const result = render(source);
  for (const tag of ['h3','strong','em','code','ol','ul','blockquote','table','thead','pre','a','hr']) assert.ok(nodes(result,tag).length, `Missing ${tag}`);
  assert.equal(nodes(result,'ol')[0].attributes.start, '3');
  assert.equal(nodes(result,'td')[1].className, 'align-right');
  assert.equal(nodes(result,'pre')[0].textContent, '{"observed": true}');
  assert.equal(nodes(result,'a')[0].attributes.href, 'https://example.invalid/review');
});
test('HTML, unsafe links and images remain inert while explicit web links are safe', () => {
  const render = reader();
  const result = render('<script>alert(1)</script>\n<img src="https://tracker.invalid/pixel" onerror="alert(1)">\n\n![tracking](https://tracker.invalid/image)\n\n[bad](javascript:evil) [data](data:text/html,bad) [file](file:///tmp/a) [secret](https://user:password@example.invalid/) [relative](/api/scans) [safe](https://example.invalid/?q=a&b=c)');
  for (const tag of ['script','img','iframe','object','style']) assert.equal(nodes(result,tag).length, 0);
  assert.match(result.textContent, /<script>alert\(1\)<\/script>/);
  for (const link of nodes(result,'a')) {
    assert.match(link.attributes.href, /^https:\/\/(tracker|example)\.invalid\//);
    assert.equal(link.attributes.rel, 'noreferrer noopener');
    assert.equal(link.attributes.target, '_blank');
    assert.deepEqual(Object.keys(link.attributes).sort(), ['href','rel','target']);
  }
});
test('tool names and partial Markdown remain visible without swallowing text', () => {
  const result = reader()('Keep file_read and file_glob **unfinished\n\n```json\npartial');
  assert.match(result.textContent, /file_read and file_glob \*\*unfinished/);
  assert.match(result.textContent, /partial/);
});
test('a literal <br> in text becomes a line break and nothing else is interpreted', () => {
  const result = reader()('| Item | Observation |\n| --- | --- |\n| Ports | 192.0.2.1 (open)<br>• 192.0.2.2 (open)<BR/>end |\n\nLine one<br />line two <b>bold</b>');
  const cell = nodes(result,'td')[1];
  assert.equal(nodes(cell,'br').length, 2);
  assert.doesNotMatch(cell.textContent, /<br/i);
  assert.match(cell.textContent, /192\.0\.2\.1 \(open\)• 192\.0\.2\.2 \(open\)end/);
  const paragraph = nodes(result,'p').at(-1);
  assert.equal(nodes(paragraph,'br').length, 1);
  // Any other tag stays inert text.
  assert.match(paragraph.textContent, /<b>bold<\/b>/);
  assert.equal(nodes(result,'b').length, 0);
});
module.exports = {reader, nodes};
