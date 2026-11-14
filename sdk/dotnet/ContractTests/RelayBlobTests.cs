using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Plowshare.Sdk;

internal static class RelayBlobChecks
{
    private const string Id = "11111111-1111-1111-1111-111111111111";
    private static readonly byte[] Data = [0, 1, 2, 255];
    private static readonly RelayBlobPublishDto Publication = new() { RequestId = Id, Project = "project", Topic = "event", OccurredAt = "2026-11-13T00:00:00Z", MediaType = "application/octet-stream" };
    private static readonly RelayBlobReadDto Read = new() { EventId = Id, Project = "project", Topic = "event", Group = "workers" };
    private static readonly RelayBlobDescriptorDto Descriptor = new() { ByteLength = Data.Length, MediaType = "application/octet-stream", Sha256 = Convert.ToHexString(SHA256.HashData(Data)).ToLowerInvariant() };
    internal static async Task RunAsync()
    {
        foreach (var kind in new[] { "valid", "redirect", "foreign", "short", "overflow", "hash", "headers", "malformed", "refused" })
        {
            await using var fixture = new Fixture(kind);
            using var client = new RelayBlobs(fixture.Origin, "fixture", TimeSpan.FromSeconds(2));
            try
            {
                if (kind is "valid" or "foreign" or "malformed" or "redirect")
                {
                    var receipt = await client.PublishAsync(Publication, Data);
                    if (receipt.Publication.Position != "9007199254740993") throw new Exception("Decimal position corrupted");
                    if (kind != "valid") throw new Exception("Invalid receipt or redirect accepted");
                    if (!(await client.ReadAsync(Read, receipt.Blob)).SequenceEqual(Data)) throw new Exception("Binary bytes changed");
                }
                else { await client.ReadAsync(Read, Descriptor); throw new Exception("Invalid download accepted"); }
            }
            catch (TransportException) when (kind != "valid") { }
            if (fixture.Calls != (kind == "valid" ? 2 : 1)) throw new Exception("Binary transfer replayed or followed redirect");
        }
        await using (var fixture = new Fixture("valid"))
        using (var client = new RelayBlobs(fixture.Origin, "fixture"))
        {
            try { await client.PublishAsync(Publication with { ParentTopic = "event" }, Data); throw new Exception("Partial ancestry accepted"); } catch (JsonException) { }
            try { await client.ReadAsync(Read, Descriptor with { ByteLength = 1.5 }); throw new Exception("Fractional size accepted"); } catch (JsonException) { }
            using var cancel = new CancellationTokenSource(); cancel.Cancel();
            try { await client.PublishAsync(Publication, Data, cancel.Token); throw new Exception("Cancelled request submitted"); } catch (RequestCancelledException failure) when (failure.Delivery == Delivery.NotSubmitted) { }
            if (fixture.Calls != 0) throw new Exception("Invalid request submitted");
        }
        await using (var fixture = new Fixture("wait"))
        using (var client = new RelayBlobs(fixture.Origin, "fixture"))
        {
            using var cancel = new CancellationTokenSource(); var call = client.PublishAsync(Publication, Data, cancel.Token);
            await fixture.Started.Task.WaitAsync(TimeSpan.FromSeconds(3)); cancel.Cancel();
            try { await call; throw new Exception("Cancelled call succeeded"); } catch (RequestCancelledException failure) when (failure.Delivery == Delivery.Unknown) { }
            fixture.Release.TrySetResult(); if (fixture.Calls != 1) throw new Exception("Cancelled publication replayed");
        }
        Console.WriteLine(".NET binary HTTP transfer checks passed");
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly HttpListener listener = new();
        private readonly Task server;
        private readonly string kind;
        internal string Origin { get; }
        internal int Calls { get; private set; }
        internal TaskCompletionSource Started { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal TaskCompletionSource Release { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal Fixture(string kind)
        {
            this.kind = kind;
            using var probe = new TcpListener(IPAddress.Loopback, 0); probe.Start(); var port = ((IPEndPoint)probe.LocalEndpoint).Port; probe.Stop();
            Origin = $"http://127.0.0.1:{port}"; listener.Prefixes.Add(Origin + "/"); listener.Start(); server = ServeAsync();
        }
        private async Task ServeAsync()
        {
            try
            {
                while (listener.IsListening)
                {
                    var context = await listener.GetContextAsync(); Calls++;
                    using var body = new MemoryStream(); await context.Request.InputStream.CopyToAsync(body);
                    if (context.Request.Headers["Authorization"] != "Bearer fixture") throw new Exception("Missing caller bearer");
                    Started.TrySetResult();
                    if (kind == "wait") { await Release.Task; context.Response.Close(); continue; }
                    var response = context.Response;
                    if (kind == "redirect") { response.StatusCode = 307; response.RedirectLocation = Origin + "/leak"; response.Close(); continue; }
                    if (kind == "refused") { response.StatusCode = 403; response.Close(); continue; }
                    byte[] data;
                    if (context.Request.HttpMethod == "POST")
                    {
                        if (!Encoding.UTF8.GetString(body.ToArray()).Contains(Id) || body.ToArray().AsSpan().IndexOf(Data) < 0) throw new Exception("Invalid multipart metadata or bytes");
                        data = kind == "malformed" ? "{}"u8.ToArray() : JsonSerializer.SerializeToUtf8Bytes(new RelayBlobPublishedDto { Blob = Descriptor, Publication = new() { RequestId = Id, Project = kind == "foreign" ? "other" : "project", Topic = "event", Position = "9007199254740993", PublishedAt = "2026-11-13T00:00:00Z" } });
                    }
                    else
                    {
                        response.ContentType = kind == "headers" ? "text/plain" : "application/octet-stream";
                        data = kind switch { "short" => Data[..2], "overflow" => [.. Data, 1], "hash" => [1, 1, 1, 1], _ => Data };
                    }
                    response.ContentLength64 = data.Length; await response.OutputStream.WriteAsync(data); response.Close();
                }
            }
            catch (HttpListenerException) when (!listener.IsListening) { }
            catch (ObjectDisposedException) when (!listener.IsListening) { }
        }
        public async ValueTask DisposeAsync()
        {
            Release.TrySetResult(); listener.Close(); await server.WaitAsync(TimeSpan.FromSeconds(3));
        }
    }
}
