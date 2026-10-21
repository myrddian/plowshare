using Plowshare.Sdk;

internal static class AuthChecks
{
    private const string Origin = "https://auth.example";
    private sealed class Authority : IAuthentication
    {
        public string Origin => AuthChecks.Origin;
        internal int Rotations;
        internal Func<CancellationToken, Task<Tokens>>? Rotation;
        internal bool Unavailable;
        public Task<SignIn> LoginAsync(string handle, string password, CancellationToken cancellationToken = default) => Task.FromResult(new SignIn(new Tokens("access0", "refresh0"), false));
        public Task<Standing> StandingAsync(Tokens tokens, CancellationToken cancellationToken = default) => Unavailable ? Task.FromException<Standing>(new IOException("private diagnostic")) : Task.FromResult(new Standing(tokens.Access != "access0"));
        public Task<Tokens> RefreshAsync(Tokens tokens, CancellationToken cancellationToken = default) { Interlocked.Increment(ref Rotations); return Rotation?.Invoke(cancellationToken) ?? Task.FromResult(new Tokens("access1", "refresh1")); }
        public Task LogoutAsync(Tokens tokens, CancellationToken cancellationToken = default) => Task.CompletedTask;
    }
    private static async Task Refused(Func<Task> work)
    {
        try { await work(); } catch (Exception error) when (error is IOException or OperationCanceledException) { return; }
        throw new Exception("Authentication refusal was hidden");
    }
    internal static async Task RunAsync()
    {
        string root = Path.Combine(Path.GetTempPath(), "sdk-auth-tests-" + Guid.NewGuid()); Directory.CreateDirectory(root);
        try
        {
            var auth = new Authority(); var store = new Credentials(Origin, "alice", root, TimeSpan.FromSeconds(2));
            string file = Path.Combine(root, "connections", Credentials.ConnectionKey(Origin, "alice"), "credentials", AuthState.Hash(Origin) + ".json");
            await store.LoginAsync("alice", "fixture-password", auth);
            var sessions = await Task.WhenAll(Enumerable.Range(0, 6).Select(_ => store.AuthenticatedAsync(auth)));
            if (auth.Rotations != 1 || sessions.Any(s => s.Tokens.Access != "access1") || (await store.SessionAsync()).Tokens.Access != "access1") throw new Exception("Concurrent renewal replayed");
            if (sessions[0].ToString().Contains("access1")) throw new Exception("Credential diagnostic leaked");
            auth.Unavailable = true; await Refused(() => store.AuthenticatedAsync(auth));
            if (auth.Rotations != 1) throw new Exception("Renewed without explicit authentication rejection");
            await Refused(() => store.LoginAsync("bob", "password", auth));
            foreach (bool cancel in new[] { false, true })
            {
                await store.LoginAsync("alice", "fixture-password", auth);
                using var cancellation = new CancellationTokenSource();
                auth.Rotation = token =>
                {
                    if (cancel) { cancellation.Cancel(); return Task.FromResult(new Tokens("new", "new-refresh")); }
                    throw new IOException("private diagnostic");
                };
                await Refused(() => store.RenewAsync(auth, cancellationToken: cancellation.Token));
                int count = auth.Rotations; await Refused(() => store.RenewAsync(auth, "older"));
                if (auth.Rotations != count) throw new Exception("Cancelled/failed refresh replayed");
            }
            await store.LoginAsync("alice", "fixture-password", auth);
            string valid = File.ReadAllText(file);
            foreach (string invalid in new[] { valid.Replace("access0", "pss_service"), valid.Replace("\"renewing\":false", "\"renewing\":null"), valid.Replace("\"version\":1", "\"version\":true"), valid.Replace("\"refresh\":\"refresh0\"", "\"refresh\":null") })
            { File.WriteAllText(file, invalid); await Refused(() => store.SessionAsync()); }
            File.Delete(file); string target = Path.Combine(root, "target"); File.WriteAllText(target, valid); File.CreateSymbolicLink(file, target); await Refused(() => store.SessionAsync()); File.Delete(file);
            await store.LoginAsync("alice", "fixture-password", auth);
            auth.Rotation = _ => { File.Delete(file); Directory.CreateDirectory(file); return Task.FromResult(new Tokens("new", "new-refresh")); };
            await Refused(() => store.RenewAsync(auth));
            if (!Directory.Exists(file + ".lock")) throw new Exception("Persistence failure released its fence");
            if (Credentials.ConnectionKey("https://EXAMPLE.com:443/", "alice") != Credentials.ConnectionKey("https://example.com", "alice")) throw new Exception("Canonical identity differs");
        }
        finally { Directory.Delete(root, recursive: true); }
        Console.WriteLine(".NET user authentication lifecycle checks passed");
    }
}
