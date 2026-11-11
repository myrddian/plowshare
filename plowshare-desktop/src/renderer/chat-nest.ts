/** Move the existing chat panel into an Application hub nest and back. */

export type NestHost = {
  parentElement: NestHost | null;
  classList: { add(name: string): void; remove(name: string): void };
  // HTMLElement.append accepts Node | string; tests use a small stand-in.
  append(...nodes: NestHost[]): void;
};

/** Live chat element or a NestHost stand-in used in unit tests. */
export type ChatHost = NestHost | HTMLElement;

const nestedClass = 'application-nested';

export function mountChat(chat: ChatHost, slot: ChatHost) {
  const panel = chat as NestHost;
  const target = slot as NestHost;
  if (panel.parentElement !== target) target.append(panel);
  panel.classList.add(nestedClass);
}

export function restoreChat(chat: ChatHost, home: ChatHost) {
  const panel = chat as NestHost;
  const target = home as NestHost;
  if (panel.parentElement !== target) target.append(panel);
  panel.classList.remove(nestedClass);
}

export type ConversationRef = {
  id: string;
  project?: string | null;
  title?: string | null;
};

export function conversationsForProject(
  conversations: ConversationRef[],
  project: string,
  title: (id: string) => string,
  selected = '',
) {
  return conversations
    .filter((row) => (row.project ?? '') === project)
    .map((row) => ({
      id: row.id,
      title: row.title?.trim() || title(row.id),
      current: row.id === selected,
    }));
}
