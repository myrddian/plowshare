/** Fixture callbacks must fail the running test on rejection, including detached socket work. */
export function background(work: Promise<unknown>): void {
  void work.catch((reason: unknown) => {
    queueMicrotask(() => {
      throw reason;
    });
  });
}
