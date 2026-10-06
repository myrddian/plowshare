import { decodeReply } from 'plowshare-client-ts/operations/schema';
import type {
  RelayScope,
  RelayEvent,
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
  more.disabled = true;
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
  if (row.id === 'relay-topic-actions') row.append(form);
  else {
    let management = row.querySelector<HTMLDetailsElement>('.relay-management');
    if (!management) {
      management = document.createElement('details');
      management.className = 'relay-management';
      const summary = document.createElement('summary');
      summary.textContent = 'Manage';
      const note = document.createElement('p');
      note.textContent =
        'Requires project manager access and a reason. Removal requires inactive configuration and empty history. Abandonment does not cancel receiver work.';
      management.append(summary, note);
      row.append(management);
    }
    management.append(form);
  }
}

const text = (id: string, value: string) => {
  document.querySelector(id)!.textContent = value;
};
const selected = (): RelayScope =>
  // Send the discriminator explicitly so the viewer also works with older strict decoders.
  scope.value === ':system:'
    ? { system: true }
    : { project: scope.value, system: false };
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
/** Render the typed payload as readable content; wire details remain available on demand. */
function eventCard(event: RelayEvent): HTMLElement {
  const row = document.createElement('article');
  const heading = document.createElement('strong');
  const message = document.createElement('p');
  message.className = 'relay-message';
  const payload = event.payload;
  switch (payload.kind) {
    case 'TEXT':
      heading.textContent = 'Message';
      message.textContent = payload.text;
      break;
    case 'SCHEDULE_DUE':
      heading.textContent = 'Scheduled work due';
      message.textContent = `${payload.schedule} · ${payload.emits}\nDue ${payload.fireAt}`;
      break;
    case 'LIFECYCLE':
      heading.textContent = 'Work status changed';
      message.textContent = payload.lifecycle
        ? `${payload.lifecycle.subject} · ${payload.lifecycle.state}\n${payload.lifecycle.source}`
        : '';
      break;
    case 'WAKE_REQUESTED':
      heading.textContent =
        payload.wake?.type === 'MESSAGE'
          ? 'Message wake requested'
          : 'Board wake requested';
      message.textContent = payload.wake?.target ?? '';
      break;
    case 'EMPTY':
      heading.textContent = 'Signal published';
      message.textContent = 'This event has no message body.';
      break;
  }
  const meta = document.createElement('p');
  meta.className = 'relay-card-meta';
  meta.textContent = `#${event.position} · ${event.publisher} · ${event.publishedAt}`;
  const details = document.createElement('details');
  const summary = document.createElement('summary');
  summary.textContent = 'Event details';
  const content = document.createElement('pre');
  content.textContent = `Event: ${event.eventId}\nOccurred: ${event.occurredAt}\n${JSON.stringify(payload, null, 2)}${event.correlationId ? `\nCorrelation: ${event.correlationId}` : ''}${event.causationId ? `\nCause: ${event.causationId}` : ''}`;
  details.append(summary, content);
  row.append(heading, meta, message, details);
  return row;
}
function empty(id: string, message: string) {
  const note = document.createElement('p');
  note.className = 'relay-empty';
  note.textContent = message;
  document.querySelector(id)!.replaceChildren(note);
}
function clearSnapshot(message: string) {
  page = undefined;
  disableControls();
  text('#relay-policy', message);
  for (const id of [
    '#relay-gap',
    '#relay-notice',
    '#relay-error',
    '#relay-topic-actions',
    '#relay-page-position',
  ])
    text(id, '');
  for (const id of [
    '#relay-event-count',
    '#relay-delivery-count',
    '#relay-subscriber-count',
  ])
    text(id, '0');
  for (const id of ['#relay-events', '#relay-subscribers', '#relay-branches'])
    empty(id, message);
  document.querySelector<HTMLDetailsElement>(
    '#relay-topic-management',
  )!.hidden = true;
}
function loading(value: boolean) {
  document.querySelector<HTMLElement>('#relay-loading')!.hidden = !value;
  document
    .querySelector('#relay-events')!
    .setAttribute('aria-busy', String(value));
}
const canRead = () =>
  state?.mode === 'live' && state.connected && !!scope.value;
