using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Plowshare.Sdk;

/// <summary>Single-attempt binary HTTP transfers with explicit origin and caller bearer.
/// Retain publication UUIDs and original bytes before submitting. Cancellation does
/// not cancel durable server work; an uncertain publication must be reconciled.</summary>
public sealed class RelayBlobs : IDisposable
{
    public const int MaxBytes = 50 * 1024 * 1024;
    private readonly string origin;
    private readonly HttpClient http;
    private readonly TimeSpan timeout;

    public RelayBlobs(string origin, string bearer, TimeSpan? timeout = null)
    {
        this.origin = AuthState.CanonicalOrigin(origin);
        if (bearer is null || bearer.Length is < 1 or > 8192 || !Regex.IsMatch(bearer, @"\A[\x21-\x7e]+\z"))
            throw new ArgumentException("Binary transfer requires a valid caller bearer");
        this.timeout = timeout ?? TimeSpan.FromSeconds(60);
        if (this.timeout <= TimeSpan.Zero || this.timeout > TimeSpan.FromMinutes(5)) throw new ArgumentOutOfRangeException(nameof(timeout));
        http = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.None, ConnectTimeout = this.timeout }) { Timeout = Timeout.InfiniteTimeSpan };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
        http.DefaultRequestHeaders.AcceptEncoding.ParseAdd("identity");
    }

    /// <summary>Publish a snapshot once; verify scope, receipt UUID, size, media type and hash.</summary>
    public async Task<RelayBlobPublishedDto> PublishAsync(RelayBlobPublishDto request, ReadOnlyMemory<byte> bytes, CancellationToken cancellationToken = default)
    {
        request = Codec.Http("RelayBlobPublish", request);
        if (bytes.Length is < 1 or > MaxBytes) throw new ArgumentException("Binary upload requires 1..50 MiB");
        var snapshot = bytes.ToArray();
        var hash = Convert.ToHexString(SHA256.HashData(snapshot)).ToLowerInvariant();
        using var body = new MultipartFormDataContent();
        // Only validated generated fields become part bodies, never part header values.
        void Add(string key, string value) => body.Add(new StringContent(value), key);
        Add("requestId", request.RequestId); Add("project", request.Project); Add("topic", request.Topic);
        Add("occurredAt", request.OccurredAt); Add("mediaType", request.MediaType);
        if (request.CorrelationId.IsSet) Add("correlationId", request.CorrelationId.Value);
        if (request.ParentTopic.IsSet) Add("parentTopic", request.ParentTopic.Value);
        if (request.ParentEventId.IsSet) Add("parentEventId", request.ParentEventId.Value);
        var file = new ByteArrayContent(snapshot); file.Headers.ContentType = new("application/octet-stream"); body.Add(file, "file", "blob.bin");
        using var message = new HttpRequestMessage(HttpMethod.Post, origin + "/v1/relay/blobs") { Content = body };
        var data = await TransferAsync(message, 16384, null, cancellationToken).ConfigureAwait(false);
        RelayBlobPublishedDto result;
        try { using var document = JsonDocument.Parse(data); result = Codec.HttpResult<RelayBlobPublishedDto>("RelayBlobPublished", document.RootElement); }
        catch (JsonException) { throw Invalid("Invalid binary receipt"); }
        var pub = result.Publication;
        if (pub.RequestId != request.RequestId || pub.Project != request.Project || pub.Topic != request.Topic || result.Blob.MediaType != request.MediaType || result.Blob.ByteLength != snapshot.Length || result.Blob.Sha256 != hash) throw Invalid("Foreign binary receipt");
        return result;
    }

    /// <summary>Read under current authority and verify the consumed descriptor before exposing bytes.</summary>
    public async Task<byte[]> ReadAsync(RelayBlobReadDto request, RelayBlobDescriptorDto expected, CancellationToken cancellationToken = default)
    {
        request = Codec.Http("RelayBlobRead", request); expected = Codec.Http("RelayBlobDescriptor", expected);
        var query = "project=" + Uri.EscapeDataString(request.Project) + "&topic=" + Uri.EscapeDataString(request.Topic) + "&group=" + Uri.EscapeDataString(request.Group) + "&eventId=" + Uri.EscapeDataString(request.EventId);
        using var message = new HttpRequestMessage(HttpMethod.Get, origin + "/v1/relay/blobs?" + query);
        var data = await TransferAsync(message, (int)expected.ByteLength, expected, cancellationToken).ConfigureAwait(false);
        if (data.Length != expected.ByteLength || Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant() != expected.Sha256) throw Invalid("Binary download failed integrity verification");
        return data;
    }

    private async Task<byte[]> TransferAsync(HttpRequestMessage request, int limit, RelayBlobDescriptorDto? expected, CancellationToken token)
    {
        if (token.IsCancellationRequested) throw new RequestCancelledException(Delivery.NotSubmitted, token);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token); deadline.CancelAfter(timeout);
        try
        {
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) throw new TransportException(Delivery.Unknown, $"Binary HTTP transfer refused ({(int)response.StatusCode}); reconcile original request");
            if (expected is not null)
            {
                if (response.Content.Headers.ContentType?.ToString() != "application/octet-stream" || response.Content.Headers.ContentLength is long length && length != limit) throw Invalid("Binary headers differ from descriptor");
                foreach (var (key, value) in new[] { ("X-Plowshare-Blob-Sha256", expected.Sha256), ("X-Plowshare-Blob-Media-Type", expected.MediaType) })
                    if (response.Headers.TryGetValues(key, out var values) && !values.SequenceEqual(new[] { value })) throw Invalid("Binary headers differ from descriptor");
            }
            await using var stream = await response.Content.ReadAsStreamAsync(deadline.Token).ConfigureAwait(false);
            using var output = new MemoryStream(); var buffer = new byte[Math.Min(65536, limit + 1)];
            while (true)
            {
                var count = await stream.ReadAsync(buffer.AsMemory(0, Math.Min(buffer.Length, limit + 1 - (int)output.Length)), deadline.Token).ConfigureAwait(false);
                if (count == 0) break;
                output.Write(buffer, 0, count);
                if (output.Length > limit) throw Invalid("Binary response exceeds its bound");
            }
            return output.ToArray();
        }
        catch (OperationCanceledException) { throw new RequestCancelledException(Delivery.Unknown, token); }
        catch (HttpRequestException) { throw new TransportException(Delivery.Unknown, "Binary HTTP transfer failed; reconcile original request"); }
        catch (IOException error) when (error is not TransportException) { throw new TransportException(Delivery.Unknown, "Binary response interrupted"); }
    }
    private static TransportException Invalid(string message) => new(Delivery.InvalidResponse, message);
    public void Dispose() => http.Dispose();
}
