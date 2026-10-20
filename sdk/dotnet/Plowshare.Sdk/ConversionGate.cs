namespace Plowshare.Sdk;

/// <summary>Serializes transient codec copies before exact wire-byte admission.
/// The lease ends before writer/response waits; requests remain concurrently in flight.
/// Caller DTO storage and runtime object overhead are outside the wire-byte budget.
/// </summary>
internal sealed class ConversionGate
{
    private readonly SemaphoreSlim capacity = new(1, 1);
    internal async Task<IDisposable> EnterAsync(CancellationToken cancellationToken)
    {
        await capacity.WaitAsync(cancellationToken);
        return new Lease(capacity);
    }
    private sealed class Lease(SemaphoreSlim owner) : IDisposable
    {
        private SemaphoreSlim? held = owner;
        public void Dispose() => Interlocked.Exchange(ref held, null)?.Release();
    }
}
