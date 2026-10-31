import { watch } from 'node:fs';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import type { Answering } from 'plowshare-client-ts/binding/channel';
import {
  REPLY_OK,
  SOURCE,
  type FileReply,
  type FileRequest,
} from 'plowshare-client-ts/binding/files';

export interface WorkspaceWatchEvents {
  subscribe(
    root: string,
    changed: (filename: string | null) => void,
    unavailable: () => void,
  ): () => void;
}

const nativeEvents: WorkspaceWatchEvents = {
  subscribe(root, changed, unavailable) {
    const watcher = watch(
      root,
      { recursive: true, persistent: false },
      (_event, filename) => changed(filename),
    );
    watcher.on('error', () => {
      watcher.close();
      unavailable();
    });
    return () => watcher.close();
  },
};

export interface WorkspaceWatch {
  /** Seed the bounded baseline from an already authorized source-metadata response. */
  observed(request: FileRequest, reply: FileReply): void;
  close(): void;
}

/**
 * Filesystem events are hints. Hash through the same fenced source reader before notifying;
 * no conversion, indexing or model work runs here. Missing filenames/overflow require server
 * reconciliation. The server remains responsible for authorization and exact-hash reuse.
 */
export function watchWorkspace(
  root: string,
  answer: Answering,
  changed: () => void,
  events: WorkspaceWatchEvents = nativeEvents,
): WorkspaceWatch {
  const hashes = new Map<string, string>();
  const pending = new Set<string>();
  let open = true;
  let overflow = false;
  let draining = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stop: (() => void) | undefined;

  const inside = (path: string): boolean => {
    const part = relative(root, path);
    return (
      part !== '' &&
      part !== '..' &&
      !part.startsWith(`..${sep}`) &&
      !isAbsolute(part)
    );
  };
  const remember = (path: string, hash: string): void => {
    hashes.delete(path);
    hashes.set(path, hash);
    if (hashes.size > 1000) {
      const oldest = hashes.keys().next().value;
      if (oldest !== undefined) hashes.delete(oldest);
    }
  };
  const close = (): void => {
    open = false;
    stop?.();
    if (timer !== undefined) clearTimeout(timer);
    pending.clear();
    hashes.clear();
  };
  const schedule = (): void => {
    if (!open || timer !== undefined || draining) return;
    timer = setTimeout(() => {
      timer = undefined;
      drain().catch(close);
    }, 250);
    timer.unref();
  };
  const drain = async (): Promise<void> => {
    draining = true;
    let different = overflow;
    overflow = false;
    const paths = [...pending];
    pending.clear();
    try {
      for (const path of paths) {
        if (!open) return;
        try {
          const reply = await answer({
            id: 'workspace-watch',
            op: SOURCE,
            path,
          });
          if (!open) return;
          const hash =
            reply.outcome === REPLY_OK ? reply.source?.sha256 : undefined;
          if (hash === undefined) {
            // Deletion, directory rename or unavailable metadata: a bounded server scan decides
            // what changed. Refusal never supplies content or authority to the server.
            hashes.delete(path);
            different = true;
          } else {
            if (hashes.get(path) !== hash) different = true;
            remember(path, hash);
          }
        } catch {
          // A failed observation cannot establish "unchanged". Reconciliation owns recovery.
          different = true;
        }
      }
      if (open && different) changed();
    } finally {
      draining = false;
      if (pending.size || overflow) schedule();
    }
  };

  try {
    stop = events.subscribe(
      root,
      (filename) => {
        if (!open) return;
        if (filename === null || pending.size >= 1000) {
          overflow = true;
          pending.clear();
        } else {
          const path = resolve(root, filename);
          if (inside(path)) pending.add(path);
          else overflow = true;
        }
        schedule();
      },
      () => {
        overflow = true;
        schedule();
      },
    );
  } catch {
    // Older/unsupported filesystems retain server reconciliation; do not break file presence.
    overflow = true;
    schedule();
  }

  return {
    observed(request, reply): void {
      if (
        open &&
        request.op === SOURCE &&
        request.offset === undefined &&
        request.path !== undefined &&
        inside(request.path) &&
        reply.outcome === REPLY_OK &&
        typeof reply.source?.sha256 === 'string'
      )
        remember(request.path, reply.source.sha256);
    },
    close,
  };
}
