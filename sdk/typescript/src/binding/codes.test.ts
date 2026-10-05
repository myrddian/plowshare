import { describe, expect, it } from 'vitest';
import { CODES, isCode, succeeded } from './codes.ts';

/**
 * The vocabulary, asserted as a set rather than one constant at a time.
 *
 * The cross-language half of this — that these seventeen are the seventeen
 * `Code.java` declares, spelled the same way — is `src/mirrors-the-server.test.ts`,
 * which reads the Java. It cannot live here: this directory's tsconfig project
 * has `types: []`, so a `node:fs` import in a file under it does not compile.
 * Same reason `neutrality.test.ts` sits where it sits.
 */
describe('the status vocabulary', () => {
  it('has seventeen constants and no duplicates', () => {
    expect(CODES).toHaveLength(17);
    expect(new Set(CODES).size).toBe(17);
  });

  it('carries four successes, kept apart from one another', () => {
    expect(CODES.filter(succeeded)).toEqual([
      'OK',
      'ACCEPTED',
      'NO_CONTENT',
      'CREATED',
    ]);
  });

  it('does not read ACCEPTED as OK, because a handle is not a result', () => {
    // The mistake Code.java's own javadoc records as the reachable one:
    // "telling a client its work had finished when what it was handed is a
    // job handle to poll." Both succeed; they are not the same answer.
    expect(succeeded('ACCEPTED')).toBe(true);
    expect(succeeded('OK')).toBe(true);
    expect(CODES.indexOf('ACCEPTED')).not.toBe(CODES.indexOf('OK'));
  });

  it('counts thirteen failures: the table ApiExceptionHandler holds, and the model being away', () => {
    expect(CODES.filter((code) => !succeeded(code))).toHaveLength(13);
  });

  it('recognises a code off the wire, and refuses anything else', () => {
    expect(isCode('CONFLICT')).toBe(true);
    expect(isCode('conflict')).toBe(false);
    expect(isCode('IM_A_TEAPOT')).toBe(false);
    expect(isCode(409)).toBe(false);
    expect(isCode(undefined)).toBe(false);
    expect(isCode(null)).toBe(false);
  });

  it('keeps the three statuses one image slug answers with apart', () => {
    // Code.java runs the fold backwards for row 10: one slug,
    // `image_not_stored`, over three statuses, which is three outcomes.
    expect(CODES.filter((code) => code.startsWith('IMAGE_NOT_STORED'))).toEqual(
      [
        'IMAGE_NOT_STORED_EMPTY',
        'IMAGE_NOT_STORED_UNRECOGNISED',
        'IMAGE_NOT_STORED_TOO_LARGE',
      ],
    );
  });
});
