using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32.SafeHandles;

namespace Plowshare.Sdk;

/// <summary>Owning private file and JSON boundary for native user authentication.</summary>
internal static class AuthState
{
    internal static string Account(string value) => value is not null && Regex.IsMatch(value, "^[A-Za-z0-9_.-]{1,64}$") ? value : throw new AuthenticationException("Invalid ordinary account handle");
    internal static string Secret(string value)
    {
        if (string.IsNullOrEmpty(value) || value.Length > 4096 || value.Any(c => c < 33 || c > 126 || ";,\"\\".Contains(c))) throw new AuthenticationException("Invalid authentication credential");
        return value;
    }
    internal static string CanonicalOrigin(string value)
    {
        if (value is null || value.Length > 2048 || value.Any(c => c <= 32 || c >= 127) || value.IndexOfAny(['\\', '?', '#', '@', '%']) >= 0 || !Regex.IsMatch(value, "^https?://[^/]+/?$", RegexOptions.IgnoreCase) || !Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") || uri.Host.Length == 0 || uri.UserInfo.Length != 0 || uri.AbsolutePath != "/") throw new AuthenticationException("Use an explicit ASCII HTTP(S) origin without credentials or path");
        string host = uri.IdnHost.ToLowerInvariant();
        if (uri.HostNameType == UriHostNameType.IPv6)
        {
            var ip = IPAddress.Parse(host.Trim('[', ']'));
            string canonical = ip.ToString();
            if (ip.IsIPv4MappedToIPv6) { var bytes = ip.GetAddressBytes(); canonical = $"::ffff:{(bytes[12] << 8 | bytes[13]):x}:{(bytes[14] << 8 | bytes[15]):x}"; }
            host = "[" + canonical + "]";
        }
        else
        {
            if (!Regex.IsMatch(host, "^[A-Za-z0-9._-]+$")) throw new AuthenticationException("Invalid origin hostname");
            // Uri normalizes alternate IPv4 representations. Refuse the input
            // spelling when it would disagree with Python's explicit identity.
            string authority = value[(value.IndexOf("://", StringComparison.Ordinal) + 3)..].TrimEnd('/');
            string inputHost = authority.Split(':')[0].ToLowerInvariant();
            if (uri.HostNameType == UriHostNameType.IPv4 && inputHost != host) throw new AuthenticationException("Use canonical dotted IPv4");
            string last = inputHost.TrimEnd('.').Split('.').Last();
            if (Regex.IsMatch(last, "^(?:[0-9]+|0[xX][0-9a-fA-F]+)$") && uri.HostNameType != UriHostNameType.IPv4) throw new AuthenticationException("Use canonical IPv4");
        }
        return uri.Scheme + "://" + host + (uri.IsDefaultPort ? "" : ":" + uri.Port);
    }
    internal static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    internal static string Key(string origin, string account) => Hash(JsonSerializer.Serialize(new[] { CanonicalOrigin(origin), Account(account) }));
    private static FileAttributes? Attributes(string path)
    {
        try { return File.GetAttributes(path); }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
    }
    internal static void DirectoryPrivate(string path)
    {
        if (Attributes(path) is { } prior && (prior & FileAttributes.ReparsePoint) != 0) throw new AuthenticationException("Authentication directories must not be links");
        if (OperatingSystem.IsWindows()) System.IO.Directory.CreateDirectory(path);
        else
        {
            System.IO.Directory.CreateDirectory(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) throw new AuthenticationException("Authentication directories must not be links");
    }
    [DllImport("libc", EntryPoint = "open", SetLastError = true)] private static extern int Open(string path, int flags);
    [DllImport("libc", EntryPoint = "fchmod", SetLastError = true)] private static extern int Chmod(int descriptor, uint mode);
    [DllImport("libc", EntryPoint = "fsync", SetLastError = true)] private static extern int Sync(int descriptor);
    [DllImport("libc", EntryPoint = "mkdir", SetLastError = true)] private static extern int Mkdir(string path, uint mode);
    [DllImport("kernel32.dll", EntryPoint = "CreateDirectoryW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)] private static extern bool CreateDirectoryWindows(string path, IntPtr attributes);
    [DllImport("libc", EntryPoint = "rename", SetLastError = true)] private static extern int Rename(string source, string destination);
    [DllImport("kernel32.dll", EntryPoint = "MoveFileExW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)] private static extern bool MoveWindows(string source, string destination, uint flags);
    internal static void MoveCredential(string source, string destination)
    {
        // File.Move may copy across volumes. A refresh credential must move
        // atomically under both locks, or remain untouched at its source.
        bool moved = OperatingSystem.IsWindows()
            ? MoveWindows(source, destination, 9)
            : Rename(source, destination) == 0;
        if (!moved) throw new AuthenticationException("Credential migration requires an atomic move on one filesystem");
    }
    internal static bool TryLock(string path)
    {
        bool created;
        int exists;
        if (OperatingSystem.IsWindows()) { created = CreateDirectoryWindows(path, IntPtr.Zero); exists = 183; }
        else { created = Mkdir(path, 448) == 0; exists = 17; }
        if (created) return true;
        if (Marshal.GetLastPInvokeError() == exists) return false;
        throw new AuthenticationException("Unable to lock private authentication state");
    }
    private static SafeFileHandle ReadHandle(string path)
    {
        if (OperatingSystem.IsWindows()) return File.OpenHandle(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        int nofollow = OperatingSystem.IsMacOS() ? 0x100 : 0x20000;
        int nonblock = OperatingSystem.IsMacOS() ? 4 : 0x800;
        int descriptor = Open(path, nofollow | nonblock);
        if (descriptor < 0) throw new AuthenticationException("Unable to open private authentication file");
        var handle = new SafeFileHandle((IntPtr)descriptor, ownsHandle: true);
        // chmod on the opened inode also refuses another user's POSIX file;
        // no check-then-follow symlink window can change the inode being read.
        if (Chmod(descriptor, 384) != 0) { handle.Dispose(); throw new AuthenticationException("Authentication file is not privately owned"); }
        return handle;
    }
    internal static JsonElement? Read(string path)
    {
        try { return ReadFile(path); }
        catch(Exception error) when(error is IOException or JsonException or UnauthorizedAccessException or NotSupportedException)
        { throw new AuthenticationException("Authentication state unreadable; sign in explicitly"); }
    }
    private static JsonElement? ReadFile(string path)
    {
        FileAttributes attrs;
        try { attrs = File.GetAttributes(path); }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
        if ((attrs & (FileAttributes.ReparsePoint | FileAttributes.Directory)) != 0) throw new AuthenticationException("Authentication needs regular private files");
        using var stream = new FileStream(ReadHandle(path), FileAccess.Read);
        if (!stream.CanSeek || stream.Length > 2000000) throw new AuthenticationException("Authentication state exceeds size limit");
        byte[] buffer = new byte[2000001]; int total = 0;
        while (total < buffer.Length) { int count = stream.Read(buffer, total, buffer.Length - total); if (count == 0) break; total += count; }
        if (total > 2000000) throw new AuthenticationException("Authentication state exceeds size limit");
        using var json = JsonDocument.Parse(buffer.AsMemory(0, total));
        if (json.RootElement.ValueKind != JsonValueKind.Object) throw new AuthenticationException("Invalid authentication state");
        return json.RootElement.Clone();
    }
    internal static void SyncDirectory(string path)
    {
        if (OperatingSystem.IsWindows()) return;
        int descriptor = Open(path, 0);
        if (descriptor < 0) throw new AuthenticationException("Unable to persist authentication directory");
        using var handle = new SafeFileHandle((IntPtr)descriptor, ownsHandle: true);
        if (Sync(descriptor) != 0) throw new AuthenticationException("Unable to persist authentication directory");
    }
    internal static void Write<T>(string path, T value)
    {
        if (Attributes(path) is { } prior && (prior & (FileAttributes.Directory | FileAttributes.ReparsePoint)) != 0) throw new AuthenticationException("Invalid authentication destination");
        string temporary = path + "." + Guid.NewGuid();
        try
        {
            var options = new FileStreamOptions { Mode = FileMode.CreateNew, Access = FileAccess.Write };
            if (!OperatingSystem.IsWindows()) options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
            using (var output = new FileStream(temporary, options)) { JsonSerializer.Serialize(output, value, JsonOptions); output.Flush(flushToDisk: true); }
            File.Move(temporary, path, overwrite: true);
            SyncDirectory(Path.GetDirectoryName(path)!);
        }
        finally { File.Delete(temporary); }
    }
    internal static readonly JsonSerializerOptions JsonOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    internal static void Fields(JsonElement raw, string[] required, string[]? optional = null)
    {
        if (raw.ValueKind != JsonValueKind.Object) throw new AuthenticationException("Invalid authentication object");
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var field in raw.EnumerateObject()) if (!seen.Add(field.Name) || (!required.Contains(field.Name) && !(optional?.Contains(field.Name) ?? false))) throw new AuthenticationException("Invalid authentication fields");
        foreach (string key in required) if (!raw.TryGetProperty(key, out var v) || v.ValueKind == JsonValueKind.Null) throw new AuthenticationException("Missing authentication field");
    }
    internal static bool Unique(JsonElement raw)
    {
        if(raw.ValueKind==JsonValueKind.Object){var seen=new HashSet<string>(StringComparer.Ordinal);return raw.EnumerateObject().All(p=>seen.Add(p.Name)&&Unique(p.Value));}
        return raw.ValueKind!=JsonValueKind.Array || raw.EnumerateArray().All(Unique);
    }
    internal static string Text(JsonElement raw, string field) => raw.TryGetProperty(field, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()! : throw new AuthenticationException("Invalid authentication field");
    internal static void Version(JsonElement raw) { if (!raw.TryGetProperty("version", out var v) || v.ValueKind != JsonValueKind.Number || !v.TryGetInt32(out int n) || n != 1) throw new AuthenticationException("Unsupported authentication state version"); }
}
