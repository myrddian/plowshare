/** The stable deep-research completion receipt names the report before its separate audit.
 * Old completed runs remain readable; unrelated UUIDs are never interpreted as reports. */
export function retainedReport(result: string | null | undefined): string | undefined {
  const id = result?.match(/^Research completed: [\s\S]*?The full report with source links is retained as information revision ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\. Read it with information_read /i)?.[1];
  return id;
}
