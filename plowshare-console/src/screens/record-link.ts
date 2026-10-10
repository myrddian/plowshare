import { el } from './dom';

/** Local deep links contain only an owning record ID, never tokens or an effect. */
export function recordLink(
  view: 'chat' | 'jobs' | 'approvals',
  id: string,
  label: string,
): HTMLAnchorElement {
  const link = document.createElement('a');
  link.href = `#${view}?${new URLSearchParams({ record: id }).toString()}`;
  link.textContent = label;
  return link;
}

export function recordLinks(
  conversation: string | null | undefined,
  job?: string | null,
): HTMLElement {
  const links = el('div', 'record-links');
  if (conversation)
    links.append(
      recordLink('chat', conversation, 'Open retained conversation'),
    );
  if (job) links.append(recordLink('jobs', job, 'Inspect job'));
  return links;
}
