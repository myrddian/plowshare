using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Plowshare.Sdk;

/// <summary>Optional authentication HTTP bootstrap; never replays application operations.</summary>
public interface IAuthentication
{
    string Origin { get; }
    Task<SignIn> LoginAsync(string handle, string password, CancellationToken cancellationToken = default);
    Task<Standing> StandingAsync(Tokens tokens, CancellationToken cancellationToken = default);
    Task<Tokens> RefreshAsync(Tokens tokens, CancellationToken cancellationToken = default);
    Task LogoutAsync(Tokens tokens, CancellationToken cancellationToken = default);
}

/// <summary>Private bearer material. Refresh is absent only for nonrenewable sessions.</summary>
public sealed record Tokens
{
    public string Access { get; }
    public string? Refresh { get; }
    public Tokens(string access, string? refresh = null)
    {
        Access = AuthState.Secret(access);
        Refresh = refresh is null ? null : AuthState.Secret(refresh);
    }
    public override string ToString() => "Tokens[redacted]";
}
public sealed record SignIn(Tokens Tokens, bool MustChangePassword, bool SetupRequired = false);
public sealed record Standing(bool SignedIn, bool MustChangePassword = false);
public class AuthenticationException : IOException
{
    public AuthenticationException(string message = "Authentication refused, invalid or unavailable") : base(message) { }
}
public sealed class LoginRequiredException : AuthenticationException
{
    public LoginRequiredException() : base("Sign in explicitly; no saved session or renewal was interrupted") { }
}

