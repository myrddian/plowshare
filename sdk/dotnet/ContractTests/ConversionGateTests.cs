using Plowshare.Sdk;

internal static class ConversionGateChecks
{
    internal static async Task RunAsync()
    {
        var gate = new ConversionGate();
        var holder = await gate.EnterAsync(CancellationToken.None);
        using var cancelled = new CancellationTokenSource();
        var encoded = 0;
        async Task EncodeAsync()
        {
            using var lease = await gate.EnterAsync(cancelled.Token);
            Interlocked.Increment(ref encoded);
        }
        var queued = EncodeAsync();
        if (queued.IsCompleted) throw new Exception("Concurrent conversion crossed the active lease");
        cancelled.Cancel();
        try { await queued; throw new Exception("Cancelled converter encoded a request"); }
        catch (OperationCanceledException) when (cancelled.IsCancellationRequested) { }
        if (encoded != 0) throw new Exception("Cancelled converter reached encoding/submission");
        holder.Dispose();
        holder.Dispose();
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(1));
        using var next = await gate.EnterAsync(deadline.Token);
        Console.WriteLine(".NET bounded conversion/cancellation checks passed");
    }
}
