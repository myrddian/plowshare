import { UsageClient, UsageWatch } from '../../../plowshare-client-ts/src/operations/usage.ts';
import { mountUsagePanel } from './usage-panel';
import type { EventStream, EventStreamOptions } from '../events';
import type { Screen } from './screen';
export function createUsage(options: {
    root: HTMLElement;
    session: string;
    openStream: (options: EventStreamOptions) => EventStream;
    project: string | null;
}): Screen {
    let closed = false, opened = false;
    let watch: UsageWatch | undefined;
    let panel: ReturnType<typeof mountUsagePanel> | undefined;
    const stream: EventStream = options.openStream({ session: options.session,
        onEvent: frame => { watch?.push(frame); }, onStatus: status => {
            if (!watch || closed)
                return;
            if (status.state === 'open') {
                opened = true;
                void watch.reconnect();
            }
            else if (status.state === 'reconnecting' || status.state === 'closed')
                watch.disconnected();
        } });
    const client = new UsageClient(stream);
    watch = new UsageWatch(client, state => panel?.update(state));
    panel = mountUsagePanel(options.root, { select: (type, filter) => watch!.open(type, filter), read: (type, payload) => client.call(type, payload), project: options.project, storage: localStorage });
    return { element: () => options.root, async load() { if (closed)
            return; if (opened)
            await watch!.reconnect();
        else if (stream.status().state === 'open') {
            opened = true;
            await watch!.reconnect();
        } }, destroy() { closed = true; void watch!.close(); panel!.destroy(); stream.close(); } };
}
