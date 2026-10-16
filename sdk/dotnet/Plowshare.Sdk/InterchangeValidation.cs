using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Plowshare.Sdk;

/// <summary>Private codec validation of foreign graph data, never permissions or application DTOs.</summary>
internal static class InterchangeValidation
{
    private sealed record Evidence(string Revision, int Start, int End, string Quote);
    private sealed record Provenance(string SourceHash, string[] Dependencies, Evidence[] Evidence);
    private sealed record Descriptor(string Resource, string Revision, int Ordinal, string Path, string ContentHash, Provenance Provenance);
    private sealed record Document(Descriptor Descriptor, string BytesBase64);
    private sealed record Link(string Source, string Target, string? PinnedRevision, string Relation);
    private sealed record Bundle(Document[] Documents, Link[] Relationships);
    private sealed record Destination(string Resource, string Name);
    private sealed record TypeMapping(string ForeignVersion);
    private sealed record Reuse(string ForeignRevision, string LocalRevision);
    private sealed record Plan(Bundle Bundle, Destination[] Destinations, TypeMapping[] Types, Reuse[] Reuse, bool RestoreRelationships);
    private static readonly JsonSerializerOptions Options = new() { PropertyNameCaseInsensitive = true };
    internal static bool Path(string value) => value is not null && value.Length is > 0 and <= 240
        && Regex.IsMatch(value, "^[A-Za-z0-9][A-Za-z0-9._/-]*$", RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1))
        && value.Split('/').All(part => part.Length > 0 && part != "." && part != ".." && !new[]{".git",".hg",".svn"}.Contains(part,StringComparer.OrdinalIgnoreCase) && !part.EndsWith('.')
            && !Regex.IsMatch(part, @"^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\..*)?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1)));
    private static void CheckDescriptor(Descriptor d)
    {
        if (d is null || !Path(d.Path) || d.Provenance is null || d.Provenance.Dependencies is null || d.Provenance.Evidence is null) throw new JsonException("Invalid portable descriptor");
        foreach (var e in d.Provenance.Evidence)
            if (e is null || e.Start < 0 || e.End <= e.Start || e.Quote is null || e.Quote.Length != e.End - e.Start) throw new JsonException("Invalid portable evidence span");
    }
    private static void CheckBundle(Bundle b)
    {
        if (b is null || b.Documents is null || b.Relationships is null || b.Documents.Length is < 1 or > 16 || b.Relationships.Length > 64) throw new JsonException("Portable record bound");
        var revisions = new Dictionary<string, Descriptor>(); var resources = new HashSet<string>();
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase); var ordinals = new HashSet<(string, int)>(); var total = 0;
        foreach (var doc in b.Documents)
        {
            if (doc is null) throw new JsonException("Invalid portable document");
            var d = doc.Descriptor; CheckDescriptor(d);
            if (d.Revision is null || d.Resource is null || !revisions.TryAdd(d.Revision, d) || !paths.Add(d.Path) || !ordinals.Add((d.Resource, d.Ordinal))) throw new JsonException("Duplicate portable identity");
            if (doc.BytesBase64 is null || doc.BytesBase64.Length > 2796204) throw new JsonException("Portable byte bound");
            byte[] bytes;
            try { bytes = Convert.FromBase64String(doc.BytesBase64); }
            catch (FormatException invalid) { throw new JsonException("Invalid portable base64", invalid); }
            if (bytes.Length == 0 || Convert.ToBase64String(bytes) != doc.BytesBase64 || Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant() != d.ContentHash) throw new JsonException("Invalid portable bytes/hash");
            total += bytes.Length; resources.Add(d.Resource);
        }
        if (total > 2097152 || paths.Any(path => paths.Any(other => other.StartsWith(path + "/", StringComparison.OrdinalIgnoreCase)))) throw new JsonException("Portable size/path collision");
        foreach (var d in revisions.Values)
        {
            if (d.Provenance.Dependencies.Distinct().Count() != d.Provenance.Dependencies.Length || d.Provenance.Dependencies.Any(dep => dep is null || !revisions.ContainsKey(dep) || dep==d.Revision)) throw new JsonException("Portable dependency missing");
            if (d.Provenance.Evidence.Any(e => e.Revision is null || !revisions.ContainsKey(e.Revision))) throw new JsonException("Portable evidence source missing");
        }
        var edges = new HashSet<(string, string, string?, string)>();
        foreach (var e in b.Relationships)
            if (e is null || !resources.Contains(e.Source) || !resources.Contains(e.Target) || !edges.Add((e.Source, e.Target, e.PinnedRevision, e.Relation)) || e.PinnedRevision is not null && (!revisions.TryGetValue(e.PinnedRevision, out var target) || target.Resource != e.Target)) throw new JsonException("Invalid portable relationship");
    }
    internal static void Check(string kind, JsonElement value)
    {
        switch (kind)
        {
            case "descriptor": CheckDescriptor(value.Deserialize<Descriptor>(Options) ?? throw new JsonException("Missing descriptor")); break;
            case "bundle": CheckBundle(value.Deserialize<Bundle>(Options) ?? throw new JsonException("Missing bundle")); break;
            case "plan":
                var p = value.Deserialize<Plan>(Options) ?? throw new JsonException("Missing plan"); CheckBundle(p.Bundle);
                if (p.Destinations is null || p.Types is null) throw new JsonException("Missing explicit mappings");
                if(p.Reuse is null)throw new JsonException("Missing explicit revision reuse");
                var all=p.Bundle.Documents.Select(d=>d.Descriptor.Revision).ToHashSet();var reused=new HashSet<string>();var local=new HashSet<string>();
                foreach(var r in p.Reuse)if(r is null||!all.Contains(r.ForeignRevision)||!reused.Add(r.ForeignRevision)||!local.Add(r.LocalRevision))throw new JsonException("Invalid portable revision reuse");
                var resources=p.Bundle.Documents.Where(d=>!reused.Contains(d.Descriptor.Revision)).Select(d=>d.Descriptor.Resource).ToHashSet();
                if(resources.Count==0||p.RestoreRelationships&&p.Bundle.Relationships.Any(e=>!resources.Contains(e.Source)))throw new JsonException("Imported source required"); if(p.RestoreRelationships)foreach(var edge in p.Bundle.Relationships)if(edge.PinnedRevision is not null) {
                    var pinned=p.Bundle.Documents.Single(d=>d.Descriptor.Revision==edge.PinnedRevision).Descriptor;
                    if(pinned.ContentHash!=pinned.Provenance.SourceHash)throw new JsonException("Cannot restore an exact pin to edited bytes");
                }
                var selected = new HashSet<string>(); var names = new HashSet<string>();
                foreach (var d in p.Destinations)
                    if (d is null || !resources.Contains(d.Resource) || !selected.Add(d.Resource) || !names.Add(d.Name)) throw new JsonException("Duplicate/unsafe portable destination");
                if (!selected.SetEquals(resources) || p.Types.Any(t => t is null) || p.Types.Select(t => t.ForeignVersion).Distinct().Count() != p.Types.Length) throw new JsonException("Explicit unique portable mapping required");
                break;
            default: throw new JsonException("Unsupported portable graph");
        }
    }
}
