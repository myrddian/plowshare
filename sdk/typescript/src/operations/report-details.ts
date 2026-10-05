import type { ReportFinding, ReportReview } from './information-payloads.ts';
/** Retained judgments mirror InformationReportDetails. Checking a reference's
 * syntax conveys no authority and does not establish support for a claim. */
export interface ReportDetails {
  readonly objectives?: readonly string[];
  readonly findings?: readonly ReportFinding[];
  readonly reviews?: readonly ReportReview[];
  readonly scopeChanges?: readonly string[];
}
export function validateReportDetails(value: ReportDetails): void {
  const text = (value: string): void => {
    if (!value.trim() || value.length > 32768 || value.includes('\0'))
      throw new Error('Report text must be nonblank within 32768 characters.');
  };
  for (const values of [value.objectives, value.scopeChanges])
    for (const entry of values ?? []) text(entry);
  const ids = new Set<string>();
  for (const finding of value.findings ?? []) {
    for (const field of [
      finding.id,
      finding.objective,
      finding.claim,
      finding.rationale,
    ])
      text(field);
    if (
      ids.has(finding.id) ||
      !(value.objectives ?? []).includes(finding.objective)
    )
      throw new Error(
        'Findings require distinct ids and a supplied objective.',
      );
    ids.add(finding.id);
    for (const id of [
      ...(finding.support ?? []),
      ...(finding.counterEvidence ?? []),
    ])
      if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(id))
        throw new Error('Report evidence must use UUID references.');
  }
  for (const review of value.reviews ?? [])
    for (const field of [review.stage, review.outcome, review.text])
      text(field);
}
