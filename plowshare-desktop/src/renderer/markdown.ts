import { parse } from 'plowshare-client-ts/operations/markdown';
import type { Block, Span } from 'plowshare-client-ts/operations/markdown';
import { copyButton } from './copy.ts';

export const escapeHtml = (value: unknown) => String(value ?? '').replace(/[&<>"']/g,
  c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/** Only explicit web links may leave the application; raw HTML is always text. */
export function webAddress(address: string): string | undefined {
  try {
    const url = new URL(address);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined;
  } catch { return undefined; }
}
function spansHtml(spans: readonly Span[]): string {
  return spans.map(span => {
    switch (span.kind) {
      case 'text': return escapeHtml(span.text);
      case 'code': return `<code>${escapeHtml(span.text)}</code>`;
      case 'strong': return `<strong>${spansHtml(span.spans)}</strong>`;
      case 'em': return `<em>${spansHtml(span.spans)}</em>`;
      case 'link': {
        const address = webAddress(span.address);
        return address ? `<a href="${escapeHtml(address)}" data-web-link="${escapeHtml(address)}" rel="noreferrer noopener">${spansHtml(span.label)}</a>`
          : `${spansHtml(span.label)} <span class="markdown-disabled-link">(${escapeHtml(span.address)})</span>`;
      }
    }
  }).join('');
}
function blocksHtml(blocks: readonly Block[]): string {
  return blocks.map(block => {
    switch (block.kind) {
      case 'para': return `<p>${spansHtml(block.spans)}</p>`;
      case 'heading': return `<h${block.level}>${spansHtml(block.spans)}</h${block.level}>`;
      case 'rule': return '<hr>';
      case 'quote': return `<blockquote>${blocksHtml(block.blocks)}</blockquote>`;
      case 'code': return `<div class="markdown-code"><div class="code-language"><span>${escapeHtml(block.lang || 'Code')}</span>${copyButton('Copy code', 'code')}</div><pre><code>${escapeHtml(block.text)}</code></pre></div>`;
      case 'list': return block.ordered
        ? `<ol start="${block.start}">${block.items.map(item => `<li>${blocksHtml(item.blocks)}</li>`).join('')}</ol>`
        : `<ul>${block.items.map(item => `<li>${blocksHtml(item.blocks)}</li>`).join('')}</ul>`;
      case 'table': {
        const cells = (row: readonly (readonly Span[])[], tag: 'td' | 'th') => row.map((cell, i) => `<${tag} class="align-${block.align[i] ?? 'left'}">${spansHtml(cell)}</${tag}>`).join('');
        return `<div class="markdown-table"><table><thead><tr>${cells(block.header, 'th')}</tr></thead><tbody>${block.rows.map(row => `<tr>${cells(row, 'td')}</tr>`).join('')}</tbody></table></div>`;
      }
    }
  }).join('');
}
/** The same grammar as TUI, emitted through fixed tags and escaped text/attributes. */
export const markdownHtml = (text: string) => blocksHtml(parse(text));