/// <summary>Native redirect-free HTTP authentication, with bounded responses and no replay.
/// Failed refresh, including cancellation, remains fenced by Credentials.</summary>
public sealed class HttpAuthentication : IAuthentication
{
    public string Origin { get; }
    private readonly TimeSpan timeout;
    private readonly string accessCookie, refreshCookie;
    public HttpAuthentication(string origin, TimeSpan? timeout = null, string accessCookie = "ps_access", string refreshCookie = "ps_refresh")
    {
        Origin = AuthState.CanonicalOrigin(origin);
        var deadline = timeout ?? TimeSpan.FromSeconds(30);
        if (deadline <= TimeSpan.Zero || deadline.TotalMilliseconds > int.MaxValue) throw new ArgumentException("Use a bounded positive authentication timeout");
        if (!System.Text.RegularExpressions.Regex.IsMatch(accessCookie, "^[A-Za-z0-9_]{1,64}$") || !System.Text.RegularExpressions.Regex.IsMatch(refreshCookie, "^[A-Za-z0-9_]{1,64}$") || accessCookie == refreshCookie) throw new ArgumentException("Invalid authentication cookie names");
        this.accessCookie = accessCookie; this.refreshCookie = refreshCookie;
        this.timeout = deadline;
    }
    private async Task<T> RequestAsync<T>(HttpRequestMessage request, Func<HttpResponseMessage, byte[], T> decode, CancellationToken cancellationToken)
    {
        // SocketsHttpHandler may retry a POST when the server closes before a
        // response, even after that server consumed its refresh token. Give each
        // exchange one physical connection and refuse a second connect BEFORE
        // any retried headers/cookies can reach the server. HTTP/1.1 exact mode
        // also excludes transparent HTTP/2 stream retry on an existing socket.
        int connections = 0;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(timeout);
        using var http = new HttpClient(new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            UseCookies = false,
            ConnectCallback = async (context, token) =>
            {
                if (Interlocked.Increment(ref connections) != 1)
                    throw new AuthenticationException("Authentication request was not replayed");
                var socket = new Socket(SocketType.Stream, ProtocolType.Tcp);
                try { await socket.ConnectAsync(context.DnsEndPoint, token).ConfigureAwait(false); return new NetworkStream(socket, ownsSocket: true); }
                catch { socket.Dispose(); throw; }
            }
        })
        { Timeout = Timeout.InfiniteTimeSpan };
        using (request)
            try
            {
                using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
                using var stream = await response.Content.ReadAsStreamAsync(deadline.Token).ConfigureAwait(false);
                // One deadline covers connect, headers and a stalled body; the
                // streaming HttpClient timeout alone would end at headers.
                using var body = new MemoryStream();
                byte[] buffer = new byte[8192];
                while (true)
                {
                    int count = await stream.ReadAsync(buffer, deadline.Token).ConfigureAwait(false);
                    if (count == 0) break;
                    if (body.Length + count > 65536) throw new AuthenticationException("Authentication response too large");
                    body.Write(buffer, 0, count);
                }
                return decode(response, body.ToArray());
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
            catch (Exception error) when (error is HttpRequestException or IOException or JsonException or ArgumentException or OperationCanceledException)
            { throw new AuthenticationException(); }
    }
    private HttpRequestMessage At(HttpMethod method, string path, Tokens? tokens = null)
    {
        var request = new HttpRequestMessage(method, Origin + path) { Version = HttpVersion.Version11, VersionPolicy = HttpVersionPolicy.RequestVersionExact };
        request.Headers.ConnectionClose = true;
        if (tokens is not null) request.Headers.Authorization = new("Bearer", tokens.Access);
        return request;
    }
    public Task<SignIn> LoginAsync(string handle, string password, CancellationToken cancellationToken = default)
    {
        AuthState.Account(handle);
        if (string.IsNullOrEmpty(password) || password.Length > 4096) throw new ArgumentException("A bounded nonempty password is required");
        var request = At(HttpMethod.Post, "/v1/auth/login");
        request.Headers.Add("X-Plowshare-Token-Delivery", "body");
        request.Content = new StringContent(JsonSerializer.Serialize(new LoginBody(handle, password)), Encoding.UTF8, "application/json");
        return RequestAsync(request, (response, body) =>
        {
            if (response.StatusCode != HttpStatusCode.OK) throw new AuthenticationException();
            using var json = JsonDocument.Parse(body);
            var raw = json.RootElement;
            if (raw.ValueKind != JsonValueKind.Object || !AuthState.Unique(raw) || !raw.TryGetProperty("mustChangePassword", out var flag) || flag.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw new AuthenticationException();
            string access = AuthState.Text(raw, "access");
            string? refresh = raw.TryGetProperty("refresh", out var renewal) && renewal.ValueKind != JsonValueKind.Null ? AuthState.Text(raw, "refresh") : null;
            bool restricted = flag.GetBoolean();
            if (!restricted && refresh is null) throw new AuthenticationException();
            return new SignIn(new Tokens(access, refresh), restricted, Header(response, "X-Plowshare-Setup-Required"));
        }, cancellationToken);
    }
    private sealed record LoginBody(string handle, string password) { public override string ToString() => "Login[redacted]"; }
    private static bool Header(HttpResponseMessage response, string name)
    {
        if (!response.Headers.TryGetValues(name, out var values)) return false;
        var rows = values.ToArray(); if (rows.Length != 1) throw new AuthenticationException("Ambiguous authentication header");
        return rows[0] == "true";
    }
    public Task<Standing> StandingAsync(Tokens tokens, CancellationToken cancellationToken = default) =>
        RequestAsync(At(HttpMethod.Get, "/v1/auth/session", tokens), (response, _) => response.StatusCode switch
        {
            HttpStatusCode.Unauthorized => new Standing(false),
            HttpStatusCode.NoContent => new Standing(true, Header(response, "X-Plowshare-Must-Change-Password")),
            _ => throw new AuthenticationException()
        }, cancellationToken);
    public Task<Tokens> RefreshAsync(Tokens tokens, CancellationToken cancellationToken = default)
    {
        if (tokens.Refresh is null) throw new LoginRequiredException();
        var request = At(HttpMethod.Post, "/v1/auth/refresh");
        request.Headers.Add("Cookie", refreshCookie + "=" + tokens.Refresh);
        return RequestAsync(request, (response, _) =>
        {
            if (response.StatusCode != HttpStatusCode.NoContent || !response.Headers.TryGetValues("Set-Cookie", out var cookies)) throw new AuthenticationException();
            var jar = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var cookie in cookies)
            {
                var parts = cookie.Split(';'); var pair = parts[0].Split('=', 2);
                if (pair.Length != 2) continue;
                string name = pair[0].Trim();
                if (name != accessCookie && name != refreshCookie) continue;
                if (parts.Skip(1).Any(p => p.Split('=', 2)[0].Trim() is var key && (key == accessCookie || key == refreshCookie)) || !jar.TryAdd(name, AuthState.Secret(pair[1]))) throw new AuthenticationException();
            }
            if (!jar.TryGetValue(accessCookie, out var access) || !jar.TryGetValue(refreshCookie, out var refresh)) throw new AuthenticationException();
            return new Tokens(access, refresh);
        }, cancellationToken);
    }
    public async Task LogoutAsync(Tokens tokens, CancellationToken cancellationToken = default) =>
        _ = await RequestAsync(At(HttpMethod.Post, "/v1/auth/logout", tokens), (response, _) => response.StatusCode == HttpStatusCode.NoContent ? true : throw new AuthenticationException(), cancellationToken).ConfigureAwait(false);
}
