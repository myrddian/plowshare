import { describe, it, expect } from 'vitest';
import { AUTHORING, authoringReady, authoringRequest } from './authoring.js';
describe('authoring authority and intent', () => {
  it('requires a rooted project, served builder and granted caller', () => {
    const caller = { served: true, orchestrations: [AUTHORING] },
      builder = { served: true };
    expect(() => authoringReady(undefined, true, caller, builder)).toThrow(
      /folder/,
    );
    expect(() => authoringReady('project', false, caller, builder)).toThrow(
      /folder/,
    );
    expect(() =>
      authoringReady('project', true, caller, {
        served: false,
        withheld: 'Unavailable authoring model',
      }),
    ).toThrow(/Unavailable authoring model/);
    expect(() =>
      authoringReady(
        'project',
        true,
        { served: true, orchestrations: [] },
        builder,
      ),
    ).toThrow(/granted/);
    expect(() =>
      authoringReady('project', true, caller, builder),
    ).not.toThrow();
  });
  it('keeps revision and person intent explicit and excludes required builder replacement', () => {
    expect(
      authoringRequest('Read sources\nAsk before publication', 'source_review'),
    ).toContain('revising source_review');
    expect(authoringRequest('Read sources')).toContain('Do not install or run');
    expect(() => authoringRequest('Read sources', AUTHORING)).toThrow(
      /required system/,
    );
    expect(() => authoringRequest('Read sources', '../escape')).toThrow(
      /valid/,
    );
    expect(() => authoringRequest(' ')).toThrow(/Describe/);
    expect(() => authoringRequest('x'.repeat(8001))).toThrow(/Describe/);
  });
});

import { runStatusOf } from './inspection.js';
it('retains caller correlation and the valid Studio source hash for review', () => {
  const hash = 'sha256:' + 'a'.repeat(64);
  const status = runStatusOf({
    code: 'OK',
    payload: {
      orchestration: {
        id: 'run',
        state: 'asking',
        callerConversation: 'launch',
      },
      todos: [],
      children: [],
      messages: [
        {
          id: 'question',
          kind: 'question',
          text: 'Install?',
          structure: {
            name: 'source_note',
            path: 'artifacts/source_note.md',
            text: 'complete source',
            sha256: hash,
            lead: 'Review',
            questions: [
              {
                header: 'Install',
                question: 'Install?',
                multi: false,
                options: [
                  { label: 'Install', description: 'Write reviewed source' },
                ],
              },
            ],
          },
        },
      ],
    },
  });
  expect(status?.run.callerConversation).toBe('launch');
  expect(status?.messages[0]?.structure?.draft?.sha256).toBe(hash);
});
