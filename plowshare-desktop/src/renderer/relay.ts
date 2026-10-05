import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type {
  RelayScope,
  RelayReplies,
  RelayOperateRequest,
  RelayControlAction,
} from 'plowshare-client-ts/operations/relay';
import type { DesktopState } from '../shared.ts';
import { background } from './events.ts';
import { errorMessage } from 'plowshare-client-ts/binding/values';
const scope = document.querySelector<HTMLSelectElement>('#relay-scope')!;
const topic = document.querySelector<HTMLSelectElement>('#relay-topic')!;
const more = document.querySelector<HTMLButtonElement>('#relay-more')!;
let state: DesktopState;
let page: RelayReplies['relay.log'] | undefined;
let generation = 0;
let identity = '';
let snapshotValid = false;
let operating = false;
function disableControls() {
  snapshotValid = false;
  for (const button of document.querySelectorAll<HTMLButtonElement>(
    '.relay-operation button',
  ))
    button.disabled = true;
}
/** Controls capture the displayed snapshot, never a subsequently selected scope. */
function control(
  row: HTMLElement,
  value: RelayReplies['relay.log'],
  action: RelayControlAction,
  label: string,
  fields: Partial<RelayOperateRequest> = {},
) {
  if (value.scope.system || !value.scope.project || !value.topic.generation)
    return;
  const form = document.createElement('form');
  form.className = 'relay-operation';
  const reason = document.createElement('input');
  reason.type = 'text';
  reason.maxLength = 256;
  reason.required = true;
  reason.placeholder = 'Reason for this action';
  reason.setAttribute('aria-label', `Reason: ${label}`);
  const button = document.createElement('button');
  button.type = 'submit';
  button.textContent = label;
  button.disabled = operating || !snapshotValid || !state?.connected;
  form.append(reason, button);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (
      !snapshotValid ||
      operating ||
      page !== value ||
      !state.connected ||
      !reason.value.trim()
    )
      return;
    const payload: RelayOperateRequest = {
      requestId: crypto.randomUUID(),
      project: value.scope.project!,
      topic: value.topic.name,
      topicGeneration: value.topic.generation!,
      action,
      reason: reason.value.trim(),
      ...fields,
    };
    operating = true;
    disableControls();
    background(
      (async () => {
        try {
          const reply = await window.plowshare.request({
            action: 'relay-operate',
            payload,
          });
          const result = decodeReply('relay.operate', reply.relayControl);
          text(
            '#relay-notice',
            `${label}: ${result.status}${result.status === 'ABANDONED_UNCERTAIN' ? ' · The receiver effect remains unknown. This does not cancel receiver work.' : ''}`,
          );
          await topics();
        } catch (error) {
          text(
            '#relay-error',
            `${errorMessage(error)} · Request ${payload.requestId}. Refresh the log before another action. An unconfirmed request is not automatically repeated.`,
          );
        } finally {
          operating = false;
          if (snapshotValid && page) render(page);
        }
      })(),
    );
  });
  row.append(form);
}

const text = (id: string, value: string) => {
  document.querySelector(id)!.textContent = value;
};
const selected = (): RelayScope =>
  scope.value === ':system:' ? { system: true } : { project: scope.value };
