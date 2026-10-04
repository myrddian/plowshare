import { test } from 'node:test';
import assert from 'node:assert/strict';
import { markdownHtml, webAddress } from './markdown.ts';

test('chapter navigation is available only in the manual reader', () => {
  const source = '[Hooks](plowshare-manual:12-hooks) **[Index](plowshare-manual:00-index)**';
  assert.doesNotMatch(markdownHtml(source), /data-manual-chapter/);
  const html = markdownHtml(source, true);
  assert.match(html, /data-manual-chapter="12-hooks"/);
  assert.match(html, /data-manual-chapter="00-index"/);
  assert.doesNotMatch(markdownHtml('[Bad](plowshare-manual:../index)', true), /data-manual-chapter/);
  assert.doesNotMatch(markdownHtml('```md\n' + source + '\n```', true), /data-manual-chapter/);
});

test('TUI Markdown emits headings, nested lists, tables, quotes and code fences', () => {
  const source = ['## A plan', '', '**Bold** and *emphasis* with `file_read`.', '',
    '1. First', '   - Nested item', '2. Second', '', '> Keep the evidence.', '',
    '| Step | Evidence |', '| :--- | ---: |', '| Read | source |', '',
    '```text', '  +------+', '  | keep |', '  +------+', '```', '', '---'].join('\n');
  const html = markdownHtml(source);
  for (const tag of ['h2', 'strong', 'em', 'ol', 'ul', 'blockquote', 'table', 'thead', 'pre', 'code']) assert.match(html, new RegExp(`<${tag}[ >]`));
  assert.match(html, /class="align-right"/);
  assert.match(html, /  \+------\+/);
  assert.match(html, /<hr>/);
});

test('raw HTML and unsafe link schemes never become executable markup', () => {
  const html = markdownHtml('<img src=x onerror=alert(1)>\n<script>bad()</script>\n\n[safe](https://example.invalid/?q=a&x=b) [bad](javascript:evil) [secret](https://user:password@example.invalid/)');
  assert.doesNotMatch(html, /<(img|script)\b/);
  assert.match(html, /&lt;img/);
  assert.match(html, /data-web-link="https:\/\/example.invalid\/\?q=a&amp;x=b"/);
  assert.doesNotMatch(html, /href="javascript:|href="https:\/\/user:/);
  for (const address of ['javascript:evil', 'data:text/html,bad', 'file:///tmp/a', 'https://user:password@example.invalid', '//example.invalid']) assert.equal(webAddress(address), undefined);
});

test('streaming fragments and snake_case identifiers remain visible', () => {
  assert.match(markdownHtml('Keep file_read and file_glob **unfinished'), /file_read and file_glob \*\*unfinished/);
  assert.doesNotMatch(markdownHtml('file_read and file_glob'), /<em>/);
  const html = markdownHtml('```json\n{"source": "unfinished"}');
  assert.match(html, /unfinished/);
  assert.match(markdownHtml('```html\n<script>literal</script>\n```'), /&lt;script&gt;literal&lt;\/script&gt;/);
});


test('numbered research citations and source titles are usable web links', () => {
  const html = markdownHtml('The observation remains qualified [[1](https://example.test/report)] and [[2](https://example.test/a%28b%29)].\n\n**[1]** [Official report](https://example.test/report)');
  assert.match(html, /data-web-link="https:\/\/example\.test\/report"/);
  assert.match(html, /data-web-link="https:\/\/example\.test\/a%28b%29"/);
  assert.match(html, />Official report<\/a>/);
  assert.doesNotMatch(html, /evidence:/);
});
