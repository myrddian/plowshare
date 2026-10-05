import { displayText } from 'plowshare-client-ts/binding/values';
import { parse } from 'plowshare-client-ts/operations/markdown';
import type { Block, Span } from 'plowshare-client-ts/operations/markdown';
import { copyButton } from './copy.ts';

export const escapeHtml = (value: unknown) =>
  displayText(value ?? '').replace(
    /[&<>"']/g,
    (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[
        c
      ]!,
  );

/** Only explicit web links may leave the application; raw HTML is always text. */
export function webAddress(address: string): string | undefined {
  try {
    const url = new URL(address);
    return ['https:', 'http:'].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
}
function spansHtml(spans: readonly Span[], manualLinks = false): string {
  return spans
    .map((span) => {
      switch (span.kind) {
        case 'text':
          return escapeHtml(span.text);
        case 'code':
          return `<code>${escapeHtml(span.text)}</code>`;
        case 'strong':
          return `<strong>${spansHtml(span.spans, manualLinks)}</strong>`;
        case 'em':
          return `<em>${spansHtml(span.spans, manualLinks)}</em>`;
        case 'link': {
          const chapter =
            /^plowshare-manual:([a-z0-9-]{1,48})(?:#[a-z0-9-]+)?$/.exec(
              span.address,
            )?.[1];
          if (manualLinks && chapter)
            return `<button type="button" class="markdown-manual-link" data-manual-chapter="${escapeHtml(chapter)}">${spansHtml(span.label)}</button>`;
          const address = webAddress(span.address);
          return address
            ? `<a href="${escapeHtml(address)}" data-web-link="${escapeHtml(address)}" rel="noreferrer noopener">${spansHtml(span.label)}</a>`
            : `${spansHtml(span.label)} <span class="markdown-disabled-link">(${escapeHtml(span.address)})</span>`;
        }
      }
    })
    .join('');
}
function blocksHtml(blocks: readonly Block[], manualLinks = false): string {
  return blocks
    .map((block) => {
      switch (block.kind) {
        case 'para':
          return `<p>${spansHtml(block.spans, manualLinks)}</p>`;
        case 'heading':
          return `<h${block.level}>${spansHtml(block.spans, manualLinks)}</h${block.level}>`;
        case 'rule':
          return '<hr>';
        case 'quote':
          return `<blockquote>${blocksHtml(block.blocks, manualLinks)}</blockquote>`;
        case 'code':
          return `<div class="markdown-code"><div class="code-language"><span>${escapeHtml(block.lang || 'Code')}</span>${copyButton('Copy code', 'code')}</div><pre><code>${escapeHtml(block.text)}</code></pre></div>`;
        case 'list':
          return block.ordered
            ? `<ol start="${block.start}">${block.items.map((item) => `<li>${blocksHtml(item.blocks, manualLinks)}</li>`).join('')}</ol>`
            : `<ul>${block.items.map((item) => `<li>${blocksHtml(item.blocks, manualLinks)}</li>`).join('')}</ul>`;
        case 'table': {
          const cells = (row: readonly (readonly Span[])[], tag: 'td' | 'th') =>
            row
              .map(
                (cell, i) =>
                  `<${tag} class="align-${block.align[i] ?? 'left'}">${spansHtml(cell, manualLinks)}</${tag}>`,
              )
              .join('');
          return `<div class="markdown-table"><table><thead><tr>${cells(block.header, 'th')}</tr></thead><tbody>${block.rows.map((row) => `<tr>${cells(row, 'td')}</tr>`).join('')}</tbody></table></div>`;
        }
      }
    })
    .join('');
}
/** The same grammar as TUI, emitted through fixed tags and escaped text/attributes. */
export const markdownHtml = (text: string, manualLinks = false) =>
  blocksHtml(parse(text), manualLinks);
