import { errorMessage } from 'plowshare-client-ts/binding/values';
import { background } from './background.ts';
import {
  readingSwarmActivity,
  swarmActivityOf,
  SWARM_PAGE,
} from '../logic/swarm.ts';
import { boardCursor } from '../logic/board-explorer.ts';
import {
  detailOf,
  listingBoard,
  readingBoard,
  readingSwarm,
  swarmOf,
  topicsOf,
  type BoardView,
} from '../logic/board.ts';
import {
  boardDescended,
  boardKeyed,
  boardLevel,
  boardSelected,
  boardTarget,
  boardOpened,
  boardRows,
  rowWords,
} from '../logic/board-explorer.ts';
import {
  boardRoom,
  boardScrollLimit,
  describeBoard,
  boardInspector,
} from '../logic/board-wording.ts';
import { cleaned } from '../logic/clean.ts';
import type { Answer, Ask } from '../logic/session.ts';
import { plainOf } from '../logic/tints.ts';
import type { Surface } from './surface.ts';

export interface BoardContext {
  surface: Surface;
  read(ask: Ask): Promise<Answer>;
  print(lines: readonly string[]): void;
  trajectory(conversation: string, label: string): Promise<void>;
  refreshMilliseconds?: number;
}

/** Read-only inspection. Keys never wait for a board read, and closing fences late replies. */
export async function inspectBoard(
  ctx: BoardContext,
  view: BoardView,
  project?: string,
): Promise<void> {
  let b = boardOpened(view, project),
    open = true,
    suspended = false,
    busy = false,
    dirty = false;
  const show = ctx.surface.explore?.bind(ctx.surface),
    exploreKey = ctx.surface.exploreKey?.bind(ctx.surface),
    exploreSize = ctx.surface.exploreSize?.bind(ctx.surface);
  const interactive =
    show !== undefined && exploreKey !== undefined && exploreSize !== undefined;
  const size = () =>
    exploreSize?.call(ctx.surface) ?? { rows: 40, columns: 100 };
  const draw = () => {
    if (open && !suspended && interactive)
      show.call(ctx.surface, describeBoard(b, size()), b.typing);
  };
  const read = async (ask: Ask): Promise<Answer['payload']> => {
    const answer = await ctx.read(ask);
    if (answer.code !== 'OK') throw new Error(answer.said ?? answer.code);
    return answer.payload;
  };
  const refresh = async (): Promise<void> => {
    if (!open || suspended) return;
    if (busy) {
      dirty = true;
      return;
    }
    busy = true;
    try {
      do {
        dirty = false;
        b.loading = true;
        draw();
        const wanted = b.view,
          pages = b.pages;
        // Remember selection by identity rather than its position in a changing list.
        const selected = boardSelected(b);
        if (selected !== undefined) boardLevel(b).at = selected.id;
        try {
          if (wanted === 'board') {
            const topics = new Map<
              string,
              ReturnType<typeof topicsOf>['topics'][number]
            >();
            let offset = 0,
              more = false;
            for (let page = 0; page < pages && open; page++) {
              const fresh = topicsOf(
                await read(listingBoard(b.project, offset)),
              );
              if (fresh.offset !== offset)
                throw new Error('Unexpected board page offset');
              for (const topic of fresh.topics)
                topics.set(topic.topic.id, topic);
              more = fresh.more;
              offset += fresh.topics.length;
              if (!more || fresh.topics.length === 0) break;
            }
            if (!open) return;
            if (b.view !== wanted) {
              dirty = true;
              continue;
            }
            b.topics = [...topics.values()];
            b.more = more;
          } else {
            const fresh = swarmOf(await read(readingSwarm()));
            if (!open) return;
            if (b.view !== wanted) {
              dirty = true;
              continue;
            }
            b.swarm = fresh;
            const rows = boardRows(b),
              start = Math.max(
                0,
                boardCursor(b) - Math.floor(boardRoom(size()) / 2),
              );
            const visible = rows.slice(
              start,
              start + Math.min(SWARM_PAGE, boardRoom(size())),
            );
            const conversations = [
              ...new Set(
                visible.flatMap((row) =>
                  row.kind === 'seat' ? [row.seat.seat.conversation] : [],
                ),
              ),
            ];
            for (
              let at = 0;
              at < conversations.length &&
              open &&
              !suspended &&
              b.view === wanted;
              at += 4
            ) {
              await Promise.all(
                conversations.slice(at, at + 4).map(async (conversation) => {
                  const target = (b.activity[conversation] ??= {});
                  target.loading = true;
                  try {
                    const activity = swarmActivityOf(
                      await read(readingSwarmActivity(conversation)),
                    );
                    if (!open) return;
                    target.value = activity;
                    delete target.error;
                  } catch (error) {
                    if (open)
                      target.error =
                        error instanceof Error
                          ? error.message
                          : errorMessage(error);
                  } finally {
                    target.loading = false;
                  }
                }),
              );
              draw();
            }
            if (!open) return;
            if (b.view !== wanted) {
              dirty = true;
              continue;
            }
          }
          draw();
          const target = boardTarget(b);
          if (wanted === 'board' && target !== undefined && open) {
            const detail = detailOf(await read(readingBoard(target)));
            if (detail.topic.id !== target)
              throw new Error('Unexpected board topic');
            if (!open) return;
            b.details[target] = detail;
            if (boardTarget(b) !== target) dirty = true;
          }
          delete b.error;
          b.updated = new Date().toLocaleTimeString();
        } catch (error) {
          if (open)
            b.error =
              error instanceof Error ? error.message : errorMessage(error);
        } finally {
          b.loading = false;
          draw();
        }
      } while (dirty && open && !suspended);
    } finally {
      busy = false;
    }
  };
  if (!interactive) {
    await refresh();
    ctx.print([
      ...describeBoard(b, size()).slice(0, 3).map(plainOf),
      ...boardRows(b).map((row) => cleaned(rowWords(row))),
      ...boardInspector(b, size().columns - 1).map(plainOf),
    ]);
    open = false;
    return;
  }
  const timer = setInterval(() => {
    if (b.live) background(refresh());
  }, ctx.refreshMilliseconds ?? 4000);
  timer.unref();
  const unresize =
    ctx.surface.onResize?.call(ctx.surface, draw) ?? (() => undefined);
  draw();
  background(refresh());
  try {
    for (;;) {
      const key = await exploreKey.call(ctx.surface);
      if (key === undefined) break;
      delete b.note;
      const previous = boardTarget(b);
      const next = boardKeyed(
        b,
        key,
        boardRoom(size()),
        boardScrollLimit(b, size()),
      );
      b = next.browser;
      if (next.then === 'close') break;
      if (next.then === 'more' && b.view === 'board' && b.more && !b.loading) {
        b.pages++;
        background(refresh());
      }
      if (next.then === 'refresh') background(refresh());
      if (next.then === 'descend') {
        const target = boardTarget(b);
        if (target !== undefined) {
          b = boardDescended(b, target);
          background(refresh());
        }
      }
      if (next.then === 'trajectory') {
        const row = boardSelected(b);
        const conversation =
          row?.kind === 'seat'
            ? row.seat.seat.conversation
            : row?.kind === 'message'
              ? row.message.conversation
              : null;
        const label =
          row?.kind === 'seat'
            ? row.seat.seat.occupant
            : row?.kind === 'message'
              ? row.message.author
              : '';
        if (conversation !== null) {
          suspended = true;
          show.call(ctx.surface, undefined);
          try {
            await ctx.trajectory(conversation, label);
          } catch (error) {
            b.note = `Trajectory unavailable · ${error instanceof Error ? error.message : errorMessage(error)}`;
          } finally {
            suspended = false;
          }
          background(refresh());
        }
      }
      if (previous !== boardTarget(b)) background(refresh());
      draw();
    }
  } finally {
    open = false;
    clearInterval(timer);
    unresize();
    show.call(ctx.surface, undefined);
  }
}
