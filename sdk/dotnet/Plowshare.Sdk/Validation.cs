using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Plowshare.Sdk;

/// <summary>Portable constraints evaluated only inside the owning JSON boundary.</summary>
internal static class Validation
{
    private static JsonElement Get(JsonElement row, string path)
    {
        foreach (var field in path.Split('.'))
        {
            if (row.ValueKind != JsonValueKind.Object || !row.TryGetProperty(field, out row)) return default;
        }
        return row;
    }
    private static bool Present(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.Undefined or JsonValueKind.Null or JsonValueKind.False => false,
        JsonValueKind.String => v.GetString() != "",
        JsonValueKind.Array => v.GetArrayLength() > 0,
        _ => true
    };
    private static JsonElement Bool(bool value) => JsonSerializer.SerializeToElement(value);
    private static JsonElement Rule(JsonElement expr, JsonElement row)
    {
        if (expr.ValueKind != JsonValueKind.Object) return expr;
        var expression = expr.EnumerateObject().Single(); var args = expression.Value;
        switch (expression.Name)
        {
            case "get": return Get(row, args.GetString()!);
            case "has": return Bool(row.ValueKind == JsonValueKind.Object && row.TryGetProperty(args.GetString()!, out _));
            case "present": return Bool(Present(Get(row, args.GetString()!)));
            case "exactlyOne": return Bool(args.EnumerateArray().Count(p => Present(Get(row, p.GetString()!))) == 1);
            case "atMostOne": return Bool(args.EnumerateArray().Count(p => Present(Get(row, p.GetString()!))) <= 1);
            case "not": return Bool(!Present(Rule(args, row)));
        }
        var values = args.EnumerateArray().Select(a => Rule(a, row)).ToArray();
        return expression.Name switch
        {
            "and" => Bool(values.All(Present)),
            "or" => Bool(values.Any(Present)),
            "contains" => Bool(values[0].ValueKind == JsonValueKind.Array && values[0].EnumerateArray().Any(v => JsonNode.DeepEquals(JsonNode.Parse(v.GetRawText()), JsonNode.Parse(values[1].GetRawText())))),
            "eq" => Bool(values[0].ValueKind == values[1].ValueKind && JsonNode.DeepEquals(JsonNode.Parse(values[0].GetRawText()), JsonNode.Parse(values[1].GetRawText()))),
            "gt" => Bool(values[0].ValueKind == JsonValueKind.Number && values[1].ValueKind == JsonValueKind.Number && values[0].GetDouble() > values[1].GetDouble()),
            _ => throw new JsonException("Unknown owned constraint")
        };
    }
    private static bool Flag(JsonElement schema, string key) => schema.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.True;
    private static double Bound(JsonElement schema, string key, double fallback) => schema.TryGetProperty(key, out var v) ? v.GetDouble() : fallback;
    private static bool Matches(JsonElement schema, string key, string value)
        => !schema.TryGetProperty(key, out var p) || Regex.IsMatch(value, p.GetString()!, RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
    private static bool CanonicalRelativePath(string value)
        => !value.Contains('\\') && !value.Contains(':')
        && !value.Any(c => c < 32 || c >= 127 && c <= 159)
        && (value.Length == 0 || value.Split('/').All(part => part.Length > 0 && part != "." && part != ".." && !part.Equals(".git", StringComparison.OrdinalIgnoreCase)));
    internal static void Check(JsonElement schema, JsonElement value)
    {
        if (value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined) return;
        bool invalid = false;
        switch (value.ValueKind)
        {
            case JsonValueKind.String:
                var text = value.GetString()!;
                invalid = Flag(schema, "web") && (!Uri.TryCreate(text, UriKind.Absolute, out var address) || address.Port < 1 || address.Port > 65535)
                    || Flag(schema, "timestamp") && !DateTimeOffset.TryParse(text, System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.RoundtripKind, out _)
                    || text.Length < Bound(schema, "minLength", 0) || text.Length > Bound(schema, "maxLength", 50 * 1024 * 1024)
                    || Flag(schema, "nonblank") && string.IsNullOrWhiteSpace(text)
                    || Flag(schema, "trimmed") && text != text.Trim()
                    || Flag(schema, "noNul") && text.Contains('\0')
                    || !Matches(schema, "pattern", text)
                    || schema.TryGetProperty("disallow", out var disallowed) && disallowed.EnumerateArray().Any(v => v.GetString() == text)
                    || Flag(schema, "canonicalRelativePath") && !CanonicalRelativePath(text)
                    || Flag(schema, "safeRelativePath") && (text.StartsWith('/') || text.Contains('\\') || text.Split('/').Contains(".."));
                if (schema.TryGetProperty("maxUtf8Bytes", out var byteLimit))
                {
                    try { invalid |= new System.Text.UTF8Encoding(false, true).GetByteCount(text) > byteLimit.GetInt32(); }
                    catch (System.Text.EncoderFallbackException) { invalid = true; }
                }
                break;
            case JsonValueKind.Number:
                if (!value.TryGetDouble(out var number)) throw new JsonException("Invalid protocol number");
                if (Flag(schema, "safePrecision"))
                {
                    var literal = number.ToString("G", System.Globalization.CultureInfo.InvariantCulture);
                    var exponent = literal.IndexOfAny(['e', 'E']);
                    invalid = Math.Truncate(number) == number && Math.Abs(number) > 9007199254740991
                        || (exponent >= 0 ? Math.Abs(int.Parse(literal[(exponent + 1)..], System.Globalization.CultureInfo.InvariantCulture)) > 18 : literal.Contains('.') && literal[(literal.IndexOf('.') + 1)..].Length > 18);
                }
                invalid |= Flag(schema, "integer") && (!double.IsFinite(number) || Math.Truncate(number) != number)
                    || number < Bound(schema, "minimum", double.NegativeInfinity) || number > Bound(schema, "maximum", double.PositiveInfinity);
                break;
            case JsonValueKind.Array:
                invalid = value.GetArrayLength() < Bound(schema, "minItems", 0) || value.GetArrayLength() > Bound(schema, "maxItems", 10000);
                if (Flag(schema, "applicationFiles")) invalid |= !ApplicationFiles(value);
                if (Flag(schema, "uniqueItems")) invalid |= value.EnumerateArray().Select(v => v.GetRawText()).Distinct().Count() != value.GetArrayLength();
                if (schema.TryGetProperty("element", out var element)) foreach (var v in value.EnumerateArray()) Check(element, v);
                break;
            case JsonValueKind.Object:
                invalid = value.EnumerateObject().Count() > Bound(schema, "maxProperties", 10000);
                foreach (var field in value.EnumerateObject())
                {
                    invalid |= !Matches(schema, "keyPattern", field.Name);
                    if (schema.TryGetProperty("values", out var shape)) Check(shape, field.Value);
                }
                break;
        }
        if (schema.TryGetProperty("rules", out var rules)) invalid |= rules.EnumerateArray().Any(r => !Present(Rule(r, value)));
        if (invalid) throw new JsonException("Value violates the owned protocol constraints");
    }
    // This codec-only check bounds the complete package, including case-insensitive collisions.
    private static bool ApplicationFiles(JsonElement files)
    {
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var total = 0;
        var manifest = false;
        foreach (var file in files.EnumerateArray())
        {
            if (file.ValueKind != JsonValueKind.Object || !file.TryGetProperty("path", out var p) || !file.TryGetProperty("text", out var t) || p.ValueKind != JsonValueKind.String || t.ValueKind != JsonValueKind.String) return false;
            var path = p.GetString()!; var text = t.GetString()!;
            if (path.Length > 512 || path.Split('/').Length > 17 || !System.Text.RegularExpressions.Regex.IsMatch(path, @"^[A-Za-z0-9_-][A-Za-z0-9_.-]*(?:/[A-Za-z0-9_-][A-Za-z0-9_.-]*)*\z") || !paths.Add(path)) return false;
            if (path.Split('/').Any(segment => new[] {".", "..", "node_modules", "build", "__pycache__"}.Contains(segment))) return false;
            int bytes;
            try { bytes = new System.Text.UTF8Encoding(false, true).GetByteCount(text); }
            catch (System.Text.EncoderFallbackException) { return false; }
            if (text.Contains('\0') || bytes > 65536) return false;
            total += bytes;
            manifest |= path == "plowshare.json";
        }
        return total <= 131072 && manifest && !paths.Any(path => paths.Any(other => other.StartsWith(path + "/", StringComparison.OrdinalIgnoreCase)));
    }

}
