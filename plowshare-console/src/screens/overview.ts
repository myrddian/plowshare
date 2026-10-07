import type { FiringRecord } from '../../../sdk/typescript/src/operations/administrative-replies.ts';
import { socketApprovals } from '../approvals';
import { background } from '../background';
import type { EventStream, EventStreamOptions } from '../events';
import { socketWorkRecords } from '../work';
import { asJobEvent } from '../repl/wire';
import { asInboxChanged } from './wire';
import { button, el, field, nothing, problemText } from './dom';
import { reconciliation } from './reconciliation';
import { recordLink, recordLinks } from './record-link';
import type { Screen, Transport } from './screen';

export function createOverview(options: {
  readonly root: HTMLElement;
  readonly session: string;
  readonly transport: Transport;
  readonly openStream: (options: EventStreamOptions) => EventStream;
  readonly pollMs?: number | null;
}): Screen {
  const element = el('section', 'screen work-overview');
  const head = el('header', 'screen-head');
  const reload = button('reload', 'Refresh work');
  head.append(el('h2', 'screen-title', 'Work overview'), reload);
  const body = el('div', 'screen-body');
  const status = el('p', 'work-status', 'Connecting to read server work…');
  status.setAttribute('role', 'status');
  const summaries = el('div', 'work-summaries');
  const workbench = el('div', 'workbench');
  const inspector = el('aside', 'work-inspector');
  inspector.setAttribute('aria-label', 'Selected work record');
  const inspection = el('div', 'work-inspection');
  inspector.append(el('h3', '', 'Record inspector'), inspection);
  workbench.append(summaries, inspector);
  const admissions = el('section', 'work-admissions');
  const previous = button('previous', 'Previous admissions');
  const next = button('next', 'Next admissions');
  body.append(
    status,
    workbench,
    admissions,
    previous,
    next,
    el(
      'p',
      'note',
      'Jobs cover the current server process, not historic job listing. Open retained conversations or inbox deliveries for saved results. Scheduled admissions and their job outcomes are separate records.',
    ),
  );
  element.append(head, body);
  options.root.replaceChildren(element);
  let stream: EventStream | null = null;
  let stopped = false,
    offset = 0;
  const limit = 30;
  let selected: string | null = null;
  let records = new Map<string, { row: HTMLElement; kind: string }>();
  function inspect(): void {
    for (const [key, record] of records) {
      record.row.dataset['selected'] = String(key === selected);
      record.row
        .querySelector('button')
        ?.setAttribute('aria-pressed', String(key === selected));
    }
    const record = selected === null ? undefined : records.get(selected);
    inspection.replaceChildren();
    if (record === undefined) {
      inspection.append(
        nothing(
          selected === null
            ? 'Select a record to inspect its state and open the owning conversation, job or approval.'
            : 'This record is no longer in the current window or could not be read. Refresh or open its owning record.',
        ),
      );
      return;
    }
    inspection.append(el('p', 'note', record.kind));
    for (const child of record.row.children) {
      if (!child.matches('.inspect-record'))
        inspection.append(child.cloneNode(true));
    }
  }
  function offer(row: HTMLElement, key: string, kind: string): void {
    const pick = button('inspect', 'Inspect');
    pick.setAttribute('aria-label', `Inspect ${kind} ${key}`);
    pick.className = 'inspect-record';
    pick.setAttribute('aria-pressed', 'false');
    pick.addEventListener('click', () => {
      selected = key;
      inspect();
    });
    row.prepend(pick);
    records.set(key, { row, kind });
  }
  inspect();
  const refresh = reconciliation({
    available: () => stream?.status().state === 'open',
    pollMs: options.pollMs === undefined ? 5000 : options.pollMs,
    async read() {
      const socket = stream;
      if (socket === null) return;
      const pageOffset = offset;
      element.setAttribute('aria-busy', 'true');
      // Independent capabilities fail independently. A denied queue does not
      // erase a successfully read inbox, and a failed read is never a zero count.
      const results = await Promise.allSettled([
        options.transport.get('/v1/jobs'),
        socketApprovals(socket).list(),
        socketWorkRecords(socket).inbox(0, 10),
        socketWorkRecords(socket).firings(pageOffset, limit),
      ]);
      if (stopped || pageOffset !== offset) return;
      const [jobs, approvals, inbox, firings] = results;
      records = new Map();
      const sections: HTMLElement[] = [];
      function section(title: string): HTMLElement {
        const node = el('section', 'work-summary');
        node.append(el('h3', '', title));
        sections.push(node);
        return node;
      }
      const runs = section('Current process jobs');
      if (jobs.status === 'fulfilled') {
        const held = jobs.value ?? [];
        runs.append(
          el(
            'p',
            '',
            `${held.filter((job) => job.state === 'RUNNING').length} running · ${held.length} listed by this process`,
          ),
        );
        const recent = [...held].reverse().slice(0, 20);
        if (recent.length === 0)
          runs.append(
            nothing(
              'No jobs in this process. Saved results may still exist in the inbox or conversation records.',
            ),
          );
        for (const job of recent) {
          const row = el('article', 'work-row');
          row.append(
            el(
              'strong',
              'work-row-title',
              `${job.agent} · ${job.state}${job.outcome == null ? '' : ' · ' + job.outcome.ending}`,
            ),
            recordLink('jobs', job.id, job.id),
            field('state', job.state),
            field(
              'outcome',
              job.outcome?.ending ?? 'No terminal outcome reported',
            ),
          );
          if (job.cancelRequested)
            row.append(
              el(
                'p',
                '',
                'Cancellation requested; the job has not yet confirmed it stopped.',
              ),
            );
          row.append(recordLinks(job.conversation));
          offer(row, 'job:' + job.id, 'Current process job');
          runs.append(row);
        }
        if (held.length > recent.length)
          runs.append(
            el(
              'p',
              'note',
              `Showing the most recent ${recent.length}. Open jobs for other current-process runs.`,
            ),
          );
      } else
        runs.append(
          el('p', 'trouble', problemText(jobs.reason, 'Jobs are unavailable.')),
        );
      const waiting = section('Approval-waiting work');
      if (approvals.status === 'fulfilled') {
        const requests = approvals.value.filter((row) => row.state === 'asked');
        waiting.append(el('p', '', `${requests.length} pending approvals`));
        for (const request of requests.slice(0, 20)) {
          const row = el('article', 'work-row');
          row.append(
            el(
              'strong',
              'work-row-title',
              `${request.agent} · Awaiting approval`,
            ),
            field('request', request.id),
            field('agent', request.agent),
            recordLinks(request.askedIn || request.conversation),
          );
          const review = document.createElement('a');
          review.href = '#approvals';
          review.textContent = 'Review this request in approvals';
          row.append(review);
          offer(row, 'approval:' + request.id, 'Approval request');
          waiting.append(row);
        }
        const link = document.createElement('a');
        link.href = '#approvals';
        link.textContent = 'Review approvals';
        waiting.append(link);
      } else
        waiting.append(
          el(
            'p',
            'trouble',
            problemText(approvals.reason, 'Approvals are unavailable.'),
          ),
        );
      const delivered = section('Retained inbox results');
      if (inbox.status === 'fulfilled') {
        delivered.append(
          el('p', '', `${inbox.value.unread} unread deliveries`),
        );
        for (const item of inbox.value.items) {
          const row = el('article', 'work-row');
          row.append(
            el(
              'strong',
              'work-row-title',
              `${item.ending ?? item.kind} · ${item.arrivedAt}`,
            ),
            field('arrived', item.arrivedAt),
            field('ending', item.ending ?? item.kind),
            el(
              'pre',
              'result-preview',
              (item.answer ?? item.about ?? '').slice(0, 1000),
            ),
            recordLinks(item.conversation),
          );
          offer(row, 'inbox:' + item.id, 'Retained inbox delivery');
          delivered.append(row);
        }
        if (inbox.value.items.length === 0)
          delivered.append(nothing('No retained inbox deliveries.'));
        const link = document.createElement('a');
        link.href = '#inbox';
        link.textContent = 'Open inbox and saved results';
        delivered.append(link);
      } else
        delivered.append(
          el(
            'p',
            'trouble',
            problemText(inbox.reason, 'Inbox results are unavailable.'),
          ),
        );
      summaries.replaceChildren(...sections);
      inspect();
      admissions.replaceChildren(
        el(
          'h3',
          '',
          `Scheduled/event admissions · page ${pageOffset / limit + 1}`,
        ),
      );
      if (firings.status === 'fulfilled') {
        if (firings.value.length === 0)
          admissions.append(
            nothing(
              'No accessible admissions in this window. Later windows may contain other records.',
            ),
          );
        admissions.append(...firings.value.map(firingRow));
      } else
        admissions.append(
          el(
            'p',
            'trouble',
            problemText(firings.reason, 'Admissions could not be read.'),
          ),
        );
      // Authorization filters after server paging; a short/empty page cannot
      // establish that no later accessible records exist.
      previous.disabled = pageOffset === 0;
      status.textContent =
        'Read from server state. Refresh and reconnect reconcile saved results without rerunning work.';
      element.setAttribute('aria-busy', 'false');
    },
  });
  function firingRow(firing: FiringRecord): HTMLElement {
    const row = el('article', 'work-row');
    row.append(
      field('admission', firing.id),
      field('event', firing.event),
      field('status', firing.status),
      field('arrived', firing.arrivedAt),
    );
    if (firing.reason !== null) row.append(field('reason', firing.reason));
    if (firing.supersededBy !== null)
      row.append(field('superseded by', firing.supersededBy));
    if (firing.jobId !== null) row.append(recordLinks(null, firing.jobId));
    if (firing.status === 'started')
      row.append(
        el(
          'p',
          'note',
          'Admission started a job. This admission status does not establish the job completed.',
        ),
      );
    return row;
  }
  reload.addEventListener('click', () => background(refresh.refresh()));
  previous.addEventListener('click', () => {
    offset = Math.max(0, offset - limit);
    background(refresh.refresh());
  });
  next.addEventListener('click', () => {
    offset += limit;
    background(refresh.refresh());
  });
  return {
    element: () => element,
    setActive: refresh.setActive,
    async load() {
      if (stopped) return;
      stream ??= options.openStream({
        session: options.session,
        onEvent: (frame) => {
          if (asJobEvent(frame) !== null || asInboxChanged(frame) !== null)
            background(refresh.refresh());
        },
        onStatus: (next) => {
          if (next.state === 'open') background(refresh.refresh());
        },
      });
      await refresh.refresh();
    },
    destroy() {
      stopped = true;
      refresh.stop();
      stream?.close();
      stream = null;
    },
  };
}
