import { procText } from 'plowshare-client-ts/operations/proc';
import type {
  ProcClient,
  ProcQuery,
} from 'plowshare-client-ts/operations/proc';
import type { QuestionStroke } from '../logic/questions.ts';

/** Local navigation only: every refresh reads the server; leaving never stops or resumes work. */
export async function exploreProc(
  client: ProcClient,
  surface: {
    show(text: string): void;
    view?: ((text: string) => Promise<void>) | undefined;
    choose?:
      | ((
          lines: readonly string[],
          again?: boolean,
        ) => Promise<QuestionStroke | undefined>)
      | undefined;
    close?: (() => void) | undefined;
  },
): Promise<void> {
  let query: ProcQuery = { limit: 12 };
  let focus = 0,
    again = false;
  for (;;) {
    const page = await client.read('proc.list', query);
    if (!surface.choose) {
      surface.show(procText(page));
      return;
    }
    focus = Math.min(focus, Math.max(0, page.conversations.length - 1));
    const rows = page.conversations.map(
      (row, i) =>
        `${i === focus ? '>' : ' '} ${row.state} · ${row.agent ?? row.origin} · ${row.id}`,
    );
    const stroke = await surface.choose(
      [
        `PROC · ${query.visibility ?? 'mine'} · ${page.total} non-archived conversations · ${page.snapshotAt}`,
        ...rows,
        '↑↓ select · Enter inspect · c calls · o tools · q queues · r refresh · n next · b first',
        'p parent · t root · l causal chain · a all · f in flight · s visibility · Esc return',
      ],
      again,
    );
    again = true;
    if (!stroke) return;
    if (stroke.kind === 'escape') {
      surface.close?.();
      return;
    }
    if (stroke.kind === 'up') {
      focus = Math.max(0, focus - 1);
      continue;
    }
    if (stroke.kind === 'down') {
      focus = Math.min(page.conversations.length - 1, focus + 1);
      continue;
    }
    const selected = page.conversations[focus];
    const key = stroke.kind === 'text' ? stroke.text : '';
    if (stroke.kind === 'enter' && selected) {
      const text = procText(
        await client.read('proc.inspect', {
          visibility: query.visibility ?? 'mine',
          conversation: selected.id,
          limit: 50,
        }),
      );
      surface.close?.();
      if (surface.view) await surface.view(text);
      else {
        surface.show(text);
        return;
      }
      again = false;
    } else if (key === 'c' || key === 'q' || key === 'o') {
      const { after: _after, ...selection } = query;
      const text = procText(
        await client.read(
          key === 'c'
            ? 'proc.calls'
            : key === 'o'
              ? 'proc.tools'
              : 'proc.queues',
          selection,
        ),
      );
      surface.close?.();
      if (surface.view) await surface.view(text);
      else {
        surface.show(text);
        return;
      }
      again = false;
    } else if (key === 'n' && page.cursor) {
      query = { ...query, after: page.cursor };
      focus = 0;
    } else if (key === 'b') {
      const { after: _after, ...selection } = query;
      query = selection;
      focus = 0;
    } else if (key === 's') {
      query = {
        limit: 12,
        visibility: query.visibility === 'server' ? 'mine' : 'server',
      };
      focus = 0;
    } else if (key === 'f') {
      const { after: _after, ...selection } = query;
      query = { ...selection, active: !query.active };
      focus = 0;
    } else if (key === 'a') {
      query = { limit: 12, visibility: query.visibility ?? 'mine' };
      focus = 0;
    } else if (key === 'p' && selected?.parent) {
      query = {
        limit: 12,
        visibility: query.visibility ?? 'mine',
        conversation: selected.parent,
      };
      focus = 0;
    } else if (key === 't' && selected) {
      query = {
        limit: 12,
        visibility: query.visibility ?? 'mine',
        root: selected.root,
      };
      focus = 0;
    } else if (key === 'l' && selected?.causationRoot) {
      query = {
        limit: 12,
        visibility: query.visibility ?? 'mine',
        causation: selected.causationRoot,
      };
      focus = 0;
    }
  }
}
