/** Authoring remains an ordinary granted caller turn; installation is a separate human decision. */
export const AUTHORING = 'design_orchestration';
export function authoringRequest(intent: string, revision?: string): string {
  if (typeof intent !== 'string' || !intent.trim() || intent.length > 8000)
    throw new Error('Describe the procedure in 1–8,000 characters.');
  if (revision !== undefined && !/^[a-z][a-z0-9_]{0,127}$/.test(revision))
    throw new Error('Choose a valid orchestration name to revise.');
  if (revision === AUTHORING)
    throw new Error(
      'The required system builder cannot be revised in a project.',
    );
  return `Start the granted ${AUTHORING} orchestration to interview me and author an agent-driven procedure${revision ? ` revising ${revision}` : ''}. Pass this intent to the builder as data. Ask me about outputs, evidence, discretion, human checkpoints, stages, returns, grants, failures and caps. Validate the draft through Studio and request my explicit installation decision. Do not install or run the resulting procedure automatically.\n\nPerson's intent:\n${intent.trim()}`;
}
export function authoringReady(
  project: string | undefined,
  rooted: boolean,
  caller: { served: boolean; orchestrations: readonly string[] } | undefined,
  builder: { served: boolean; withheld?: string | null } | undefined,
) {
  if (!project || !rooted)
    throw new Error(
      'Connect this project’s folder before authoring an orchestration.',
    );
  if (!builder?.served)
    throw new Error(
      builder?.withheld || 'The system orchestration builder is unavailable.',
    );
  if (!caller?.served || !caller.orchestrations.includes(AUTHORING))
    throw new Error(
      'Choose an available caller agent granted design_orchestration.',
    );
}
