import type { InformationFacetCounts } from 'plowshare-client-ts/operations/information-replies';
import { background } from './events.ts';

export type FacetProgress =
  | { state: 'loading' }
  | { state: 'ready' }
  | { state: 'failed'; reason: unknown };

interface CollectionRead<T> {
  facetKey: string;
  current: () => boolean;
  facetsCurrent: () => boolean;
  list: () => Promise<T>;
  facets?: () => Promise<InformationFacetCounts>;
  showList: (value: T) => Promise<void>;
  showFacets: (value: InformationFacetCounts) => void;
  facetProgress: (value: FacetProgress) => void;
}

/** Owns pending facet work, not retained data. A slow aggregate never delays the list.
 * The caller supplies live connection/selection fences for both independent replies.
 */
export class InformationCollectionReads {
  private generation = 0;
  private pending: { key: string; generation: number } | undefined;

  invalidate(): void {
    this.generation++;
    this.pending = undefined;
  }

  async load<T>(read: CollectionRead<T>): Promise<void> {
    // Keep the list first on the shared transport even when the server orders reads.
    const list = read.list();
    if (read.facets && this.pending?.key !== read.facetKey) {
      const generation = ++this.generation;
      this.pending = { key: read.facetKey, generation };
      const current = () =>
        generation === this.generation && read.facetsCurrent();
      read.facetProgress({ state: 'loading' });
      // Attach both outcomes before awaiting either read. Rejected facets belong to
      // their panel and cannot blank the list or become an unhandled rejection.
      background(
        read
          .facets()
          .then((value) => {
            if (current()) {
              read.showFacets(value);
              read.facetProgress({ state: 'ready' });
            }
          })
          .catch((reason: unknown) => {
            if (current()) read.facetProgress({ state: 'failed', reason });
          })
          .finally(() => {
            if (this.pending?.generation === generation)
              this.pending = undefined;
          }),
      );
    }
    const result = await list;
    if (read.current()) await read.showList(result);
  }
}