const article = (title: string, body: string): HTMLElement => {
  const row = document.createElement('article');
  const heading = document.createElement('strong');
  heading.textContent = title;
  row.append(heading);
  const content = document.createElement('pre');
  content.textContent = body;
  row.append(content);
  return row;
};
function render(value: RelayReplies['relay.log']) {
  page = value;
  snapshotValid = true;
  const topicActions = document.querySelector<HTMLElement>(
    '#relay-topic-actions',
  )!;
  topicActions.replaceChildren();
  control(topicActions, value, 'REMOVE_TOPIC', 'Remove inactive empty topic');
  text(
    '#relay-policy',
    `${value.scope.project ?? 'Server system topics'} · ${value.topic.name} · ${value.topic.kind} · retained for ${value.topic.retentionSeconds}s${value.topic.maxRecords ? ` / ${value.topic.maxRecords} records` : ''} · published through ${value.topic.through} · expired through ${value.topic.expiredThrough}`,
  );
  text(
    '#relay-gap',
    value.gapThrough
      ? `Earlier events expired through position ${value.gapThrough}. Reading this page does not acknowledge that loss.`
      : '',
  );
  const events = document.querySelector('#relay-events')!;
  events.replaceChildren();
  for (const event of value.events)
    events.append(
      article(
        `${event.position} · ${event.eventId}`,
        `${event.publisher} · ${event.publishedAt}
Occurred: ${event.occurredAt}
${JSON.stringify(event.payload, null, 2)}${
          event.correlationId
            ? `
Correlation: ${event.correlationId}`
            : ''
        }${
          event.causationId
            ? `
Cause: ${event.causationId}`
            : ''
        }`,
      ),
    );
  if (!value.events.length)
    events.textContent = 'No retained events in this page.';
  const subs = document.querySelector('#relay-subscribers')!;
  subs.replaceChildren();
  for (const sub of value.subscribers) {
    const recovery = value.recoveries?.find(
      (item) => item.subscriber === sub.name,
    );
    const row = article(
      sub.name,
      `Seen through ${sub.seenThrough} at ${sub.seenAt}${sub.gapThrough ? `\nExpired gap through ${sub.gapThrough}` : ''}${recovery ? `\nRecovered availability through ${recovery.expiredThrough} at ${recovery.recoveredAt} from the owning inbox (${recovery.pendingInbox} queued).` : ''}`,
    );
    if (sub.name.startsWith('relay.') && sub.generation) {
      const fields = {
        subscriber: sub.name,
        subscriptionGeneration: sub.generation,
      };
      if (sub.gapThrough)
        control(
          row,
          value,
          'ACKNOWLEDGE_GAP',
          `Acknowledge gap through ${sub.gapThrough}`,
          { ...fields, expiredThrough: sub.gapThrough },
        );
      control(
        row,
        value,
        'REMOVE_SUBSCRIPTION',
        'Remove inactive empty subscription',
        fields,
      );
    }
    subs.append(row);
  }
  if (!value.subscribers.length) subs.textContent = 'No subscribers.';
  const branches = document.querySelector('#relay-branches')!;
  branches.replaceChildren();
  for (const branch of value.branches) {
    const row = article(
      `${branch.position} · ${branch.name} · ${branch.state}`,
      `${branch.subscriber} → ${branch.receiver}
${branch.updatedAt}${
        branch.failure
          ? `
${branch.failure}`
          : ''
      }${
        branch.receiptId
          ? `
Receipt: ${branch.receiptNamespace} ${branch.receiptId}`
          : ''
      }`,
    );
    if (branch.conversation) {
      const button = document.createElement('button');
      button.textContent = 'Open trajectory';
      button.addEventListener('click', () =>
        background(
          window.plowshare.request({
            action: 'relay-trajectory',
            conversation: branch.conversation!,
          }),
        ),
      );
      row.append(button);
    }
    const subscriber = value.subscribers.find(
      (sub) => sub.name === branch.subscriber,
    );
    if (subscriber?.generation && subscriber.name.startsWith('relay.')) {
      const fields = {
        subscriber: subscriber.name,
        subscriptionGeneration: subscriber.generation,
        deliveryId: branch.id,
        fence: branch.fence,
        expectedState: branch.state,
      };
      if (
        branch.state === 'UNCERTAIN' ||
        branch.state === 'ABANDONED_UNCERTAIN'
      )
        control(row, value, 'RECONCILE', 'Reconcile receipt', fields);
      if (branch.state === 'READY' || branch.state === 'UNCERTAIN')
        control(row, value, 'ABANDON', 'Abandon delivery', fields);
    }
    const hashes = document.createElement('details');
    const label = document.createElement('summary');
    label.textContent = 'Pinned source hashes';
    hashes.append(label);
    const content = document.createElement('pre');
    content.textContent = `Routing: ${branch.routingHash}${
      branch.handlerHash
        ? `
Handler: ${branch.handlerHash}`
        : ''
    }`;
    hashes.append(content);
    row.append(hashes);
    branches.append(row);
  }
  if (!value.branches.length) branches.textContent = 'No retained deliveries.';
  more.disabled = BigInt(value.next) >= BigInt(value.topic.through);
}
async function read(after = '0') {
  disableControls();
  const version = ++generation;
  if (!topic.value) return;
  try {
    const reply = await window.plowshare.request({
      action: 'relay-read',
      type: 'relay.log',
      payload: { ...selected(), topic: topic.value, after, limit: 100 },
    });
    if (version !== generation) return;
    render(decodeReply('relay.log', reply.relay));
    text('#relay-error', '');
  } catch (error) {
    if (version === generation)
      text('#relay-error', `${errorMessage(error)} · Last snapshot retained.`);
  }
}
async function topics() {
  disableControls();
  const version = ++generation;
  try {
    const reply = await window.plowshare.request({
      action: 'relay-read',
      type: 'relay.topics',
      payload: selected(),
    });
    if (version !== generation) return;
    const value = decodeReply('relay.topics', reply.relay);
    const previous = topic.value;
    topic.replaceChildren();
    for (const item of value.topics) {
      const option = new Option(item.name, item.name);
      topic.add(option);
    }
    if (value.topics.some((item) => item.name === previous))
      topic.value = previous;
    text('#relay-error', '');
    if (topic.value) await read();
    else {
      page = undefined;
      more.disabled = true;
      text('#relay-policy', 'No registered topics in this scope.');
      for (const id of [
        '#relay-events',
        '#relay-subscribers',
        '#relay-branches',
        '#relay-gap',
        '#relay-topic-actions',
      ])
        text(id, '');
    }
  } catch (error) {
    if (version === generation)
      text('#relay-error', `${errorMessage(error)} · Last snapshot retained.`);
  }
}
function update(next: DesktopState) {
  state = next;
  if (!state.connected) disableControls();
  text(
    '#relay-connection',
    state.mode === 'demo'
      ? 'Connect a server to inspect Relay logs.'
      : `${state.base} · ${state.handle} · ${state.connected ? 'Connected' : 'Disconnected · last snapshot'}`,
  );
  const nextIdentity = `${state.base}|${state.handle}|${state.connected}|${JSON.stringify(state.projects.map((project) => project.name))}`;
  if (identity === nextIdentity) return;
  identity = nextIdentity;
  const previous = scope.value;
  scope.replaceChildren();
  for (const project of state.projects)
    scope.add(new Option(project.name, project.name));
  scope.add(new Option('Server system topics (administrator)', ':system:'));
  if ([...scope.options].some((option) => option.value === previous))
    scope.value = previous;
  if (state.mode === 'live' && state.connected) background(topics());
}
scope.addEventListener('change', () => background(topics()));
topic.addEventListener('change', () => background(read()));
document
  .querySelector('#relay-refresh')!
  .addEventListener('click', () => background(topics()));
more.addEventListener('click', () => {
  if (page) background(read(page.next));
});
window.addEventListener('workspace-refresh', () => background(topics()));
window.plowshare.subscribe(update);
background(
  window.plowshare
    .request({ action: 'bootstrap' })
    .then((reply) => update(reply.state)),
);
