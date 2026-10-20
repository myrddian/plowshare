// Share pending GETs only. Completed protected data stays in the visible model,
// never a second response cache. Mutations do not pass through this owner.
export class PendingReads {
  constructor(load, limit = 16) {
    this.load = load;
    this.limit = limit;
    this.pending = new Map();
  }

  read(path) {
    const existing = this.pending.get(path);
    if (existing) return existing.promise;
    if (this.pending.size >= this.limit)
      return Promise.reject(new Error('Too many reads. Try again shortly.'));
    const controller = new AbortController();
    const entry = { controller, promise: null };
    entry.promise = Promise.resolve()
      .then(() => this.load(path, controller.signal))
      .finally(() => {
        if (this.pending.get(path) === entry) this.pending.delete(path);
      });
    this.pending.set(path, entry);
    return entry.promise;
  }

  invalidate(paths = [...this.pending.keys()]) {
    for (const path of paths) {
      const entry = this.pending.get(path);
      this.pending.delete(path);
      entry?.controller.abort();
    }
  }
}
