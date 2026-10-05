import { describe, expect, it } from 'vitest';
import { clientProject, projectLabel } from './project-label.ts';

describe('project display labels', () => {
  it('recognizes client identities and decodes unpadded labels', () => {
    expect(clientProject('client:scope:SG91c2U')).toBe(true);
    expect(clientProject('house')).toBe(false);
    expect(projectLabel('client:scope:SG91c2U')).toBe('House');
    expect(projectLabel('client:scope:8J-PoCBjYWbDqQ')).toBe('🏠 café');
  });
  it('retains server, Personal and legacy client names', () => {
    for (const project of [
      'Research',
      'Personal:alice',
      'personal:616c696365',
      'client:Research',
    ]) {
      expect(projectLabel(project)).toBe(project);
    }
  });
  it('decodes client-private names across accounts and machines', () => {
    expect(projectLabel('client:alice:UmVzZWFyY2g')).toBe('Research');
    expect(projectLabel('client:alice:laptop:UmVzZWFyY2g=')).toBe('Research');
    expect(projectLabel('client:bob:5pel5pys6Kqe')).toBe('日本語');
    expect(projectLabel('client:bob:8J-agA')).toBe('🚀');
  });
  it('keeps malformed identities visible without throwing during rendering', () => {
    for (const project of [
      'client:alice:',
      'client:alice:!',
      'client:alice:a',
      'client:alice:_w',
    ]) {
      expect(projectLabel(project)).toBe(project);
    }
  });
});
