using System.Text.Json;
using System.Text.RegularExpressions;

namespace Plowshare.Sdk;

/// <summary>Strict read-only projection of the existing native connection registry.</summary>
internal static class AuthConnections
{
    private sealed record Entry(string Key, string Name, string Server, string Account);
    private static bool ValidName(string name) => name.Length is > 0 and <= 100 && name == name.Trim() && !name.Any(c => c < 32 || c == 127);
    internal static Credentials Resolve(string? origin, string? account, string? connection, string? directory)
    {
        string root = directory ?? Credentials.ConfigurationDirectory();
        if (!Path.IsPathFullyQualified(root)) throw new AuthenticationException("Use an absolute authentication root");
        AuthState.DirectoryPrivate(root); var raw = AuthState.Read(Path.Combine(root, "config.json"));
        var entries = new List<Entry>(); string? selected = null;
        if (raw is not null)
        {
            var config = raw.Value;
            AuthState.Fields(config, ["version", "connections"], ["selected", "fileStoreDefault", "connectionSetupPending"]); AuthState.Version(config);
            var rows = config.GetProperty("connections");
            if (rows.ValueKind != JsonValueKind.Array || rows.GetArrayLength() > 100) throw new AuthenticationException("Invalid connections");
            var names = new HashSet<string>(StringComparer.Ordinal); var keys = new HashSet<string>(StringComparer.Ordinal);
            foreach (var row in rows.EnumerateArray())
            {
                AuthState.Fields(row, ["key", "name", "server", "account", "reconnect"]);
                string key = AuthState.Text(row, "key"), name = AuthState.Text(row, "name"), server = AuthState.Text(row, "server"), handle = AuthState.Account(AuthState.Text(row, "account"));
                if (server != AuthState.CanonicalOrigin(server) || key != Credentials.ConnectionKey(server, handle) || !ValidName(name) || row.GetProperty("reconnect").ValueKind is not (JsonValueKind.True or JsonValueKind.False) || !names.Add(name) || !keys.Add(key)) throw new AuthenticationException("Invalid named identity");
                entries.Add(new Entry(key, name, server, handle));
            }
            if (config.TryGetProperty("selected", out _)) { selected = AuthState.Text(config, "selected"); if (!keys.Contains(selected)) throw new AuthenticationException("Selected connection is missing"); }
            bool hasDefault = config.TryGetProperty("fileStoreDefault", out var defaultStore);
            if (hasDefault)
            {
                AuthState.Fields(defaultStore, ["alias", "root"]); string alias = AuthState.Text(defaultStore, "alias"), path = AuthState.Text(defaultStore, "root");
                if (!Regex.IsMatch(alias, "^[a-z][a-z0-9_-]{0,63}$") || !Path.IsPathFullyQualified(path) || path.Length > 4096 || path.Any(c => c < 32 || c == 127)) throw new AuthenticationException("Invalid FileStore default");
            }
            if (config.TryGetProperty("connectionSetupPending", out var pending) && (pending.ValueKind != JsonValueKind.True || entries.Count != 0 || selected is not null || !hasDefault)) throw new AuthenticationException("Invalid pending setup");
        }
        connection ??= Environment.GetEnvironmentVariable("PLOWSHARE_CONNECTION");
        origin ??= Environment.GetEnvironmentVariable("PLOWSHARE_URL");
        account ??= Environment.GetEnvironmentVariable("PLOWSHARE_ACCOUNT") ?? Environment.GetEnvironmentVariable("PLOWSHARE_HANDLE");
        Entry? chosen = null;
        if (connection is not null)
        {
            if (!ValidName(connection) || (chosen = entries.FirstOrDefault(e => e.Name == connection)) is null) throw new AuthenticationException("Unknown named connection");
        }
        else if (origin is null && account is null) chosen = entries.FirstOrDefault(e => e.Key == selected);
        if (chosen is not null)
        {
            if ((origin is not null && AuthState.CanonicalOrigin(origin) != chosen.Server) || (account is not null && account != chosen.Account)) throw new AuthenticationException("Connection conflicts with explicit server/account");
            origin = chosen.Server; account = chosen.Account;
        }
        if (origin is null) throw new AuthenticationException("Select an explicit server or named connection");
        return new Credentials(origin, account, directory);
    }
}