function render(value: RelayReplies['relay.log']) {
  page = value;
  snapshotValid = true;
  const topicActions = document.querySelector<HTMLElement>(
    '#relay-topic-actions',
  )!;
  topicActions.replaceChildren();
  control(topicActions, value, 'REMOVE_TOPIC', 'Remove inactive empty topic');
  document.querySelector<HTMLDetailsElement>(
    '#relay-topic-management',
  )!.hidden = !topicActions.childElementCount;
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
  text('#relay-event-count', String(value.events.length));
  text('#relay-subscriber-count', String(value.subscribers.length));
  text('#relay-delivery-count', String(value.branches.length));
  text(
    '#relay-page-position',
    `Through #${value.next} of #${value.topic.through}`,
  );
  for (const event of value.events) events.append(eventCard(event));
  if (!value.events.length)
    empty('#relay-events', 'No retained events in this page.');
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
  if (!value.subscribers.length)
    empty('#relay-subscribers', 'No subscribers have observed this topic.');
  const branches = document.querySelector('#relay-branches')!;
  branches.replaceChildren();
  for (const branch of value.branches) {
    const row = article(
      branch.name,
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
    const heading = row.querySelector('strong')!;
    const header = document.createElement('div');
    header.className = 'relay-card-header';
    const status = document.createElement('span');
    status.className = 'relay-status';
    status.dataset['state'] = branch.state;
    status.textContent = branch.state;
    heading.replaceWith(header);
    header.append(heading, status);
    const position = document.createElement('p');
    position.className = 'relay-card-meta';
    position.textContent = `Event #${branch.position}`;
    header.after(position);
    if (branch.conversation) {
      const button = document.createElement('button');
      button.textContent = 'Open trajectory';
      button.className = 'secondary-button';
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
  if (!value.branches.length)
    empty('#relay-branches', 'No retained deliveries for this topic.');
  more.disabled = BigInt(value.next) >= BigInt(value.topic.through);
}
async function read(after = '0') {
  disableControls();
  const version = ++generation;
  if (!canRead() || !topic.value) return;
  loading(true);
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
      text(
        '#relay-error',
        `${errorMessage(error)}${page ? ' · Last snapshot retained.' : ''}`,
      );
  } finally {
    if (version === generation) loading(false);
  }
}
async function topics() {
  disableControls();
  const version = ++generation;
  if (!canRead()) return;
  loading(true);
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
    else clearSnapshot('No registered topics in this scope.');
  } catch (error) {
    if (version === generation)
      text(
        '#relay-error',
        `${errorMessage(error)}${page ? ' · Last snapshot retained.' : ''}`,
      );
  } finally {
    if (version === generation) loading(false);
  }
}
function update(next: DesktopState) {
  state = next;
  if (!state.connected) {
    ++generation;
    disableControls();
    loading(false);
  }
  scope.disabled = !state.connected;
  topic.disabled = !state.connected;
  document.querySelector<HTMLButtonElement>('#relay-refresh')!.disabled =
    !state.connected;
  text(
    '#relay-connection',
    state.mode === 'demo'
      ? 'Connect a server to inspect Relay logs.'
      : `${state.base} · ${state.handle} · ${state.connected ? 'Connected' : 'Disconnected · last snapshot'}`,
  );
  const nextIdentity = `${state.base}|${state.handle}|${state.connected}|${state.serverAdmin}|${JSON.stringify(state.projects.map((project) => project.name))}`;
  if (identity === nextIdentity) return;
  identity = nextIdentity;
  const previous = scope.value;
  scope.replaceChildren();
  for (const project of state.projects)
    scope.add(new Option(project.name, project.name));
  if (state.serverAdmin)
    scope.add(new Option('Server system topics (administrator)', ':system:'));
  if ([...scope.options].some((option) => option.value === previous))
    scope.value = previous;
  if (state.mode === 'live' && state.connected) {
    clearSnapshot('Loading retained events…');
    background(topics());
  }
}
scope.addEventListener('change', () => {
  topic.replaceChildren();
  clearSnapshot('Loading topics for this scope…');
  background(topics());
});
topic.addEventListener('change', () => {
  clearSnapshot('Loading retained events…');
  background(read());
});
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
