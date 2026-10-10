import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { background } from '../background.ts';
import {
  UsageClient,
  UsageWatch,
} from '../../../sdk/typescript/src/operations/usage.ts';
import { mountUsagePanel } from './usage-panel';
import type { EventStream, EventStreamOptions } from '../events';
import type { Screen } from './screen';
export function createUsage(options: {
  root: HTMLElement;
  session: string;
  openStream: (options: EventStreamOptions) => EventStream;
  project: string | null;
}): Screen {
  let closed = false,
    opened = false;
  const held: {
    watch?: UsageWatch;
    panel?: ReturnType<typeof mountUsagePanel>;
  } = {};
  const stream: EventStream = options.openStream({
    session: options.session,
    onEvent: (frame) => {
      held.watch?.push(frame);
    },
    onStatus: (status) => {
      const watch = held.watch;
      if (!watch || closed) return;
      if (status.state === 'open') {
        opened = true;
        background(watch.reconnect());
      } else if (status.state === 'reconnecting' || status.state === 'closed')
        watch.disconnected();
    },
  });
  const client = new UsageClient(checkedTransport(stream));
  const activeWatch = new UsageWatch(client, (state) =>
    held.panel?.update(state),
  );
  held.watch = activeWatch;
  const panel = mountUsagePanel(options.root, {
    select: (type, filter) => activeWatch.open(type, filter),
    read: (type, payload) => client.call(type, payload),
    project: options.project,
    storage: localStorage,
  });
  // Panel construction selects synchronously. Replay that initial state once
  // its renderer exists, rather than reading a const in its temporal dead zone.
  held.panel = panel;
  panel.update(activeWatch.state);
  return {
    element: () => options.root,
    async load() {
      if (closed) return;
      if (opened && !activeWatch.state.loading) await activeWatch.reconnect();
      else if (stream.status().state === 'open') {
        // Mount already owns the first selection on an open stream.
        opened = true;
      }
    },
    destroy() {
      closed = true;
      background(activeWatch.close());
      panel.destroy();
      stream.close();
    },
  };
}
