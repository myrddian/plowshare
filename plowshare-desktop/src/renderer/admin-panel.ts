import type { DesktopState } from '../shared.ts';

/** A workspace-owned administration section. Leaving it clears credentials and fences pending reads. */
export interface AdministrationPanel {
  readonly element: HTMLElement;
  open(): void;
  close(): void;
  update(state: DesktopState): void;
}
