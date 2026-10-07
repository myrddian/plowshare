import {
  callStanding,
  stepKey,
  type Step,
} from '../../../sdk/typescript/src/operations/trajectory.ts';
import { el, nothing } from './dom';
import { recordLinks } from './record-link';

/** A bounded page of shared trajectory steps. Pairing is page-local: an absent
 * result is not proof a job is running, and orphan results remain inspectable. */
export function trajectorySummary(steps: readonly Step[]): HTMLElement {
  const root = el('section', 'trajectory-summary');
  root.setAttribute('aria-label', 'Trajectory breakdown');
  root.append(
    el('h3', '', 'What happened'),
    el(
      'p',
      'note',
      'Breakdown of this retained page. Tool calls pair with results present on this page; job outcomes belong to the job record.',
    ),
  );
  if (steps.length === 0)
    root.append(nothing('No recorded steps in this window.'));
  const shown = steps.slice(0, 100);
  if (steps.length > shown.length)
    root.append(
      el(
        'p',
        'note',
        'Showing the first 100 steps in this page. Open the full retained record for other steps.',
      ),
    );
  for (const step of shown) {
    const row = document.createElement('details');
    row.className = 'trajectory-step';
    row.dataset['step'] = stepKey(step);
    const heading = document.createElement('summary');
    const labels = {
      person: 'Request',
      reasoning: 'Reasoning',
      answer: 'Answer',
      fold: 'Compaction',
      note: step.entry.kind,
      call: 'Tool',
    };
    heading.textContent = `Turn ${step.turn} · ${labels[step.kind]}`;
    row.append(heading);
    if (step.kind === 'call') {
      row.dataset['standing'] = callStanding(step);
      heading.textContent += ` · ${step.tool} · ${step.result?.outcome ?? 'Result not present on this page'}`;
      row.append(
        el('p', 'note', `Call ${step.id}`),
        el('pre', '', step.arguments),
      );
      if (step.argumentsCut)
        row.append(
          el(
            'p',
            'note',
            `Arguments truncated. Recorded length: ${step.argumentsLength}.`,
          ),
        );
      if (step.opened)
        row.append(
          el('p', '', `Delegated to ${step.opened.agent}`),
          recordLinks(step.opened.conversation),
        );
      if (step.result) {
        row.append(
          el('pre', '', step.result.text ?? 'Result content is unavailable.'),
        );
        if (step.result.cut)
          row.append(
            el(
              'p',
              'note',
              'Result preview is truncated; open the full record for metadata.',
            ),
          );
      }
      if (step.unanswered)
        row.append(
          el(
            'p',
            'note',
            'Later entries exist without a matching result in this window.',
          ),
        );
      if (step.hooks.length)
        row.append(
          el(
            'p',
            'note',
            `${step.hooks.length} recorded hooks; inspect the full record below.`,
          ),
        );
    } else {
      const text = step.entry.text ?? 'Content is unavailable.';
      heading.textContent += ' · ' + text.replace(/\s+/g, ' ').slice(0, 140);
      row.append(
        el('p', 'step-preview', text.slice(0, 240)),
        el('pre', '', text),
      );
      if (step.entry.cut)
        row.append(el('p', 'note', 'This retained preview is truncated.'));
    }
    if (step.entry.state !== 'stands')
      row.append(el('p', 'note', `Retained entry: ${step.entry.state}.`));
    if (step.entry.source)
      row.append(
        el(
          'p',
          'note',
          `Origin: ${step.entry.source.kind}${step.entry.source.reference === null ? '' : ' · ' + step.entry.source.reference}`,
        ),
      );
    if (step.entry.job) row.append(recordLinks(null, step.entry.job));
    root.append(row);
  }
  return root;
}
