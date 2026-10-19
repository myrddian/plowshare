import { parse } from 'plowshare-client-ts/operations/markdown';

function node(tag, text) {
  const result = document.createElement(tag);
  if (text !== undefined) result.textContent = text;
  return result;
}

function webAddress(address) {
  try {
    const url = new URL(address);
    return ['https:', 'http:'].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : null;
  } catch {
    return null;
  }
}

function appendSpans(parent, spans) {
  for (const span of spans) {
    switch (span.kind) {
      case 'text':
        parent.append(document.createTextNode(span.text));
        break;
      case 'code':
        parent.append(node('code', span.text));
        break;
      case 'strong':
      case 'em': {
        const mark = node(span.kind);
        appendSpans(mark, span.spans);
        parent.append(mark);
        break;
      }
      case 'link': {
        const address = webAddress(span.address);
        const link = node(address ? 'a' : 'span');
        appendSpans(link, span.label);
        if (address) {
          link.setAttribute('href', address);
          link.setAttribute('target', '_blank');
          link.setAttribute('rel', 'noreferrer noopener');
        } else {
          link.append(document.createTextNode(` (${span.address})`));
        }
        parent.append(link);
        break;
      }
    }
  }
}

function appendBlocks(parent, blocks) {
  for (const block of blocks) {
    switch (block.kind) {
      case 'para':
      case 'heading': {
        // The Application owns h1 and panel titles own h2. Document headings
        // keep their hierarchy within the reader, without replacing its shell.
        const tag =
          block.kind === 'para' ? 'p' : `h${Math.min(6, block.level + 2)}`;
        const content = node(tag);
        appendSpans(content, block.spans);
        parent.append(content);
        break;
      }
      case 'code': {
        const pre = node('pre');
        pre.append(node('code', block.text));
        parent.append(pre);
        break;
      }
      case 'quote': {
        const quote = node('blockquote');
        appendBlocks(quote, block.blocks);
        parent.append(quote);
        break;
      }
      case 'rule':
        parent.append(node('hr'));
        break;
      case 'list': {
        const list = node(block.ordered ? 'ol' : 'ul');
        if (block.ordered) list.setAttribute('start', String(block.start));
        for (const item of block.items) {
          const entry = node('li');
          appendBlocks(entry, item.blocks);
          list.append(entry);
        }
        parent.append(list);
        break;
      }
      case 'table': {
        const wrapper = node('div');
        wrapper.className = 'markdown-table';
        const table = node('table');
        const row = (cells, tag) => {
          const line = node('tr');
          cells.forEach((spans, index) => {
            const cell = node(tag);
            cell.className = `align-${block.align[index] || 'left'}`;
            appendSpans(cell, spans);
            line.append(cell);
          });
          return line;
        };
        const head = node('thead');
        head.append(row(block.header, 'th'));
        const body = node('tbody');
        block.rows.forEach((cells) => body.append(row(cells, 'td')));
        table.append(head, body);
        wrapper.append(table);
        parent.append(wrapper);
        break;
      }
    }
  }
}

/** Shared SDK grammar, rendered with DOM nodes rather than an HTML sink.
 * Raw HTML remains text; links require an explicit web origin and user click.
 * No embedded image, iframe or executable content is created from a document.
 */
export function render(source) {
  if (typeof source !== 'string')
    throw new Error('Document text is unavailable.');
  const article = node('article');
  article.className = 'markdown-body';
  appendBlocks(article, parse(source));
  return article;
}
