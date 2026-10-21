using System.Diagnostics;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Plowshare.Sdk;

/// <summary>Local identity metadata, independent of current server authority.</summary>
public sealed record UserSession(string Origin, string Handle, Tokens Tokens);

/// <summary>Optional shared native user-login store. A shared mkdir lock covers
/// reload, renewal intent, HTTP rotation and persistence. No application operation
/// reconnects or replays; callers retain original mutation UUIDs and payloads.</summary>
public sealed class Credentials
{
    public string Origin { get; }
    private readonly string? account;
    private readonly string root;
    private readonly string[] legacy;
    private readonly TimeSpan lockTimeout;
    private sealed record Saved(int Version, string Origin, string Handle, Tokens Tokens, bool Renewing = false);
    private sealed record Migration(int Version, string Origin, string Account);
    public Credentials(string origin, string? account = null, string? directory = null, TimeSpan? lockTimeout = null)
    {
        Origin = AuthState.CanonicalOrigin(origin); this.account = account is null ? null : AuthState.Account(account);
        root = directory ?? ConfigurationDirectory(); this.lockTimeout = lockTimeout ?? TimeSpan.FromSeconds(10);
        if (!Path.IsPathFullyQualified(root) || this.lockTimeout <= TimeSpan.Zero || this.lockTimeout.TotalMilliseconds > int.MaxValue) throw new AuthenticationException("Use an absolute state root and positive bounded lock timeout");
        var sources = new List<string> { Path.Combine(root, "credentials") };
        if (directory is null && Environment.GetEnvironmentVariable("PLOWSHARE_CONFIG_DIR") is null && Environment.GetEnvironmentVariable("PLOWSHARE_DESKTOP_CONFIG") is null)
        {
            string xdg = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config");
            if (!Path.IsPathFullyQualified(xdg)) throw new AuthenticationException("XDG_CONFIG_HOME must be absolute");
            sources.Add(Path.Combine(xdg, "plowshare", "credentials"));
        }
        legacy = sources.ToArray();
    }
    private Credentials(Credentials parent, string handle) { Origin = parent.Origin; account = handle; root = parent.root; legacy = parent.legacy; lockTimeout = parent.lockTimeout; }
    /// <summary>Shared native configuration precedence; XDG is migration-only.</summary>
    public static string ConfigurationDirectory()
    {
        string root = Environment.GetEnvironmentVariable("PLOWSHARE_CONFIG_DIR") ?? Environment.GetEnvironmentVariable("PLOWSHARE_DESKTOP_CONFIG") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".plowshare");
        return Path.IsPathFullyQualified(root) ? root : throw new AuthenticationException("Configure an absolute authentication root");
    }
    public static string ConnectionKey(string origin, string account) => AuthState.Key(origin, account);
    private string Scope => Path.Combine(root, "connections", ConnectionKey(Origin, account!));
    private string FilePath => Path.Combine(Scope, "credentials", AuthState.Hash(Origin) + ".json");
    private void Directories() { foreach (string path in new[] { root, Path.Combine(root, "connections"), Scope, Path.GetDirectoryName(FilePath)! }) AuthState.DirectoryPrivate(path); }
    private sealed class StoreLock(string path) : IDisposable
    {
        internal bool Retain { get; set; }
        public void Dispose() { if (!Retain) Directory.Delete(path); }
    }
    private async Task<StoreLock> LockAsync(string file, CancellationToken cancellationToken)
    {
        string path = file + ".lock"; var watch = Stopwatch.StartNew();
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (AuthState.TryLock(path)) return new StoreLock(path);
            if (watch.Elapsed >= lockTimeout) throw new AuthenticationException("Credential store busy; inspect interrupted locks, never steal them");
            await Task.Delay(25, cancellationToken).ConfigureAwait(false);
        }
    }
    private Saved Decode(JsonElement raw)
    {
        AuthState.Fields(raw, ["version", "origin", "handle", "tokens"], ["renewing"]); AuthState.Version(raw);
        string origin = AuthState.Text(raw, "origin"), handle = AuthState.Account(AuthState.Text(raw, "handle"));
        if (AuthState.CanonicalOrigin(origin) != origin) throw new AuthenticationException("Invalid saved origin");
        var pair = raw.GetProperty("tokens"); AuthState.Fields(pair, ["access", "refresh"]);
        var tokens = new Tokens(AuthState.Text(pair, "access"), AuthState.Text(pair, "refresh"));
        if (tokens.Access.StartsWith("pss_", StringComparison.Ordinal)) throw new AuthenticationException("An ordinary saved user session is required");
        bool renewing = false;
        if (raw.TryGetProperty("renewing", out var flag))
        {
            if (flag.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw new AuthenticationException("Invalid renewal intent");
            renewing = flag.GetBoolean();
        }
        return new Saved(1, origin, handle, tokens, renewing);
    }
    private static Migration DecodeMigration(JsonElement raw)
    {
        AuthState.Fields(raw, ["version", "origin", "account"]); AuthState.Version(raw);
        string origin = AuthState.Text(raw, "origin"), handle = AuthState.Account(AuthState.Text(raw, "account"));
        if (AuthState.CanonicalOrigin(origin) != origin) throw new AuthenticationException("Invalid migration origin");
        return new Migration(1, origin, handle);
    }
    private void Mark() => AuthState.Write(Path.Combine(Scope, "credential-migration.json"), new Migration(1, Origin, account!));
    private async Task MigrateAsync(CancellationToken cancellationToken)
    {
        var raw = AuthState.Read(Path.Combine(Scope, "credential-migration.json"));
        if (raw is not null) { var m = DecodeMigration(raw.Value); if (m.Origin != Origin || m.Account != account) throw new AuthenticationException("Migration identity differs"); return; }
        if (AuthState.Read(FilePath) is not null) return;
        foreach (string folder in legacy.Distinct())
        {
            if (!Directory.Exists(folder) && !File.Exists(folder)) continue;
            AuthState.DirectoryPrivate(folder); string source = Path.Combine(folder, Path.GetFileName(FilePath));
            using var held = await LockAsync(source, cancellationToken).ConfigureAwait(false);
            raw = AuthState.Read(source); if (raw is null) continue;
            var saved = Decode(raw.Value);
            if (saved.Origin != Origin) throw new AuthenticationException("Legacy origin differs");
            if (saved.Handle != account) continue;
            if (saved.Renewing) throw new LoginRequiredException();
            AuthState.MoveCredential(source, FilePath); AuthState.SyncDirectory(Path.GetDirectoryName(FilePath)!); Mark(); AuthState.SyncDirectory(folder); return;
        }
    }
    private Saved Load()
    {
        var raw = AuthState.Read(FilePath) ?? throw new LoginRequiredException(); var saved = Decode(raw);
        if (saved.Origin != Origin || saved.Handle != account) throw new AuthenticationException("Saved identity differs");
        if (saved.Renewing) throw new LoginRequiredException(); return saved;
    }
    private Credentials Chosen()
    {
        if (account is not null) return this;
        AuthState.DirectoryPrivate(root); var accounts = new HashSet<string>(StringComparer.Ordinal); string parent = Path.Combine(root, "connections");
        if (Directory.Exists(parent) || File.Exists(parent))
        {
            AuthState.DirectoryPrivate(parent); var directories = Directory.EnumerateFileSystemEntries(parent).Take(1025).ToArray();
            if (directories.Length > 1024) throw new AuthenticationException("Too many identities; select an account explicitly");
            foreach (string path in directories)
            {
                if (!Regex.IsMatch(Path.GetFileName(path), "^[a-f0-9]{64}$")) continue;
                AuthState.DirectoryPrivate(path); var raw = AuthState.Read(Path.Combine(path, "credential-migration.json"));
                if (raw is not null) { var m = DecodeMigration(raw.Value); if (Path.GetFileName(path) != ConnectionKey(m.Origin, m.Account)) throw new AuthenticationException("Migration key differs"); if (m.Origin == Origin) accounts.Add(m.Account); }
                string folder = Path.Combine(path, "credentials");
                if (Directory.Exists(folder) || File.Exists(folder))
                {
                    AuthState.DirectoryPrivate(folder); raw = AuthState.Read(Path.Combine(folder, AuthState.Hash(Origin) + ".json"));
                    if (raw is not null) { var s = Decode(raw.Value); if (s.Origin != Origin || Path.GetFileName(path) != ConnectionKey(s.Origin, s.Handle)) throw new AuthenticationException("Saved identity key differs"); accounts.Add(s.Handle); }
                }
            }
        }
        if (accounts.Count > 1) throw new AuthenticationException("Multiple saved accounts; select an account or named connection");
        if (accounts.Count == 1) return new Credentials(this, accounts.Single());
        foreach (string folder in legacy.Distinct())
        {
            if (!Directory.Exists(folder) && !File.Exists(folder)) continue;
            AuthState.DirectoryPrivate(folder); var raw = AuthState.Read(Path.Combine(folder, AuthState.Hash(Origin) + ".json"));
            if (raw is not null) { var s = Decode(raw.Value); if (s.Origin != Origin) throw new AuthenticationException("Legacy origin differs"); return new Credentials(this, s.Handle); }
        }
        throw new LoginRequiredException();
    }
    private async Task<T> WithAuthentication<T>(IAuthentication? auth, Func<IAuthentication, Task<T>> work)
    {
        var selected = auth ?? new HttpAuthentication(Origin);
        if (selected.Origin != Origin) throw new AuthenticationException("Authentication belongs to another server");
        return await work(selected).ConfigureAwait(false);
    }
    /// <summary>Reload local state under the shared lock, without network or renewal.</summary>
    public async Task<UserSession> SessionAsync(CancellationToken cancellationToken = default)
    {
        var c = Chosen(); c.Directories(); using var held = await c.LockAsync(c.FilePath, cancellationToken).ConfigureAwait(false);
        await c.MigrateAsync(cancellationToken).ConfigureAwait(false); var s = c.Load(); return new UserSession(s.Origin, s.Handle, s.Tokens);
    }
    /// <summary>Explicit login recovers interrupted renewal. Passwords are never saved.</summary>
    public Task<SignIn> LoginAsync(string handle, string password, IAuthentication? auth = null, CancellationToken cancellationToken = default) => WithAuthentication(auth, async a =>
    {
        AuthState.Account(handle); if (account is not null && account != handle) throw new AuthenticationException("Login conflicts with selected account");
        var c = new Credentials(this, handle); c.Directories(); using var held = await c.LockAsync(c.FilePath, cancellationToken).ConfigureAwait(false);
        var signed = await a.LoginAsync(handle, password, cancellationToken).ConfigureAwait(false); cancellationToken.ThrowIfCancellationRequested();
        if ((!signed.MustChangePassword && signed.Tokens.Refresh is null) || signed.Tokens.Access.StartsWith("pss_", StringComparison.Ordinal)) throw new AuthenticationException("An ordinary user session is required");
        if (!signed.MustChangePassword && signed.Tokens.Refresh is not null) { AuthState.Write(c.FilePath, new Saved(1, Origin, handle, signed.Tokens)); c.Mark(); }
        return signed;
    });
    private async Task<Tokens> RotateAsync(IAuthentication auth, Saved saved, StoreLock held, CancellationToken cancellationToken)
    {
        AuthState.Write(FilePath, saved with { Renewing = true }); Tokens pair;
        try
        {
            pair = await auth.RefreshAsync(saved.Tokens, cancellationToken).ConfigureAwait(false); cancellationToken.ThrowIfCancellationRequested();
            if (pair.Refresh is null || pair.Access.StartsWith("pss_", StringComparison.Ordinal)) throw new LoginRequiredException();
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception) { throw new LoginRequiredException(); }
        // If replace succeeds but fsync fails, even a readable pair is unproven.
        // Retain the lock as a second fence; no client may observe success.
        try { AuthState.Write(FilePath, saved with { Tokens = pair }); }
        catch { held.Retain = true; throw new AuthenticationException("Credential persistence failed; inspect retained lock and sign in again"); }
        return pair;
    }
    /// <summary>Rotate explicitly, or reuse a pair that already replaced rejectedAccess.
    /// Ambiguous/cancelled refresh remains fenced; locks are never stolen.</summary>
    public Task<Tokens> RenewAsync(IAuthentication? auth = null, string? rejectedAccess = null, CancellationToken cancellationToken = default) => WithAuthentication(auth, async a =>
    {
        if (rejectedAccess is not null) AuthState.Secret(rejectedAccess);
        var c = Chosen(); c.Directories(); using var held = await c.LockAsync(c.FilePath, cancellationToken).ConfigureAwait(false);
        await c.MigrateAsync(cancellationToken).ConfigureAwait(false); var s = c.Load();
        return rejectedAccess is not null && s.Tokens.Access != rejectedAccess ? s.Tokens : await c.RotateAsync(a, s, held, cancellationToken).ConfigureAwait(false);
    });
    /// <summary>Check server status; only explicit HTTP 401 permits renewal, once.</summary>
    public Task<UserSession> AuthenticatedAsync(IAuthentication? auth = null, CancellationToken cancellationToken = default) => WithAuthentication(auth, async a =>
    {
        var c = Chosen(); var s = await c.SessionAsync(cancellationToken).ConfigureAwait(false);
        var before = await a.StandingAsync(s.Tokens, cancellationToken).ConfigureAwait(false);
        if (before.SignedIn) { if (before.MustChangePassword) throw new LoginRequiredException(); return s; }
        var pair = await c.RenewAsync(a, s.Tokens.Access, cancellationToken).ConfigureAwait(false);
        var after = await a.StandingAsync(pair, cancellationToken).ConfigureAwait(false);
        if (!after.SignedIn || after.MustChangePassword) throw new LoginRequiredException();
        return new UserSession(s.Origin, s.Handle, pair);
    });
    /// <summary>Persist rotation, revoke, mark migration then delete. Revoke failure retains the pair.</summary>
    public async Task LogoutAsync(IAuthentication? auth = null, CancellationToken cancellationToken = default) =>
        _ = await WithAuthentication(auth, async a =>
        {
            var c = Chosen(); c.Directories(); using var held = await c.LockAsync(c.FilePath, cancellationToken).ConfigureAwait(false);
            await c.MigrateAsync(cancellationToken).ConfigureAwait(false);
            var pair = await c.RotateAsync(a, c.Load(), held, cancellationToken).ConfigureAwait(false);
            await a.LogoutAsync(pair, cancellationToken).ConfigureAwait(false); cancellationToken.ThrowIfCancellationRequested();
            c.Mark(); File.Delete(c.FilePath); AuthState.SyncDirectory(Path.GetDirectoryName(c.FilePath)!); return true;
        }).ConfigureAwait(false);
    /// <summary>Resolve the shared native registry with explicit/environment selector precedence.</summary>
    public static Credentials Resolve(string? origin = null, string? account = null, string? connection = null, string? directory = null) => AuthConnections.Resolve(origin, account, connection, directory);
}
