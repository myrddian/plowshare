using System.Globalization;
using System.Numerics;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
namespace Plowshare.Sdk;
/// <summary>Preserves scalar versus union spelling in the shared vocabulary.</summary>
[JsonConverter(typeof(SchemaTypesConverter))]
public sealed record SchemaTypes(IReadOnlyList<string> Values, bool Union = false);
[JsonConverter(typeof(SchemaRuleConverter))]
public abstract record SchemaRule
{
    private protected SchemaRule()
    {
    }
    public sealed record Allowed(bool Value) : SchemaRule;
    public sealed record Constrained(SchemaDefinition Schema) : SchemaRule;
}
internal sealed class SchemaTypesConverter : JsonConverter<SchemaTypes>
{
    public override SchemaTypes Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
    {
        using var document = JsonDocument.ParseValue(ref reader);
        var value = document.RootElement;
        if (value.ValueKind == JsonValueKind.String) return new([value.GetString() ?? throw new JsonException()]);
        if (value.ValueKind != JsonValueKind.Array) throw new JsonException("Invalid schema types");
        return new(value.EnumerateArray().Select(item => item.GetString() ?? throw new JsonException()).ToArray(), true);
    }
    public override void Write(Utf8JsonWriter writer, SchemaTypes value, JsonSerializerOptions options)
    {
        if (!value.Union && value.Values.Count == 1) writer.WriteStringValue(value.Values[0]);
        else JsonSerializer.Serialize(writer, value.Values, options);
    }
}
internal sealed class SchemaRuleConverter : JsonConverter<SchemaRule>
{
    public override SchemaRule Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
    {
        using var document = JsonDocument.ParseValue(ref reader);
        var value = document.RootElement;
        return value.ValueKind is JsonValueKind.True or JsonValueKind.False ? new SchemaRule.Allowed(value.GetBoolean()) : new SchemaRule.Constrained(JsonSerializer.Deserialize<SchemaDefinition>(value.GetRawText(), options) ?? throw new JsonException());
    }
    public override void Write(Utf8JsonWriter writer, SchemaRule value, JsonSerializerOptions options)
    {
        if (value is SchemaRule.Allowed allowed) writer.WriteBooleanValue(allowed.Value);
        else JsonSerializer.Serialize(writer, ((SchemaRule.Constrained)value).Schema, options);
    }
}
internal sealed class ToolValueConverter : JsonConverter<ToolValue>
{
    public override bool HandleNull => true;
    public override ToolValue Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
    {
        using var document = JsonDocument.ParseValue(ref reader);
        return ToolSchemas.DecodeValue(document.RootElement);
    }
    public override void Write(Utf8JsonWriter writer, ToolValue value, JsonSerializerOptions options)
    {
        switch (value)
        {
            case ToolValue.Text text:
                writer.WriteStringValue(text.Value);
                break;
            case ToolValue.Number number:
                writer.WriteNumberValue(number.Value);
                break;
            case ToolValue.Boolean flag:
                writer.WriteBooleanValue(flag.Value);
                break;
            case ToolValue.Null:
                writer.WriteNullValue();
                break;
            case ToolValue.Array array:
                JsonSerializer.Serialize(writer, array.Values, options);
                break;
            case ToolValue.ObjectValue fields:
                JsonSerializer.Serialize(writer, fields.Values, options);
                break;
            default: throw new JsonException("Invalid tool value");
        }
    }
}
/// <summary>Bounded executable profile of the shared vocabulary. References never load resources.</summary>
public static class ToolSchemas
{
    public const string Dialect = "https://json-schema.org/draft/2020-12/schema";
    private static readonly JsonSerializerOptions Json = new()
    {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow
    };
    private sealed class Budget
    {
        private int remaining = 10000;
        internal void Step(int depth)
        {
            if (depth > 64 || --remaining < 0) throw new ArgumentException("Schema validation exceeds work bound");
        }
    }
    private static void Field(string key)
    {
        if (string.IsNullOrEmpty(key) || key.Length > 1024 || key.Any(char.IsControl)) throw new ArgumentException("Invalid tool field");
    }
    internal static void Unique(JsonElement value, int depth = 0)
    {
        if (depth > 64) throw new ArgumentException("Schema exceeds depth bound");
        if (value.ValueKind == JsonValueKind.Number && (!value.TryGetDouble(out var number) || !RelayTools.PortableNumber(number) || Normalize(value.GetRawText()) != Normalize(number.ToString("R", CultureInfo.InvariantCulture)))) throw new ArgumentException("Nonportable schema number");
        if (value.ValueKind == JsonValueKind.Object)
        {
            var seen = new HashSet<string>();
            foreach (var field in value.EnumerateObject())
            {
                if (!seen.Add(field.Name)) throw new ArgumentException("Duplicate schema field");
                Unique(field.Value, depth + 1);
            }
        }
        else if (value.ValueKind == JsonValueKind.Array) foreach (var child in value.EnumerateArray()) Unique(child, depth + 1);
    }
    /// <summary>Reject unknown keywords and null/absence ambiguity before returning typed declarations.</summary>
    public static SchemaDefinition Decode(string source)
    {
        if (Encoding.UTF8.GetByteCount(source) > 50 * 1024 * 1024) throw new ArgumentException("Schema exceeds byte bound");
        using var document = JsonDocument.Parse(source);
        Unique(document.RootElement);
        void Shape(JsonElement node, int depth)
        {
            if (depth > 64 || node.ValueKind != JsonValueKind.Object) throw new ArgumentException("Invalid schema shape");
            foreach (var field in node.EnumerateObject())
            {
                if (field.Value.ValueKind == JsonValueKind.Null && field.Name is not ("const" or "default")) throw new ArgumentException("Null schema keyword");
                if (field.Name is "$defs" or "properties" or "patternProperties")
                {
                    if (field.Value.ValueKind != JsonValueKind.Object) throw new ArgumentException("Invalid schema map");
                    foreach (var child in field.Value.EnumerateObject()) Shape(child.Value, depth + 1);
                }
                else if (field.Name is "items" or "contains" or "not" or "if" or "then" or "else" || field.Name == "additionalProperties" && field.Value.ValueKind == JsonValueKind.Object) Shape(field.Value, depth + 1);
                else if (field.Name is "prefixItems" or "allOf" or "anyOf" or "oneOf")
                {
                    if (field.Value.ValueKind != JsonValueKind.Array) throw new ArgumentException("Invalid schema list");
                    foreach (var child in field.Value.EnumerateArray()) Shape(child, depth + 1);
                }
            }
        }
        Shape(document.RootElement, 0);
        var schema = JsonSerializer.Deserialize<SchemaDefinition>(source, Json) ?? throw new ArgumentException("Missing schema");
        Validate(schema);
        return schema;
    }
    internal static ToolValue DecodeValue(JsonElement source) => DecodeValue(source, new Budget(), 0);
    private static ToolValue DecodeValue(JsonElement source, Budget budget, int depth)
    {
        budget.Step(depth);
        if (depth > 32) throw new ArgumentException("Tool value exceeds depth bound");
        return source.ValueKind switch
        {
            JsonValueKind.Null => new ToolValue.Null(),
            JsonValueKind.String => new ToolValue.Text(RelayTools.Text(source.GetString() ?? throw new JsonException(), 4096)),
            JsonValueKind.Number when source.TryGetDouble(out var n) && RelayTools.PortableNumber(n) && Normalize(source.GetRawText()) == Normalize(n.ToString("R", CultureInfo.InvariantCulture)) => new ToolValue.Number(n),
            JsonValueKind.True => new ToolValue.Boolean(true),
            JsonValueKind.False => new ToolValue.Boolean(false),
            JsonValueKind.Array when source.GetArrayLength() <= 1024 => new ToolValue.Array(source.EnumerateArray().Select(item => DecodeValue(item, budget, depth + 1)).ToArray()),
            JsonValueKind.Object when source.EnumerateObject().Count() <= 1024 => new ToolValue.ObjectValue(source.EnumerateObject().ToDictionary(field =>
            {
                Field(field.Name); return field.Name;
            }, field => DecodeValue(field.Value, budget, depth + 1))),
            _ => throw new ArgumentException("Invalid recursive tool value")
        };
    }
    internal static void CheckValue(ToolValue value) => Value(value, new Budget(), 0);
    private static void Value(ToolValue value, Budget budget, int depth)
    {
        budget.Step(depth);
        if (depth > 32) throw new ArgumentException("Tool value exceeds depth bound");
        switch (value)
        {
            case ToolValue.Text text:
                RelayTools.Text(text.Value, 4096);
                break;
            case ToolValue.Number number when RelayTools.PortableNumber(number.Value): break;
            case ToolValue.Boolean: case ToolValue.Null: break;
            case ToolValue.Array array when array.Values.Count <= 1024:
                foreach (var child in array.Values) Value(child, budget, depth + 1);
                break;
            case ToolValue.ObjectValue fields when fields.Values.Count <= 1024:
                foreach (var field in fields.Values)
                {
                    Field(field.Key);
                    Value(field.Value, budget, depth + 1);
                }
                break;
            default: throw new ArgumentException("Invalid recursive tool value");
        }
    }
    private static SchemaDefinition Reference(SchemaDefinition root, string reference)
    {
        if (!reference.StartsWith("#/$defs/", StringComparison.Ordinal) || reference[8..].Contains('/') || System.Text.RegularExpressions.Regex.IsMatch(reference, "~(?![01])")) throw new ArgumentException("Reference must select a root definition");
        var key = reference[8..].Replace("~1", "/").Replace("~0", "~");
        return root.Defs is not null && root.Defs.TryGetValue(key, out var value) ? value : throw new ArgumentException("Unresolved schema reference");
    }
    /// <summary>Check the complete declaration, including unused definitions. Format is annotation.</summary>
    public static void Validate(SchemaDefinition root)
    {
        if (root.Type is null || root.Type.Values.Count != 1 || root.Type.Values[0] != "object") throw new ArgumentException("Tool schema must have object type");
        var budget = new Budget();
        void Check(SchemaDefinition s, int depth)
        {
            budget.Step(depth);
            if (s.Schema is not null && s.Schema != Dialect || s.Id is not null || s.Anchor is not null || s.Pattern is not null || s.PatternProperties is not null) throw new ArgumentException("Unsupported executable schema feature");
            if (s.Ref is not null) Check(Reference(root, s.Ref), depth + 1);
            if (s.Type is not null && (s.Type.Values.Count == 0 || !s.Type.Union && s.Type.Values.Count != 1 || s.Type.Values.Distinct().Count() != s.Type.Values.Count || s.Type.Values.Any(t => t is not ("object" or "array" or "string" or "number" or "integer" or "boolean" or "null")))) throw new ArgumentException("Invalid schema types");
            foreach (var text in new[] {
 s.Title, s.Schema, s.Ref, s.Format
}) if (text is not null) RelayTools.Text(text, 4096);
            if (s.Description is not null) RelayTools.Text(s.Description, 32768);
            foreach (var number in new[] {
 s.Minimum, s.Maximum, s.ExclusiveMinimum, s.ExclusiveMaximum, s.MultipleOf
}) if (number is not null && !RelayTools.PortableNumber(number.Value)) throw new ArgumentException("Nonportable schema number");
            if (s.MultipleOf is <= 0) throw new ArgumentException("Invalid multipleOf");
            foreach (var count in new[] {
 s.MinLength, s.MaxLength, s.MinItems, s.MaxItems, s.MinProperties, s.MaxProperties
}) if (count is < 0) throw new ArgumentException("Invalid schema bound");
            if (s.Minimum > s.Maximum || s.MinLength > s.MaxLength || s.MinItems > s.MaxItems || s.MinProperties > s.MaxProperties) throw new ArgumentException("Inverted schema range");
            foreach (var map in new[] {
 s.Defs, s.Properties
}) if (map is not null)
                {
                    if (map.Count > 10000) throw new ArgumentException("Too many schemas");
                    foreach (var field in map)
                    {
                        Field(field.Key);
                        Check(field.Value, depth + 1);
                    }
                }
            foreach (var list in new[] {
 s.PrefixItems, s.AllOf, s.AnyOf, s.OneOf
}) if (list is not null)
                {
                    if (list.Count > 10000 || list.Count == 0 && !ReferenceEquals(list, s.PrefixItems)) throw new ArgumentException("Invalid schema composition");
                    foreach (var child in list) Check(child, depth + 1);
                }
            foreach (var child in new[] {
 s.Items, s.Contains, s.Not, s.If, s.Then, s.Else
}) if (child is not null) Check(child, depth + 1);
            if (s.AdditionalProperties is SchemaRule.Constrained constrained) Check(constrained.Schema, depth + 1);
            if (s.Required is not null)
            {
                if (s.Required.Count > 10000 || s.Required.Distinct().Count() != s.Required.Count) throw new ArgumentException("Invalid required names");
                foreach (var key in s.Required) Field(key);
            }
            void Literal(ToolValue value)
            {
                if (value is ToolValue.Array or ToolValue.ObjectValue) throw new ArgumentException("Schema constants must be scalar");
                Value(value, budget, 0);
            }
            if (s.Const is not null) Literal(s.Const);
            if (s.Default is not null) Literal(s.Default);
            foreach (var list in new[] {
 s.Enum, s.Examples
}) if (list is not null)
                {
                    if (list.Count > 10000 || ReferenceEquals(list, s.Enum) && list.Count == 0) throw new ArgumentException("Invalid schema literals");
                    for (var i = 0; i < list.Count; i++)
                    {
                        Literal(list[i]);
                        if (ReferenceEquals(list, s.Enum)) for (var j = 0; j < i; j++) if (Equal(list[i], list[j], budget, 0)) throw new ArgumentException("Duplicate enum literal");
                    }
                }
        }
        Check(root, 0);
    }
    private static bool Bound(double value, double? minimum, double? maximum) => (minimum is null || value >= minimum) && (maximum is null || value <= maximum);
    private static bool Type(string type, ToolValue value) => type switch
    {
        "null" => value is ToolValue.Null,
        "string" => value is ToolValue.Text,
        "boolean" => value is ToolValue.Boolean,
        "number" => value is ToolValue.Number,
        "integer" => value is ToolValue.Number n && Math.Truncate(n.Value) == n.Value,
        "array" => value is ToolValue.Array,
        "object" => value is ToolValue.ObjectValue,
        _ => false
    };
    private static bool Equal(ToolValue a, ToolValue b, Budget budget, int depth)
    {
        budget.Step(depth);
        if (a is ToolValue.Array x && b is ToolValue.Array y) return x.Values.Count == y.Values.Count && x.Values.Where((value, i) => !Equal(value, y.Values[i], budget, depth + 1)).Any() == false;
        if (a is ToolValue.ObjectValue p && b is ToolValue.ObjectValue q) return p.Values.Count == q.Values.Count && p.Values.All(field => q.Values.TryGetValue(field.Key, out var value) && Equal(field.Value, value, budget, depth + 1));
        return a == b;
    }
    /// <summary>Validation and exhausted budgets refuse before any external invocation.</summary>
    public static void ValidateArguments(SchemaDefinition root, ToolValue value)
    {
        Validate(root);
        Value(value, new Budget(), 0);
        var budget = new Budget();
        bool Match(SchemaDefinition s, ToolValue v, int depth)
        {
            budget.Step(depth);
            if (s.Ref is not null && !Match(Reference(root, s.Ref), v, depth + 1) || s.Type is not null && !s.Type.Values.Any(t => Type(t, v)) || s.Const is not null && !Equal(s.Const, v, budget, depth + 1) || s.Enum is not null && !s.Enum.Any(item => Equal(item, v, budget, depth + 1))) return false;
            if (s.AllOf is not null && !s.AllOf.All(child => Match(child, v, depth + 1)) || s.AnyOf is not null && !s.AnyOf.Any(child => Match(child, v, depth + 1)) || s.OneOf is not null && s.OneOf.Count(child => Match(child, v, depth + 1)) != 1 || s.Not is not null && Match(s.Not, v, depth + 1)) return false;
            if (s.If is not null)
            {
                var branch = Match(s.If, v, depth + 1) ? s.Then : s.Else;
                if (branch is not null && !Match(branch, v, depth + 1)) return false;
            }
            if (v is ToolValue.Text text && !Bound(text.Value.EnumerateRunes().Count(), s.MinLength, s.MaxLength)) return false;
            if (v is ToolValue.Number n && (!Bound(n.Value, s.Minimum, s.Maximum) || s.ExclusiveMinimum is not null && n.Value <= s.ExclusiveMinimum || s.ExclusiveMaximum is not null && n.Value >= s.ExclusiveMaximum || s.MultipleOf is not null && !Multiple(n.Value, s.MultipleOf.Value))) return false;
            if (v is ToolValue.ObjectValue fields)
            {
                if (!Bound(fields.Values.Count, s.MinProperties, s.MaxProperties) || s.Required is not null && s.Required.Any(key => !fields.Values.ContainsKey(key))) return false;
                foreach (var field in fields.Values)
                {
                    SchemaDefinition? child = null;
                    if (s.Properties is not null) s.Properties.TryGetValue(field.Key, out child);
                    if (child is not null)
                    {
                        if (!Match(child, field.Value, depth + 1)) return false;
                    }
                    else if (s.AdditionalProperties is SchemaRule.Allowed allowed && !allowed.Value) return false;
                    else if (s.AdditionalProperties is SchemaRule.Constrained constrained && !Match(constrained.Schema, field.Value, depth + 1)) return false;
                }
            }
            if (v is ToolValue.Array array)
            {
                if (!Bound(array.Values.Count, s.MinItems, s.MaxItems)) return false;
                var contained = false;
                for (var i = 0; i < array.Values.Count; i++)
                {
                    var item = array.Values[i];
                    var child = i < (s.PrefixItems?.Count ?? 0) ? s.PrefixItems?[i] : s.Items;
                    if (child is not null && !Match(child, item, depth + 1)) return false;
                    if (s.Contains is not null && Match(s.Contains, item, depth + 1)) contained = true;
                    if (s.UniqueItems is true) for (var j = 0; j < i; j++) if (Equal(item, array.Values[j], budget, depth + 1)) return false;
                }
                if (s.Contains is not null && !contained) return false;
            }
            return true;
        }
        if (!Match(root, value, 0)) throw new ArgumentException("Tool arguments do not satisfy offered schema");
    }
    private static string Normalize(string source)
    {
        var parts = source.ToLowerInvariant().Split('e');
        var negative = parts[0].StartsWith('-');
        var raw = parts[0].Replace("-", "").Replace(".", "");
        var digits = raw.TrimStart('0').TrimEnd('0');
        if (digits.Length == 0) return "0e0";
        var exponent = parts.Length == 2 ? int.Parse(parts[1], CultureInfo.InvariantCulture) : 0;
        var fraction = parts[0].Contains('.') ? parts[0].Split('.')[1].Length : 0;
        return (negative ? "-" : "") + digits + "e" + (exponent - fraction + raw.Length - raw.TrimEnd('0').Length).ToString(CultureInfo.InvariantCulture);
    }
    private static bool Multiple(double number, double divisor)
    {
        var a = Normalize(number.ToString("R", CultureInfo.InvariantCulture)).Split('e');
        var b = Normalize(divisor.ToString("R", CultureInfo.InvariantCulture)).Split('e');
        var x = int.Parse(a[1], CultureInfo.InvariantCulture);
        var y = int.Parse(b[1], CultureInfo.InvariantCulture);
        var baseline = Math.Min(x, y);
        return BigInteger.Parse(a[0], CultureInfo.InvariantCulture) * BigInteger.Pow(10, x - baseline) % (BigInteger.Parse(b[0], CultureInfo.InvariantCulture) * BigInteger.Pow(10, y - baseline)) == 0;
    }
    /// <summary>Canonical token hashing is independent of serializer formatting and numeric spelling.</summary>
    public static string Fingerprint(SchemaDefinition schema)
    {
        Validate(schema);
        using var document = JsonDocument.Parse(JsonSerializer.Serialize(schema, Json));
        string Canonical(JsonElement v) => v.ValueKind switch
        {
            JsonValueKind.Null => "z;",
            JsonValueKind.True => "b1;",
            JsonValueKind.False => "b0;",
            JsonValueKind.String => "s" + Convert.ToHexString(Encoding.UTF8.GetBytes(v.GetString() ?? throw new JsonException())).ToLowerInvariant() + ";",
            JsonValueKind.Number => "n" + Normalize(v.GetRawText()) + ";",
            JsonValueKind.Array => "[" + string.Concat(v.EnumerateArray().Select(Canonical)) + "]",
            JsonValueKind.Object => "{" + string.Concat(v.EnumerateObject().OrderBy(field => Convert.ToHexString(Encoding.UTF8.GetBytes(field.Name)), StringComparer.Ordinal).Select(field => "s" + Convert.ToHexString(Encoding.UTF8.GetBytes(field.Name)).ToLowerInvariant() + ";" + Canonical(field.Value))) + "}",
            _ => throw new JsonException()
        };
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Canonical(document.RootElement)))).ToLowerInvariant();
    }
}
