import { ProcClient } from '../../../sdk/typescript/src/operations/proc.ts';
import './proc-panel.css';
import { checkedTransport } from '../../../sdk/typescript/src/operations/transport.ts';
import { mountProcPanel } from './proc-panel';
import type { EventStream, EventStreamOptions } from '../events';
import type { Screen } from './screen';

/** A handle on the shell's shared socket; closing this screen stops its observation only. */
export function createProc(options: {
  root: HTMLElement;
  session: string;
  project: string | null;
  openStream: (options: EventStreamOptions) => EventStream;
}): Screen {
  let closed = false;
  const stream = options.openStream({
    session: options.session,
    onEvent: () => {},
  });
  const panel = mountProcPanel(
    options.root,
    new ProcClient(checkedTransport(stream)),
  );
  return {
    element: () => options.root,
    load: () => panel.refresh(),
    destroy() {
      if (closed) return;
      closed = true;
      panel.destroy();
      stream.close();
    },
  };
}
