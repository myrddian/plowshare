import type { Agent } from '../shared.ts';
import type { FilePresenceState } from '../files-shared.ts';
import type { ContextSnapshot } from 'plowshare-client-ts/operations/usage';
import { escapeHtml as esc } from './markdown.ts';

export const botDisplayName = (agent: Agent) =>
  agent.displayName ||
  agent.name.replace(/[_-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

/** Resolved definition grants and observed context/presence are labelled separately. */
export function botDetails(
  agent: Agent,
  files: FilePresenceState,
  snapshot?: ContextSnapshot,
): string {
  const row = (label: string, value: string) =>
    `<dt>${esc(label)}</dt><dd>${esc(value)}</dd>`;
  return (
    `<summary>Bot details and access</summary><strong>${esc(botDisplayName(agent))}</strong><p>${esc(agent.description)}</p><dl>` +
    row('Identifier', agent.name) +
    row('Definition', agent.origin || 'Origin unavailable from this server') +
    row('Model', agent.model || 'Not reported') +
    row(
      snapshot
        ? 'Tools offered at ' + snapshot.captured_at
        : 'Tools allowed by the resolved definition',
      (snapshot ? snapshot.tools.map((tool) => tool.name) : agent.tools).join(
        ', ',
      ) || 'None',
    ) +
    row(
      'File scopes allowed by the definition',
      agent.scopes.join(', ') || 'None',
    ) +
    row(
      'Local folder access',
      files.status + (files.root ? ' · ' + files.root : ''),
    ) +
    row('Skills', agent.skills?.join(', ') || 'None declared') +
    row('Delegation', agent.calls.join(', ') || 'None') +
    row('Workflows', agent.orchestrations.join(', ') || 'None') +
    `</dl>${agent.withheld.length ? '<p>Withheld: ' + esc(agent.withheld.join('; ')) + '</p>' : ''}` +
    '<p>File access also depends on the current folder connection and your project grants.</p>'
  );
}
