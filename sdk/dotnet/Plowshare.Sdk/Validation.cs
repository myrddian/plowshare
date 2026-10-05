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
            "eq" => Bool(values[0].ValueKind == values[1].ValueKind && JsonNode.DeepEquals(JsonNode.Parse(values[0].GetRawText()), JsonNode.Parse(values[1].GetRawText()))),
            "gt" => Bool(values[0].ValueKind == JsonValueKind.Number && values[1].ValueKind == JsonValueKind.Number && values[0].GetDouble() > values[1].GetDouble()),
            _ => throw new JsonException("Unknown owned constraint")
        };
    }
    private static bool Flag(JsonElement schema, string key) => schema.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.True;
    private static double Bound(JsonElement schema, string key, double fallback) => schema.TryGetProperty(key, out var v) ? v.GetDouble() : fallback;
    private static bool Matches(JsonElement schema, string key, string value)
        => !schema.TryGetProperty(key, out var p) || Regex.IsMatch(value, p.GetString()!, RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
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
                    || text.Length < Bound(schema, "minLength", 0) || text.Length > Bound(schema, "maxLength", 8 * 1024 * 1024)
                    || Flag(schema, "nonblank") && string.IsNullOrWhiteSpace(text)
                    || Flag(schema, "trimmed") && text != text.Trim()
                    || Flag(schema, "noNul") && text.Contains('\0')
                    || !Matches(schema, "pattern", text)
                    || schema.TryGetProperty("disallow", out var disallowed) && disallowed.EnumerateArray().Any(v => v.GetString() == text)
                    || Flag(schema, "safeRelativePath") && (text.StartsWith('/') || text.Contains('\\') || text.Split('/').Contains(".."));
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
}
